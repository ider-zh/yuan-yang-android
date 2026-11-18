#!/usr/bin/env python3
"""Test Android Back and visible navigation without accessing personal albums."""
import argparse
import os
import time
from pathlib import Path

from check_export_preview_ui import Ui, force_disposable, match, seed, tap_node, window
from check_native_redesign_ui import active_action, center, parse_count, visible_thumbs
from smoke_ui import APP, passed

ALBUM = "默认相册"
GALLERY = "继续拍摄"
CAMERA = f"打开当前相册：{ALBUM}"
VIEWER = "返回相册"
LIBRARY = "新建相册"


def screen(ui, label):
    return window(ui, label)[0]


def tap(ui, tree, label, *, contains=False):
    tap_node(ui, active_action(tree, label, contains=contains))


def gallery(ui, expected):
    tree = screen(ui, GALLERY)
    assert parse_count(tree) == expected, ("Gallery lost photos", parse_count(tree), expected)
    assert len(visible_thumbs(tree)) >= 2, "Expected populated album thumbnails"
    return tree


def exercise(ui, count, check_selection):
    camera = screen(ui, CAMERA)
    tap(ui, camera, CAMERA)
    gallery(ui, count)
    ui.back()
    camera = screen(ui, CAMERA)
    passed("Android Back from current album restores the same camera")

    tap(ui, camera, CAMERA)
    current = gallery(ui, count)
    first = visible_thumbs(current)[0]
    tap_node(ui, first)
    screen(ui, VIEWER)
    ui.back()
    current = gallery(ui, count)
    tap_node(ui, visible_thumbs(current)[0])
    viewer = screen(ui, VIEWER)
    tap(ui, viewer, VIEWER)
    current = gallery(ui, count)
    passed("Android Back and the viewer's Return button both restore the same album")

    if check_selection:
        x, y = center(visible_thumbs(current)[0])
        ui.shell("input", "swipe", str(x), str(y), str(x), str(y), "850")
        screen(ui, "已选 1 张")
        ui.back()
        current = gallery(ui, count)
        assert match(current, "已选 1 张") is None
        passed("Back clears selection before leaving the album")

    tap(ui, current, GALLERY)
    camera = screen(ui, CAMERA)
    passed("Visible Continue Shooting returns to camera")

    tap(ui, camera, "所有相册")
    screen(ui, LIBRARY)
    ui.back()
    camera = screen(ui, CAMERA)
    passed("Android Back from All Albums opened by camera restores the same camera")

    tap(ui, camera, "所有相册")
    library = screen(ui, LIBRARY)
    tap(ui, library, "返回拍摄", contains=True)
    camera = screen(ui, CAMERA)
    passed("Visible Return to Camera leaves All Albums opened by camera")

    tap(ui, camera, CAMERA)
    current = gallery(ui, count)
    tap(ui, current, "所有相册")
    screen(ui, LIBRARY)
    ui.back()
    current = gallery(ui, count)
    passed("Android Back from All Albums opened by the gallery restores the current album")

    tap(ui, current, "制作影像")
    screen(ui, "每天一张")
    ui.back()
    gallery(ui, count)
    passed("Android Back dismisses export options before leaving the gallery")

    ui.back()
    screen(ui, CAMERA)
    ui.back()
    for _ in range(20):
        activities = ui.shell("dumpsys", "activity", "activities")
        resumed = next((line for line in activities.splitlines()
                        if "mResumedActivity:" in line or "topResumedActivity=" in line), "")
        if resumed and APP + "/" not in resumed:
            break
        time.sleep(.3)
    else:
        raise AssertionError("Camera-root Back neither left the app nor exposed a resumed system activity")
    passed("Back exits only from the camera root instead of reopening an album")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    p.add_argument("--serial", required=True)
    p.add_argument("--seed-apk", type=Path, help="Install this debuggable APK and seed its empty default album first")
    p.add_argument("--apk", type=Path, help="Install final same-package APK without clearing data")
    p.add_argument("--photos", type=int, default=8)
    p.add_argument("--skip-selection", action="store_true")
    p.add_argument("--out", type=Path, default=Path("dist/back-navigation-qa"))
    args = p.parse_args()
    if args.photos < 2 or not args.serial.startswith("emulator-"):
        p.error("Use an explicitly selected disposable emulator and at least two synthetic photos")
    ui = Ui(args.adb, args.serial, args.out)
    ui.ready(320)
    force_disposable(ui)
    if args.seed_apk:
        ui.install(args.seed_apk)
        ui.launch(stop=True)
        seed(ui, ALBUM, args.photos)
    if args.apk:
        ui.install(args.apk)
        passed(f"Installed {args.apk.name} without clearing app data")
    ui.launch(stop=True)
    try:
        exercise(ui, args.photos, not args.skip_selection)
    except Exception:
        try:
            ui.screenshot("failure")
        except Exception:
            pass
        raise


if __name__ == "__main__":
    main()
