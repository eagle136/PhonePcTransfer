#!/usr/bin/env python3
"""Check border continuity: each button's 4 sides must be continuous ink
(values <= 235) from top corner to bottom corner, with >=2px of ink per side."""
from scan_clip import load_png

w, h, ch, px = load_png('pc/probe_info.png')

def lum(x, y):
    i = (y * w + x) * ch
    return (px[i] + px[i+1] + px[i+2]) / 3

def col_profile(x0, x1, y0, y1, label):
    print(f"== {label} (x={x0}..{x1}) ==")
    for y in range(y0, y1 + 1):
        vals = []
        for x in range(x0, x1 + 1):
            v = lum(x, y)
            vals.append(' ' if v >= 252 else '.' if v >= 240 else ':' if v >= 220 else '-' if v >= 180 else '=' if v >= 140 else '+' if v >= 100 else '#')
        print(f"y={y:3d} {''.join(vals)}")
    print()

# buttons: themeBtn x=38..96, settingsBtn x=124..163, y=18..41
col_profile(36, 42, 16, 44, "B1 left border")
col_profile(93, 99, 16, 44, "B1 right border")
col_profile(121, 127, 16, 44, "B2 left border")
col_profile(160, 166, 16, 44, "B2 right border")

# top/bottom border flat segments
def row_profile(y, x0, x1, label):
    vals = []
    for x in range(x0, x1 + 1):
        v = lum(x, y)
        vals.append(' ' if v >= 252 else '.' if v >= 240 else ':' if v >= 220 else '-' if v >= 180 else '=' if v >= 140 else '+' if v >= 100 else '#')
    print(f"{label} y={y}: {''.join(vals)}")

row_profile(18, 36, 99, "B1 top")
row_profile(19, 36, 99, "B1 top+1")
row_profile(41, 36, 99, "B1 bottom")
row_profile(40, 36, 99, "B1 bottom-1")
row_profile(18, 121, 166, "B2 top")
row_profile(41, 121, 166, "B2 bottom")
