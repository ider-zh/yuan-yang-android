package com.yuanyang.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.function.IntConsumer;

/** Computes offline, export-only transformations. Only transforms and one image are retained. */
final class PersonAlignment implements Timelapse.Alignment {
    static final String MODEL = "pose_landmarker_lite.task";
    private static final float MAX_ZOOM = 1.20f;
    private final Context context;
    private Matrix[] matrices;
    private int aligned, skipped;
    private float zoom = 1f;
    private boolean unavailable;

    PersonAlignment(Context context) { this.context = context.getApplicationContext(); }
    @Override public Matrix matrix(int index) { return matrices[index]; }
    @Override public float zoom() { return zoom; }

    String summary() {
        if (unavailable) return "此设备暂无法对齐人物，已按原画面导出。";
        if (aligned == 0) return "没有找到可可靠对齐的人物，已按原画面导出。";
        return "已对齐 " + aligned + " 张；" + (skipped == 0 ? "其余为参考照。" :
                "有 " + skipped + " 张未对齐（人物不清晰、角度变化或需要裁剪太多）。");
    }

    @Override public void prepare(List<File> frames, int w, int h, IntConsumer progress)
            throws IOException {
        matrices = new Matrix[frames.size()];
        int refIndex = -1;
        PoseAlignment.Point[] ref = null;
        try {
            BaseOptions base = BaseOptions.builder().setModelAssetPath(MODEL).build();
            PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(base)
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumPoses(2)
                    .setMinPoseDetectionConfidence(.55f)
                    .setMinPosePresenceConfidence(.55f)
                    .build();
            try (PoseLandmarker detector = PoseLandmarker.createFromOptions(context, options)) {
                Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                RectF canvasBounds = new RectF(0, 0, w, h);
                for (int i = 0; i < frames.size(); i++) {
                    checkCancelled();
                    Bitmap photo = PhotoTools.load(frames.get(i), 2 * Math.max(w, h));
                    if (photo == null) throw new IOException("无法读取第 " + (i + 1) + " 张照片");
                    PoseAlignment.Point[] current;
                    Bitmap input = null;
                    try {
                        input = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(input);
                        canvas.drawColor(0xff000000);
                        PhotoTools.drawCover(canvas, photo, canvasBounds, paint);
                        // Bitmap-backed MPImage owns this bitmap and recycles it on close.
                        current = points(detector, input);
                    } finally {
                        photo.recycle();
                        if (input != null && !input.isRecycled()) input.recycle();
                    }
                    checkCancelled();
                    if (current != null) {
                        if (ref == null && PoseAlignment.fit(current, current, w, h) != null) {
                            ref = current;
                            refIndex = i;
                        } else if (ref != null) {
                            PoseAlignment.Transform t = PoseAlignment.fit(current, ref, w, h);
                            if (t != null) {
                                Matrix m = toMatrix(t);
                                float required = borderFreeZoom(m, w, h);
                                if (Float.isFinite(required)) {
                                    zoom = Math.max(zoom, required);
                                    matrices[i] = m;
                                    aligned++;
                                }
                            }
                        }
                    }
                    progress.accept((int) (45L * (i + 1) / frames.size()));
                }
            }
        } catch (RuntimeException | LinkageError e) {
            checkCancelled();
            Log.w("FrameAgain", "Offline person alignment unavailable", e);
            unavailable = true;
            matrices = new Matrix[frames.size()];
            aligned = 0;
            zoom = 1f;
        }
        skipped = frames.size() - aligned - (refIndex >= 0 ? 1 : 0);
    }

    private static PoseAlignment.Point[] points(PoseLandmarker detector, Bitmap bitmap) {
        try (MPImage image = new BitmapImageBuilder(bitmap).build()) {
            List<List<NormalizedLandmark>> batch = detector.detect(image).landmarks();
            if (batch.size() != 1 || batch.get(0).size() != 33) return null;
            PoseAlignment.Point[] points = new PoseAlignment.Point[33];
            for (int i = 0; i < points.length; i++) {
                NormalizedLandmark p = batch.get(0).get(i);
                double confidence = Math.min(p.visibility().orElse(0f), p.presence().orElse(0f));
                points[i] = new PoseAlignment.Point(p.x() * bitmap.getWidth(),
                        p.y() * bitmap.getHeight(), confidence);
            }
            return points;
        }
    }

    private static Matrix toMatrix(PoseAlignment.Transform t) {
        Matrix m = new Matrix();
        m.setValues(new float[]{(float)t.a, (float)-t.b, (float)t.tx,
                (float)t.b, (float)t.a, (float)t.ty, 0, 0, 1});
        return m;
    }

    private static float borderFreeZoom(Matrix transform, int w, int h) {
        Matrix inverse = new Matrix();
        if (!transform.invert(inverse) || !covered(inverse, w, h, MAX_ZOOM)) return Float.NaN;
        if (covered(inverse, w, h, 1f)) return 1f;
        float low = 1f, high = MAX_ZOOM;
        for (int i = 0; i < 14; i++) {
            float mid = (low + high) * .5f;
            if (covered(inverse, w, h, mid)) high = mid;
            else low = mid;
        }
        return high;
    }

    private static boolean covered(Matrix inverse, int w, int h, float z) {
        float left = w * (1 - 1 / z) * .5f, top = h * (1 - 1 / z) * .5f;
        float[] corners = {left, top, w - left, top, w - left, h - top, left, h - top};
        inverse.mapPoints(corners);
        for (int i = 0; i < corners.length; i += 2) {
            float x = corners[i], y = corners[i + 1];
            if (x < -.01f || x > w + .01f || y < -.01f || y > h + .01f) return false;
        }
        return true;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消对齐");
    }
}
