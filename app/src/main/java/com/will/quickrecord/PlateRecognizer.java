package com.will.quickrecord;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline, multi-frame recognition for Chinese license plates. */
final class PlateRecognizer {
    private static final long SAMPLE_INTERVAL_MILLIS = 500L;
    private static final int MAX_SAMPLE_FRAMES = 60;
    private static final int OCR_WIDTH = 1920;
    private static final int OCR_HEIGHT = 1080;
    private static final long OCR_TIMEOUT_SECONDS = 10L;
    private static final String PROVINCES = "京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼";
    private static final String SEPARATOR = "[\\s·•.\\-_:：]*";
    private static final String PLATE_CHARACTER = "[A-HJ-NP-Z0-9挂学警港澳领使]";
    private static final Pattern PLATE_PATTERN = Pattern.compile(
            "[" + PROVINCES + "]" + SEPARATOR
                    + "[A-HJ-NP-Z]"
                    + "(?:" + SEPARATOR + PLATE_CHARACTER + "){5,6}",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SEPARATORS = Pattern.compile("[\\s·•.\\-_:：]+");

    private PlateRecognizer() {
    }

    static Result recognize(Context context, Uri videoUri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        TextRecognizer recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        Map<String, CandidateScore> scores = new LinkedHashMap<>();
        int successfulOcrFrames = 0;
        try {
            retriever.setDataSource(context, videoUri);
            long durationMillis = parseDuration(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
            int frameCount = durationMillis <= 0
                    ? 1
                    : (int) Math.min(
                            MAX_SAMPLE_FRAMES,
                            Math.max(1L, (durationMillis + SAMPLE_INTERVAL_MILLIS - 1)
                                    / SAMPLE_INTERVAL_MILLIS));
            long intervalMillis = durationMillis > frameCount * SAMPLE_INTERVAL_MILLIS
                    ? Math.max(1L, durationMillis / frameCount)
                    : SAMPLE_INTERVAL_MILLIS;

            for (int index = 0; index < frameCount && !Thread.currentThread().isInterrupted(); index++) {
                long timeMillis = durationMillis <= 0
                        ? 0L
                        : Math.min(durationMillis - 1L, index * intervalMillis);
                Bitmap frame = frameAt(retriever, TimeUnit.MILLISECONDS.toMicros(timeMillis));
                if (frame == null) {
                    continue;
                }
                try {
                    String recognizedText = Tasks.await(
                            recognizer.process(InputImage.fromBitmap(frame, 0)),
                            OCR_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS).getText();
                    successfulOcrFrames++;
                    for (String plate : extractCandidates(recognizedText)) {
                        CandidateScore score = scores.get(plate);
                        if (score == null) {
                            scores.put(plate, new CandidateScore(scores.size()));
                        } else {
                            score.occurrences++;
                        }
                    }
                } catch (Exception ignored) {
                    // A damaged or difficult frame must not prevent later frames from being scanned.
                } finally {
                    frame.recycle();
                }
            }
        } catch (RuntimeException ignored) {
            return Result.retryLater();
        } finally {
            recognizer.close();
            try {
                retriever.release();
            } catch (IOException ignored) {
                // Nothing else can usefully be done after recognition has finished.
            }
        }

        List<String> plates = new ArrayList<>(scores.keySet());
        plates.sort((left, right) -> {
            CandidateScore leftScore = scores.get(left);
            CandidateScore rightScore = scores.get(right);
            int byFrequency = Integer.compare(rightScore.occurrences, leftScore.occurrences);
            return byFrequency != 0
                    ? byFrequency
                    : Integer.compare(leftScore.firstSeen, rightScore.firstSeen);
        });
        return new Result(plates, successfulOcrFrames > 0);
    }

    static List<String> extractCandidates(String recognizedText) {
        if (recognizedText == null || recognizedText.isEmpty()) {
            return Collections.emptyList();
        }
        String upperText = recognizedText.toUpperCase(Locale.ROOT);
        Matcher matcher = PLATE_PATTERN.matcher(upperText);
        Set<String> unique = new LinkedHashSet<>();
        while (matcher.find()) {
            String normalized = SEPARATORS.matcher(matcher.group()).replaceAll("");
            if (normalized.length() == 7 || normalized.length() == 8) {
                unique.add(normalized);
            }
        }
        return new ArrayList<>(unique);
    }

    private static Bitmap frameAt(MediaMetadataRetriever retriever, long timeMicros) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                    timeMicros,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    OCR_WIDTH,
                    OCR_HEIGHT);
        }
        return retriever.getFrameAtTime(timeMicros, MediaMetadataRetriever.OPTION_CLOSEST);
    }

    private static long parseDuration(String duration) {
        if (duration == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(duration));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    static final class Result {
        final List<String> plates;
        final boolean completed;

        Result(List<String> plates, boolean completed) {
            this.plates = Collections.unmodifiableList(new ArrayList<>(plates));
            this.completed = completed;
        }

        static Result retryLater() {
            return new Result(Collections.emptyList(), false);
        }
    }

    private static final class CandidateScore {
        int occurrences = 1;
        final int firstSeen;

        CandidateScore(int firstSeen) {
            this.firstSeen = firstSeen;
        }
    }
}
