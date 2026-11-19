package com.yuanyang.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Device-reported capabilities; never infer an optical lens from a zoom ratio. */
final class CameraOptions {
    private CameraOptions() {}

    static String albumKey(String album, String option) {
        return "album/" + (album == null || album.isEmpty() ? "@default" : album) + "/" + option;
    }

    static String rearLabel(int position, float mainAngle, float lensAngle) {
        if (position <= 0) return "后置默认镜头";
        if (isAngle(mainAngle) && isAngle(lensAngle)) {
            if (lensAngle >= mainAngle + 10f) return "广角镜头 " + position;
            if (lensAngle <= mainAngle - 10f) return "窄视角镜头 " + position;
        }
        return "后置镜头 " + (position + 1);
    }

    private static boolean isAngle(float angle) {
        return angle > 0 && angle < 180;
    }

    static List<Integer> validatedZoomRatios(List<Integer> ratios, int maxIndex) {
        if (maxIndex <= 0 || ratios == null || ratios.size() != maxIndex + 1
                || !Integer.valueOf(100).equals(ratios.get(0))) return Collections.emptyList();
        int last = 0;
        for (Integer ratio : ratios) {
            if (ratio == null || ratio < last) return Collections.emptyList();
            last = ratio;
        }
        return last > 100 ? new ArrayList<>(ratios) : Collections.emptyList();
    }

    static int zoomIndex(List<Integer> ratios, int requestedRatio) {
        int best = 0;
        long distance = Long.MAX_VALUE;
        for (int i = 0; i < ratios.size(); i++) {
            long next = Math.abs((long) ratios.get(i) - requestedRatio);
            if (next < distance) {
                distance = next;
                best = i;
            }
        }
        return best;
    }
}
