# -*- coding: utf-8 -*-
"""逐文件比对两棵目录树（名字 + 字节数），用来独立验证「传文件夹有没有漏/坏」。

用法：python verify_tree.py <源目录> <目标目录> [--md5]

只读：不改动任何文件。退出码 0=完全一致，1=存在差异。
"""
import hashlib
import os
import sys


def scan(root, md5=False):
    out = {}
    for dp, dn, fn in os.walk(root):
        for f in fn:
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, root).replace(os.sep, '/')
            if md5:
                h = hashlib.md5()
                with open(p, 'rb') as fh:
                    for blk in iter(lambda: fh.read(1 << 20), b''):
                        h.update(blk)
                out[rel] = (os.path.getsize(p), h.hexdigest())
            else:
                out[rel] = (os.path.getsize(p), '')
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    src_root, dst_root = sys.argv[1], sys.argv[2]
    md5 = '--md5' in sys.argv

    src, dst = scan(src_root, md5), scan(dst_root, md5)
    sb = sum(v[0] for v in src.values())
    db = sum(v[0] for v in dst.values())
    print("源端   : %s" % src_root)
    print("目标端 : %s" % dst_root)
    print("源端   : %d 个 / %d 字节" % (len(src), sb))
    print("目标端 : %d 个 / %d 字节" % (len(dst), db))

    miss = sorted(set(src) - set(dst))
    extra = sorted(set(dst) - set(src))
    diff = sorted(k for k in set(src) & set(dst) if src[k][0] != dst[k][0])
    hashdiff = sorted(k for k in set(src) & set(dst)
                      if md5 and src[k][1] != dst[k][1])

    print("缺失   : %d %s" % (len(miss), miss[:5]))
    print("多出   : %d %s" % (len(extra), extra[:5]))
    print("大小不符: %d" % len(diff))
    for k in diff[:10]:
        print("    ≠ %s: 源 %d → 目标 %d" % (k, src[k][0], dst[k][0]))
    if md5:
        print("MD5 不符: %d" % len(hashdiff))
        for k in hashdiff[:10]:
            print("    ≠ %s" % k)
    zeros = [k for k, v in dst.items() if v[0] == 0]
    print("0 字节  : %d %s" % (len(zeros), zeros[:5]))

    ok = not miss and not extra and not diff and not hashdiff
    print()
    print("结论: %s" % ("✅ %d/%d 全部一致（无漏件、无截断）" % (len(src), len(src))
                       if ok else "⚠️ 存在差异"))
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
