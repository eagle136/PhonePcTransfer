#!/usr/bin/env python3
"""Border continuity check for probe renders at any scale.
Usage: python verify_borders2.py <png> <scale>
Auto-detects button boxes: rows with long horizontal ink runs = top/bottom borders;
then checks vertical continuity of the two side borders per button."""
import sys
from scan_clip import load_png

path, scale = sys.argv[1], float(sys.argv[2])
w, h, ch, px = load_png(path)
print(f"size {w}x{h} scale {scale}")

def lum(x, y):
    i = (y * w + x) * ch
    return (px[i] + px[i+1] + px[i+2]) / 3

def runs_in_row(y, thr=235):
    runs, start = [], None
    for x in range(w):
        dark = lum(x, y) <= thr
        if dark and start is None:
            start = x
        elif not dark and start is not None:
            if x - start >= 20:
                runs.append((start, x - 1))
            start = None
    if start is not None and w - start >= 20:
        runs.append((start, w - 1))
    return runs

# find button top/bottom border rows (long horizontal runs in lower half)
border_rows = []
for y in range(h // 2, h):
    r = runs_in_row(y)
    if r:
        border_rows.append((y, r))
print("border rows:", border_rows[:4], "...", border_rows[-2:] if len(border_rows) > 4 else "")

if not border_rows:
    sys.exit("no button borders found")

top_y = border_rows[0][0]
bot_y = border_rows[-1][0]
# button boxes from the top border row's runs
boxes = border_rows[0][1]
print(f"top_y={top_y} bot_y={bot_y} boxes={boxes}")

ok = True
for bi, (x0, x1) in enumerate(boxes):
    for side, xs in (("left", range(x0, x0 + 4)), ("right", range(x1 - 3, x1 + 1))):
        missing = []
        for y in range(top_y, bot_y + 1):
            if all(lum(x, y) > 235 for x in xs):
                missing.append(y)
        status = "OK" if not missing else f"GAP at y={missing}"
        if missing:
            ok = False
        print(f"button {bi+1} {side} border x={xs}: {status}")

# also print per-row ink count in the side-border columns to see uniformity
for bi, (x0, x1) in enumerate(boxes):
    print(f"-- button {bi+1} left column x={x0}..{x0+3} values (y={top_y}..{bot_y}) --")
    for y in range(top_y, bot_y + 1):
        vals = [int(lum(x, y)) for x in range(x0, x0 + 4)]
        print(f"y={y:3d} {vals}")
print("RESULT:", "PASS" if ok else "FAIL")
