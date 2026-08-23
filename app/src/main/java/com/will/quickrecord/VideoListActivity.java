package com.will.quickrecord;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MediaStore-backed list of saved recordings with local plate-number annotations. */
public final class VideoListActivity extends Activity {
    private static final String PLATE_PREFERENCES = "video_plate_numbers";
    private static final String RECORDING_DIRECTORY = "Movies/CarPlateRecorder/";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final ExecutorService deletionExecutor = Executors.newSingleThreadExecutor();

    private LinearLayout rowsContainer;
    private TextView summaryView;
    private Button deleteAllButton;
    private SharedPreferences platePreferences;
    private List<VideoEntry> displayedVideos = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        FullscreenUi.hideStatusBar(this);
        platePreferences = getSharedPreferences(PLATE_PREFERENCES, MODE_PRIVATE);
        buildInterface();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FullscreenUi.hideStatusBar(this);
        loadVideos();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenUi.hideStatusBar(this);
        }
    }

    @Override
    protected void onDestroy() {
        thumbnailExecutor.shutdownNow();
        deletionExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF090909);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(18), dp(10), dp(14), dp(10));
        toolbar.setBackgroundColor(0xFF151515);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("录像列表");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        titleBlock.addView(title);
        summaryView = new TextView(this);
        summaryView.setText("正在读取…");
        summaryView.setTextColor(0xFFAAAAAA);
        summaryView.setTextSize(13f);
        titleBlock.addView(summaryView);
        toolbar.addView(titleBlock, new LinearLayout.LayoutParams(0, dp(64), 1f));

        deleteAllButton = new Button(this);
        deleteAllButton.setText("删除全部");
        deleteAllButton.setTextColor(Color.WHITE);
        deleteAllButton.setTextSize(14f);
        deleteAllButton.setAllCaps(false);
        deleteAllButton.setBackground(roundedBackground(0xFF4A1D1D, 20));
        deleteAllButton.setOnClickListener(view -> confirmDeleteAll());
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(104), dp(46));
        deleteParams.rightMargin = dp(10);
        toolbar.addView(deleteAllButton, deleteParams);

        ImageButton recordButton = new ImageButton(this);
        recordButton.setImageResource(R.drawable.ic_record_triangle);
        recordButton.setContentDescription("开始录像");
        recordButton.setPadding(dp(14), dp(14), dp(14), dp(14));
        recordButton.setBackground(roundedBackground(0xFFD72525, 23));
        recordButton.setOnClickListener(view -> startNewRecording());
        toolbar.addView(recordButton, new LinearLayout.LayoutParams(dp(46), dp(46)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(84)));

        LinearLayout headers = new LinearLayout(this);
        headers.setGravity(Gravity.CENTER_VERTICAL);
        headers.setPadding(dp(12), dp(8), dp(12), dp(8));
        headers.setBackgroundColor(0xFF202020);
        headers.addView(headerText("视频", Gravity.START), new LinearLayout.LayoutParams(dp(104), dp(32)));
        headers.addView(headerText("时间", Gravity.START), new LinearLayout.LayoutParams(dp(88), dp(32)));
        headers.addView(headerText("车牌号", Gravity.START), new LinearLayout.LayoutParams(0, dp(32), 1f));
        root.addView(headers);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        rowsContainer = new LinearLayout(this);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        rowsContainer.setPadding(dp(8), dp(8), dp(8), dp(24));
        scrollView.addView(rowsContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        setContentView(root);
    }

    private TextView headerText(String text, int gravity) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFBDBDBD);
        view.setTextSize(13f);
        view.setGravity(gravity | Gravity.CENTER_VERTICAL);
        return view;
    }

    private void loadVideos() {
        List<VideoEntry> videos = queryVideos();
        displayedVideos = videos;
        rowsContainer.removeAllViews();
        summaryView.setText(videos.size() + " 段已保存录像");
        setDeleteAllEnabled(!videos.isEmpty());
        if (videos.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有保存的录像");
            empty.setTextColor(0xFFAAAAAA);
            empty.setTextSize(17f);
            empty.setGravity(Gravity.CENTER);
            rowsContainer.addView(empty, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(220)));
            return;
        }
        for (VideoEntry video : videos) {
            rowsContainer.addView(buildVideoRow(video));
        }
    }

    private void setDeleteAllEnabled(boolean enabled) {
        deleteAllButton.setEnabled(enabled);
        deleteAllButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private void confirmDeleteAll() {
        int count = displayedVideos.size();
        if (count == 0) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("删除全部录像？")
                .setMessage("将从手机中永久删除列表里的 " + count + " 段录像，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("彻底删除", (ignored, which) -> deleteAllDisplayedVideos())
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(0xFFD72525));
        dialog.show();
    }

    private void deleteAllDisplayedVideos() {
        List<VideoEntry> videosToDelete = new ArrayList<>(displayedVideos);
        if (videosToDelete.isEmpty()) {
            return;
        }
        setDeleteAllEnabled(false);
        summaryView.setText("正在彻底删除…");
        deletionExecutor.execute(() -> {
            int deleted = 0;
            SharedPreferences.Editor annotationEditor = platePreferences.edit();
            for (VideoEntry video : videosToDelete) {
                try {
                    if (getContentResolver().delete(video.uri, null, null) > 0) {
                        annotationEditor.remove(plateKey(video.id));
                        deleted++;
                    }
                } catch (RuntimeException ignored) {
                    // Keep failed entries and their annotations visible for another attempt.
                }
            }
            annotationEditor.apply();
            int deletedCount = deleted;
            int failedCount = videosToDelete.size() - deleted;
            mainHandler.post(() -> {
                if (failedCount == 0) {
                    Toast.makeText(this,
                            "已彻底删除 " + deletedCount + " 段录像",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                            "已删除 " + deletedCount + " 段，" + failedCount + " 段删除失败",
                            Toast.LENGTH_LONG).show();
                }
                loadVideos();
            });
        });
    }

    private List<VideoEntry> queryVideos() {
        List<VideoEntry> videos = new ArrayList<>();
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DISPLAY_NAME
        };
        String selection;
        String[] selectionArgs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = MediaStore.Video.Media.RELATIVE_PATH + " = ?";
            selectionArgs = new String[]{RECORDING_DIRECTORY};
        } else {
            selection = MediaStore.Video.Media.DATA + " LIKE ?";
            selectionArgs = new String[]{"%/Movies/CarPlateRecorder/%"};
        }

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return videos;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN);
            int addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                long dateTaken = cursor.isNull(takenColumn) ? 0L : cursor.getLong(takenColumn);
                long dateAdded = cursor.getLong(addedColumn) * 1000L;
                String displayName = cursor.getString(nameColumn);
                Uri uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        Long.toString(id));
                videos.add(new VideoEntry(id, uri, dateTaken > 0 ? dateTaken : dateAdded, displayName));
            }
        } catch (RuntimeException queryError) {
            Toast.makeText(this, "读取录像列表失败", Toast.LENGTH_LONG).show();
        }
        return videos;
    }

    private View buildVideoRow(VideoEntry video) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        row.setBackground(roundedBackground(0xFF151515, 12));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(0xFF292929);
        thumbnail.setContentDescription("播放 " + video.displayName);
        thumbnail.setOnClickListener(view -> playVideo(video.uri));
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(dp(96), dp(64));
        thumbnailParams.rightMargin = dp(8);
        row.addView(thumbnail, thumbnailParams);
        loadThumbnail(video, thumbnail);

        TextView time = new TextView(this);
        time.setText(new SimpleDateFormat("yyyy-MM-dd\nHH:mm:ss", Locale.CHINA)
                .format(new Date(video.recordedAtMillis)));
        time.setTextColor(Color.WHITE);
        time.setTextSize(14f);
        time.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(time, new LinearLayout.LayoutParams(dp(80), dp(72)));

        EditText plate = new EditText(this);
        plate.setSingleLine(true);
        plate.setHint("点击录入");
        plate.setText(platePreferences.getString(plateKey(video.id), ""));
        plate.setTextColor(Color.WHITE);
        plate.setHintTextColor(0xFF777777);
        plate.setTextSize(17f);
        plate.setSelectAllOnFocus(true);
        plate.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        plate.setImeOptions(EditorInfo.IME_ACTION_DONE);
        plate.setPadding(dp(8), 0, dp(8), 0);
        plate.setBackground(roundedBackground(0xFF242424, 8));
        plate.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                platePreferences.edit()
                        .putString(plateKey(video.id), text.toString().trim())
                        .apply();
            }
        });
        plate.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                view.clearFocus();
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams plateParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        plateParams.leftMargin = dp(8);
        row.addView(plate, plateParams);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(88));
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);
        return row;
    }

    private void loadThumbnail(VideoEntry video, ImageView target) {
        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bitmap = getContentResolver().loadThumbnail(video.uri, new Size(384, 216), null);
                } else {
                    bitmap = MediaStore.Video.Thumbnails.getThumbnail(
                            getContentResolver(),
                            video.id,
                            MediaStore.Video.Thumbnails.MINI_KIND,
                            null);
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep the neutral placeholder if a damaged or unavailable video has no thumbnail.
            }
            Bitmap finalBitmap = bitmap;
            if (finalBitmap != null) {
                mainHandler.post(() -> target.setImageBitmap(finalBitmap));
            }
        });
    }

    private void playVideo(Uri videoUri) {
        Intent play = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(videoUri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(play);
        } catch (ActivityNotFoundException noPlayer) {
            Toast.makeText(this, "没有找到视频播放器", Toast.LENGTH_LONG).show();
        }
    }

    private void startNewRecording() {
        Intent record = new Intent(this, QuickRecordActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(record);
        finish();
    }

    private String plateKey(long videoId) {
        return "plate_" + videoId;
    }

    private GradientDrawable roundedBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class VideoEntry {
        final long id;
        final Uri uri;
        final long recordedAtMillis;
        final String displayName;

        VideoEntry(long id, Uri uri, long recordedAtMillis, String displayName) {
            this.id = id;
            this.uri = uri;
            this.recordedAtMillis = recordedAtMillis;
            this.displayName = displayName;
        }
    }
}
