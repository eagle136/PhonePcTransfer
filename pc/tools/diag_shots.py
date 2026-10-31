# -*- coding: utf-8 -*-
# 诊断：dump 探针 PNG 的关键区域像素
import sys
from collections import Counter
from PIL import Image

def mode_row(img, y):
    w, h = img.size
    px = img.load()
    return Counter(px[x, y][:3] for x in range(w)).most_common(3)

def show(path, rows):
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    print("== %s  size=%dx%d" % (path, w, h))
    for y in rows:
        if 0 <= y < h:
            top = mode_row(img, y)
            print("  y=%3d " % y + "  ".join("#%02X%02X%02X(n=%d)" % (c[0], c[1], c[2], n) for c, n in top))

def col_scan(path, x_lo, x_hi, y0, y1):
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    px = img.load()
    print("== %s  cols %d..%d (y %d..%d)" % (path, x_lo, x_hi, y0, y1))
    for x in range(x_lo, x_hi):
        top = Counter(px[x, y][:3] for y in range(y0, y1)).most_common(2)
        print("  x=%4d " % x + "  ".join("#%02X%02X%02X(n=%d)" % (c[0], c[1], c[2], n) for c, n in top))

if len(sys.argv) > 1 and sys.argv[1] == "chrome":
    show("shots/chrome_dark_settings.png", [0, 5, 10, 16, 20, 30, 32, 35, 40, 50])
    show("shots/chrome_light_settings.png", [0, 5, 10, 16, 20, 30, 32, 35, 40, 50])
elif sys.argv[1] == "ui":
    show("shots/ui_long_dark.png", [40, 100, 200, 400, 600, 690, 700, 710, 720])
    col_scan("shots/ui_long_light.png", 900, 951, 100, 600)
    col_scan("shots/ui_long_light.png", 195, 230, 100, 600)