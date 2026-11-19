#!/usr/bin/env python3
"""ADB smoke tests for the 原样 APK on a disposable Android test device.

Use a fresh emulator, not a personal device with existing 原样 data. Tests
capture synthetic camera images. Share tests open and dismiss Android’s system
chooser without attaching to any external app. The optional upgrade check
installs APKs over the same app ID without resetting its data.
"""
import argparse
import json
import shutil
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE_APP = 'com.yuanyang.camera'
APP = os.environ.get('YUANYANG_TEST_PACKAGE', 'com.yuanyang.camera.preview')
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+',APP):
    raise ValueError('Invalid YUANYANG_TEST_PACKAGE application ID')
ACTIVITY = f'{APP}/{SOURCE_APP}.MainActivity'
PREVIEW = ROOT/'dist/yuan-yang-0.1.0-debug.apk'
CURRENT = ROOT/'dist/yuan-yang-0.3.0-preview.apk'
NEW_ALBUM = 'RuntimeQA'

class Ui:
    def __init__(self, adb, serial, output):
        self.adb = [adb] + (['-s', serial] if serial else [])
        self.output = output
        self.output.mkdir(parents=True, exist_ok=True)
    def run(self, *args, timeout=35, raw=False):
        data = subprocess.check_output([*self.adb,*args], timeout=timeout, stderr=subprocess.PIPE)
        return data if raw else data.decode(errors='replace').strip()
    def shell(self, *args, timeout=35):
        return self.run('shell', *args, timeout=timeout)
    def ready(self, secs):
        end = time.monotonic() + secs
        while time.monotonic() < end:
            try:
                if self.shell('getprop', 'sys.boot_completed', timeout=4) == '1':
                    self.shell('input', 'keyevent', '82')
                    return
            except subprocess.SubprocessError:
                pass
            time.sleep(2)
        raise AssertionError('No booted Android device before ADB timeout')
    def install(self, path):
        if 'Success' not in self.run('install', '--no-streaming', '-r', '-g', str(path), timeout=150):
            raise AssertionError(f'Failed to install {path}')
    def launch(self, stop=False):
        if stop:
            self.shell('am', 'force-stop', APP)
        result = self.shell('am', 'start', '-W', '-n', ACTIVITY, timeout=45)
        if 'Status: ok' not in result:
            raise AssertionError(result)
    def back(self):
        self.shell('input', 'keyevent', '4')
    def screenshot(self, name):
        path = self.output/f'{name}.png'
        png = self.run('exec-out', 'screencap', '-p', raw=True)
        if png[:8] != b'\x89PNG\r\n\x1a\n':
            raise AssertionError('ADB screencap did not return PNG')
        path.write_bytes(png)
        return path
    def snapshot(self):
        self.shell('uiautomator', 'dump', '/sdcard/yuanyang-smoke.xml', timeout=25)
        xml = self.shell('cat', '/sdcard/yuanyang-smoke.xml')
        return ET.fromstring(xml)
    def find(self, label, retries=5, contains=False, require_enabled=False, predicate=None):
        visible = []
        for _ in range(retries):
            try:
                visible = []
                for node in self.snapshot().iter('node'):
                    attrs = node.attrib
                    for value in (attrs.get('text',''), attrs.get('content-desc','')):
                        if not value:
                            continue
                        visible.append(value)
                        labels = label if isinstance(label,(tuple,list)) else (label,)
                        matched = any((item in value if contains else item == value) for item in labels)
                        if matched and (not require_enabled or attrs.get('enabled') == 'true') and (predicate is None or predicate(attrs)):
                            x1,y1,x2,y2 = map(int,re.findall(r'\d+',attrs['bounds']))
                            if x2 > x1 and y2 > y1:
                                return (x1+x2)//2, (y1+y2)//2
            except (subprocess.SubprocessError, ET.ParseError):
                time.sleep(.5)
        raise AssertionError(f'Could not find {label!r}; visible: {visible}')
    def tap(self, label, **options):
        x, y = self.find(label, **options)
        self.shell('input', 'tap', str(x), str(y))
    def scroll_up(self):
        tree=self.snapshot()
        scroller=next((n.attrib for n in tree.iter('node') if n.attrib.get('scrollable')=='true'),None)
        if scroller:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',scroller['bounds']))
            x=(x1+x2)//2
            for _ in range(2):
                self.shell('input','swipe',str(x),str(y1+(y2-y1)//4),str(x),str(y2-(y2-y1)//5),'240')

def passed(description):
    print(f'PASS {time.strftime("%H:%M:%S", time.gmtime())}Z {description}', flush=True)

def preview_seed(ui, preview):
    ui.install(preview)
    ui.launch(stop=True)
    ui.tap('使用三点位模板')
    ui.tap('保存')
    ui.find('0 / 3')
    passed('v0.1 installed, launched and created legacy album data using its three-point template')

def capture(ui, label, resulting_count, album):
    ui.tap(label, require_enabled=True)
    ui.find(('拍照','复拍'), require_enabled=True)
    enter_album(ui,album)
    ui.find(f'{resulting_count} 张照片',contains=True)
    ui.tap('继续拍摄',require_enabled=True)
    camera_nav(ui,album)

def verify_media(ui, extension, name):
    path = ui.output/name
    if extension == 'gif':
        if path.read_bytes()[:6] not in (b'GIF87a',b'GIF89a'):
            raise AssertionError('Export is not a valid GIF container')
        try:
            from PIL import Image
        except ImportError:
            return 'GIF signature present (Pillow not installed)'
        with Image.open(path) as image:
            frame_total=image.n_frames
            if frame_total != 2:
                raise AssertionError(f'Expected two GIF frames, got {frame_total}')
            for i in range(frame_total):
                image.seek(i)
                image.load()
            return f'{frame_total} decoded frames, {image.width}×{image.height}'
    if b'ftyp' not in path.read_bytes()[:32]:
        raise AssertionError('Export does not contain an MP4 file type box')
    if not shutil.which('ffprobe'):
        return 'MP4 signature present (ffprobe not installed)'
    result = subprocess.check_output(['ffprobe','-v','error','-count_frames','-show_streams','-of','json',str(path)],text=True,timeout=45)
    streams = [v for v in json.loads(result)['streams'] if v.get('codec_type')=='video']
    assert len(streams) == 1, f'Unexpected MP4 video streams: {streams}'
    stream=streams[0]
    duration = float(stream.get('duration',0))
    decoded=int(stream.get('nb_read_frames',0))
    if duration<=0 or stream.get('codec_name')!='h264' or decoded<2:
        raise AssertionError(f'Unplayable or unexpected MP4 stream: {stream}')
    return f'H.264, {decoded} decoded frames, {stream["width"]}×{stream["height"]}, {duration:.2f}s'

def downloads(ui, ext):
    return set(ui.shell('ls', '-1', f'/sdcard/Download/*.{ext}', '2>/dev/null', '||', ':').splitlines())

def share(ui, extension):
    ui.tap('分享到其他应用')
    end = time.monotonic() + 40
    while time.monotonic() < end:
        active = ui.shell('dumpsys', 'activity', 'activities')
        if any('ChooserActivity' in line and 'ResumedActivity' in line
               for line in active.splitlines()):
            break
        time.sleep(1)
    else:
        raise AssertionError(f'{extension.upper()} system share chooser not observed')
    ui.screenshot(f'share-{extension}')
    ui.back()
    passed(f'{extension.upper()} export opened the Android share chooser without sending')

def select_format(ui, label):
    ui.tap('导出 GIF / MP4', contains=True, require_enabled=True)
    ui.tap(label, require_enabled=True)
    ui.find(label+'已生成', retries=14)
    ui.find('保存到文件')
    ui.screenshot('export-'+('gif' if label.startswith('GIF') else 'mp4'))

def save_file(ui, ext):
    before = downloads(ui, ext)
    ui.tap('保存到文件')
    ui.screenshot(f'file-picker-{ext}')
    ui.tap(('SAVE','Save','保存'), require_enabled=True, predicate=lambda a:a.get('clickable')=='true')
    end = time.monotonic() + 45
    while time.monotonic() < end:
        new = [p for p in sorted(downloads(ui,ext)-before) if p.startswith('/sdcard/Download/')]
        if new:
            path = new[-1]
            fname=f'exported-synthetic.{ext}'
            ui.run('pull', path, str(ui.output/fname))
            info=verify_media(ui, ext, fname)
            passed(f'{ext.upper()} saved through Android file picker and decoded: {info}')
            return
        time.sleep(1.5)
    raise AssertionError('No saved export observed in test emulator Download folder')

def album_labels(album):
    return album, f'打开当前相册：{album}'

def camera_nav(ui, album):
    left = ui.find('所有相册', predicate=lambda a:a.get('clickable')=='true')
    right = ui.find(album_labels(album), predicate=lambda a:a.get('clickable')=='true')
    assert left[1] < right[1], f'Camera must put all albums in the header and current album in the shutter row: {left}, {right}'

def enter_album(ui, album):
    ui.tap(album_labels(album), predicate=lambda a:a.get('clickable')=='true')
    ui.find('导出 GIF / MP4', contains=True)

def tap_day(ui):
    for _ in range(3):
        snapshot = ui.snapshot()
        for n in snapshot.iter('node'):
            name = n.attrib.get('text','')
            if re.fullmatch(r'20\d{2}-\d{2}-\d{2}', name):
                ui.tap(name)
                return
        scrollers = [n.attrib for n in snapshot.iter('node') if n.attrib.get('scrollable') == 'true']
        if not scrollers:
            break
        x1,y1,x2,y2 = map(int,re.findall(r'\d+',scrollers[0]['bounds']))
        x=(x1+x2)//2
        upper=y1+(y2-y1)//3
        lower=y1+2*(y2-y1)//3
        ui.shell('input','swipe',str(x),str(lower),str(x),str(upper),'280')
    raise AssertionError('No date group in album UI')

def verify_day_selection(ui):
    tap_day(ui)
    ui.find('2 / 2',contains=True)
    ui.tap('上一张',require_enabled=True)
    ui.find('1 / 2',contains=True)
    ui.tap('设为当天选片',require_enabled=True)
    tap_day(ui)
    ui.find('1 / 2',contains=True)
    ui.screenshot('daily-photo-selected')
    ui.tap('下一张',require_enabled=True)
    ui.find('2 / 2',contains=True)
    ui.tap('设为当天选片',require_enabled=True)
    passed('daily picker saved an earlier choice, then switched back to the most recent photo')

def open_photo_delete(ui):
    try:
        ui.tap(('删除','删除照片'), retries=1, predicate=lambda a:a.get('clickable')=='true')
    except AssertionError:
        tap_day(ui)
        ui.tap(('删除','删除照片'), predicate=lambda a:a.get('clickable')=='true')
    ui.find('删除照片？')

def verify_deletion(ui, album):
    open_photo_delete(ui)
    ui.screenshot('delete-confirm')
    ui.tap('取消')
    # Return to the album and inspect both the exact count and original frame count.
    ui.find('2 / 2',contains=True)
    ui.tap('关闭')
    ui.scroll_up()
    ui.find('2 张照片',contains=True)
    ui.find('2 帧',contains=True)
    passed('cancel retained both photos and both all-records frames')
    open_photo_delete(ui)
    ui.tap('删除', predicate=lambda a:a.get('clickable')=='true')
    try:
        ui.find('导出 GIF / MP4',contains=True,retries=1)
    except AssertionError:
        ui.back()
        ui.find('导出 GIF / MP4',contains=True)
    ui.scroll_up()
    ui.find('1 张照片',contains=True)
    ui.tap('全部记录',contains=True)
    ui.find('1 帧',contains=True)
    ui.screenshot('post-delete-frames')
    passed('photo deletion leaves the other file intact and refreshes all-records frame count')
    open_photo_delete(ui)
    ui.tap('删除', predicate=lambda a:a.get('clickable')=='true')
    try:
        ui.find('0 张照片',contains=True,retries=1)
    except AssertionError:
        ui.back()
        ui.find('0 张照片',contains=True)
    ui.scroll_up()
    ui.screenshot('empty-after-delete')
    ui.tap('继续拍摄', require_enabled=True)
    camera_nav(ui, album)
    ui.find('拍照', require_enabled=True)
    assert not any(n.attrib.get('content-desc') == '旧照片叠影透明度'
                   for n in ui.snapshot().iter('node')), 'Overlay slider still shown after deleting the final photo'
    ui.screenshot('no-overlay-after-delete')
    passed('deleting the last photo removes its overlay from the album camera')

def new_album(ui):
    ui.tap('所有相册')
    ui.find('所有相册')
    ui.find('新建相册', contains=True)
    assert not any('使用三点位模板' in n.attrib.get('text','')
                   for n in ui.snapshot().iter('node')), 'Legacy point template still visible in the album list'
    ui.screenshot('all-albums')
    ui.tap('新建相册', contains=True)
    # Android input text supports portable ASCII, passed as the new album name.
    tree = ui.snapshot()
    edit = next(n for n in tree.iter('node') if n.attrib.get('class') == 'android.widget.EditText')
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',edit.attrib['bounds']))
    ui.shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
    ui.shell('input','text',NEW_ALBUM)
    ui.back() # Dismiss keyboard before locating the recentered Save button.
    ui.tap(('保存','创建'))
    camera_nav(ui, NEW_ALBUM)
    ui.launch(stop=True)
    camera_nav(ui, NEW_ALBUM)
    ui.screenshot('relaunch-album')
    passed('new album starts its own camera and is remembered after a force-stop')

def timeline_walkthrough(ui, upgraded=False, opts=None):
    album = opts.expected_album or ('我的房间' if upgraded else '默认相册')
    camera_nav(ui, album)
    ui.screenshot('clean-camera')
    passed(f'camera-first launch and left/right navigation placement: {album}')
    ui.tap('所有相册')
    ui.find('新建相册',contains=True)
    ui.find(album+'，打开相机')
    ui.back()
    camera_nav(ui,album)
    passed('left camera button shows album list directly and Android Back resumes the same album camera')
    if not opts.existing_two:
        enter_album(ui,album)
        ui.find('0 张照片',contains=True)
        ui.tap('继续拍摄', require_enabled=True)
        camera_nav(ui,album)
        capture(ui, ('拍照','复拍'), 1, album)
        ui.screenshot('first-overlay')
        passed('first shutter created one local JPEG and returned to capture')
        if opts.overlay_controls:
            ui.find('叠影？滑块拖到最左就是隐藏')
            slider = next(n for n in ui.snapshot().iter('node')
                          if n.get('content-desc') == '旧照片叠影透明度' and n.get('enabled') == 'true')
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', slider.attrib['bounds']))
            y = str((y1+y2)//2)
            ui.shell('input', 'swipe', str((x1+x2)//2), y, str(x1+1), y, '300')
            ui.find('0%')
            ui.find('叠影已隐藏', contains=True)
            ui.screenshot('overlay-hidden')
            ui.shell('input', 'swipe', str(x1+1), y, str(x2-2), y, '300')
            ui.find('100%')
            ui.find('移动手机，手动对齐旧照。', contains=True)
            ui.screenshot('overlay-restored')
            passed('overlay slider hides at zero and restores when moved right')
        capture(ui, ('拍照','复拍'), 2, album)
        passed('second shutter produced an additional file and returned to capture')
    enter_album(ui, album)
    ui.find('2 张照片', contains=True)
    ui.find('1 天', contains=True)
    ui.find('1 帧', contains=True)
    ui.screenshot('daily-frames')
    passed('right camera button opens current album directly; two photos, one day, one selected frame')
    verify_day_selection(ui)
    ui.scroll_up()
    ui.tap('全部记录', contains=True)
    ui.find('2 帧', contains=True)
    ui.screenshot('all-frames')
    passed('all-records mode includes both photos')
    if not opts.skip_exports:
        for ext, label in (('gif','GIF 动图'),('mp4','MP4 视频')):
            select_format(ui, label)
            share(ui, ext)
            if opts.save_exports:
                select_format(ui, label)
                save_file(ui, ext)
    if opts.test_delete:
        verify_deletion(ui, album)
    else:
        ui.tap('继续拍摄', require_enabled=True)
        camera_nav(ui, album)
    new_album(ui)

def check_side_by_side(ui, old_apk, new_apk):
    global APP, ACTIVITY
    new_id=APP
    if new_id == SOURCE_APP:
        raise AssertionError('Side-by-side check requires an isolated preview package ID')
    if not old_apk.is_file():
        raise AssertionError(f'Legacy APK is missing: {old_apk}')
    if ui.shell('getprop','ro.kernel.qemu') != '1' and ui.shell('getprop','ro.boot.qemu') != '1':
        raise AssertionError('Co-installation checks require a disposable emulator')
    try:
        APP=SOURCE_APP
        ACTIVITY=f'{APP}/{SOURCE_APP}.MainActivity'
        ui.install(old_apk)
        ui.launch(stop=True)
        ui.tap('项目与记录')
        try:
            ui.find('0 张照片',contains=True,retries=1)
            ui.tap('继续拍摄',require_enabled=True)
            ui.tap(('拍照','复拍'),require_enabled=True)
            ui.find(('拍照','复拍'),require_enabled=True)
            ui.tap('项目与记录')
        except AssertionError:
            pass
        ui.find('1 张照片',contains=True)
        ui.screenshot('legacy-before-coinstall')
        APP=new_id
        ACTIVITY=f'{APP}/{SOURCE_APP}.MainActivity'
        ui.install(new_apk)
        ui.launch(stop=True)
        ui.find('所有相册')
        assert ui.shell('pm','path',SOURCE_APP).startswith('package:'), 'Legacy package is not installed'
        assert ui.shell('pm','path',new_id).startswith('package:'), 'Preview package is not installed'
        APP=SOURCE_APP
        ACTIVITY=f'{APP}/{SOURCE_APP}.MainActivity'
        ui.launch(stop=True)
        ui.tap('项目与记录')
        ui.find('1 张照片',contains=True)
        ui.screenshot('legacy-after-coinstall')
        passed('both package IDs are installed and the legacy synthetic photo remains present')
    finally:
        APP=new_id
        ACTIVITY=f'{APP}/{SOURCE_APP}.MainActivity'

def main():
    global APP, ACTIVITY
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    p.add_argument('--serial')
    p.add_argument('--wait-boot', type=int, default=120)
    p.add_argument('--preview', type=Path, default=PREVIEW)
    p.add_argument('--apk', type=Path, default=CURRENT)
    p.add_argument('--package', default=APP, help='APK application ID (defaults to YUANYANG_TEST_PACKAGE or isolated preview)')
    p.add_argument('--out', type=Path, default=ROOT/'dist/device-smoke')
    p.add_argument('--expected-album', '--expected-project', dest='expected_album')
    p.add_argument('--existing-two', action='store_true')
    p.add_argument('--overlay-controls', action='store_true')
    p.add_argument('--save-exports', action='store_true')
    p.add_argument('--skip-exports', action='store_true', help='Isolate camera/album regressions from system file-picker tests')
    p.add_argument('--test-delete', action='store_true', help='Exercise deletion of synthetic photos only (disposable emulator)')
    tests = p.add_mutually_exclusive_group()
    tests.add_argument('--launch-only', action='store_true')
    tests.add_argument('--check-side-by-side', type=Path, metavar='OLD_APK', help='Check separate install IDs and unchanged legacy synthetic JPEG(s)')
    tests.add_argument('--preview-seed-only', action='store_true')
    tests.add_argument('--upgrade', action='store_true', help='Install v0.1 and seed its store before upgrading')
    tests.add_argument('--upgrade-seeded', action='store_true', help='Upgrade an emulator already seeded by --preview-seed-only')
    args = p.parse_args()
    APP = args.package
    ACTIVITY = f'{APP}/{SOURCE_APP}.MainActivity'
    if (args.upgrade or args.upgrade_seeded or args.preview_seed_only) and APP != SOURCE_APP:
        p.error('Legacy in-place upgrade needs --package com.yuanyang.camera and an APK signed with the legacy key')
    ui = Ui(args.adb,args.serial,args.out)
    try:
        ui.ready(args.wait_boot)
        passed('Android booted and responsive')
        if args.test_delete:
            emulator = (ui.shell('getprop','ro.kernel.qemu') == '1'
                        or ui.shell('getprop','ro.boot.qemu') == '1')
            if not emulator:
                raise AssertionError('Deletion checks require a disposable emulator, not a physical device')
        if args.check_side_by_side:
            check_side_by_side(ui,args.check_side_by_side,args.apk)
            return
        if args.preview_seed_only or args.upgrade:
            preview_seed(ui,args.preview)
            if args.preview_seed_only:
                return
        ui.install(args.apk)
        ui.launch(stop=True)
        passed('v0.3 installed over v0.1 and launched' if (args.upgrade or args.upgrade_seeded)
               else 'v0.3 installed and launched')
        if args.launch_only:
            camera_nav(ui, args.expected_album or '默认相册')
            ui.screenshot('camera-launch')
            passed('camera activity showing navigation')
            return
        timeline_walkthrough(ui, args.upgrade or args.upgrade_seeded, args)
    except Exception:
        try:
            ui.screenshot('failure-state')
            (ui.output/'failure-activity.txt').write_text(ui.shell('dumpsys','activity','activities'))
        except Exception:
            pass
        raise

if __name__ == '__main__':
    main()
