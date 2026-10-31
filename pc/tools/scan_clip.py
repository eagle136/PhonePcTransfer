#!/usr/bin/env python3
"""Scan clip_report.png: find vertical divider, button border extents, text ink extents.
Pure-stdlib PNG decode (no PIL)."""
import zlib, struct, sys

def load_png(path):
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos = 8
    idat = b''
    w = h = bitdepth = colortype = None
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos+4])[0]
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, bitdepth, colortype = struct.unpack('>IIBB', chunk[:10])
        elif typ == b'IDAT':
            idat += chunk
        elif typ == b'IEND':
            break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = {0:1, 2:3, 3:1, 4:2, 6:4}[colortype]
    assert bitdepth == 8, f"bitdepth {bitdepth}"
    stride = w * ch
    out = bytearray(w * h * ch)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if ft == 1:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + a) & 255
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif ft == 3:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif ft == 4:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                b = prev[i]
                c = prev[i-ch] if i >= ch else 0
                p_ = a + b - c
                pa, pb, pc = abs(p_-a), abs(p_-b), abs(p_-c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, ch, out

w, h, ch, px = load_png(sys.argv[1] if len(sys.argv) > 1 else 'clip_report.png')
print(f"size {w}x{h} ch={ch}")

def rgb(x, y):
    i = (y * w + x) * ch
    return px[i], px[i+1], px[i+2]

# --- 1. find dark/ink pixels per column (non-white) ---
def ink(x, y):
    r, g, b = rgb(x, y)
    return (r + g + b) / 3 < 235

# Row profile: for each row, count ink pixels
print("\n== row ink profile (row: count, x-range) ==")
for y in range(h):
    xs = [x for x in range(w) if ink(x, y)]
    if xs:
        print(f"y={y:3d} n={len(xs):3d} x={min(xs)}..{max(xs)}")

# --- 2. column profile of 'gray-ish border' pixels: detect the vertical divider near right edge ---
print("\n== columns with many non-white pixels (candidate divider/edges) ==")
for x in range(w):
    n = sum(1 for y in range(h) if ink(x, y))
    if n > 20:
        print(f"x={x:3d} n={n}")

# --- 3. sample specific rows: button border rows (find horizontal border lines in lower half) ---
print("\n== lower-half horizontal line segments (button top/bottom borders) ==")
for y in range(h // 2, h):
    xs = [x for x in range(w) if ink(x, y)]
    if len(xs) > 30:
        # find runs
        runs = []
        start = xs[0]
        prev = xs[0]
        for x in xs[1:]:
            if x - prev > 2:
                runs.append((start, prev))
                start = x
            prev = x
        runs.append((start, prev))
        long_runs = [r for r in runs if r[1] - r[0] > 20]
        if long_runs:
            print(f"y={y:3d} runs={long_runs}")
