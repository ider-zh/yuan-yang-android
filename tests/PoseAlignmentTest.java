package com.yuanyang.camera;

import java.util.Random;

public final class PoseAlignmentTest {
    private static final int W = 600, H = 800;

    public static void main(String[] ignored) {
        PoseAlignment.Point[] reference = torso();
        for (double scale : new double[]{0.92, 1, 1.08}) {
            for (int angle : new int[]{-8, 0, 8}) {
                PoseAlignment.Transform expected = aroundCenter(scale, angle, 31, -24);
                PoseAlignment.Point[] dest = apply(reference, expected);
                assertNear(fit(reference, dest), expected, 1e-8, "exact known transform");
                dest[15] = p(30, 700, 0.99); // new hand pose must not steer torso
                dest[16] = p(590, 100, 1.00);
                dest[0] = p(220, 250, 0.99); // turning head does not steer the ordinary fit
                assertNear(fit(reference, dest), expected, 1e-8, "ignore limbs and independent head motion");
                dest[24] = null;
                assertNear(fit(reference, dest), expected, 1e-8, "one occluded anchor");
                dest[23] = p(350, 520, 0.54);
                reject(fit(reference, dest), "two low confidence/missing anchors");
            }
        }
        PoseAlignment.Transform expected = aroundCenter(1.03, 4, 17, 9);
        PoseAlignment.Point[] outlier = apply(reference, expected);
        outlier[24] = p(outlier[24].x + 50, outlier[24].y + 15, 0.64);
        assertNear(fit(reference, outlier), expected, 1e-8, "moderate outlier confirmed by head");
        outlier[0] = outlier[7] = outlier[8] = null;
        reject(fit(reference, outlier), "unconfirmed outlier");
        outlier = apply(reference, expected);
        outlier[24] = p(outlier[24].x + 50, outlier[24].y + 15, 0.93);
        reject(fit(reference, outlier), "high-confidence perspective or deformation");

        reject(fit(reference, apply(reference, aroundCenter(1.0, 18, 0, 0))), "large roll");
        reject(fit(reference, apply(reference, aroundCenter(.75, 0, 0, 0))), "large zoom-out");
        reject(fit(reference, apply(reference, aroundCenter(1.2, 0, 0, 0))), "large zoom-in");
        reject(fit(reference, apply(reference, aroundCenter(1, 0, 130, 0))), "large translation");
        reject(fit(reference, apply(reference, aroundCenter(.86, 14, 82, 82))), "combined crop");
        PoseAlignment.Point[] turned = reference.clone();
        for (int i : new int[]{11, 12, 23, 24})
            turned[i] = p(300 + (turned[i].x - 300) * 0.76, turned[i].y, turned[i].confidence);
        reject(fit(reference, turned), "foreshortening without equivalent change in torso height");
        PoseAlignment.Point[] mirrored = reference.clone();
        for (int i = 0; i < 33; i++)
            if (mirrored[i] != null) mirrored[i] = p(W - mirrored[i].x, mirrored[i].y, mirrored[i].confidence);
        reject(fit(reference, mirrored), "left-right reflection");

        PoseAlignment.Point[] bad = apply(reference, expected);
        bad[11] = p(Double.NaN, bad[11].y, 0.98);
        bad[12] = p(310, 325, 0.31);
        reject(fit(reference, bad), "non-finite and low-confidence observations");
        bad = apply(reference, expected);
        bad[23] = p(350, -2, 0.95);
        bad[24] = p(880, 520, 0.95);
        reject(fit(reference, bad), "out-of-canvas observations");
        PoseAlignment.Point[] tiny = apply(reference, aroundCenter(.2, 0, 0, 0));
        reject(fit(tiny, tiny), "small or straight-line torso");
        reject(PoseAlignment.fit(null, reference, W, H), "null source");
        reject(PoseAlignment.fit(reference, new PoseAlignment.Point[3], W, H), "wrong schema");
        reject(PoseAlignment.fit(reference, reference, 0, H), "zero width");

        Random random = new Random(8990);
        for (int trial = 0; trial < 100; trial++) {
            PoseAlignment.Point[] noisy = apply(reference, expected);
            for (int k : new int[]{11, 12, 23, 24})
                noisy[k] = p(noisy[k].x + random.nextGaussian() * 3, noisy[k].y + random.nextGaussian() * 3, .90);
            PoseAlignment.Transform actual = fit(reference, noisy);
            if (actual == null || Math.hypot(actual.x(300, 400) - expected.x(300, 400),
                    actual.y(300, 400) - expected.y(300, 400)) > 8)
                throw new AssertionError("small realistic landmark jitter (trial " + trial + ")");
        }
        System.out.println("Verified torso transform, occlusion, unique outlier recovery, jitter and conservative rejection.");
    }

    private static PoseAlignment.Transform fit(PoseAlignment.Point[] a, PoseAlignment.Point[] b) {
        return PoseAlignment.fit(a, b, W, H);
    }

    private static PoseAlignment.Point p(double x, double y, double confidence) {
        return new PoseAlignment.Point(x, y, confidence);
    }

    private static PoseAlignment.Point[] torso() {
        PoseAlignment.Point[] pts = new PoseAlignment.Point[33];
        pts[0] = p(300, 215, .99);
        pts[7] = p(330, 227, .96);
        pts[8] = p(270, 221, .97);
        pts[11] = p(380, 312, .94);
        pts[12] = p(222, 324, .92);
        pts[23] = p(352, 511, .95);
        pts[24] = p(246, 515, .94);
        return pts;
    }

    private static PoseAlignment.Transform aroundCenter(double zoom, double degrees, double dx, double dy) {
        double angle = Math.toRadians(degrees);
        double a = Math.cos(angle) * zoom, b = Math.sin(angle) * zoom;
        return new PoseAlignment.Transform(a, b, W / 2.0 + dx - a * W / 2.0 + b * H / 2.0,
                H / 2.0 + dy - b * W / 2.0 - a * H / 2.0);
    }

    private static PoseAlignment.Point[] apply(PoseAlignment.Point[] src, PoseAlignment.Transform t) {
        PoseAlignment.Point[] dst = new PoseAlignment.Point[33];
        for (int i = 0; i < src.length; i++)
            if (src[i] != null)
                dst[i] = p(t.x(src[i].x, src[i].y), t.y(src[i].x, src[i].y), src[i].confidence);
        return dst;
    }

    private static void assertNear(PoseAlignment.Transform actual, PoseAlignment.Transform expected,
            double epsilon, String name) {
        if (actual == null || Math.abs(actual.a - expected.a) > epsilon
                || Math.abs(actual.b - expected.b) > epsilon || Math.abs(actual.tx - expected.tx) > epsilon
                || Math.abs(actual.ty - expected.ty) > epsilon) throw new AssertionError(name);
    }

    private static void reject(PoseAlignment.Transform actual, String name) {
        if (actual != null) throw new AssertionError("should reject: " + name);
    }
}
