package com.yuanyang.camera;

/** Conservative 2-D torso registration for MediaPipe's 33 pose landmarks. */
final class PoseAlignment {
    private static final int[] TORSO = {11, 12, 23, 24};
    private static final int[] HEAD = {0, 7, 8};
    private static final double VISIBILITY = 0.60;
    private static final double MAX_ANGLE = Math.toRadians(14);
    private static final double MIN_SCALE = 0.86, MAX_SCALE = 1.17;

    private PoseAlignment() {}

    static final class Point {
        final double x, y, confidence;
        Point(double x, double y, double confidence) {
            this.x = x;
            this.y = y;
            this.confidence = confidence;
        }
    }

    static final class Transform {
        final double a, b, tx, ty;
        Transform(double a, double b, double tx, double ty) {
            this.a = a;
            this.b = b;
            this.tx = tx;
            this.ty = ty;
        }
        double x(double x, double y) { return a * x - b * y + tx; }
        double y(double x, double y) { return b * x + a * y + ty; }
    }

    static Transform fit(Point[] source, Point[] target, int width, int height) {
        if (source == null || target == null || source.length < 33 || target.length < 33
                || width <= 0 || height <= 0) return null;

        // Four torso points only. Hands, legs and torso-relative head movement must not steer
        // the transform. The face can only corroborate a hypothesis if a torso outlier is dropped.
        final boolean[] usable = new boolean[TORSO.length];
        int count = 0;
        for (int i = 0; i < TORSO.length; i++) {
            int k = TORSO[i];
            usable[i] = visible(source[k], width, height) && visible(target[k], width, height);
            if (usable[i]) count++;
        }
        if (count < 3 || !geometry(source, usable, width, height)
                || !geometry(target, usable, width, height)) return null;

        final double minSide = Math.min(width, height);
        final double bodyLength = Math.min(torsoSpan(source, usable), torsoSpan(target, usable));
        if (!(bodyLength >= minSide * 0.10)) return null;
        final double limit = Math.max(minSide * 0.009, Math.min(minSide * 0.031, bodyLength * 0.095));

        Transform regular = regress(source, target, usable);
        if (safe(regular, width, height) && residual(regular, source, target, usable, limit))
            return regular;
        if (count < 4) return null;

        // A low-quality fourth anchor may be wrong; try each corresponding three-point subset.
        // Accept only a unique torso hypothesis and only if the head independently corroborates.
        Transform robust = null;
        for (int i = 0; i < usable.length; i++) {
            int dropped = TORSO[i];
            if (confidence(source[dropped], target[dropped]) >= 0.80) continue;
            usable[i] = false;
            Transform candidate = regress(source, target, usable);
            boolean accepted = safe(candidate, width, height)
                    && geometry(source, usable, width, height)
                    && geometry(target, usable, width, height)
                    && residual(candidate, source, target, usable, limit * 0.67)
                    && headSupport(candidate, source, target, width, height, limit);
            usable[i] = true;
            if (accepted) {
                if (robust != null) return null; // ambiguous motion
                robust = candidate;
            }
        }
        return robust;
    }

    private static boolean visible(Point p, int w, int h) {
        return p != null && Double.isFinite(p.x) && Double.isFinite(p.y)
                && Double.isFinite(p.confidence) && p.confidence >= VISIBILITY && p.confidence <= 1
                && p.x >= 0 && p.x <= w && p.y >= 0 && p.y <= h;
    }

    private static double confidence(Point a, Point b) {
        return Math.min(a.confidence, b.confidence);
    }

    private static double torsoSpan(Point[] points, boolean[] usable) {
        double sx = 0, sy = 0, hx = 0, hy = 0;
        int shoulders = 0, hips = 0;
        for (int i = 0; i < TORSO.length; i++) {
            if (!usable[i]) continue;
            Point p = points[TORSO[i]];
            if (i < 2) {
                sx += p.x; sy += p.y; shoulders++;
            } else {
                hx += p.x; hy += p.y; hips++;
            }
        }
        if (shoulders == 0 || hips == 0) return 0;
        return Math.hypot(sx / shoulders - hx / hips, sy / shoulders - hy / hips);
    }

    private static boolean geometry(Point[] p, boolean[] usable, int w, int h) {
        double min = Math.min(w, h), span = torsoSpan(p, usable);
        if (span < min * 0.10) return false;
        if (usable[0] && usable[1] && distance(p[11], p[12]) < Math.max(min * 0.035, span * 0.20))
            return false;
        if (usable[2] && usable[3] && distance(p[23], p[24]) < Math.max(min * 0.028, span * 0.14))
            return false;
        if (usable[0] && usable[1] && usable[2] && usable[3]) {
            // Reject left/right swaps or markedly crossed shoulders vs hips.
            double sx = p[12].x - p[11].x, sy = p[12].y - p[11].y;
            double hx = p[24].x - p[23].x, hy = p[24].y - p[23].y;
            if (sx * hx + sy * hy < 0.45 * Math.hypot(sx, sy) * Math.hypot(hx, hy)) return false;
        }
        double largest = 0;
        for (int i = 0; i < TORSO.length; i++) {
            if (!usable[i]) continue;
            Point a = p[TORSO[i]];
            for (int j = i + 1; j < TORSO.length; j++) {
                if (!usable[j]) continue;
                Point b = p[TORSO[j]];
                for (int k = j + 1; k < TORSO.length; k++) {
                    if (!usable[k]) continue;
                    Point c = p[TORSO[k]];
                    double area2 = Math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x));
                    largest = Math.max(largest, area2);
                }
            }
        }
        return largest >= span * span * 0.12;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static Transform regress(Point[] source, Point[] target, boolean[] usable) {
        double wt = 0, cx = 0, cy = 0, dx = 0, dy = 0;
        for (int i = 0; i < TORSO.length; i++) {
            if (!usable[i]) continue;
            Point a = source[TORSO[i]], b = target[TORSO[i]];
            double w = confidence(a, b);
            w *= w;
            wt += w;
            cx += w * a.x;
            cy += w * a.y;
            dx += w * b.x;
            dy += w * b.y;
        }
        if (wt == 0) return null;
        cx /= wt; cy /= wt; dx /= wt; dy /= wt;
        double dot = 0, cross = 0, size = 0;
        for (int i = 0; i < TORSO.length; i++) {
            if (!usable[i]) continue;
            Point a = source[TORSO[i]], b = target[TORSO[i]];
            double w = confidence(a, b);
            w *= w;
            double x = a.x - cx, y = a.y - cy;
            double u = b.x - dx, v = b.y - dy;
            dot += w * (x * u + y * v);
            cross += w * (x * v - y * u);
            size += w * (x * x + y * y);
        }
        if (size <= 0) return null;
        double a = dot / size, b = cross / size;
        return new Transform(a, b, dx - a * cx + b * cy, dy - b * cx - a * cy);
    }

    private static double error(Transform fit, Point u, Point v) {
        return Math.hypot(fit.x(u.x, u.y) - v.x, fit.y(u.x, u.y) - v.y);
    }

    private static boolean residual(Transform fit, Point[] source, Point[] target,
            boolean[] usable, double limit) {
        int count = 0;
        double error2 = 0;
        for (int i = 0; i < TORSO.length; i++) {
            if (!usable[i]) continue;
            int k = TORSO[i];
            double e = error(fit, source[k], target[k]);
            if (e > limit * 1.30) return false;
            error2 += e * e;
            count++;
        }
        if (count < 3 || error2 > count * limit * limit) return false;
        // A 2-D similarity cannot correct a substantial turn: a complete left/right
        // pair should not be strongly foreshortened relative to the fitted scale.
        double scale = Math.hypot(fit.a, fit.b);
        for (int i = 0; i < TORSO.length; i += 2) {
            if (!usable[i] || !usable[i + 1]) continue;
            int left = TORSO[i], right = TORSO[i + 1];
            double expected = scale * distance(source[left], source[right]);
            double observed = distance(target[left], target[right]);
            if (observed < 0.84 * expected || observed > 1.19 * expected) return false;
        }
        return true;
    }

    private static boolean headSupport(Transform fit, Point[] source, Point[] target,
            int w, int h, double limit) {
        int votes = 0;
        for (int k : HEAD) {
            if (!visible(source[k], w, h) || !visible(target[k], w, h)) continue;
            if (error(fit, source[k], target[k]) <= limit * 1.1) votes++;
        }
        return votes >= 2;
    }

    private static boolean safe(Transform t, int w, int h) {
        if (t == null) return false;
        double scale = Math.hypot(t.a, t.b);
        if (!(scale >= MIN_SCALE && scale <= MAX_SCALE)) return false;
        if (Math.abs(Math.atan2(t.b, t.a)) > MAX_ANGLE) return false;
        double x = w / 2.0, y = h / 2.0;
        if (Math.hypot((t.x(x, y) - x) / w, (t.y(x, y) - y) / h) > 0.18) return false;
        // Both the original and unchanged output must retain at least 72% field of view.
        double area = intersection(t, w, h);
        return area / (w * (double) h * Math.max(1, scale * scale)) >= 0.72;
    }

    // Sutherland–Hodgman clip transformed source quadrilateral to the output canvas.
    private static double intersection(Transform t, int w, int h) {
        double[] x = {t.x(0, 0), t.x(w, 0), t.x(w, h), t.x(0, h), 0, 0, 0, 0};
        double[] y = {t.y(0, 0), t.y(w, 0), t.y(w, h), t.y(0, h), 0, 0, 0, 0};
        double[] ox = new double[8], oy = new double[8];
        int n = 4;
        for (int side = 0; side < 4 && n > 0; side++) {
            int count = 0;
            int prev = n - 1;
            double boundary = side == 0 || side == 2 ? 0 : side == 1 ? w : h;
            double[] axis = side < 2 ? x : y;
            boolean lower = side == 0 || side == 2;
            for (int cur = 0; cur < n; cur++) {
                boolean a = lower ? axis[prev] >= boundary : axis[prev] <= boundary;
                boolean b = lower ? axis[cur] >= boundary : axis[cur] <= boundary;
                if (a != b) {
                    double f = (boundary - axis[prev]) / (axis[cur] - axis[prev]);
                    ox[count] = x[prev] + f * (x[cur] - x[prev]);
                    oy[count++] = y[prev] + f * (y[cur] - y[prev]);
                }
                if (b) {
                    ox[count] = x[cur]; oy[count++] = y[cur];
                }
                prev = cur;
            }
            double[] swap = x; x = ox; ox = swap;
            swap = y; y = oy; oy = swap;
            n = count;
        }
        double twiceArea = 0;
        for (int i = 0; i < n; i++) {
            int prev = (i + n - 1) % n;
            twiceArea += x[prev] * y[i] - y[prev] * x[i];
        }
        return Math.abs(twiceArea) * 0.5;
    }
}
