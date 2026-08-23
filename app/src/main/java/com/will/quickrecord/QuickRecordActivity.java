package com.will.quickrecord;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Range;
import android.util.Rational;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ResolutionInfo;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.core.TorchState;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** One-tap evidence recorder tuned for nearby moving vehicles. */
public final class QuickRecordActivity extends Activity
        implements LifecycleOwner, SensorEventListener {
    private static final int REQUEST_PERMISSIONS = 20;
    private static final long LIGHT_SAMPLE_MILLIS = 650L;

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PreviewView previewView;
    private TextView statusView;
    private Button stopButton;
    private Button listButton;
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private Float measuredLux;
    private ProcessCameraProvider cameraProvider;
    private Camera activeCamera;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private CaptureProfile activeProfile;
    private int configuredFps;
    private boolean cameraStarted;
    private boolean lightProfileResolved;
    private boolean torchObserverConfigured;
    private boolean stopping;
    private boolean awaitingFinalize;
    private boolean discardForList;
    private boolean listOpened;
    private boolean hasFlashUnit;
    private boolean torchEnabled;
    private boolean torchRequestFailed;
    private long lastElapsedSeconds;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        FullscreenUi.hideStatusBar(this);

        buildInterface();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        if (hasCameraPermission()) {
            beginFastStartup();
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        FullscreenUi.hideStatusBar(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            FullscreenUi.hideStatusBar(this);
        }
    }

    @Override
    protected void onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (activeRecording != null && !stopping) {
            stopAndSave();
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
    }

    private void buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setText("正在检测光线…");
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16f);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(dp(14), dp(8), dp(14), dp(8));
        statusView.setBackground(roundedBackground(0xB0000000, 18));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        statusParams.setMargins(dp(18), dp(18), dp(18), 0);
        root.addView(statusView, statusParams);

        listButton = new Button(this);
        listButton.setText("录像列表");
        listButton.setTextColor(Color.WHITE);
        listButton.setTextSize(15f);
        listButton.setAllCaps(false);
        listButton.setPadding(dp(16), 0, dp(16), 0);
        listButton.setBackground(roundedBackground(0xC0202020, 22));
        listButton.setOnClickListener(view -> discardAndOpenList());
        FrameLayout.LayoutParams listParams = new FrameLayout.LayoutParams(
                dp(124),
                dp(48),
                Gravity.TOP | Gravity.END);
        listParams.setMargins(dp(18), dp(18), dp(18), 0);
        root.addView(listButton, listParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);

        stopButton = new Button(this);
        stopButton.setText("00:00");
        stopButton.setTextColor(Color.WHITE);
        stopButton.setTextSize(17f);
        stopButton.setAllCaps(false);
        stopButton.setContentDescription("停止并保存");
        stopButton.setPadding(0, 0, 0, 0);
        stopButton.setEnabled(false);
        stopButton.setBackground(roundedBackground(0xE6D72525, 30));
        stopButton.setOnClickListener(view -> stopAndSave());
        controls.addView(stopButton, new LinearLayout.LayoutParams(dp(96), dp(60)));

        TextView safety = new TextView(this);
        safety.setText("先观察道路安全 · 音量键也可停止");
        safety.setTextColor(0xFFE0E0E0);
        safety.setTextSize(14f);
        safety.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams safetyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        safetyParams.topMargin = dp(6);
        controls.addView(safety, safetyParams);

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        controlsParams.bottomMargin = dp(20);
        root.addView(controls, controlsParams);

        setContentView(root);
    }

    private void beginFastStartup() {
        if (cameraStarted) {
            return;
        }
        cameraStarted = true;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        activeProfile = CaptureProfile.choose(null, hour);
        statusView.setText(activeProfile.label + " · 正在启动相机…");

        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        mainHandler.postDelayed(this::resolveLightProfile, LIGHT_SAMPLE_MILLIS);

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                if (discardForList || isFinishing()) {
                    cameraProvider.unbindAll();
                    return;
                }
                bindCamera(activeProfile);
            } catch (Exception firstError) {
                showStartError(firstError);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void resolveLightProfile() {
        if (lightProfileResolved || discardForList || isFinishing()) {
            return;
        }
        lightProfileResolved = true;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        activeProfile = CaptureProfile.choose(measuredLux, hour);
        if (activeCamera != null) {
            applyLightControls(activeCamera, activeProfile);
        }
        if (activeRecording != null && !stopping) {
            updateRecordingStatus(lastElapsedSeconds);
        } else {
            String lightText = measuredLux == null
                    ? "按时间判断"
                    : Math.round(measuredLux) + " lx";
            statusView.setText(activeProfile.label + " · " + lightText + " · 正在启动相机…");
        }
    }

    private void bindCamera(CaptureProfile profile) {
        if (cameraProvider == null || discardForList || isFinishing()) {
            return;
        }
        cameraProvider.unbindAll();

        bindRegularCamera(profile);
    }

    private void bindRegularCamera(CaptureProfile profile) {
        int rotation = targetRotation();

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(rotation)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        List<Quality> orderedQualities = profile.preferUhd
                ? Arrays.asList(Quality.UHD, Quality.FHD, Quality.HD)
                : Arrays.asList(Quality.FHD, Quality.HD);
        QualitySelector qualitySelector = QualitySelector.fromOrderedList(
                orderedQualities,
                FallbackStrategy.lowerQualityOrHigherThan(Quality.HD));
        Recorder recorder = new Recorder.Builder()
                .setAspectRatio(AspectRatio.RATIO_16_9)
                .setQualitySelector(qualitySelector)
                .build();
        VideoCapture.Builder<Recorder> captureBuilder = new VideoCapture.Builder<>(recorder)
                .setTargetRotation(rotation)
                .setTargetFrameRate(new Range<>(30, 30));
        videoCapture = captureBuilder.build();

        activeCamera = cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                videoCapture);
        configuredFps = 30;
        startRecording();
        tuneForLicensePlate(activeCamera, profile);
    }

    private int targetRotation() {
        return previewView.getDisplay() == null
                ? getWindowManager().getDefaultDisplay().getRotation()
                : previewView.getDisplay().getRotation();
    }

    private void tuneForLicensePlate(Camera camera, CaptureProfile profile) {
        applyLightControls(camera, profile);

        ZoomState zoomState = camera.getCameraInfo().getZoomState().getValue();
        if (zoomState != null && zoomState.getMinZoomRatio() <= 1f && zoomState.getMaxZoomRatio() >= 1f) {
            camera.getCameraControl().setZoomRatio(1f);
        }

        previewView.post(() -> {
            if (previewView.getWidth() <= 0 || previewView.getHeight() <= 0) {
                return;
            }
            SurfaceOrientedMeteringPointFactory factory = new SurfaceOrientedMeteringPointFactory(
                    previewView.getWidth(), previewView.getHeight());
            MeteringPoint center = factory.createPoint(0.5f, 0.5f, 0.20f);
            FocusMeteringAction action = new FocusMeteringAction.Builder(
                    center,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
        });
    }

    private void applyLightControls(Camera camera, CaptureProfile profile) {
        configureAutomaticTorch(camera, profile);

        androidx.camera.core.ExposureState exposure = camera.getCameraInfo().getExposureState();
        if (exposure.isExposureCompensationSupported()) {
            Rational step = exposure.getExposureCompensationStep();
            int index = Math.round(profile.exposureEv / step.floatValue());
            Range<Integer> range = exposure.getExposureCompensationRange();
            index = Math.max(range.getLower(), Math.min(range.getUpper(), index));
            camera.getCameraControl().setExposureCompensationIndex(index);
        }
    }

    private void configureAutomaticTorch(Camera camera, CaptureProfile profile) {
        hasFlashUnit = camera.getCameraInfo().hasFlashUnit();
        torchRequestFailed = false;
        if (!hasFlashUnit) {
            return;
        }

        if (!torchObserverConfigured) {
            torchObserverConfigured = true;
            camera.getCameraInfo().getTorchState().observe(this, torchState -> {
                torchEnabled = torchState != null && torchState == TorchState.ON;
                if (activeRecording != null && !stopping) {
                    updateRecordingStatus(lastElapsedSeconds);
                }
            });
        }

        ListenableFuture<Void> torchRequest = camera.getCameraControl()
                .enableTorch(profile.enableTorch);
        torchRequest.addListener(() -> {
            try {
                torchRequest.get();
            } catch (Exception error) {
                torchRequestFailed = profile.enableTorch;
                if (activeRecording != null && !stopping) {
                    updateRecordingStatus(lastElapsedSeconds);
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startRecording() {
        if (videoCapture == null
                || isFinishing()
                || discardForList
                || activeRecording != null) {
            return;
        }
        ContentValues values = new ContentValues();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Calendar.getInstance().getTime());
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "CAR_PLATE_" + timestamp);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/CarPlateRecorder");
        }

        MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build();
        PendingRecording pending = videoCapture.getOutput()
                .prepareRecording(this, outputOptions);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }

        stopping = false;
        activeRecording = pending.start(
                ContextCompat.getMainExecutor(this),
                this::handleVideoEvent);
    }

    private void handleVideoEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            stopButton.setEnabled(true);
            updateRecordingStatus(0L);
        } else if (event instanceof VideoRecordEvent.Status) {
            if (stopping) {
                return;
            }
            long nanos = event.getRecordingStats().getRecordedDurationNanos();
            updateRecordingStatus(TimeUnit.NANOSECONDS.toSeconds(nanos));
        } else if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;
            activeRecording = null;
            awaitingFinalize = false;
            stopButton.setEnabled(false);
            if (discardForList) {
                deleteDiscardedOutput(finalizeEvent.getOutputResults().getOutputUri());
                launchVideoList();
                return;
            }
            if (!finalizeEvent.hasError()) {
                statusView.setText("已自动保存到 Movies/CarPlateRecorder");
                Toast.makeText(this, "录像已保存", Toast.LENGTH_SHORT).show();
                mainHandler.postDelayed(this::launchVideoList, 450L);
            } else {
                stopping = false;
                statusView.setText(getString(R.string.recording_failed)
                        + "（错误 " + finalizeEvent.getError() + "）");
                stopButton.setText("关闭");
                stopButton.setContentDescription("关闭");
                stopButton.setEnabled(true);
                stopButton.setOnClickListener(view -> finish());
            }
        }
    }

    private void updateRecordingStatus(long elapsedSeconds) {
        lastElapsedSeconds = elapsedSeconds;
        stopButton.setText(String.format(
                Locale.CHINA,
                "%02d:%02d",
                elapsedSeconds / 60,
                elapsedSeconds % 60));
        String resolution = "自动分辨率";
        ResolutionInfo info = videoCapture == null ? null : videoCapture.getResolutionInfo();
        if (info != null) {
            resolution = info.getResolution().getWidth() + "×" + info.getResolution().getHeight();
        }
        String lux = measuredLux == null ? "按时间判断" : Math.round(measuredLux) + " lx";
        statusView.setText(String.format(
                Locale.CHINA,
                "%s · %dfps · 1× · %s · %s · %s",
                resolution,
                configuredFps,
                activeProfile.label,
                torchStatusText(),
                lux));
    }

    private String torchStatusText() {
        if (!hasFlashUnit) {
            return "无补光灯";
        }
        if (!activeProfile.enableTorch) {
            return "补光关";
        }
        if (torchRequestFailed) {
            return "补光失败";
        }
        return torchEnabled ? "补光开" : "补光启动中";
    }

    private void stopAndSave() {
        if (activeRecording == null || stopping) {
            return;
        }
        stopping = true;
        stopButton.setEnabled(false);
        listButton.setEnabled(false);
        statusView.setText("正在停止并保存…");
        Recording recording = activeRecording;
        activeRecording = null;
        awaitingFinalize = true;
        recording.stop();
    }

    private void discardAndOpenList() {
        if (discardForList || listOpened) {
            return;
        }
        discardForList = true;
        stopping = true;
        listButton.setEnabled(false);
        stopButton.setEnabled(false);
        statusView.setText("正在放弃当前录像…");
        mainHandler.removeCallbacksAndMessages(null);
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        if (activeRecording != null) {
            Recording recording = activeRecording;
            activeRecording = null;
            awaitingFinalize = true;
            recording.stop();
        } else if (!awaitingFinalize) {
            launchVideoList();
        }
    }

    private void deleteDiscardedOutput(Uri outputUri) {
        if (outputUri == null || Uri.EMPTY.equals(outputUri)) {
            return;
        }
        try {
            getContentResolver().delete(outputUri, null, null);
        } catch (RuntimeException deleteError) {
            Toast.makeText(this, "当前录像删除失败，请在列表中检查", Toast.LENGTH_LONG).show();
        }
    }

    private void launchVideoList() {
        if (listOpened) {
            return;
        }
        listOpened = true;
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        startActivity(new Intent(this, VideoListActivity.class));
        finish();
    }

    private void showStartError(Exception error) {
        statusView.setText(getString(R.string.camera_start_failed) + "："
                + error.getClass().getSimpleName());
        stopButton.setText("关闭");
        stopButton.setContentDescription("关闭");
        stopButton.setEnabled(true);
        stopButton.setOnClickListener(view -> finish());
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        if (hasCameraPermission()) {
            beginFastStartup();
        } else {
            Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            stopAndSave();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (activeRecording != null) {
            stopAndSave();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT && event.values.length > 0) {
            measuredLux = event.values[0];
            resolveLightProfile();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // One approximate ambient-light reading is sufficient for profile selection.
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
}
