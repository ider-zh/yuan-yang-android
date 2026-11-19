package com.yuanyang.camera;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CameraOptionsTest {
    private static void eq(Object actual, Object expected) {
        if (!actual.equals(expected)) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        eq(CameraOptions.rearLabel(0, 68f, 68f), "后置默认镜头");
        eq(CameraOptions.rearLabel(1, 68f, 97f), "广角镜头 1");
        eq(CameraOptions.rearLabel(2, 68f, 48f), "窄视角镜头 2");
        eq(CameraOptions.rearLabel(1, -1f, 97f), "后置镜头 2");
        eq(CameraOptions.rearLabel(1, 68f, 73f), "后置镜头 2");
        List<Integer> ratios = Arrays.asList(100, 130, 175, 300);
        eq(CameraOptions.validatedZoomRatios(ratios, 3), ratios);
        eq(CameraOptions.validatedZoomRatios(Arrays.asList(null, 130), 1), Collections.emptyList());
        eq(CameraOptions.validatedZoomRatios(Arrays.asList(100, 140, 130), 2), Collections.emptyList());
        eq(CameraOptions.validatedZoomRatios(Arrays.asList(100, 100), 1), Collections.emptyList());
        eq(CameraOptions.validatedZoomRatios(ratios, 2), Collections.emptyList());
        eq(CameraOptions.zoomIndex(ratios, 180), 2);
        eq(CameraOptions.zoomIndex(ratios, 700), 3);
        eq(CameraOptions.zoomIndex(ratios, 90), 0);
        eq(CameraOptions.zoomIndex(Arrays.asList(100, 150), 125), 0);
        eq(CameraOptions.albumKey("first", "rear"), "album/first/rear");
        for (String key : Arrays.asList("facing", "rear", "zoom/3", "macro/3")) {
            if (CameraOptions.albumKey("first", key).equals(CameraOptions.albumKey("second", key))) {
                throw new AssertionError("Album settings collided: " + key);
            }
        }
        System.out.println("PASS camera lens labeling, validated zoom ratios, closest zoom, independent album keys");
    }
}
