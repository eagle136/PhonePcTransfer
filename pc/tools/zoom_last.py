#!/usr/bin/env python3
"""Exact ink positions for the last glyph of the path text (x=146..160) and
the settings button right edge (x=198..210), per row."""
from scan_clip import load_png

w, h, ch, px = load_png('clip_report.png')

def lum(x, y):
    i = (y * w + x) * ch
    return (px[i] + px[i+1] + px[i+2]) / 3

def show(title, x0, x1, y0, y1):
    print(f"== {title} ==")
    print("x: " + ''.join(str(x % 10) for x in range(x0, x1 + 1)))
    for y in range(y0, y1 + 1):
        row = []
        for x in range(x0, x1 + 1):
            v = lum(x, y)
            if v >= 252:
                row.append(' ')
            elif v >= 240:
                row.append('.')
            elif v >= 220:
                row.append(':')
            elif v >= 180:
                row.append('-')
            elif v >= 140:
                row.append('=')
            elif v >= 100:
                row.append('+')
            else:
                row.append('#')
        print(f"y={y:3d} " + ''.join(row))
    print()

show("last glyph (载?) x=146..160", 146, 160, 63, 78)
show("settings btn right edge x=196..210", 196, 210, 78, 110)
show("panel right edge scan x=202..220 (divider?)", 202, 220, 0, 112)
