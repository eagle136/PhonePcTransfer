#!/usr/bin/env python3
"""Targeted value grids for the 1.25x probe render.
Logical button boxes: B1 x=38..96, B2 x=124..163, y=18..41 (from probe dump).
Device @1.25: B1 x=47.5..120, B2 x=155..203.75, y=22.5..51.25."""
from scan_clip import load_png

w, h, ch, px = load_png('pc/probe_info_125.png')
print(f"size {w}x{h}")

def lum(x, y):
    i = (y * w + x) * ch
    return (px[i] + px[i+1] + px[i+2]) / 3

def grid(x0, x1, y0, y1, label):
    print(f"== {label} (x={x0}..{x1}, y={y0}..{y1}) ==")
    hdr = "     " + "".join(f"{x%10:1d}" for x in range(x0, x1+1))
    print(hdr)
    for y in range(y0, y1+1):
        row = []
        for x in range(x0, x1+1):
            v = lum(x, y)
            row.append(' ' if v >= 252 else '.' if v >= 244 else ':' if v >= 235 else '-' if v >= 220 else '=' if v >= 200 else '+' if v >= 170 else '#')
        print(f"y={y:3d} {''.join(row)}")
    print()

grid(46, 51, 21, 53, "B1 LEFT border (ring x=47.5..49.3)")
grid(116, 122, 21, 53, "B1 RIGHT border (ring x=118.2..120)")
grid(153, 159, 21, 53, "B2 LEFT border (ring x=155..156.8)")
grid(199, 206, 21, 53, "B2 RIGHT border (ring x=201.95..203.75)")
grid(46, 122, 21, 26, "B1 TOP (ring y=22.5..24.3)")
grid(46, 122, 49, 54, "B1 BOTTOM (ring y=49.45..51.25)")
