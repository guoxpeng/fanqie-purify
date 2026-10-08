#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
adscan.py — 主动逆向：在 dex 里枚举「广告 / 弹窗 / 促销 / 会员 / hybrid bridge」相关类，
用于找出净化模块尚未覆盖的入口。

用法:
  python adscan.py --dex-dir .buildtmp/dex672 --list-ads          # 广告类清单
  python adscan.py --dex-dir .buildtmp/dex672 --list-bridges      # hybrid bridge 模块
  python adscan.py --dex-dir .buildtmp/dex672 --list-popup        # 弹窗/子窗口/浮层类
  python adscan.py --dex-dir .buildtmp/dex672 --list-promo        # 会员/促销/金币类
  python adscan.py --dex-dir .buildtmp/dex672 --str 开通 会员      # 字符串池搜索
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dexfind import Dex  # noqa


AD_KEYS = ("/ad/", "/ads/", "admodule", "advert", "mannor", "adlynx", "adconfig",
           "feedbanner", "onestop", "inspire", "interstitial", "rewardvideo",
           "reward", "splashad", "splash_ad", "unlocktime", "freead", "adunlock",
           "adcard", "adview", "aditem", "adslot", "admanager", "adloader")

POPUP_KEYS = ("dialog", "popup", "subwindow", "floatview", "overlay", "panel",
              "bottomsheet", "toast", "guideview", "maskview")

PROMO_KEYS = ("vip", "promo", "member", "purse", "charge", "cash", "coupon",
              "lottery", "lucky", "welfare")

BRIDGE_KEYS = ("hybrid/bridge/modules",)


def load_all(dex_dir):
    out = []
    for fn in sorted(os.listdir(dex_dir)):
        if not fn.endswith(".dex"):
            continue
        out.append((fn, Dex(os.path.join(dex_dir, fn))))
    return out


def class_names(dex):
    """通过 class_defs 拿真实定义的类描述符（不含纯引用）。"""
    return [dex.class_name(ci) for ci, _ in dex.iter_classes()]


def cmd_list(dexes, keys, title, limit):
    seen = {}
    for fn, dex in dexes:
        for d in class_names(dex):
            low = d.lower()
            if any(k in low for k in keys):
                seen.setdefault(d, []).append(fn)
    print("== %s：%d 个类 ==" % (title, len(seen)))
    for d in sorted(seen, key=lambda x: (len(x), x))[:limit]:
        print("  %s   [%s]" % (d[1:-1].replace("/", "."), ",".join(sorted(set(seen[d])))))
    return seen


def cmd_str(dexes, kws):
    for kw in kws:
        hits = {}
        for fn, dex in dexes:
            for s in dex.strings():
                if kw in s:
                    hits.setdefault(s, set()).add(fn)
        print("== 字符串含 '%s'：%d 条 ==" % (kw, len(hits)))
        for s in sorted(hits)[:60]:
            print("  %s  [%s]" % (s, ",".join(sorted(hits[s]))))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dex-dir", required=True)
    ap.add_argument("--list-ads", action="store_true")
    ap.add_argument("--list-popup", action="store_true")
    ap.add_argument("--list-promo", action="store_true")
    ap.add_argument("--list-bridges", action="store_true")
    ap.add_argument("--str", nargs="+")
    ap.add_argument("--limit", type=int, default=400)
    a = ap.parse_args()

    dexes = load_all(a.dex_dir)
    if a.list_ads:
        cmd_list(dexes, AD_KEYS, "广告相关类", a.limit)
    if a.list_popup:
        cmd_list(dexes, POPUP_KEYS, "弹窗/浮层类", a.limit)
    if a.list_promo:
        cmd_list(dexes, PROMO_KEYS, "会员/促销/金币类", a.limit)
    if a.list_bridges:
        cmd_list(dexes, BRIDGE_KEYS, "hybrid bridge 模块", a.limit)
    if a.str:
        cmd_str(dexes, a.str)


if __name__ == "__main__":
    main()
