#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
dexq.py — 轻量 dex 查询工具（不依赖 jadx/baksmali，秒级出结果）。

用法:
  # 1) 谁的返回类型是某个类（找「工厂方法」最有用）
  python3 tools/dexq.py --dex-dir .buildtmp/dex676 --returns Lcom/dragon/read/ad/AddShortcutLine;

  # 2) 某个类的所有方法签名
  python3 tools/dexq.py --dex-dir .buildtmp/dex676 --class f32.q

  # 3) 字符串池里搜关键词（找 URL / 广告位 / 文案）
  python3 tools/dexq.py --dex-dir .buildtmp/dex676 --str warningtone

  # 4) 谁引用了某个类（调用者定位）
  python3 tools/dexq.py --dex-dir .buildtmp/dex676 --refs Lcom/dragon/read/ad/ButtonLine;

  # 5) 类名子串匹配
  python3 tools/dexq.py --dex-dir .buildtmp/dex676 --find-class addshortcut
"""
import argparse
import glob
import os
import struct
import sys


class Dex:
    def __init__(self, path):
        self.path = path
        self.buf = open(path, "rb").read()
        b = self.buf
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from("<12I", b, 56)
        self._strings = {}

    # ---- 基础表 ----
    def string(self, idx):
        s = self._strings.get(idx)
        if s is not None:
            return s
        off = struct.unpack_from("<I", self.buf, self.string_ids_off + idx * 4)[0]
        p = off
        # uleb128 字符数
        while self.buf[p] & 0x80:
            p += 1
        p += 1
        end = self.buf.index(b"\x00", p)
        s = self.buf[p:end].decode("utf-8", "replace")
        self._strings[idx] = s
        return s

    def type(self, idx):
        return self.string(struct.unpack_from("<I", self.buf, self.type_ids_off + idx * 4)[0])

    def proto(self, idx):
        _shorty, ret, params_off = struct.unpack_from("<III", self.buf, self.proto_ids_off + idx * 12)
        return self.type(ret), self.type_list(params_off)

    def type_list(self, off):
        if off == 0:
            return []
        n = struct.unpack_from("<I", self.buf, off)[0]
        return [self.type(struct.unpack_from("<H", self.buf, off + 4 + 2 * i)[0]) for i in range(n)]

    def method(self, idx):
        cls, proto_idx, name_idx = struct.unpack_from("<HHI", self.buf, self.method_ids_off + idx * 8)
        ret, params = self.proto(proto_idx)
        return self.type(cls), self.string(name_idx), ret, params

    def field(self, idx):
        cls, type_idx, name_idx = struct.unpack_from("<HHI", self.buf, self.field_ids_off + idx * 8)
        return self.type(cls), self.string(name_idx), self.type(type_idx)

    # ---- class_data ----
    def class_data(self, class_def_idx):
        """返回 (class_desc, [(method_idx, access, code_off, kind)])，kind: direct/virtual"""
        base = self.class_defs_off + class_def_idx * 32
        cls_idx, _acc, _sup, _ifaces, _src, _ann, data_off, _sv = struct.unpack_from("<8I", self.buf, base)
        desc = self.type(cls_idx)
        methods = []
        if data_off == 0:
            return desc, methods
        p = data_off

        def uleb():
            nonlocal p
            r = s = 0
            while True:
                byte = self.buf[p]
                p += 1
                r |= (byte & 0x7F) << s
                if not byte & 0x80:
                    return r
                s += 7

        sf = uleb(); inf = uleb(); dm = uleb(); vm = uleb()
        for _ in range(sf):
            uleb(); uleb()
        for _ in range(inf):
            uleb(); uleb()
        for kind, cnt in (("direct", dm), ("virtual", vm)):
            midx = 0
            for _ in range(cnt):
                midx += uleb()
                acc = uleb()
                code = uleb()
                methods.append((midx, acc, code, kind))
        return desc, methods

    def classes(self):
        for i in range(self.class_defs_size):
            yield i


def load(dexdir):
    return [Dex(f) for f in sorted(glob.glob(os.path.join(dexdir, "*.dex")))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dex-dir", required=True)
    ap.add_argument("--returns")
    ap.add_argument("--class", dest="cls")
    ap.add_argument("--refs")
    ap.add_argument("--str")
    ap.add_argument("--find-class")
    a = ap.parse_args()
    dexes = load(a.dex_dir)

    if a.str:
        kw = a.str
        seen = set()
        for d in dexes:
            for i in range(d.string_ids_size):
                s = d.string(i)
                if kw in s and s not in seen:
                    seen.add(s)
                    print("%-14s %s" % (os.path.basename(d.path), s))
        return

    if a.find_class:
        kw = a.find_class.lower()
        for d in dexes:
            for i in d.classes():
                desc, _ = d.class_data(i)
                if kw in desc.lower():
                    print("%-14s %s" % (os.path.basename(d.path), desc))
        return

    if a.cls:
        want = "L" + a.cls.replace(".", "/") + ";"
        for d in dexes:
            for i in d.classes():
                desc, methods = d.class_data(i)
                if desc != want:
                    continue
                print("== %s in %s (%d methods)" % (desc, os.path.basename(d.path), len(methods)))
                for midx, acc, code, kind in methods:
                    c, name, ret, params = d.method(midx)
                    print("  [%s]%s %s(%s)%s  code=%d" % (
                        kind, " static" if acc & 0x8 else "", name, ", ".join(params), ret, code))
        return

    if a.returns:
        want = a.returns
        for d in dexes:
            for i in d.classes():
                desc, methods = d.class_data(i)
                for midx, acc, code, kind in methods:
                    c, name, ret, params = d.method(midx)
                    if ret == want:
                        print("%-14s %s->%s(%s)%s" % (
                            os.path.basename(d.path), c, name, ", ".join(params), ret))
        return

    if a.refs:
        want = a.refs
        # 粗筛：整个 dex 里出现过该描述符，再逐个类看它的 method/field 表
        for d in dexes:
            if want.encode() not in d.buf:
                continue
            for i in d.classes():
                desc, methods = d.class_data(i)
                for midx, acc, code, kind in methods:
                    c, name, ret, params = d.method(midx)
                    if want in params or ret == want:
                        print("%-14s %s->%s(%s)%s" % (
                            os.path.basename(d.path), c, name, ", ".join(params), ret))
        return

    ap.print_help()


if __name__ == "__main__":
    main()
