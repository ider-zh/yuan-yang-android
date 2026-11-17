package com.yuanyang.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

final class Timelapse {
    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    @SuppressWarnings("deprecation")
    private static final int PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
    @SuppressWarnings("deprecation")
    private static final int SEMIPLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    private static final int FLEXIBLE = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    private static final long VIDEO_STEP_US = 350_000;
    private static final int GIF_DELAY_CS = 35;
    private Timelapse() {}

    /** Worker-thread only. Original JPEGs are decoded one at a time and remain unmodified. */
    static File export(Context ctx, List<File> frames, boolean video) throws IOException {
        return export(ctx, frames, video, null);
    }

    /** Reports increasing percentages on the worker thread. Interrupt that thread to cancel. */
    static File export(Context ctx, List<File> frames, boolean video, IntConsumer progress)
            throws IOException {
        return export(ctx, frames, video, progress, null);
    }

    interface Alignment {
        void prepare(List<File> frames, int w, int h, IntConsumer progress) throws IOException;
        Matrix matrix(int frame);
        float zoom();
    }

    static File export(Context ctx, List<File> frames, boolean video, IntConsumer progress,
                       Alignment alignment) throws IOException {
        checkCancelled();
        Progress reporting = new Progress(progress, alignment == null ? 0 : 45);
        reporting.report(0);
        List<File> ordered = new ArrayList<>(frames);
        if (ordered.isEmpty()) throw new IOException("选定日期内没有照片可导出");
        checkCancelled();
        Bitmap first = PhotoTools.load(ordered.get(0), 640);
        if (first == null) throw new SourcePhotoException(1);
        boolean portrait = first.getHeight() >= first.getWidth();
        first.recycle();
        int shortSide = video ? 480 : 360, longSide = video ? 640 : 480;
        int w = portrait ? shortSide : longSide, h = portrait ? longSide : shortSide;
        if (alignment != null) {
            alignment.prepare(ordered, w, h, reporting::report);
            reporting.report(45);
        }
        checkCancelled();
        File output = PhotoTools.createExportFile(ctx, video ? "mp4" : "gif");
        boolean done = false;
        try {
            if (video) writeVideo(output, ordered, w, h, reporting, alignment);
            else writeGif(output, ordered, w, h, reporting, alignment);
            checkCancelled();
            reporting.report(100);
            checkCancelled();
            done = true;
            return output;
        } finally {
            if (!done) output.delete();
        }
    }

    static final class Progress {
        private final IntConsumer callback;
        private final int offset;
        private int last = -1;

        Progress(IntConsumer callback) { this(callback, 0); }
        Progress(IntConsumer callback, int offset) { this.callback = callback; this.offset = offset; }

        void report(int percent) {
            if (percent <= last) return; // Codec fallback may restart at the first frame.
            last = percent;
            if (callback != null) callback.accept(percent);
        }

        void frame(int done, int total) {
            report(offset + (int) (done * (99L - offset) / total)); // 100 means output has finalized.
        }
    }

    private static final class SourcePhotoException extends IOException {
        private static final long serialVersionUID = 1L;
        SourcePhotoException(int photo) { super("无法读取第 " + photo + " 张照片"); }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消延时导出");
    }

    private static final class Render implements AutoCloseable {
        final int w, h;
        final Bitmap surface;
        final Canvas canvas;
        final RectF bounds;
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final int[] pixels;
        final Alignment alignment;
        Render(int w, int h, Alignment alignment) {
            this.w = w; this.h = h; this.alignment = alignment;
            surface = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(surface);
            bounds = new RectF(0, 0, w, h);
            pixels = new int[w * h];
        }
        void next(File file, int index) throws IOException {
            checkCancelled();
            Bitmap pic = PhotoTools.load(file, Math.max(w, h) * 2);
            if (pic == null) throw new SourcePhotoException(index + 1);
            try {
                canvas.drawColor(0xff000000);
                int restore = canvas.save();
                try {
                    if (alignment != null) {
                        canvas.scale(alignment.zoom(), alignment.zoom(), w * .5f, h * .5f);
                        Matrix adjust = alignment.matrix(index);
                        if (adjust != null) canvas.concat(adjust);
                    }
                    PhotoTools.drawCover(canvas, pic, bounds, paint);
                } finally { canvas.restoreToCount(restore); }
                surface.getPixels(pixels, 0, w, 0, 0, w, h);
            } finally {
                pic.recycle();
            }
        }
        @Override public void close() { surface.recycle(); }
    }

    private static void writeGif(File output, List<File> frames, int w, int h, Progress progress, Alignment alignment)
            throws IOException {
        try (Render render = new Render(w, h, alignment);
             OutputStream stream = new BufferedOutputStream(new FileOutputStream(output), 16_384)) {
            Gif gif = new Gif(stream, w, h);
            for (int i = 0; i < frames.size(); i++) {
                render.next(frames.get(i), i);
                gif.frame(render.pixels);
                progress.frame(i + 1, frames.size());
            }
            gif.finish();
        }
    }

    // GIF89a with a fixed RGB332 table and a streaming, bounded GIF-LZW dictionary.
    static final class Gif {
        private final OutputStream out;
        private final int w, h;
        private final int[] keys = new int[1024];
        private final int[] codes = new int[1024];
        private final byte[] block = new byte[255];
        private int byteCount, packed, packedBits;

        Gif(OutputStream out, int w, int h) throws IOException {
            this.out = out; this.w = w; this.h = h;
            ascii("GIF89a");
            word(w); word(h); bytes(0xf7, 0, 0);
            for (int i = 0; i < 256; i++)
                bytes(((i >>> 5) & 7) * 255 / 7, ((i >>> 2) & 7) * 255 / 7, (i & 3) * 85);
            bytes(0x21, 0xff, 11); ascii("NETSCAPE2.0");
            bytes(3, 1, 0, 0, 0);
        }

        void frame(int[] argb) throws IOException {
            checkCancelled();
            bytes(0x21, 0xf9, 4, 0x04);
            word(GIF_DELAY_CS); bytes(0, 0, 0x2c);
            word(0); word(0); word(w); word(h);
            bytes(0, 8);
            byteCount = packed = packedBits = 0;
            Arrays.fill(codes, 0);
            code(256);
            int prefix = sample(argb[0]), next = 258;
            int length = w * h;
            for (int i = 1; i < length; i++) {
                if ((i & 4095) == 0) checkCancelled();
                int value = sample(argb[i]), key = (prefix << 8) | value;
                int slot = ((key * 0x9e3779b1) >>> 22);
                while (codes[slot] != 0 && keys[slot] != key) slot = (slot + 1) & 1023;
                if (codes[slot] != 0) {
                    prefix = codes[slot];
                    continue;
                }
                code(prefix);
                if (next == 511) {
                    // Clear before the decoder needs a 10-bit code. Maximum code is 510.
                    code(256);
                    Arrays.fill(codes, 0);
                    next = 258;
                } else {
                    keys[slot] = key;
                    codes[slot] = next++;
                }
                prefix = value;
            }
            code(prefix);
            code(257);
            if (packedBits > 0) push(packed);
            flushBlock();
            out.write(0);
        }

        private int sample(int rgb) {
            return ((rgb >> 16) & 0xe0) | ((rgb >> 11) & 0x1c) | ((rgb >> 6) & 3);
        }
        private void code(int value) throws IOException {
            packed |= value << packedBits;
            packedBits += 9;
            while (packedBits >= 8) {
                push(packed);
                packed >>>= 8;
                packedBits -= 8;
            }
        }
        private void push(int value) throws IOException {
            block[byteCount++] = (byte) value;
            if (byteCount == block.length) flushBlock();
        }
        private void flushBlock() throws IOException {
            if (byteCount != 0) {
                out.write(byteCount);
                out.write(block, 0, byteCount);
                byteCount = 0;
            }
        }
        private void word(int n) throws IOException { bytes(n & 255, (n >>> 8) & 255); }
        private void bytes(int... values) throws IOException { for (int value : values) out.write(value); }
        private void ascii(String value) throws IOException {
            for (int i = 0; i < value.length(); i++) out.write(value.charAt(i));
        }
        void finish() throws IOException { out.write(0x3b); }
    }

    private static void writeVideo(File file, List<File> frames, int w, int h, Progress progress, Alignment alignment)
            throws IOException {
        Exception last = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            checkCancelled();
            if (!info.isEncoder()) continue;
            MediaCodecInfo.CodecCapabilities caps;
            try { caps = info.getCapabilitiesForType(AVC); }
            catch (IllegalArgumentException ignored) { continue; }
            if (!caps.getVideoCapabilities().isSizeSupported(w, h)) continue;
            for (int desired : new int[]{FLEXIBLE, PLANAR, SEMIPLANAR}) {
                if (!has(caps.colorFormats, desired)) continue;
                try {
                    writeAvc(info.getName(), desired, file, frames, w, h, progress, alignment);
                    return;
                } catch (InterruptedIOException | SourcePhotoException stop) {
                    throw stop;
                } catch (IllegalArgumentException | IllegalStateException | IOException failure) {
                    last = failure;
                }
            }
        }
        throw new IOException("此设备无法生成 MP4，可改用 GIF 导出", last);
    }

    private static boolean has(int[] values, int want) {
        for (int value : values) if (value == want) return true;
        return false;
    }

    private static void writeAvc(String encoder, int color, File file, List<File> frames, int w, int h,
            Progress reporting, Alignment alignment) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(AVC, w, h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 3);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        // Older AOSP AVC encoders require a level whenever a profile is sent; omitting
        // both picks their Baseline default and lets the codec choose a supported level.
        if (android.os.Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        MediaCodec codec = MediaCodec.createByCodecName(encoder);
        MediaMuxer muxer = null;
        try (Render render = new Render(w, h, alignment)) {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            byte[] yuv = new byte[w * h * 3 / 2];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            // A single source photo is held for 350 ms using two identical 175 ms samples.
            int frameCount = Math.max(2, frames.size());
            long step = frames.size() == 1 ? VIDEO_STEP_US / 2 : VIDEO_STEP_US;
            int next = 0, track = -1, written = 0;
            boolean inputDone = false, outputDone = false;
            long progressAt = SystemClock.elapsedRealtime();
            while (!outputDone) {
                checkCancelled();
                boolean progress = false;
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(2_000);
                    if (index >= 0) {
                        if (next == frameCount) {
                            codec.queueInputBuffer(index, 0, 0, next * step,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            if (next < frames.size()) {
                                render.next(frames.get(next), next);
                                rgbToYuv(render.pixels, yuv, w, h, color == SEMIPLANAR);
                            }
                            if (color == FLEXIBLE) fillImage(codec.getInputImage(index), yuv, w, h);
                            else {
                                ByteBuffer target = codec.getInputBuffer(index);
                                if (target == null || target.capacity() < yuv.length)
                                    throw new IOException("视频编码器的图像缓冲区不可用");
                                target.clear();
                                target.put(yuv);
                            }
                            codec.queueInputBuffer(index, 0, yuv.length, next * step, 0);
                            next++;
                        }
                        progress = true;
                    }
                }
                final int status = codec.dequeueOutputBuffer(info, inputDone ? 10_000 : 2_000);
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (track != -1) throw new IOException("视频编码器重复变更格式");
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    progress = true;
                } else if (status >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            ByteBuffer data = codec.getOutputBuffer(status);
                            if (track == -1 || data == null) throw new IOException("无法读取编码的视频");
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            muxer.writeSampleData(track, data, info);
                            written++;
                            reporting.frame(Math.min(written, frameCount), frameCount);
                        }
                        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(status, false);
                    }
                    progress = true;
                }
                if (progress) progressAt = SystemClock.elapsedRealtime();
                else if (SystemClock.elapsedRealtime() - progressAt > 10_000)
                    throw new IOException("视频编码超时");
            }
            if (track < 0 || written < frameCount) throw new IOException("视频编码未保留全部照片");
            info.set(0, 0, frameCount * step, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            muxer.writeSampleData(track, ByteBuffer.allocateDirect(1), info);
            muxer.stop();
        } finally {
            if (muxer != null) try { muxer.release(); } catch (RuntimeException ignored) {}
            try { codec.release(); } catch (RuntimeException ignored) {}
        }
    }

    private static void fillImage(Image image, byte[] i420, int w, int h) throws IOException {
        if (image == null) throw new IOException("视频编码器不支持可写入的图像平面");
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 3) throw new IOException("视频编码器的图像平面不匹配");
        for (int n = 0; n < 3; n++) {
            checkCancelled();
            int rowWidth = n == 0 ? w : w / 2;
            int rows = n == 0 ? h : h / 2;
            int start = n == 0 ? 0 : w * h + (n - 1) * w * h / 4;
            Image.Plane plane = planes[n];
            ByteBuffer bytes = plane.getBuffer();
            final int offset = bytes.position(), rowStride = plane.getRowStride(), step = plane.getPixelStride();
            if (offset + (rows - 1) * rowStride + (rowWidth - 1) * step >= bytes.limit())
                throw new IOException("视频编码器的图像缓冲区太小");
            for (int y = 0; y < rows; y++) {
                int row = offset + y * rowStride;
                if (step == 1) {
                    bytes.position(row);
                    bytes.put(i420, start + y * rowWidth, rowWidth);
                } else {
                    for (int x = 0; x < rowWidth; x++)
                        bytes.put(row + x * step, i420[start + y * rowWidth + x]);
                }
            }
        }
    }

    // Limited-range BT.601; average each 2 by 2 chroma block.
    static void rgbToYuv(int[] rgb, byte[] out, int w, int h, boolean nv12)
            throws InterruptedIOException {
        final int area = w * h;
        for (int y = 0; y < h; y += 2) {
            if ((y & 15) == 0) checkCancelled();
            for (int x = 0; x < w; x += 2) {
                int r = 0, g = 0, b = 0;
                for (int j = 0; j < 2; j++) {
                    for (int i = 0; i < 2; i++) {
                        int pos = (y + j) * w + x + i, p = rgb[pos];
                        int pr = (p >>> 16) & 255, pg = (p >>> 8) & 255, pb = p & 255;
                        out[pos] = (byte) (((66 * pr + 129 * pg + 25 * pb + 128) >> 8) + 16);
                        r += pr; g += pg; b += pb;
                    }
                }
                int u = ((-38 * r - 74 * g + 112 * b + 512) >> 10) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 512) >> 10) + 128;
                int uv = (y / 2) * (w / 2) + x / 2;
                if (nv12) {
                    out[area + uv * 2] = (byte) u;
                    out[area + uv * 2 + 1] = (byte) v;
                } else {
                    out[area + uv] = (byte) u;
                    out[area + area / 4 + uv] = (byte) v;
                }
            }
        }
    }
}
