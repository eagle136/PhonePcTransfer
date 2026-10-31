# -*- coding: utf-8 -*-
"""Android UI 自动化小助手（adb + uiautomator dump）。

给「触发 App 内部操作」这种没有外部入口的场景用：dump 当前界面 → 按 text /
content-desc / resource-id 找节点 → 算中心坐标 → input tap。

用法（单独执行某一步）：
  python ui_auto.py dump                      打印可点击 / 有文字的节点
  python ui_auto.py tap  <关键字>             按中心点点击第一个匹配节点
  python ui_auto.py tapx <关键字>             同上，但正则匹配

需要的环境：adb 已连上（默认取 $ADB_SERIAL，未设则单设备）。
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("ADB_SERIAL", "")
ADB = ["adb"] + (["-s", SERIAL] if SERIAL else [])


def sh(args, timeout=25):
    try:
        p = subprocess.run(ADB + args, capture_output=True, timeout=timeout)
        return p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return ""


def shell(cmd, timeout=25):
    return sh(["shell", cmd], timeout)


def dump():
    shell("uiautomator dump /sdcard/_wbui.xml >/dev/null 2>&1")
    raw = shell("cat /sdcard/_wbui.xml")
    i = raw.find("<?xml")
    if i < 0:
        raise RuntimeError("dump 失败：" + raw[:200])
    return ET.fromstring(raw[i:])


def nodes(root):
    attrs = ("text", "content-desc", "resource-id", "class", "bounds", "clickable", "enabled")
    out = []
    for n in root.iter("node"):
        a = {k: n.get(k, "") for k in attrs}
        a["sel"] = a["text"] or a["content-desc"] or a["resource-id"]
        if a["sel"] or a["class"].endswith(("Button", "ImageButton")):
            out.append(a)
    return out


def center(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(ns, key, regex=False):
    for a in ns:
        hay = " ".join([a["text"], a["content-desc"], a["resource-id"]])
        hit = re.search(key, hay) if regex else (key in hay)
        if hit:
            return a
    return None


def tap(a):
    c = center(a["bounds"])
    if not c:
        return False, "没有 bounds"
    r = shell("input tap %d %d" % c)
    return True, "tap %s @ %s %s" % (a["sel"][:40], c, r.strip())


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if cmd == "tap":
        ns = nodes(dump())
        for k in sys.argv[2:]:
            a = find(ns, k)
            if not a:
                print("没找到:", k)
                sys.exit(1)
            ok, msg = tap(a)
            print(msg)
            if ok:
                sys.exit(0)
        sys.exit(1)
    if cmd == "tapx":
        ns = nodes(dump())
        for k in sys.argv[2:]:
            a = find(ns, k, True)
            if not a:
                print("没找到:", k)
                sys.exit(1)
            ok, msg = tap(a)
            print(msg)
            if ok:
                sys.exit(0)
        sys.exit(1)
    ns = nodes(dump())
    for a in ns:
        print("%-46s | %-40s | %s" % (a["sel"][:46], a["resource-id"][:40], a["bounds"]))


if __name__ == "__main__":
    main()
