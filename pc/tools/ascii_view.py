#!/usr/bin/env python3
"""Render PNG as ASCII art to see exact layout. Usage: ascii_view.py <png> [scale]"""
import sys
from scan_clip import load_png

w, h, ch, px = load_png(sys.argv[1])
scale = int(sys.argv[2]) if len(sys.argv) > 2 else 2

def lum(x, y):
    i = (y * w + x) * ch
    return (px[i] + px[i+1] + px[i+2]) / 3

rows = []
for Y in range(0, h, scale):
    line = []
    for X in range(0, w, scale):
        # sample a block
        vals = []
        for dy in range(scale):
            for dx in range(scale):
                if X+dx < w and Y+dy < h:
                    vals.append(lum(X+dx, Y+dy))
        m = min(vals)
        if m < 120:
            line.append('#')
        elif m < 180:
            line.append('+')
        elif m < 225:
            line.append('.')
        elif m < 245:
            line.append(',')
        else:
            line.append(' ')
    rows.append(''.join(line))

for y, r in enumerate(rows):
    print(f"{y*scale:3d} {r}")
print("    " + ''.join(str((x*scale) % 10) for x in range(len(rows[0]))))
