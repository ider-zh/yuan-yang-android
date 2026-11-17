#!/usr/bin/env python3
"""Exercise GIF and MP4 preview, system copy dialog, and chooser in a disposable AVD.

Use an already seeded synthetic album, or pass --qa-apk (debuggable build signed
with preview key) to inject six deterministic frames in an empty default album.
The final nondebuggable APK is installed over seed APK before feature tests.
No file is shared to an external app; all copies remain in the test Downloads.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageStat
from smoke_ui import APP, Ui as BaseUi, album_labels, downloads, passed

ROOT = Path(__file__).resolve().parents[1]
PREVIEW_TITLE = '导出预览'
EXPORT = '导出 GIF / MP4'
SAVE = '保存到文件'
SHARE = '分享到其他应用'
FORMATS = {'gif':'GIF 动图','mp4':'MP4 视频'}
MEDIA = {'gif':('GIF 动图预览','动图预览'),'mp4':('MP4 视频预览','视频预览')}
SEEK = ('视频进度','播放进度','视频播放进度')

class Ui(BaseUi):
    def run(self, *args, timeout=35, raw=False):
        # ADB shell sends stdin to the Android process by default. Explicitly
        # detach it so a test running in a shared diagnostic PTY cannot eat
        # subsequent host commands. The JPEG seeder uses its own binary pipe.
        result=subprocess.check_output([*self.adb,*args],timeout=timeout,
                                       stdin=subprocess.DEVNULL,stderr=subprocess.PIPE)
        return result if raw else result.decode(errors='replace').strip()

    def snapshot(self):
        # Android 9 uiautomator may return exit 0 with "could not get idle
        # state" and leave an old file behind. Never interpret it as live.
        path='/sdcard/yuanyang-preview-ui.xml'
        self.shell('rm','-f',path)
        status=self.shell('uiautomator','dump',path,timeout=22)
        if 'ERROR' in status or 'idle state' in status:
            raise OSError(f'No live accessibility snapshot: {status}')
        return ET.fromstring(self.shell('cat',path))

def bounds(node):
    return tuple(map(int,re.findall(r'\d+',node.attrib['bounds'])))

def match(tree, labels, *, contains=False, enabled=False, clickable=False):
    labels=(labels,) if isinstance(labels,str) else labels
    for node in tree.iter('node'):
        a=node.attrib
        values=(a.get('text',''),a.get('content-desc',''))
        if any((label in value if contains else label==value) for value in values for label in labels):
            x1,y1,x2,y2=bounds(node)
            if x1<x2 and y1<y2 and (not enabled or a.get('enabled')=='true') \
                 and (not clickable or a.get('clickable')=='true'):
                return node
    return None

def xy(ui, x, y):
    ui.shell('input','tap',str(x),str(y))

def tap_node(ui, node):
    assert node is not None
    x1,y1,x2,y2=bounds(node)
    xy(ui,(x1+x2)//2,(y1+y2)//2)

def window(ui, label, *, contains=False, retries=8, retry_option=None):
    visible=[]
    for _ in range(retries):
        try:
            tree=ui.snapshot()
            node=match(tree,label,contains=contains)
            if node is not None:return tree,node
            visible=[a.attrib.get('text','') or a.attrib.get('content-desc','') for a in tree.iter('node')]
            if retry_option is not None:
                # Re-observe the format modal before retrying input: ADB input
                # can reach the previous Android window before a slow transition.
                option=match(tree,retry_option,enabled=True)
                if option is not None and match(tree,'导出 ',contains=True) is not None:
                    tap_node(ui,option)
                    time.sleep(.7)
                    continue
            if any('System UI isn' in value for value in visible):
                wait=match(tree,('Wait','等待'))
                if wait is not None:
                    tap_node(ui,wait)
                    time.sleep(.5)
                    continue
        except (OSError,subprocess.SubprocessError,ET.ParseError):
            pass
        time.sleep(.35)
    raise AssertionError(f'Missing {label!r}; visible controls: {visible}')

def force_disposable(ui):
    assert '1' in (ui.shell('getprop','ro.kernel.qemu'),ui.shell('getprop','ro.boot.qemu')), 'Requires disposable Android emulator'

def make_seed(i):
    colors=((200,32,48),(36,181,70),(34,73,201),(207,153,24),(59,172,201),(110,53,194))
    im=Image.new('RGB',(720,960),colors[(i-1)%len(colors)])
    draw=ImageDraw.Draw(im)
    draw.rectangle((55+i*39,310,155+i*39,592),fill=(240,237,211))
    draw.ellipse((95,675-i*44,275,845-i*44),fill=(13,18,24))
    buf=io.BytesIO(); im.save(buf,format='JPEG',quality=94)
    return buf.getvalue()

def seed(ui, album, frames):
    albums=json.loads(ui.shell('run-as',APP,'cat','files/spaces.json'))
    selected=next(x for x in albums if x.get('name')==album)
    base='files/timeline/'+str(uuid.UUID(selected['id']))
    ui.shell('run-as',APP,'mkdir','-p',base)
    assert not any(line.endswith('.jpg') for line in ui.shell('run-as',APP,'ls',base).splitlines()), 'Seed only the empty test album'
    for i in range(1,frames+1):
        path=f'{base}/7fa00000-0000-4000-a000-{i:012x}.jpg'
        remote=f'run-as {APP} sh -c {shlex.quote(f"cat > {path}")}'
        subprocess.run([*ui.adb,'shell','-T',remote],input=make_seed(i),check=True,timeout=45)
        ui.shell('run-as',APP,'touch','-t',f'202611151155.{i:02d}',path)
    passed(f'prepared {frames} synthetic motion and color frames')

def open_album(ui, album, frames):
    tree,_=window(ui,(EXPORT,*album_labels(album)),contains=True)
    if match(tree,EXPORT,contains=True) is None:
        target=match(tree,album_labels(album),clickable=True)
        if target is None:
            # Earlier API28 emulation can restore camera with coarse accessibility
            # tree; system Back is the app's supported "current album" camera route.
            ui.back()
        else:
            tap_node(ui,target)
        tree,_=window(ui,EXPORT,contains=True)
    assert match(tree,f'{frames} 张照片',contains=True) is not None, 'Unexpected synthetic album item count'
    all_photo=match(tree,'全部记录',contains=True,enabled=True,clickable=True)
    tap_node(ui,all_photo)
    tree,_=window(ui,EXPORT,contains=True)
    alignment=match(tree,'对齐人物')
    if alignment is not None and alignment.attrib.get('checked')=='true':
        tap_node(ui,alignment)
    passed(f'opened synthetic album, selected all {frames} frames, pose-alignment off')

def export_from_album(ui, ext):
    tree,export=window(ui,EXPORT,contains=True)
    assert export.attrib.get('enabled')=='true'
    tap_node(ui,export)
    ui.tap(FORMATS[ext],require_enabled=True)
    tree,_=window(ui,PREVIEW_TITLE,retries=16,retry_option=FORMATS[ext])
    assert match(tree,SAVE) is not None and match(tree,SHARE) is not None
    passed(f'{ext.upper()} opens an in-app preview automatically')
    return tree

def full_image(ui):
    raw=ui.run('exec-out','screencap','-p',raw=True)
    with Image.open(io.BytesIO(raw)) as im:
        return im.convert('RGB')

def media_box(tree, ext, first):
    media=match(tree,MEDIA[ext],contains=True)
    if media is None and ext=='mp4':
        media=next((n for n in tree.iter('node') if n.attrib.get('class')=='android.widget.VideoView'),None)
    if media is not None:
        left,top,right,bottom=bounds(media)
    else:
        left,top,right,bottom=0,first.height//5,first.width,first.height*3//4
    left=max(0,left+(right-left)*2//7);right=min(first.width,right-(right-left)*2//7)
    top=max(0,top+(bottom-top)*2//7);bottom=min(first.height,bottom-(bottom-top)*2//7)
    assert left<right and top<bottom,'Media crop region not visible'
    return (left,top,right,bottom)

def delta_rgb(pairs):
    return round(max(sum(abs(a-b) for a,b in zip(s,t))/3 for s in pairs for t in pairs),2)

def frame_samples(ui,ext,tree):
    image=full_image(ui)
    box=media_box(tree,ext,image)
    values=[ImageStat.Stat(image.crop(box)).mean]
    for _ in range(7):
        time.sleep(.19)
        values.append(ImageStat.Stat(full_image(ui).crop(box)).mean)
    return delta_rgb(values)

def inspect_mp4_controls(ui, strict):
    tree,_=window(ui,PREVIEW_TITLE)
    seek=match(tree,SEEK,contains=True,enabled=True)
    play=match(tree,('播放','暂停','重新播放','播放视频','暂停视频'),enabled=True,clickable=True)
    if seek is None or play is None:
        if strict: raise AssertionError('MP4 preview lacks active playback controls after preparing')
        return {'controls':'unverified'}
    if play.attrib.get('text') in ('暂停','暂停视频'):
        tap_node(ui,play)
    else:
        # App autoplays once. Press Play again and immediately pause without
        # waiting on uiautomator (the six-frame clip lasts only 2.1 seconds).
        tap_node(ui,play)
        time.sleep(.20)
        tap_node(ui,play)
    paused,_=window(ui,'播放')
    assert match(paused,SEEK,contains=True,enabled=True) is not None
    x1,y1,x2,y2=bounds(seek); y=(y1+y2)//2
    early=(x1+max(12,(x2-x1)//12),y)
    late=(x2-max(12,(x2-x1)//12),y)
    xy(ui,*early); time.sleep(.40)
    a=full_image(ui); a.save(ui.output/'mp4-scrub-early.png')
    xy(ui,*late); time.sleep(.40)
    b=full_image(ui); b.save(ui.output/'mp4-scrub-late.png')
    box=media_box(paused,'mp4',a)
    delta=delta_rgb([ImageStat.Stat(im.crop(box)).mean for im in (a,b)])
    if strict and delta<=4:
        raise AssertionError(f'MP4 seek did not visibly select a different frame (mean RGB delta {delta})')
    checked={'seek_present':True,'paused_label_observed':True,'seek_pixel_delta':delta}
    passed(f'MP4 play/pause and scrub exercised: {checked}')
    return checked

def wait_component(ui, patterns, *, timeout=30):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        value=ui.shell('dumpsys','activity','activities')
        for line in value.splitlines():
            if 'ResumedActivity' in line and any(pattern in line for pattern in patterns):return line.strip()
        time.sleep(1)
    raise AssertionError(f'Expected active Android component: {patterns!r}')

def cancel_save(ui, ext):
    before=downloads(ui,ext)
    ui.tap(SAVE,require_enabled=True)
    wait_component(ui,('documentsui','DocumentsActivity','FilesActivity'))
    ui.screenshot(ext+'-save-cancel')
    ui.back()
    window(ui,PREVIEW_TITLE)
    assert downloads(ui,ext)==before,'Canceled Save As unexpectedly created a public file'
    passed(f'{ext.upper()} Save As cancel returned to retained preview without creating a file')

def verify_copy(path, frames):
    info=dict(bytes=path.stat().st_size,sha256=hashlib.sha256(path.read_bytes()).hexdigest())
    if path.suffix.lower()=='.gif':
        with Image.open(path) as im:
            assert im.format=='GIF'
            assert im.n_frames==frames, f'Missing GIF frames: {im.n_frames}'
            delays=[]
            for i in range(im.n_frames):
                im.seek(i); im.load(); delays.append(im.info['duration'])
            assert delays==[350]*frames,delays
            info.update(width=im.width,height=im.height,decoded_frames=im.n_frames,delays_ms=delays)
    else:
        if not shutil.which('ffprobe'):
            raise RuntimeError('ffprobe required to verify actual MP4 decode; signature alone is not a pass')
        cmd=['ffprobe','-v','error','-select_streams','v:0','-count_frames','-show_streams','-of','json',str(path)]
        video=json.loads(subprocess.check_output(cmd,text=True,timeout=60))['streams'][0]
        decoded=int(video['nb_read_frames'])
        duration=float(video['duration'])
        assert video['codec_name']=='h264' and decoded==frames and duration>=.30*frames,video
        info.update(width=video['width'],height=video['height'],decoded_frames=decoded,duration_s=duration,codec='h264')
    return info

def save_retry(ui, ext, frames):
    before=downloads(ui,ext)
    ui.tap(SAVE,require_enabled=True)
    wait_component(ui,('documentsui','DocumentsActivity','FilesActivity'))
    ui.tap(('SAVE','Save','保存'),require_enabled=True, predicate=lambda node:node.get('clickable')=='true')
    end=time.monotonic()+85
    last=None
    while time.monotonic()<end:
        candidates=sorted(p for p in downloads(ui,ext)-before if p.startswith('/sdcard/Download/'))
        if candidates:
            path=ui.output/f'preview-saved.{ext}'
            ui.run('pull',candidates[-1],str(path),timeout=55)
            try:
                info=verify_copy(path,frames)
                window(ui,PREVIEW_TITLE)
                passed(f'{ext.upper()} saved through system picker and fully decoded: {info}')
                return info
            except (EOFError,OSError,KeyError,AssertionError,subprocess.CalledProcessError) as exc:
                last=exc
        time.sleep(1)
    raise AssertionError(f'No complete saved {ext.upper()} under Downloads: {last!r}')

def share_cancel(ui,ext):
    ui.tap(SHARE,require_enabled=True)
    component=wait_component(ui,('ChooserActivity','IntentResolver','BluetoothOpp'))
    ui.screenshot(f'{ext}-share-chooser')
    mode='system-chooser' if 'ChooserActivity' in component or 'IntentResolver' in component else 'single-target-Bluetooth-prompt'
    ui.back()
    window(ui,PREVIEW_TITLE)
    passed(f'{ext.upper()} system sharing route opened and was dismissed without sending ({mode})')
    return mode

def background_cycle(ui, ext):
    ui.shell('input','keyevent','KEYCODE_HOME')
    wait_component(ui,('Launcher','launcher','home'),timeout=35)
    ui.shell('am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',
             '-f','0x10200000','-n',APP+'/com.yuanyang.camera.MainActivity',timeout=40)
    tree,_=window(ui,PREVIEW_TITLE)
    assert match(tree,SAVE,enabled=True) is not None
    passed(f'{ext.upper()} preview survived Home and launcher re-entry')

def rotate_cycle(ui,ext):
    settings={}
    for k in ('accelerometer_rotation','user_rotation'):
        settings[k]=ui.shell('settings','get','system',k)
    try:
        if full_image(ui).width > full_image(ui).height:
            raise AssertionError('Rotation test requires portrait as its starting orientation')
        ui.shell('settings','put','system','accelerometer_rotation','0')
        ui.shell('settings','put','system','user_rotation','1')
        for _ in range(15):
            im=full_image(ui)
            if im.width>im.height:
                im.save(ui.output/f'{ext}-landscape.png')
                break
            time.sleep(.6)
        else:raise AssertionError('Emulator did not rotate preview to landscape')
        tree,_=window(ui,PREVIEW_TITLE)
        assert match(tree,SAVE,enabled=True) is not None, 'Save action missing in landscape preview'
    finally:
        for k,v in reversed(tuple(settings.items())):
            if v in ('null',''):
                ui.shell('settings','delete','system',k)
            else:
                ui.shell('settings','put','system',k,v)
    tree,_=window(ui,PREVIEW_TITLE)
    assert match(tree,SHARE,enabled=True) is not None, 'Share action missing after rotation restored'
    passed(f'{ext.upper()} preview and actions survived rotate/restore')

def format_flow(ui,ext,frames,strict,background=False,rotate=False):
    tree=export_from_album(ui,ext)
    ui.screenshot(ext+'-preview')
    delta=frame_samples(ui,ext,tree)
    if delta>4:passed(f'{ext.upper()} preview media pixels changed in place (mean RGB delta {delta})')
    elif ext=='gif' and strict:raise AssertionError(f'GIF preview did not visibly advance (mean RGB delta {delta})')
    extras=inspect_mp4_controls(ui,strict) if ext=='mp4' else {}
    cancel_save(ui,ext)
    decoded=save_retry(ui,ext,frames)
    share_route=share_cancel(ui,ext)
    if background:background_cycle(ui,ext)
    if rotate:rotate_cycle(ui,ext)
    ui.back()
    tree,_=window(ui,EXPORT,contains=True)
    assert match(tree,'查看上次导出结果',enabled=True) is not None
    passed(f'{ext.upper()} Back restored album and retained reopen action')
    return {'preview_pixel_delta':delta,'saved':decoded,'share_route':share_route,**extras}

def run(args):
    ui=Ui(args.adb,args.serial,args.out)
    ui.ready(args.wait_boot)
    force_disposable(ui)
    passed('disposable emulator booted')
    if args.qa_apk:
        ui.install(args.qa_apk); ui.launch(stop=True)
        seed(ui,args.album,args.frames)
    if not args.use_installed:
        ui.install(args.apk)
        passed('installed final preview APK')
    if not args.on_album:
        ui.launch(stop=True)
    open_album(ui,args.album,args.frames)
    report={}
    for ext in args.formats:
        report[ext]=format_flow(ui,ext,args.frames,args.strict_playback,args.test_background,args.test_rotate)
        (ui.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default=os.environ.get('ADB','adb'))
    p.add_argument('--serial',default='emulator-5556')
    p.add_argument('--apk',type=Path,default=ROOT/'dist/yuan-yang-0.5.0-preview.apk')
    p.add_argument('--qa-apk',type=Path)
    p.add_argument('--album',default='默认相册')
    p.add_argument('--frames',type=int,default=6)
    p.add_argument('--formats',nargs='+',choices=tuple(FORMATS),default=tuple(FORMATS))
    p.add_argument('--out',type=Path,default=ROOT/'dist/export-preview-qa')
    p.add_argument('--wait-boot',type=int,default=320)
    p.add_argument('--use-installed',action='store_true')
    p.add_argument('--on-album',action='store_true',help='App is already showing the synthetic test album')
    p.add_argument('--test-background',action='store_true',help='Also round-trip through Home and Android launcher')
    p.add_argument('--test-rotate',action='store_true',help='Also rotate the disposable Android emulator and restore its original settings')
    p.add_argument('--strict-playback',action='store_true',help='Fail if moving GIF pixels and MP4 controls are not observed')
    args=p.parse_args()
    if not 2<=args.frames<=20:
        p.error('--frames must be between 2 and 20')
    try:run(args)
    except Exception:
        try: Ui(args.adb,args.serial,args.out).screenshot('failure-state')
        except Exception: pass
        raise

if __name__=='__main__':main()
