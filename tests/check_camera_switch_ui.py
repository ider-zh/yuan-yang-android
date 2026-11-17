#!/usr/bin/env python3
"""Test synthetic front/rear capture on a disposable Android emulator only.

Use camera-back virtualscene and camera-front emulated for --expect dual;
camera-front none for --expect single. Requires installed preview APK, and
a disposable album containing only synthetic QA photos. No photos are shared.
"""
import argparse
import json
import os
from pathlib import Path
import re
import time

from smoke_ui import passed
from check_export_preview_ui import Ui, force_disposable, match, tap_node, window

ROOT=Path(__file__).resolve().parents[1]
BACK=('切换到后置','切换到后置摄像头')
FRONT=('切换到前置','切换到前置摄像头')
SHUTTER=('拍照','复拍')

def info(ui,stage):
    data=ui.shell('dumpsys','media.camera',timeout=35)
    (ui.output/f'camera-service-{stage}.txt').write_text(data+'\n')
    summary=re.search(r'Number of (?:normal camera devices|camera devices):\s*(\d+)',data,flags=re.I)
    return int(summary[1]) if summary else None

def in_camera(ui):
    tree,_=window(ui,(*SHUTTER,'继续拍摄'))
    go=match(tree,'继续拍摄',enabled=True,clickable=True)
    if go is not None:tap_node(ui,go)
    for _ in range(4):
        tree,_=window(ui,SHUTTER)
        if match(tree,SHUTTER,enabled=True,clickable=True) is not None:
            return tree
        time.sleep(.3)
    raise AssertionError('Camera shutter never became enabled')

def assure_facing(ui,target):
    expected=BACK if target=='front' else FRONT
    for _ in range(5):
        tree,_=window(ui,expected)
        if match(tree,expected,enabled=True,clickable=True) is not None and match(tree,SHUTTER,enabled=True,clickable=True) is not None:
            return tree
        time.sleep(.4)
    raise AssertionError(f'{target} camera selection did not become ready')

def switch(ui,from_facing):
    labels=FRONT if from_facing=='rear' else BACK
    tree,_=window(ui,labels)
    tap_node(ui,match(tree,labels,enabled=True,clickable=True))
    return assure_facing(ui,'front' if from_facing=='rear' else 'rear')

def capture_and_check(ui,tree,expected=None):
    shutter=match(tree,SHUTTER,enabled=True,clickable=True)
    tap_node(ui,shutter)
    if expected is None:
        return in_camera(ui)
    return assure_facing(ui,expected)

def get_count(ui):
    tree,_=window(ui,'张照片',contains=True)
    for node in tree.iter('node'):
        m=re.search(r'\b(\d+)\s*张照片',node.attrib.get('text',''))
        if m: return int(m[1])
    raise AssertionError('Photo count not visible')

def run(args):
    ui=Ui(args.adb,args.serial,args.out)
    ui.ready(args.wait_boot); force_disposable(ui)
    n=info(ui,'start')
    if args.expect=='single' and n!=1:
        raise AssertionError(f'Cannot prove single-facing behavior: Android camera count is {n}')
    if args.expect=='dual' and n is not None and n<2:
        raise AssertionError(f'Dual-facing fixture required, Android camera count is {n}')
    if not args.on_album:ui.launch(stop=True)
    # Hardware Back from Camera is the supported current-album route; on-album
    # resumes after the renderer test without restarting or reinitializing camera.
    if not args.on_album:
        in_camera(ui); ui.back()
    before=get_count(ui)
    tree=in_camera(ui)
    if args.expect=='single':
        assert match(tree,(*FRONT,*BACK)) is None,'Switch control should be hidden with only one facing'
        ui.screenshot('single-facing-switch-hidden')
        capture_and_check(ui,tree)
        ui.back()
        after=get_count(ui)
        assert after==before+1,(before,after)
        passed('single-facing Android device hides switch and still captures')
    else:
        if match(tree,BACK) is not None:tree=switch(ui,'front')
        rear=assure_facing(ui,'rear')
        ui.screenshot('camera-rear-ready')
        capture_and_check(ui,rear,expected='rear')
        passed('rear shutter returns to the same facing with a working preview')
        front=switch(ui,'rear')
        ui.screenshot('camera-front-ready')
        capture_and_check(ui,front,expected='front')
        info(ui,'front')
        passed('front switch opens front camera; taking a photo preserves that facing')
        ui.back()
        after=get_count(ui)
        assert after==before+2,(before,after)
        in_camera(ui)
        assure_facing(ui,'front')
        passed('opening the same album camera again remembers front')
        ui.launch(stop=True)
        assure_facing(ui,'front')
        ui.screenshot('camera-front-after-relaunch')
        passed('force-stop and relaunch preserve last successful selection')
        switch(ui,'front')
        ui.screenshot('camera-switched-back-to-rear')
        info(ui,'rear')
        passed('switching from front back to rear restores preview/shutter')
    (ui.output/'report.json').write_text(json.dumps(dict(mode=args.expect,reported_device_count=n,
                album_count_before=before,album_count_after=after),indent=2)+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default=os.environ.get('ADB','adb'))
    p.add_argument('--serial',default='emulator-5556')
    p.add_argument('--expect',choices=('dual','single'),default='dual')
    p.add_argument('--out',type=Path,default=ROOT/'dist/camera-switch-qa')
    p.add_argument('--wait-boot',type=int,default=320)
    p.add_argument('--on-album',action='store_true')
    args=p.parse_args()
    try:run(args)
    except Exception:
        try: Ui(args.adb,args.serial,args.out).screenshot('failure-state')
        except Exception: pass
        raise

if __name__=='__main__':main()
