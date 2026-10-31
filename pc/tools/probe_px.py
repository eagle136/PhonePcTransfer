#!/usr/bin/env python3
"""Print exact pixel values in selected regions of clip_report.png."""
from scan_clip import load_png

w, h, ch, px = load_png('clip_report.png')

def pxv(x, y):
    i = (y * w + x) * ch
    return (px[i], px[i+1], px[i+2], px[i+3])

# B2 left edge region: x 152..162, y 78..110
print("== B2 left-edge region (x=152..162) ==")
for y in range(78, 111):
    row = []
    for x in range(152, 163):
        r, g, b, a = pxv(x, y)
        m = (r + g + b) // 3
        row.append(f"{m:3d}")
    print(f"y={y:3d} " + ' '.join(row))

# B2 right edge region: x 196..206, y 78..110
print("\n== B2 right-edge region (x=196..206) ==")
for y in range(78, 110):
    row = []
    for x in range(196, 207):
        r, g, b, a = pxv(x, y)
        m = (r + g + b) // 3
        row.append(f"{m:3d}")
    print(f"y={y:3d} " + ' '.join(row))

# B1 left edge for reference: x 48..56
print("\n== B1 left-edge region (x=48..56) ==")
for y in range(78, 110):
    row = []
    for x in range(48, 57):
        r, g, b, a = pxv(x, y)
        m = (r + g + b) // 3
        row.append(f"{m:3d}")
    print(f"y={y:3d} " + ' '.join(row))

# panel right edge / divider: x 200..214, full height sample
print("\n== right edge / divider (x=200..214) ==")
for y in range(0, h, 4):
    row = []
    for x in range(200, 215):
        r, g, b, a = pxv(x, y)
        m = (r + g + b) // 3
        row.append(f"{m:3d}")
    print(f"y={y:3d} " + ' '.join(row))

# text row: x 88..165, y 64..78
print("\n== path text right end (x=150..165) ==")
for y in range(63, 80):
    row = []
    for x in range(150, 166):
        r, g, b, a = pxv(x, y)
        m = (r + g + b) // 3
        row.append(f"{m:3d}")
    print(f"y={y:3d} " + ' '.join(row))
