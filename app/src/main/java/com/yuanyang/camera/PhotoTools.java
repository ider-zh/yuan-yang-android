package com.yuanyang.camera;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PhotoTools {
    private static final int WIDTH = 1056;
    private static final int MAX_EXPORT_HEIGHT = 6200;
    private static final int INK = Color.rgb(30, 42, 36);
    private static final int MUTED = Color.rgb(84, 104, 94);
    private static final int PAPER = Color.rgb(244, 246, 240);
    private static final int IMAGE_MAT = Color.rgb(226, 232, 226);

    private PhotoTools() {}

    /** Loads a bounded preview, respecting all EXIF orientations including reflected photos. */
    static Bitmap load(File file, int maxSide) {
        if (file == null || !file.isFile() || maxSide <= 0) return null;
        String path = file.getAbsolutePath();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        int max = Math.max(options.outWidth, options.outHeight);
        if (max <= 0) return null;
        int sample = 1;
        while ((long) max > 2L * maxSide * sample) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        if (bitmap == null) return null;

        Matrix matrix = new Matrix();
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            orientation = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException ignored) {
            // Some valid image files have no readable EXIF metadata.
        }
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                break;
        }
        float scale = Math.min(1f, (float) maxSide / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scale < 1f) matrix.postScale(scale, scale);
        if (matrix.isIdentity()) return bitmap;
        Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (result != bitmap) bitmap.recycle();
        return result;
    }

    /** Draws the image with centered, aspect-preserving cropping to the requested rectangle. */
    static void drawCover(Canvas canvas, Bitmap image, RectF dst, Paint paint) {
        if (image == null || image.isRecycled() || dst.isEmpty()) return;
        float scale = Math.max(dst.width() / image.getWidth(), dst.height() / image.getHeight());
        RectF fitted = fitRect(image, dst, scale);
        int save = canvas.save();
        canvas.clipRect(dst);
        canvas.drawBitmap(image, null, fitted, paint);
        canvas.restoreToCount(save);
    }

    private static RectF fitRect(Bitmap image, RectF dst, float scale) {
        float halfW = image.getWidth() * scale / 2f;
        float halfH = image.getHeight() * scale / 2f;
        return new RectF(dst.centerX() - halfW, dst.centerY() - halfH,
                dst.centerX() + halfW, dst.centerY() + halfH);
    }

    private static void drawFit(Canvas canvas, Bitmap image, RectF dst, Paint paint) {
        float scale = Math.min(dst.width() / image.getWidth(), dst.height() / image.getHeight());
        canvas.drawBitmap(image, null, fitRect(image, dst, scale), paint);
    }

    /** Writes one JPEG sheet to a private cache file; original images never leave app storage. */
    static File exportPairs(Context ctx, String title, List<String> labels,
            List<File> before, List<File> after) throws IOException {
        List<Integer> complete = new ArrayList<>();
        int size = Math.min(before.size(), after.size());
        for (int i = 0; i < size; i++) {
            if (before.get(i) != null && after.get(i) != null) complete.add(i);
        }
        if (complete.isEmpty()) throw new IOException("还没有拍摄完成的对照组");
        final int header = 146;
        final int bottom = 40;
        final int rowHeight = Math.min(472, (MAX_EXPORT_HEIGHT - header - bottom) / complete.size());
        if (rowHeight < 100) throw new IOException("对照组太多，无法清晰地合成一张图片");
        Bitmap sheet = Bitmap.createBitmap(WIDTH, header + complete.size() * rowHeight + bottom,
                Bitmap.Config.RGB_565);
        File output = null;
        boolean ok = false;
        try {
            Canvas canvas = new Canvas(sheet);
            canvas.drawColor(PAPER);
            TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            final int margin = 34;
            final int gap = 14;
            final int panelWidth = (WIDTH - 2 * margin - gap) / 2;
            caption.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            caption.setColor(INK);
            caption.setTextSize(48);
            canvas.drawText(TextUtils.ellipsize(clean(title, "原样"), caption, WIDTH - 2 * margin,
                    TextUtils.TruncateAt.END).toString(), margin, 64, caption);
            caption.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            caption.setColor(MUTED);
            caption.setTextSize(25);
            canvas.drawText("原样 · 照片对照    " + complete.size() + " 组", margin, 108, caption);
            for (int row = 0; row < complete.size(); row++) {
                int i = complete.get(row);
                int y = header + row * rowHeight;
                String fallback = "拍摄点 " + (i + 1);
                String label = clean(i < labels.size() ? labels.get(i) : null, fallback);
                caption.setColor(INK);
                caption.setTextSize(31);
                canvas.drawText(TextUtils.ellipsize(label, caption, WIDTH - 2 * margin,
                        TextUtils.TruncateAt.END).toString(), margin, y + 28, caption);
                caption.setColor(MUTED);
                caption.setTextSize(20);
                canvas.drawText("基准", margin, y + 54, caption);
                canvas.drawText("复拍", margin + panelWidth + gap, y + 54, caption);
                RectF left = new RectF(margin, y + 62, margin + panelWidth, y + rowHeight - 14);
                RectF right = new RectF(left);
                right.offset(panelWidth + gap, 0);
                box.setColor(IMAGE_MAT);
                canvas.drawRect(left, box);
                canvas.drawRect(right, box);
                drawExportImage(canvas, before.get(i), left, imagePaint, label);
                drawExportImage(canvas, after.get(i), right, imagePaint, label);
            }
            output = createExportFile(ctx, "jpg");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!sheet.compress(Bitmap.CompressFormat.JPEG, 90, stream))
                    throw new IOException("不能写入导出图片");
            }
            ok = true;
            return output;
        } finally {
            sheet.recycle();
            if (!ok && output != null) output.delete();
        }
    }

    private static void drawExportImage(Canvas canvas, File file, RectF dst, Paint paint, String label)
            throws IOException {
        Bitmap image = load(file, (int) Math.max(dst.width(), dst.height()));
        if (image == null) throw new IOException("无法读取「" + label + "」的照片");
        try {
            drawFit(canvas, image, dst, paint);
        } finally {
            image.recycle();
        }
    }

    private static String clean(String text, String fallback) {
        if (text == null) return fallback;
        String value = text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.isEmpty() ? fallback : value;
    }

    static File createExportFile(Context ctx, String extension) throws IOException {
        if (!"jpg".equals(extension) && !"gif".equals(extension) && !"mp4".equals(extension))
            throw new IOException("不支持的导出格式");
        File directory = new File(ctx.getCacheDir(), ShareProvider.DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("不能创建导出文件夹");
        File output = new File(directory, "yuanyang-" + UUID.randomUUID() + "." + extension);
        if (!output.createNewFile()) throw new IOException("不能创建导出文件");
        return output;
    }

    static void share(Activity activity, File file) {
        activity.startActivity(shareChooser(activity, file));
    }

    static Intent shareChooser(Context context, File file) {
        String mime = file == null ? null : ShareProvider.mimeType(file.getName());
        if (mime == null || !file.isFile())
            throw new IllegalArgumentException("分享文件不可用");
        String label = "image/jpeg".equals(mime) ? "对照图" : "延时影像";
        Uri uri = new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".share")
                .appendPath(file.getName()).build();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.setClipData(ClipData.newRawUri(label, uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(intent, "分享" + label);
    }
}
