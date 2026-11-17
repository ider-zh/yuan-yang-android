#!/usr/bin/env python3
"""Generate internal, reproducible image pairs for 0.4 person alignment QA.

Obtain pose.jpg from https://storage.googleapis.com/mediapipe-assets/pose.jpg,
the Google MediaPipe benchmark's photo. Supply the downloaded JPEG locally.
Do not ship the resulting photos or derivatives without verifying licensing.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
from PIL import Image, ImageOps

W, H = 1200, 900
CENTER = (600, 530)  # approximately torso center of the prepared reference
FIXTURES = {
    "01-reference": (0, 0, 1, 0),
    "02-offset": (48, -31, 1, 0),
    "03-downscale": (0, 0, 0.88, 0),
    "04-rotate": (0, 0, 1, 6),
    "05-combined": (-51, 33, 0.91, -4),
    "06-over-limit": (-255, -72, 0.69, 29),
}

def warp(base, dx, dy, scale, deg):
    x0, y0 = CENTER
    rad = math.radians(deg)
    c, s = math.cos(rad) / scale, math.sin(rad) / scale
    x1, y1 = x0 + dx, y0 + dy
    inverse = (c, s, x0 - c * x1 - s * y1, -s, c, y0 + s * x1 - c * y1)
    return base.transform(base.size, Image.Transform.AFFINE, inverse,
                          Image.Resampling.BICUBIC, fillcolor=(25, 34, 41)), inverse

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", type=Path)
    p.add_argument("out", type=Path)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    source = ImageOps.exif_transpose(Image.open(args.source)).convert("RGB")
    def save(name, image, **data):
        dest = args.out / (name + ".jpg")
        image.save(dest, quality=94, subsampling=0)
        return {"name": dest.name, "sha256": sha(dest), **data}
    base = Image.new("RGB", (W, H), (25, 34, 41))
    resized = ImageOps.contain(source, (925, 695), Image.Resampling.LANCZOS)
    base.paste(resized, ((W - resized.width) // 2, 134))
    cases = []
    for name, (dx, dy, scale, deg) in FIXTURES.items():
        rotated, lower_to_ref = warp(base, dx, dy, scale, deg)
        cases.append(save(name, rotated, forward={"dx": dx, "dy": dy, "scale": scale, "theta_y_down_degrees": deg},
                          variant_to_reference_affine=lower_to_ref,
                          expected="reject_excessive" if name.startswith("06") else "align_to_reference"))
    no_person = ImageOps.fit(source.crop((35, 8, 780, 226)), (W, H), Image.Resampling.BICUBIC)
    cases.append(save("07-no-person-sky", no_person, expected="reject_no_person"))
    cases.append(save("08-empty", Image.new("RGB", (W, H), (62, 62, 62)), expected="reject_no_person"))
    cases.append(save("09-head-only", ImageOps.fit(source.crop((421, 213, 536, 322)),
                           (W, H), Image.Resampling.BICUBIC), expected="reject_incomplete_torso"))
    multi = Image.new("RGB", (W, H), (25, 34, 41))
    pair = ImageOps.contain(source, (600, 730), Image.Resampling.LANCZOS)
    multi.paste(pair, (0, 224))
    multi.paste(ImageOps.mirror(pair), (600, 224))
    cases.append(save("10-two-persons", multi, expected="reject_multiple_persons_if_both_detected"))
    meta = dict(source={"url":"https://storage.googleapis.com/mediapipe-assets/pose.jpg",
                        "sha256":sha(args.source), "redistribute": False},
                width=W, height=H, pivot_px=CENTER, cases=cases)
    (args.out / "manifest.json").write_text(json.dumps(meta, indent=2) + "\n")
    print(f"Generated {len(cases)} private QA images.")

if __name__ == "__main__":
    main()
