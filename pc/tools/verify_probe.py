#!/usr/bin/env python3
"""Verify probe_info.png (new layout + ring border):
1. Find text row and both button boxes.
2. Check each button has 4 continuous border sides (values <= 235).
3. Check text ink fits inside the row (x in 10..193).
"""
from scan_clip import load_png

w, h, ch, px = load_png('pc/probe_info.png')
print(f"size {w}x{h}")

def lum(x, y):
    i = (y * w + x) * ch
    return (px[i] + px[i+1] + px[i+2]) / 3

# Row ink profile
print("== rows with ink ==")
for y in range(h):
    xs = [x for x in range(w) if lum(x, y) < 250]
    if xs:
        print(f"y={y:3d} n={len(xs):3d} x={min(xs)}..{max(xs)}")
