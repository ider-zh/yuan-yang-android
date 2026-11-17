#!/usr/bin/env python3
"""Seed PRIVATE synthetic inputs in a disposable emulator, then test the release APK.

Usage: python3 tests/check_person_alignment_ui.py --qa-apk FILE --apk FILE \
    --fixtures DIR --adb PATH --serial emulator-5556

Requires the same app ID and signature on both APKs; the QA-only APK permits run-as.
The release APK is installed over it before any export is tested. The supplied
fixture album is left on the disposable test device. No picture is sent anywhere.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

from PIL import Image
import numpy as np
from smoke_ui import APP, Ui, album_labels, camera_nav, downloads, passed

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_NAMES = ('01-reference', '02-offset', '03-downscale', '04-rotate', '05-combined', '07-no-person-sky')

def text_fields(tree):
    return tuple(x.attrib.get(k,'') for x in tree.iter('node') for k in ('text','content-desc') if x.attrib.get(k))

def require_disposable(ui):
    if (ui.shell('getprop','ro.kernel.qemu') != '1' and ui.shell('getprop','ro.boot.qemu') != '1'):
        raise AssertionError('This test writes only a newly created QA album in a disposable Android emulator.')

def seed(ui, album, fixtures):
    albums=json.loads(ui.shell('run-as',APP,'cat','files/spaces.json'))
    match = next(x for x in albums if x.get('name')==album)
    album_id=str(uuid.UUID(match['id']))
    base=f'files/timeline/{album_id}'
    ui.shell('run-as',APP,'mkdir','-p',base)
    assert not any(line.endswith('.jpg') for line in ui.shell('run-as',APP,'ls',base).splitlines()), 'The default QA album must be empty before seeding'
    for i, name in enumerate(FIXTURE_NAMES,1):
        local=fixtures/(name+'.jpg')
        dest=base+'/'+f'7f000000-0000-4000-a000-{i:012x}.jpg'
        payload=local.read_bytes()
        if payload[:2]!=b'\xff\xd8' or payload[-2:]!=b'\xff\xd9':
            raise AssertionError(f'{local} not a JPEG')
        remote=f'run-as {APP} sh -c {shlex.quote(f"cat > {shlex.quote(dest)}")}'
        cmd=[*ui.adb,'shell','-T',remote]
        subprocess.run(cmd,input=payload,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=60)
        ui.shell('run-as',APP,'touch','-t',f'202611151155.{i:02d}',dest)
        copied=ui.run('exec-out','run-as',APP,'cat',dest,raw=True)
        assert copied==payload, f'Fixture did not survive test-only copy: {dest}'
    passed('seeded six synthetic JPEG fixtures in chronological order with exact readback')

def select_format(ui):
    ui.tap('导出 GIF / MP4',contains=True,require_enabled=True)
    ui.tap('GIF 动图',require_enabled=True)
    ui.find('GIF 动图已生成',contains=True,retries=15)

def save_output(ui, name):
    before=downloads(ui,'gif')
    ui.tap('保存到文件')
    ui.tap(('SAVE','Save','保存'),require_enabled=True,predicate=lambda a:a.get('clickable')=='true')
    end=time.monotonic()+75
    while time.monotonic()<end:
        choices=[p for p in sorted(downloads(ui,'gif')-before) if p.startswith('/sdcard/Download/')]
        if choices:
            host_path=ui.output/name
            ui.run('pull',choices[-1],str(host_path))
            try:
                with Image.open(host_path) as img:
                    img.seek(len(FIXTURE_NAMES)-1)
                    img.load()
                return host_path
            except (OSError, EOFError):
                pass  # File provider may list destination before bytes finish writing.
        time.sleep(1)
    raise AssertionError('No complete GIF saved to the Android test Downloads directory')

def alignment_summary(ui):
    values=text_fields(ui.snapshot())
    observed=next((x for x in values if re.search(r'已对齐\s*\d+\s*张',x)),None)
    if not observed:
        raise AssertionError(f'Alignment summary missing: {values}')
    aligned=int(re.search(r'已对齐\s*(\d+)\s*张',observed)[1])
    skipped=re.search(r'有\s*(\d+)\s*张未对齐',observed)
    if aligned<2 or not skipped or int(skipped[1])<1:
        raise AssertionError(f'Expected at least two transformed frames and a fallback, got {observed!r}')
    return observed

def gif_frames(path):
    result=[]
    with Image.open(path) as image:
        for i in range(image.n_frames):
            image.seek(i)
            result.append(np.asarray(image.convert('RGB')).copy())
    if len(result)!=len(FIXTURE_NAMES):
        raise AssertionError(f'{path} dropped frames: expected {len(FIXTURE_NAMES)}, got {len(result)}')
    return result

def ncc_offset(reference, frame):
    """Measure image displacement close to the upper torso. No ML dependencies."""
    gray=lambda im:np.mean(im.astype(np.float32),axis=2)
    ref, mov=gray(reference),gray(frame)
    h,w=ref.shape
    cx,cy=round(w*.502),round(h*.586)
    rx,ry=max(10,w//37),max(12,h//28)
    target=ref[cy-ry:cy+ry,cx-rx:cx+rx]
    target=target-target.mean()
    target/=max(1e-6,float(np.linalg.norm(target)))
    d=max(8,round(w*.095))
    score,dx,dy=-1.0,0,0
    for oy in range(-d,d+1,2):
        for ox in range(-d,d+1,2):
            patch=mov[cy+oy-ry:cy+oy+ry,cx+ox-rx:cx+ox+rx]
            patch=patch-patch.mean()
            corr=float((target*patch).sum())/max(1e-6,float(np.linalg.norm(patch)))
            if corr>score:
                score,dx,dy=corr,ox,oy
    return {'x':dx,'y':dy,'ncc':score,'norm':float(np.hypot(dx,dy))}

def center_zoom(source, zoom):
    h,w=source.shape[:2]
    f=1.0/zoom
    matrix=(f,0,w*(1-f)/2,0,f,h*(1-f)/2)
    return np.asarray(Image.fromarray(source).transform((w,h),Image.Transform.AFFINE,matrix,
                                                       Image.Resampling.BILINEAR)).astype(np.float32)

def pixel_error(a, b):
    return float(np.abs(a-b.astype(np.float32)).mean())

def analyze(ordinary_path, aligned_path, summary, report):
    ordinary,aligned=gif_frames(ordinary_path),gif_frames(aligned_path)
    if aligned[0].shape != ordinary[0].shape:
        raise AssertionError('Alignment unexpectedly changed the GIF dimensions')
    # The same limited zoom can be applied to all frames to avoid visible bars.
    # Measure each variant against the reference from its own GIF, not the other
    # GIF's source geometry, and verify no person-specific transform via summary.
    before=[ncc_offset(ordinary[0],frame) for frame in ordinary[1:-1]]
    after=[ncc_offset(aligned[0],frame) for frame in aligned[1:-1]]
    drift_before=float(np.mean([p['norm'] for p in before]))
    drift_after=float(np.mean([p['norm'] for p in after]))
    # A common center crop is permissible, but the no-person photo must not
    # receive the offset/rotation applied to any person. Estimate common zoom
    # from the reference (which lacks a person-specific transform by definition).
    zoom_guess=min(np.linspace(1.0,1.2,41),
                   key=lambda z:pixel_error(center_zoom(ordinary[0],z),aligned[0]))
    zoom_fit_error=pixel_error(center_zoom(ordinary[0],zoom_guess),aligned[0])
    no_person_error=pixel_error(center_zoom(ordinary[-1],zoom_guess),aligned[-1])
    data=dict(summary=summary,before=before,after=after,mean_drift_px_before=drift_before,
              mean_drift_px_after=drift_after,estimated_common_zoom=float(zoom_guess),
              reference_zoom_fit_mae=zoom_fit_error,no_person_fallback_mae=no_person_error,
              frames=len(ordinary))
    report.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    if no_person_error>max(12.,zoom_fit_error+4.):
        raise AssertionError(f'No-person frame deviates from common zoom: {no_person_error:.2f} mean RGB levels')
    if drift_before<4.0 or drift_after>drift_before*.80:
        raise AssertionError(f'Torso drift did not improve by 20%: {drift_before:.2f}→{drift_after:.2f}px')
    passed(f'GIF output: both six frames; fallback frame retained and counted; measured torso drift {drift_before:.2f}px → {drift_after:.2f}px')

def run(args):
    ui=Ui(args.adb,args.serial,args.out)
    ui.ready(args.wait_boot)
    require_disposable(ui)
    passed('Android emulator booted and responsive')
    state=args.out/'fixture-state.json'
    if args.export_only:
        album=json.loads(state.read_text())['album']
    else:
        if not args.seed_on_installed:
            ui.install(args.qa_apk)
            passed('installed seed-only QA APK')
            ui.launch(stop=True)
        album='默认相册'
        seed(ui,album,args.fixtures)
        state.write_text(json.dumps({'album':album})+'\n')
        if args.seed_only:return
    ui.install(args.apk)
    passed('installed final preview APK over seed APK')
    ui.launch(stop=True)
    camera_nav(ui,album)
    ui.tap(album_labels(album),predicate=lambda a:a.get('clickable')=='true')
    ui.find('6 张照片',contains=True)
    checkbox='对齐人物'
    ui.find(checkbox,require_enabled=False)
    flags=lambda: [n.attrib for n in ui.snapshot().iter('node') if n.attrib.get('text')==checkbox]
    assert any(a.get('enabled')=='false' for a in flags()), 'Alignment enabled for one frame'
    ui.tap('全部记录',contains=True,require_enabled=True)
    ui.find('6 帧',contains=True)
    ui.find(checkbox,require_enabled=True)
    current=flags()
    if any(a.get('checked')=='true' for a in current):
        ui.tap(checkbox,require_enabled=True)
    ui.screenshot('person-alignment-opt-in')
    if not args.aligned_only:
        select_format(ui)
        ordinary=save_output(ui,'ordinary-6-frames.gif')
        passed('release APK exported ordinary GIF via the Android file picker')
    ui.tap(checkbox,require_enabled=True)
    select_format(ui)
    summary=alignment_summary(ui)
    ui.screenshot('person-alignment-result')
    aligned=save_output(ui,'aligned-6-frames.gif')
    passed('release APK exported opt-in aligned GIF via the Android file picker')
    if args.aligned_only:
        confirmed=gif_frames(aligned)
        (args.out/'alignment-report.json').write_text(json.dumps(dict(summary=summary,frames=len(confirmed)),
                            ensure_ascii=False,indent=2)+'\n')
        passed(f'aligned-only run decoded all {len(confirmed)} GIF frames; summary: {summary}')
    else:
        analyze(ordinary,aligned,summary,args.out/'alignment-report.json')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default=os.environ.get('ADB','adb'))
    p.add_argument('--serial',default='emulator-5556')
    p.add_argument('--qa-apk',type=Path)
    p.add_argument('--apk',type=Path)
    p.add_argument('--fixtures',type=Path,default=ROOT.parent/'yy-person-qa')
    p.add_argument('--out',type=Path,default=ROOT/'dist/person-alignment-qa')
    p.add_argument('--wait-boot',type=int,default=400)
    p.add_argument('--seed-on-installed',action='store_true',help='Use an already installed/launched seed APK')
    p.add_argument('--aligned-only',action='store_true',help='Fast smoke: skip the ordinary control export')
    phases=p.add_mutually_exclusive_group()
    phases.add_argument('--seed-only',action='store_true')
    phases.add_argument('--export-only',action='store_true')
    args=p.parse_args()
    if not args.export_only and not args.seed_on_installed and not args.qa_apk:
        p.error('--qa-apk required for seeding')
    if not args.seed_only and not args.apk:
        p.error('--apk required for release export test')
    try:
        run(args)
    except Exception:
        try: Ui(args.adb,args.serial,args.out).screenshot('alignment-qa-failure')
        except Exception: pass
        raise
if __name__=='__main__':
    main()
