#!/usr/bin/env python3
"""Verify app_panel_125.png (real app code rendered @1.25x).
Real geometry: info 199x48; dirLabel x=74..126 y=4..18;
btnRow x=10..193 y=18..42; themeBtn x=38..96; settingsBtn x=124..163.
Device @1.25: themeBtn x=47.5..120, settingsBtn x=155..203.75, y=22.5..51.25."""
from scan_clip import load_png

w, h, ch, px = load_png('pc/app_panel_125.png')
print(f"size {w}x{h}")

def lum(x, y):
    i = (y * w + x) * ch
    return (px[i] + px[i+1] + px[i+2]) / 3

def grid(x0, x1, y0, y1, label):
    print(f"== {label} (x={x0}..{x1}, y={y0}..{y1}) ==")
    for y in range(y0, y1+1):
        row = []
        for x in range(x0, x1+1):
            v = lum(x, y)
            row.append(' ' if v >= 252 else '.' if v >= 244 else ':' if v >= 235 else '-' if v >= 220 else '=' if v >= 200 else '+' if v >= 170 else '#')
        print(f"y={y:3d} {''.join(row)}")
    print()

grid(46, 51, 21, 53, "themeBtn LEFT border")
grid(116, 122, 21, 53, "themeBtn RIGHT border")
grid(153, 159, 21, 53, "settingsBtn LEFT border")
grid(199, 206, 21, 53, "settingsBtn RIGHT border")
grid(92, 160, 6, 20, "dirLabel text region (x=74..126, y=4..18 logical)")

# text completeness: last glyph ink must end before x=193*1.25=241 (inner right edge)
print("== text row max-x per row ==")
for y in range(6, 22):
    xs = [x for x in range(w) if lum(x, y) < 250]
    if xs:
        print(f"y={y:3d} x={min(xs)}..{max(xs)}")
