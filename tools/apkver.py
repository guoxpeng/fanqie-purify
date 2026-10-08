#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读取 APK 二进制 AndroidManifest.xml 的 versionName / versionCode，并可按期望值断言。

为什么不用 aapt/aapt2：CI 上只有 android.jar、没有 build-tools；而且本项目的发布包是
「换 dex」方式组装出来的，直接解析二进制 XML 最省依赖、也最确定。

用法：
    python3 tools/apkver.py <apk>
    python3 tools/apkver.py <apk> --expect-name 1.9.23 --expect-code 192300

退出码：0 通过；1 解析失败或与期望值不符。
"""
import struct
import sys
import zipfile


def uleb(buf, p):
    r = s = 0
    while True:
        b = buf[p]
        p += 1
        r |= (b & 0x7F) << s
        if not (b & 0x80):
            return r, p
        s += 7


def parse_axml(buf):
    """解析二进制 XML，返回 (字符串池, [(属性名索引, 数据类型, data), ...])。"""
    if struct.unpack_from("<I", buf, 0)[0] != 0x00080003:
        raise ValueError("不是二进制 AndroidManifest.xml")
    strings, attrs = [], []
    off = 8
    while off + 8 <= len(buf):
        ctype, _hsize, csize = struct.unpack_from("<HHI", buf, off)
        if csize == 0:
            break
        if ctype == 0x0001:                       # string pool
            cnt, _scnt, flags, str_start = struct.unpack_from("<IIII", buf, off + 8)
            offs = [struct.unpack_from("<I", buf, off + 28 + 4 * i)[0] for i in range(cnt)]
            strings = []
            for o in offs:
                p = off + str_start + o
                if flags & (1 << 8):              # UTF-8 池
                    _, p = uleb(buf, p)
                    n, p = uleb(buf, p)
                    strings.append(buf[p:p + n].decode("utf-8", "replace"))
                else:                             # UTF-16 池
                    # 注意：本仓库模板的池用 **u16 长度前缀**，不是标准 uleb128。
                    # 按 uleb128 读会整体错开一字节，属性名全变乱码，按名字永远找不到。
                    n16 = struct.unpack_from("<H", buf, p)[0]
                    q = p + 2 + n16 * 2
                    if q + 2 <= len(buf) and buf[q] == 0 and buf[q + 1] == 0:
                        strings.append(buf[p + 2:q].decode("utf-16-le", "replace"))
                    else:
                        n, q = uleb(buf, p)
                        strings.append(buf[q:q + n * 2].decode("utf-16-le", "replace"))
        elif ctype == 0x0102:                     # start element
            _ns, _nm, a_start, a_size, a_cnt = struct.unpack_from("<IIHHH", buf, off + 16)
            base = off + 16 + a_start
            for i in range(a_cnt):
                a = base + i * a_size
                _ans, a_name, _raw, _sz, _r0, dtype, data = struct.unpack_from("<IIIHBBI", buf, a)
                attrs.append((a_name, dtype, data))
        off += csize
    return strings, attrs


def apk_version(path):
    with zipfile.ZipFile(path) as z:
        buf = z.read("AndroidManifest.xml")
    strings, attrs = parse_axml(buf)
    pkg = vname = vcode = None
    for a_name, dtype, data in attrs:
        name = strings[a_name] if 0 <= a_name < len(strings) else ""
        if name == "versionName" and dtype == 0x03 and 0 <= data < len(strings):
            vname = strings[data]
        elif name == "versionCode" and dtype == 0x10:
            vcode = data
        elif name == "package" and dtype == 0x03 and 0 <= data < len(strings):
            pkg = strings[data]
    return pkg, vname, vcode


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 1
    path = argv[1]
    exp_name = exp_code = None
    i = 2
    while i < len(argv):
        if argv[i] == "--expect-name" and i + 1 < len(argv):
            exp_name = argv[i + 1]
            i += 2
        elif argv[i] == "--expect-code" and i + 1 < len(argv):
            exp_code = int(argv[i + 1])
            i += 2
        else:
            print("未知参数: %s" % argv[i], file=sys.stderr)
            return 1

    pkg, vname, vcode = apk_version(path)
    print("%s" % path)
    print("  package=%s  versionName=%s  versionCode=%s" % (pkg, vname, vcode))

    bad = []
    if exp_name is not None and vname != exp_name:
        bad.append("versionName 期望 %r，实际 %r" % (exp_name, vname))
    if exp_code is not None and vcode != exp_code:
        bad.append("versionCode 期望 %r，实际 %r" % (exp_code, vcode))
    for b in bad:
        print("::error::" + b)
        print("ERROR: " + b, file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
