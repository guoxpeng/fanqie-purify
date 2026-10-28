#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_targets.py — 把 MainHook.java 里出现的「类名候选」全部拿到目标 dex 里核对存在性。

用法:
  python3 tools/check_targets.py --dex-dir .buildtmp/dex676 \
      --src module/src/com/eta/fanqie/enhance/MainHook.java
"""
import argparse
import glob
import os
import re
import sys

CLS_RE = re.compile(r'^[A-Za-z][A-Za-z0-9_$]*(\.[A-Za-z0-9_$]+)+$')
# 明显不是类名的字符串（包名前缀片段 / 资源名 / 域名）
BAD_SUFFIX = (".apk", ".png", ".jpg", ".xml", ".json", ".txt", ".com.cn")


def literals(src):
    """抽取 Java 源码里的字符串字面量。"""
    out = []
    for m in re.finditer(r'"((?:[^"\\]|\\.)*)"', src):
        out.append(m.group(1))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dex-dir", required=True)
    ap.add_argument("--src", default="module/src/com/eta/fanqie/enhance/MainHook.java")
    a = ap.parse_args()

    src = open(a.src, encoding="utf-8").read()
    cands = set()
    for s in literals(src):
        if len(s) < 4 or len(s) > 120:
            continue
        if s.endswith(BAD_SUFFIX):
            continue
        if not CLS_RE.match(s):
            continue
        # 只要「看起来像全限定类名」的：至少两段，且不含空格/中文
        if not s[0].isalpha():
            continue
        cands.add(s)

    dexfiles = sorted(glob.glob(os.path.join(a.dex_dir, "*.dex")))
    if not dexfiles:
        sys.exit("没找到 dex: %s" % a.dex_dir)
    blobs = [open(f, "rb").read() for f in dexfiles]

    missing, present = [], []
    for c in sorted(cands):
        desc = ("L" + c.replace(".", "/") + ";").encode()
        hit = any(desc in b for b in blobs)
        (present if hit else missing).append(c)

    print("== 存在 %d 个 ==" % len(present))
    for c in present:
        print("  OK   " + c)
    print("\n== 缺失 %d 个（需要重新定位） ==" % len(missing))
    for c in missing:
        print("  MISS " + c)


if __name__ == "__main__":
    main()
