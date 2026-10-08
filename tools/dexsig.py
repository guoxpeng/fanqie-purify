#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dexsig.py — 打印指定类的方法签名清单（用于决定 hook 哪种签名）。

用法:
  python dexsig.py --dex-dir .buildtmp/dex672 --class com.bytedance.polaris.impl.voice.n
"""
import argparse
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexfind import Dex  # noqa


def cls_desc(name):
    if name.startswith("L"):
        return name
    return "L" + name.replace(".", "/") + ";"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dex-dir", required=True)
    ap.add_argument("--class", dest="cls", nargs="+", required=True)
    a = ap.parse_args()

    want = {}
    for c in a.cls:
        want[cls_desc(c)] = c
    for fn in sorted(os.listdir(a.dex_dir)):
        if not fn.endswith(".dex"):
            continue
        dex = Dex(os.path.join(a.dex_dir, fn))
        b = dex.buf
        for i in range(dex.class_defs_size):
            base = dex.class_defs_off + i * 32
            cidx = struct.unpack_from("<I", b, base)[0]
            name = dex.class_name(cidx)
            if name not in want:
                continue
            cdata = struct.unpack_from("<I", b, base + 24)[0]
            print("== %s  [%s]" % (name, fn))
            for midx, code_off in dex.iter_methods(cdata):
                print("   %s   code=%s" % (dex.method_signature(midx), hex(code_off)))


if __name__ == "__main__":
    main()
