package com.yuanyang.camera;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.Node;

/** JVM-only check: independent ImageIO GIF reader, YUV vectors, cancellation and MIME allowlist. */
public final class TimelapseMediaJvmTest {
    private static final int W = 360;
    private static final int H = 480;

    public static void main(String[] args) throws Exception {
        int[][] frames = new int[4][W * H];
        Random random = new Random(883);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                frames[0][i] = 0xff000000 | random.nextInt(0x1000000);
                frames[1][i] = 0xff000000 | ((x / 8) * 5 << 16) | ((y / 6) * 3 << 8) | ((x + y) / 5);
                frames[2][i] = 0xffff0020;
                frames[3][i] = x == W / 2 ? 0xff002010 : 0xfffffefe;
            }
        }
        checkGif(frames);
        checkGif(new int[][]{frames[2]});
        checkYuv();
        checkProgressAndInterruption(frames[0]);
        checkShareMime();
        System.out.println("Verified GIF pixels, timing, single-frame export, YUV, progress, cancellation, MIME.");
    }

    private static void checkGif(int[][] frames) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Timelapse.Gif encoded = new Timelapse.Gif(output, W, H);
        for (int[] frame : frames) encoded.frame(frame);
        encoded.finish();
        final byte[] data = output.toByteArray();
        ImageReader decoder = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            decoder.setInput(input);
            check(decoder.getNumImages(true) == frames.length, "frame count");
            for (int f = 0; f < frames.length; f++) {
                BufferedImage decoded = decoder.read(f);
                check(decoded.getWidth() == W && decoded.getHeight() == H, "frame dimensions");
                Node metadata = decoder.getImageMetadata(f).getAsTree("javax_imageio_gif_image_1.0");
                boolean correctDelay = false;
                for (Node n = metadata.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if ("GraphicControlExtension".equals(n.getNodeName()))
                        correctDelay = "35".equals(n.getAttributes().getNamedItem("delayTime").getNodeValue());
                }
                check(correctDelay, "350 ms frame delay");
                for (int i = 0; i < W * H; i++) {
                    if (decoded.getRGB(i % W, i / W) != paletteColor(frames[f][i]))
                        throw new AssertionError("GIF frame " + f + " pixel " + i);
                }
            }
        } finally {
            decoder.dispose();
        }
    }

    private static int paletteColor(int rgb) {
        return 0xff000000 | (((rgb >>> 21) & 7) * 255 / 7 << 16)
                | (((rgb >>> 13) & 7) * 255 / 7 << 8) | ((rgb >>> 6) & 3) * 85;
    }

    private static void checkYuv() throws Exception {
        byte[] out = new byte[12];
        int[] source = new int[8];
        Arrays.fill(source, 0xff000000);
        Timelapse.rgbToYuv(source, out, 4, 2, false);
        for (int i = 0; i < 8; i++) check((out[i] & 255) == 16, "black luma");
        for (int i = 8; i < 12; i++) check((out[i] & 255) == 128, "black chroma");
        Arrays.fill(source, 0xffffffff);
        Timelapse.rgbToYuv(source, out, 4, 2, true);
        for (int i = 0; i < 8; i++) check((out[i] & 255) == 235, "white luma");
        for (int i = 8; i < 12; i++) check((out[i] & 255) == 128, "white chroma");

        source = new int[]{0xffff0000, 0xffff0000, 0xff0000ff, 0xff0000ff,
                0xffff0000, 0xffff0000, 0xff0000ff, 0xff0000ff};
        Timelapse.rgbToYuv(source, out, 4, 2, false);
        check((out[0] & 255) == 82 && (out[2] & 255) == 41, "red and blue luma");
        check((out[8] & 255) == 90 && (out[9] & 255) == 240, "planar U");
        check((out[10] & 255) == 240 && (out[11] & 255) == 110, "planar V");
        Timelapse.rgbToYuv(source, out, 4, 2, true);
        check((out[8] & 255) == 90 && (out[9] & 255) == 240
                && (out[10] & 255) == 240 && (out[11] & 255) == 110, "NV12 chroma order");
    }

    private static void checkProgressAndInterruption(int[] frame) throws Exception {
        List<Integer> actual = new ArrayList<>();
        Timelapse.Progress progress = new Timelapse.Progress(actual::add);
        progress.report(0);
        progress.frame(1, 2);
        progress.frame(0, 2); // Simulated encoder fallback.
        progress.frame(2, 2);
        progress.report(100);
        check(actual.equals(Arrays.asList(0, 49, 99, 100)), "monotonic progress");
        actual.clear();
        Timelapse.Progress withAlignment = new Timelapse.Progress(actual::add, 45);
        withAlignment.report(0);
        withAlignment.report(23);
        withAlignment.report(45);
        withAlignment.frame(1, 2);
        withAlignment.frame(0, 2);
        withAlignment.frame(2, 2);
        withAlignment.report(100);
        check(actual.equals(Arrays.asList(0, 23, 45, 72, 99, 100)), "alignment and encoding share monotonic progress");
        Timelapse.Gif gif = new Timelapse.Gif(new ByteArrayOutputStream(), W, H);
        Thread.currentThread().interrupt();
        try {
            gif.frame(frame);
            throw new AssertionError("expected cancellation");
        } catch (InterruptedIOException expected) {
            check(Thread.currentThread().isInterrupted(), "interruption flag preserved");
        } finally {
            Thread.interrupted();
        }
    }

    private static void checkShareMime() {
        String prefix = "yuanyang-01234567-89ab-cdef-0123-456789abcdef.";
        check("image/jpeg".equals(ShareProvider.mimeType(prefix + "jpg")), "JPEG MIME");
        check("image/gif".equals(ShareProvider.mimeType(prefix + "gif")), "GIF MIME");
        check("video/mp4".equals(ShareProvider.mimeType(prefix + "mp4")), "MP4 MIME");
        check(ShareProvider.mimeType(prefix + "bin") == null, "unrecognized file");
        check(ShareProvider.mimeType("../" + prefix + "jpg") == null, "reject traversal");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
