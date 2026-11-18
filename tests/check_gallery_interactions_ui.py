#!/usr/bin/env python3
"""Exercise zoom, browsing and multiple selection on a disposable synthetic Android emulator."""
import argparse
import os
from pathlib import Path

from PIL import ImageChops, ImageStat
from check_export_preview_ui import Ui, bounds, force_disposable, match, seed, tap_node, window
from check_native_redesign_ui import (active_action, center, parse_count, visible_thumbs,
                                      verify_gallery, wait_action, frame)
from smoke_ui import passed


def capture_viewport(ui, box, label):
    image = frame(ui, label).crop(box).convert("RGB")
    # Exclude the edges; button bars and notification overlays must not count.
    dx, dy = image.width // 10, image.height // 10
    return image.crop((dx, dy, image.width - dx, image.height - dy))


def difference(a, b):
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3


def swipe(ui, x0, y0, x1, y1, duration=350):
    ui.shell("input", "swipe", str(x0), str(y0), str(x1), str(y1), str(duration))


def zoom_flow(ui, photos, thumbs):
    tap_node(ui, thumbs[0])
    wait_action(ui, "删除照片")
    locator = "全屏照片预览"
    viewer, _ = window(ui, locator, contains=True)
    preview = match(viewer, locator, contains=True)
    box = bounds(preview)
    x, y = center(preview)
    baseline = capture_viewport(ui, box, "gallery-zoom-fit")
    # Keep both taps on the device side of the ADB connection.
    ui.shell(f"input tap {x} {y}; input tap {x} {y}")
    assert difference(baseline, capture_viewport(ui, box, "gallery-zoom-in")) > 2, "Double tap did not zoom"
    shift = (box[2]-box[0])//4
    swipe(ui, x + shift, y, x - shift, y)
    tree, _ = window(ui, locator, contains=True)
    assert match(tree, f"1 / {photos}") is not None, "Dragging a zoomed photo advanced the gallery"
    ui.shell(f"input tap {x} {y}; input tap {x} {y}")
    assert difference(baseline, capture_viewport(ui, box, "gallery-zoom-reset")) < 1.5, "Second double tap did not restore fit"
    swipe(ui, x + shift, y, x - shift, y)
    window(ui, f"2 / {photos}")
    ui.back()
    window(ui, "继续拍摄")
    passed("double-tap zoom/reset and zoomed drag do not interfere with base-scale swipe navigation")


def selection_flow(ui, photos, delete):
    gallery, thumbs = verify_gallery(ui, photos)
    x, y = center(thumbs[0])
    swipe(ui, x, y, x, y, 850)
    selecting, _ = window(ui, "已选 1 张")
    checked = match(selecting, "已选中，点按取消", contains=True)
    other = match(selecting, "未选中，点按选择", contains=True)
    assert checked is not None and other is not None
    tap_node(ui, other)
    selecting, _ = window(ui, "已选 2 张")
    frame(ui, "gallery-multiple")
    tap_node(ui, active_action(selecting, "删除"))
    prompt, _ = window(ui, "删除 2 张照片？")
    tap_node(ui, active_action(prompt, "取消"))
    selecting, _ = window(ui, "已选 2 张")
    assert parse_count(selecting) == photos, "Cancel changed the album"
    if delete:
        tap_node(ui, active_action(selecting, "删除"))
        prompt, _ = window(ui, "删除 2 张照片？")
        tap_node(ui, active_action(prompt, "删除"))
        gallery, _ = window(ui, "继续拍摄")
        assert parse_count(gallery) == photos-2, "Incorrect post-deletion count"
        passed("confirmed test-only deletion removes only the two selected photos")
    else:
        ui.back()
        gallery, _ = window(ui, "继续拍摄")
        assert parse_count(gallery) == photos
        assert match(gallery, "已选 2 张") is None, "Back must clear selection first"
    passed("long press selects, tap adds, and cancel keeps both selection and files")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    p.add_argument("--serial", default="emulator-5556")
    p.add_argument("--apk", type=Path)
    p.add_argument("--seed", action="store_true", help="Seed an empty synthetic fixture from the test app")
    p.add_argument("--album", default="默认相册")
    p.add_argument("--photos", type=int, default=8)
    p.add_argument("--out", type=Path, default=Path("dist/gallery-interactions-qa"))
    p.add_argument("--delete", action="store_true", help="Confirm two deletions from a known synthetic test album")
    args = p.parse_args()
    if args.photos < 4:
        p.error("Use at least four synthetic photos")
    ui = Ui(args.adb, args.serial, args.out)
    ui.ready(320)
    force_disposable(ui)
    if args.apk:
        ui.install(args.apk)
    ui.launch(stop=True)
    if args.seed:
        seed(ui, args.album, args.photos)
        ui.launch(stop=True)
    camera, _ = window(ui, f"打开当前相册：{args.album}")
    tap_node(ui, active_action(camera, f"打开当前相册：{args.album}"))
    _, thumbs = verify_gallery(ui, args.photos)
    zoom_flow(ui, args.photos, thumbs)
    selection_flow(ui, args.photos, args.delete)


if __name__ == "__main__":
    main()
