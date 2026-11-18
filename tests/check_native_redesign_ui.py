#!/usr/bin/env python3
"""Native-gallery and camera QA on a disposable, synthetic Android emulator.

Pass --apk for an in-place APK update (same package and signer as the source test
install). Reuse the known 0.5 emulator's eight-image default synthetic album
with --photos 8, or seed an empty test album using --seed-apk. Deletion is
off unless --delete is passed, and the runner always requires an emulator.
"""
import argparse
import json
import os
from pathlib import Path
import re
import time

from PIL import ImageStat
from smoke_ui import album_labels, passed
from check_export_preview_ui import (Ui, bounds, force_disposable, full_image,
                                     match, seed, tap_node, window, xy)

ROOT=Path(__file__).resolve().parents[1]
SHUTTER=('拍照','复拍')
FRONT=('切换到前置摄像头','切换到前置')
REAR=('切换到后置摄像头','切换到后置')
OVERLAY=('叠影设置','收起叠影设置')
SLIDER='旧照片叠影透明度'
EXPORT='制作影像'
GALLERY='继续拍摄'
VIEWER='返回相册'
THUMB='查看照片'

def active_action(tree,labels,*,contains=False):
    target=match(tree,labels,contains=contains,enabled=True,clickable=True)
    if target is None:
        target=match(tree,labels,contains=contains,enabled=True)
    if target is None:raise AssertionError(f'Missing active action: {labels!r}')
    return target

def wait_action(ui, labels, attempts=5):
    for _ in range(attempts):
        tree,_=window(ui,labels)
        node=match(tree,labels,enabled=True,clickable=True)
        if node is not None:return tree,node
        time.sleep(.4)
    raise AssertionError(f'Action never became enabled: {labels!r}')

def center(node):
    x1,y1,x2,y2=bounds(node)
    return ((x1+x2)//2,(y1+y2)//2)

def frame(ui,name):
    img=full_image(ui);img.save(ui.output/(name+'.png'));return img

def density(ui):
    m=re.findall(r'(?i)(?:override|physical) density:\s*(\d+)',ui.shell('wm','density'))
    return int(m[-1]) if m else 160

def camera(ui):
    tree,_=window(ui,SHUTTER)
    return tree,active_action(tree,SHUTTER)

def expect_center(ui, tree, shutter, label):
    img=frame(ui,label)
    x,y=center(shutter)
    assert abs(x-img.width/2)<=max(4,img.width*.025), (label,x,img.width)
    x1,y1,x2,y2=bounds(shutter);minimum=48*density(ui)/160
    assert min(x2-x1,y2-y1)>=minimum-1, ('unsupported tap target',bounds(shutter),minimum)
    return img,(x,y)

def assert_x_stable(before,after,label):
    assert abs(before[0]-after[0])<=3,(label,before,after)

def facing_in_system(ui):
    raw=ui.shell('dumpsys','media.camera',timeout=35)
    (ui.output/'native-camera-service.txt').write_text(raw+'\n')
    m=re.search(r'Number of (?:normal camera devices|camera devices):\s*(\d+)',raw,re.I)
    return int(m[1]) if m else None

def camera_review(ui,args,tree,shutter):
    img,before=expect_center(ui,tree,shutter,'native-camera-entry')
    album=active_action(tree,f'打开当前相册：{args.album}')
    library=active_action(tree,'所有相册')
    album_center=center(album)
    assert album_center[0]<before[0] and abs(album_center[1]-before[1])<=3,(
        'current album shortcut must occupy lower-left shutter row',album_center,before)
    assert center(library)[1] < album_center[1], 'All albums stays in the header'
    x1,y1,x2,y2=bounds(album)
    assert min(x2-x1,y2-y1)>=48*density(ui)/160-1, 'album shortcut target below 48dp'
    assert not any(n.get('text')==args.album and n.get('clickable')=='true'
        for n in tree.iter('node')), 'Duplicate album text shortcut on camera'
    passed('bottom-left current album shortcut is distinct from the header and shutter')
    cnt=facing_in_system(ui)
    current=match(tree,(*FRONT,*REAR),enabled=True)
    if args.facing=='single':
        assert cnt==1,'Single-facing check requires evidence of exactly one reported camera'
        assert match(tree,(*FRONT,*REAR)) is None,'Single-facing device still shows flip'
        passed('single-facing camera: no flip control, shutter still centered')
    else:
        assert cnt is not None and cnt>=2,'Dual-facing fixture unavailable'
        assert current is not None,'Missing flip button'
        assert center(current)[0]>before[0],'Flip must be on the right'
        x1,y1,x2,y2=bounds(current); minimum=48*density(ui)/160
        assert min(x2-x1,y2-y1)>=minimum-1,('flip target below 48dp',bounds(current),minimum)
        candidates={current.attrib.get('content-desc',''),current.attrib.get('text','')}
        target=FRONT if candidates.intersection(REAR) else REAR
        tap_node(ui,current)
        updated,_=window(ui,target)
        after_node=active_action(updated,SHUTTER)
        _,after=expect_center(ui,updated,after_node,'native-camera-flipped')
        assert_x_stable(before,after,'camera flip')
        assert abs(before[1]-after[1])<=3,('flip shifted shutter vertically',before,after)
        passed('flipping cameras preserves shutter position and 48dp hit targets')
        tree=updated
    if args.photos:
        ghost=active_action(tree,OVERLAY)
        assert center(ghost)[1]<album_center[1],'Overlay settings must be separate from the shutter row'
        assert match(tree,SLIDER) is None,'Overlay slider should start collapsed'
        tap_node(ui,ghost)
        opened,_=window(ui,SLIDER)
        button=match(opened,('隐藏叠影','显示叠影'))
        assert button is not None,'Overlay drawer lacks independent visibility action'
        _,shutter_open=expect_center(ui,opened,active_action(opened,SHUTTER),'native-camera-overlay-expanded')
        assert_x_stable(before,shutter_open,'overlay panel')
        tap_node(ui,active_action(opened,'收起叠影设置'))
        closed,_=window(ui,'叠影设置')
        assert match(closed,SLIDER) is None,'Collapsing should hide opacity slider'
        _,shutter_closed=expect_center(ui,closed,active_action(closed,SHUTTER),'native-camera-overlay-collapsed')
        assert_x_stable(before,shutter_closed,'collapsed panel')
        passed('overlay settings expand/collapse independently without moving the shutter off center')
        tree=closed
    return tree

def parse_count(tree):
    for node in tree.iter('node'):
        s=node.attrib.get('text','')
        m=re.search(r'\b(\d+)\s*张照片',s)
        if m:return int(m[1])
    return None

def visible_thumbs(tree):
    items=[]
    for n in tree.iter('node'):
        desc=n.attrib.get('content-desc','')
        if THUMB in desc:
            x1,y1,x2,y2=bounds(n)
            if x2-x1>8 and y2-y1>8:items.append(n)
    return items

def find_date(node):
    desc=node.attrib.get('content-desc','')
    m=re.search(r'\d{4}-\d\d-\d\d',desc)
    return m[0] if m else None

def verify_gallery(ui,photos):
    tree,_=window(ui,GALLERY)
    if parse_count(tree) is not None:
        assert parse_count(tree)==photos,('album photo count',parse_count(tree),photos)
    thumbs=visible_thumbs(tree)
    assert len(thumbs)>=min(photos,2),('Too few visible first-viewport photo tiles',len(thumbs),photos)
    assert match(tree,EXPORT) is not None,'Missing pinned export action'
    assert match(tree,'对齐人物') is None,'Export controls should not crowd gallery viewport'
    img=frame(ui,'native-gallery-first-viewport')
    def tile_signal(tile):
        x1,y1,x2,y2=bounds(tile); dx=(x2-x1)//5; dy=(y2-y1)//5
        return sum(ImageStat.Stat(img.crop((x1+dx,y1+dy,x2-dx,y2-dy))).stddev)/3
    image_signal=max((tile_signal(t) for t in thumbs),default=0)
    if photos:
        assert image_signal>=4,('gallery appears to show placeholders rather than loaded photos',image_signal)
    passed(f'gallery first viewport shows {len(thumbs)} accessible photo tiles before opening export')
    return tree,thumbs

def check_all_albums(ui,args,tree):
    tap_node(ui,active_action(tree,'所有相册'))
    library,_=window(ui,'新建相册')
    album=match(library,f'{args.album}，{args.photos} 张照片，打开相机')
    assert album is not None,'Album row is missing name/count or accessible direct-camera route'
    assert match(library,'管理点位与前后对照') is None,'Legacy tools leaked into all albums'
    image=frame(ui,'native-all-albums')
    x1,y1,x2,y2=bounds(album); scale=density(ui)/160
    if args.photos:
        cover_margin=int(17*scale)
        cover_right=int(57*scale)
        cover_bottom=min(y2-int(8*scale),y1+int(56*scale))
        cover_box=(x1+cover_margin,y1+cover_margin,x1+cover_right,cover_bottom)
        cover_stat=ImageStat.Stat(image.crop(cover_box)).stddev
        assert sum(cover_stat)/3>3,('all-albums cover appears unpopulated',cover_stat)

    ui.back()
    back_tree,_=camera(ui)
    assert match(back_tree,album_labels(args.album),contains=True) is not None
    passed('all albums opens directly with a real cover/count; Back restores the same album camera')
    return back_tree

def choose_non_daily(thumbs):
    return next((t for t in thumbs if '每日选片' not in t.attrib.get('content-desc','')),None)

def fullscreen_flow(ui,args,tree,thumbs):
    target=choose_non_daily(thumbs)
    assert target is not None,'Fixture album needs a date with at least two photos'
    day=find_date(target)
    assert day is not None
    tap_node(ui,target)
    viewer,_=window(ui,VIEWER,contains=True)
    frame(ui,'native-photo-fullscreen')
    viewer,choose=wait_action(ui,'设为当天选片')
    tap_node(ui,choose)
    selected,_=window(ui,'已设为当天选片')
    assert match(selected,'已设为当天选片',enabled=True) is None,'Selected state should not be an active repeat action'
    assert match(selected,'删除照片') is not None
    # Drawer close and gallery update must preserve group and a selected badge.
    ui.back()
    fresh,fresh_thumbs=verify_gallery(ui,args.photos)
    subject=target.attrib.get('content-desc','').split('，',1)[0]
    assert any(t.attrib.get('content-desc','').startswith(subject+'，每日选片')
               for t in fresh_thumbs),('daily selection badge missing for exact photo',subject)
    passed('full-screen viewer can change selection; Back returns to gallery with daily badge')

    # Reopen the same selected day for delete-confirm cancellation. Test only
    # synthetic images, never an external album.
    selected_thumb=next(t for t in fresh_thumbs if t.attrib.get('content-desc','').startswith(subject+'，每日选片'))
    tap_node(ui,selected_thumb)
    viewer,remove=wait_action(ui,'删除照片')
    tap_node(ui,remove)
    confirm,_=window(ui,'删除照片？')
    tap_node(ui,active_action(confirm,'取消'))
    viewer,_=window(ui,VIEWER,contains=True)
    frame(ui,'native-photo-delete-cancel')
    passed('delete confirmation can be canceled without leaving photo viewer')
    if args.delete:
        tap_node(ui,active_action(viewer,'删除照片'))
        confirm,_=window(ui,'删除照片？')
        tap_node(ui,active_action(confirm,'删除'))
        after,_=window(ui,(VIEWER,GALLERY),contains=True)
        if match(after,VIEWER,contains=True) is not None:ui.back()
        gallery,_=window(ui,GALLERY)
        if parse_count(gallery) is not None:
            assert parse_count(gallery)==args.photos-1,('delete count',parse_count(gallery))
        assert len(visible_thumbs(gallery))>0,'Delete unexpectedly emptied gallery'
        frame(ui,'native-gallery-after-delete')
        passed('one confirmed synthetic photo removed; gallery remains reachable')
        return args.photos-1
    ui.back()
    gallery,_=window(ui,GALLERY)
    assert parse_count(gallery) in (args.photos,None)
    return args.photos

def export_flow(ui, photos, skip_file):
    tree,_=window(ui,GALLERY)
    tap_node(ui,active_action(tree,EXPORT))
    sheet,_=window(ui,'每天一张')
    frame(ui,'native-export-bottom-sheet')
    assert match(sheet,'全部照片') is not None and match(sheet,'对齐人物') is not None
    assert match(sheet,'GIF 动图') is not None and match(sheet,'MP4 视频') is not None
    if not skip_file:
        # Prove format selection still opens preview automatically, without
        # relying on the changed camera or using user photos.
        tap_node(ui,active_action(sheet,'全部照片'))
        sheet,_=window(ui,'对齐人物')
        align=match(sheet,'对齐人物')
        if align is not None and align.attrib.get('checked')=='true':
            tap_node(ui,align)
            sheet,_=window(ui,'GIF 动图')
        tap_node(ui,active_action(sheet,'GIF 动图'))
        preview,_=window(ui,'导出预览',retries=16)
        assert match(preview,'保存到文件') is not None and match(preview,'分享到其他应用') is not None
        frame(ui,'native-export-preview')
        ui.back()
        window(ui,GALLERY)
        passed('export bottom sheet produces actual GIF preview, Back returns to gallery')
    else:
        ui.back()
        window(ui,GALLERY)
        passed('bottom-sheet settings visible and dismiss back to gallery')

def run(args):
    ui=Ui(args.adb,args.serial,args.out)
    ui.ready(args.wait_boot);force_disposable(ui)
    if args.seed_apk:
        ui.install(args.seed_apk);ui.launch(stop=True);seed(ui,args.album,args.photos)
    if args.apk:
        ui.install(args.apk)
        passed(f'installed candidate {args.apk.name}')
    if not args.on_gallery:
        ui.launch(stop=True)
        tree,shutter=camera(ui)
        if not args.gallery_only:
            tree=camera_review(ui,args,tree,shutter)
        if args.camera_only:
            (ui.output/'report.json').write_text(json.dumps({'camera':args.facing,'gallery':False})+'\n')
            return
        if not args.skip_library:tree=check_all_albums(ui,args,tree)
        tap_node(ui,active_action(tree,album_labels(args.album)))
    tree,thumbs=verify_gallery(ui,args.photos)
    count=args.photos
    if not args.gallery_first_pane_only:
        count=fullscreen_flow(ui,args,tree,thumbs)
        export_flow(ui,count,args.skip_file)
    (ui.output/'report.json').write_text(json.dumps({'gallery':True,'photos_after':count,
         'first_viewport_tiles':len(thumbs),'tested_camera':not(args.on_gallery or args.gallery_only)},indent=2)+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default=os.environ.get('ADB','adb'))
    p.add_argument('--serial',default='emulator-5556')
    p.add_argument('--apk',type=Path)
    p.add_argument('--seed-apk',type=Path)
    p.add_argument('--album',default='默认相册')
    p.add_argument('--photos',type=int,default=8)
    p.add_argument('--out',type=Path,default=ROOT/'dist/native-redesign-qa')
    p.add_argument('--wait-boot',type=int,default=320)
    p.add_argument('--facing',choices=('dual','single'),default='dual')
    rr=p.add_mutually_exclusive_group()
    rr.add_argument('--gallery-only',action='store_true')
    rr.add_argument('--camera-only',action='store_true')
    p.add_argument('--on-gallery',action='store_true')
    p.add_argument('--skip-library',action='store_true')
    p.add_argument('--skip-file',action='store_true',help='Only inspect and dismiss settings sheet')
    p.add_argument('--gallery-first-pane-only',action='store_true')
    p.add_argument('--delete',action='store_true',help='Confirm a deletion in known synthetic test album')
    args=p.parse_args()
    if args.on_gallery and (args.apk or args.camera_only):
        p.error('--on-gallery requires an installed APK and gallery mode')
    try:run(args)
    except Exception:
        try:frame(Ui(args.adb,args.serial,args.out),'failure')
        except Exception:pass
        raise

if __name__=='__main__':main()
