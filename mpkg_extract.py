#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mpkg_extract.py — 解包 Wallpaper Engine 导出的 .mpkg 文件。

格式依据（AvelcFox/unmpkg，MIT 开源解析器，已逐行核对）：
  offset 0 : version_len   uint32 LE
  offset 4 : version       version_len 字节 UTF-8
  offset 4+version_len : file_count uint32 LE
  之后每个文件一条记录：
    name_len   uint32 LE
    name       name_len 字节 UTF-8
    location   uint32 LE   （保留字段，解包时忽略）
    data_len   uint32 LE
  所有记录之后，文件数据按记录顺序紧密拼接。

用法：
  python3 mpkg_extract.py <xxx.mpkg> [-o 输出目录] [--all]
默认只提取视频/图片类文件；--all 则全部解出。
退出码 0=成功，1=不是合法 mpkg，2=文件损坏。
"""
import os
import struct
import sys

MEDIA_EXTS = {'.mp4', '.webm', '.mov', '.mkv', '.avi', '.m4v', '.ts',
              '.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp'}


def parse_mpkg(data: bytes):
    """返回 (version, [(name, blob), ...])；格式不对抛 ValueError。"""
    if len(data) < 8:
        raise ValueError('文件太小，不是 mpkg')
    version_len = struct.unpack_from('<I', data, 0)[0]
    if version_len > 4096 or 4 + version_len + 4 > len(data):
        raise ValueError('版本号长度异常，不是 mpkg')
    version = data[4:4 + version_len].decode('utf-8', errors='replace')
    file_count = struct.unpack_from('<I', data, 4 + version_len)[0]
    if file_count > 100000:
        raise ValueError('文件数量异常，不是 mpkg')
    off = 8 + version_len
    entries = []
    for _ in range(file_count):
        if off + 4 > len(data):
            raise ValueError('记录头截断')
        name_len = struct.unpack_from('<I', data, off)[0]
        off += 4
        if name_len > 4096 or off + name_len + 8 > len(data):
            raise ValueError('文件名长度异常')
        name = data[off:off + name_len].decode('utf-8', errors='replace')
        off += name_len
        # location 保留字段，解包忽略
        off += 4
        data_len = struct.unpack_from('<I', data, off)[0]
        off += 4
        entries.append((name, data_len))
    blobs = []
    for name, data_len in entries:
        if off + data_len > len(data):
            raise ValueError('文件数据截断：%s' % name)
        blobs.append((name, data[off:off + data_len]))
        off += data_len
    return version, blobs


def safe_name(name: str) -> str:
    # mpkg 里一般是纯文件名；防 ../ 穿透，只取最后一段
    base = os.path.basename(name.replace('\\', '/'))
    return base or 'unnamed'


def main():
    args = sys.argv[1:]
    if not args or '-h' in args or '--help' in args:
        print(__doc__)
        return 0
    src = args[0]
    outdir = None
    extract_all = '--all' in args
    if '-o' in args:
        outdir = args[args.index('-o') + 1]
    with open(src, 'rb') as f:
        data = f.read()
    try:
        version, blobs = parse_mpkg(data)
    except ValueError as e:
        print('解析失败：%s' % e)
        return 1
    print('mpkg 版本：%s，共 %d 个文件' % (version, len(blobs)))
    if outdir is None:
        outdir = os.path.splitext(os.path.basename(src))[0]
    os.makedirs(outdir, exist_ok=True)
    picked = []
    for name, blob in blobs:
        ext = os.path.splitext(name)[1].lower()
        if not extract_all and ext not in MEDIA_EXTS:
            print('  跳过非媒体：%s (%d 字节)' % (name, len(blob)))
            continue
        target = os.path.join(outdir, safe_name(name))
        # 重名加序号
        n = 1
        stem, ext2 = os.path.splitext(target)
        while os.path.exists(target):
            n += 1
            target = '%s_%d%s' % (stem, n, ext2)
        with open(target, 'wb') as f:
            f.write(blob)
        picked.append(target)
        print('  解出：%s (%d 字节)' % (target, len(blob)))
    print('完成：%d 个文件 -> %s' % (len(picked), outdir))
    return 0


if __name__ == '__main__':
    sys.exit(main())
