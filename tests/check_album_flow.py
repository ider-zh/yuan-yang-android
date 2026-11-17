#!/usr/bin/env python3
"""Focused smoke for a fresh disposable emulator; isolates the 0.3 preview app."""
import argparse
import re
import time
from pathlib import Path
from smoke_ui import Ui, passed, ACTIVITY, CURRENT, APP

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--out', type=Path, required=True)
p.add_argument('--apk', type=Path, default=CURRENT)
p.add_argument('--wait-boot', type=int, default=240)
p.add_argument('--resume-confirmation', action='store_true', help='Resume a synthetic run already at its first delete confirmation')
args = p.parse_args()
ui = Ui(args.adb, args.serial, args.out)
ui.ready(args.wait_boot)
assert ui.shell('getprop','ro.kernel.qemu') == '1' or ui.shell('getprop','ro.boot.qemu') == '1'
if not args.resume_confirmation:
    ui.install(args.apk)
    ui.launch(stop=True)

def locate(node):
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
    return str((x1+x2)//2),str((y1+y2)//2)
def hit(node):
    ui.shell('input','tap',*locate(node))
def tree():
    return [e.attrib for e in ui.snapshot().iter('node')]
def find(nodes, word, *, clickable=False, contains=False):
    for n in nodes:
        for v in (n.get('text',''),n.get('content-desc','')):
            if (word in v if contains else word == v) and (not clickable or n.get('clickable') == 'true'):
                return n
    raise AssertionError((word,[(n.get('text'),n.get('content-desc')) for n in nodes if n.get('text') or n.get('content-desc')]))
def exists(nodes, word):
    return any(word in n.get('text','') or word in n.get('content-desc','') for n in nodes)
def camera(nodes, album):
    l=find(nodes,'所有相册',clickable=True)
    r=find(nodes,'打开当前相册：'+album,clickable=True)
    assert int(locate(l)[0]) < int(locate(r)[0])
    return l,r

def open_date(nodes):
    for _ in range(3):
        for n in nodes:
            if re.fullmatch(r'20\d\d-\d\d-\d\d',n.get('text','')):
                hit(n); return
        ui.shell('input','swipe','270','760','270','380','260')
        nodes=tree()
    raise AssertionError('Date missing')

if not args.resume_confirmation:
    ui.find('拍照',require_enabled=True)
    start=tree()
    left,right=camera(start,'默认相册')
    ui.screenshot('camera')
    hit(left)
    albums=tree()
    find(albums,'所有相册')
    new=find(albums,'新建相册',contains=True,clickable=True)
    assert not exists(albums,'模板') and not exists(albums,'点位')
    ui.screenshot('album-list')
    ui.back()
    ui.find('拍照',require_enabled=True)
    passed('one tap left directly opens all albums; Back resumes camera')

    # Take two real virtual-camera photos without seeding app-private files.
    shutter=locate(find(start,'拍照',clickable=True))
    ui.shell('input','tap',*shutter)
    ui.find('隐藏叠影',require_enabled=True)
    ui.find(('拍照','复拍'),require_enabled=True)
    ui.tap(('拍照','复拍'),require_enabled=True)
    time.sleep(1.2)
    ui.tap('打开当前相册：默认相册',require_enabled=True, predicate=lambda a:a.get('clickable')=='true')
    album=tree()
    find(album,'2 张照片',contains=True)
    find(album,'1 帧',contains=True)
    assert not exists(album,'点位')
    ui.screenshot('current-album')
    passed('one tap right directly opens current album, with two captured photos and one selected day')

    open_date(album)
    ui.tap('删除',require_enabled=True)
ui.find('删除照片？')
conf=tree()
find(conf,'删除照片？')
ui.screenshot('delete-confirmation')
hit(find(conf,'取消',clickable=True))
ui.find('2 / 2',contains=True)
passed('cancel keeps both photos')
ui.tap('删除',require_enabled=True)
ui.find('删除照片？')
ui.tap('删除',require_enabled=True, predicate=lambda n:n.get('class')=='android.widget.Button')
ui.find('1 张照片',contains=True)
album=tree()
find(album,'1 张照片',contains=True)
hit(find(album,'全部记录',contains=True,clickable=True))
ui.find('✓ 全部记录')
album=tree()
find(album,'1 帧',contains=True)
ui.screenshot('after-delete')
passed('confirmed deletion removes only one photo and updates the all-records frame count')
hit(find(album,'所有相册',clickable=True))
ui.find('新建相册',contains=True)
albums=tree()
hit(find(albums,'新建相册',contains=True,clickable=True))
ui.find('保存',require_enabled=True)
editor=tree()
edit=next(n for n in editor if n.get('class')=='android.widget.EditText')
hit(edit)
ui.shell('input','text','AlbumQA')
ui.back() # The keyboard moves the Save button; locate it again.
ui.tap('保存',require_enabled=True)
ui.find('打开当前相册：AlbumQA')
ui.launch(stop=True)
ui.find('打开当前相册：AlbumQA')
ui.screenshot('reopen-last-album')
passed('new album opens its camera and is restored after cold restart')
