#!/usr/bin/env python3
"""High-res ASCII zoom of the path text region (x=90..160, y=62..78)."""
from scan_clip import load_png

w, h, ch, px = load_png('clip_report.png')

def lum(x, y):
    i = (y * w + x) * ch
    return (px[i] + px[i+1] + px[i+2]) / 3

# one char per pixel
chars = ' .:-=+*#%@'
for y in range(62, 79):
    row = []
    for x in range(90, 160):
        v = lum(x, y)
        if v >= 250:
            row.append(' ')
        elif v >= 235:
            row.append('.')
        elif v >= 200:
            row.append(':')
        elif v >= 160:
            row.append('-')
        elif v >= 120:
            row.append('=')
        elif v >= 80:
            row.append('+')
        elif v >= 40:
            row.append('*')
        else:
            row.append('#')
    print(f"y={y:3d} {'' .join(row) if False else ''.join(row)}")
print("x: " + ''.join(str((x % 10)) for x in range(90, 160)))
