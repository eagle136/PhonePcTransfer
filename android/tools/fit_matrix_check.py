#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fit_matrix_check.py —— 校验 Android 侧 videoFitMatrix() 的「等比 + 留边 + 居中」。

为什么需要它：`android.graphics.Matrix` 的 post* 是 **前乘**（M' = A·M，即新变换作用在
“已经变换过的结果”上，在 view 空间里生效），顺序写错不会崩、只会静默拉伸/偏移，
而真机上肉眼判断「有没有被压扁」很容易误判。这里用纯数学复刻同一套语义，把三条
不变量量出来：

  1. **等比**：映射后内容框的 宽/高 比值 == 缓冲区 宽/高 比值（旋转 90/270 时为倒数）；
  2. **留边（contain）**：四个角全部落在 View 内，且至少一个方向刚好贴边；
  3. **居中**：内容框中心 == View 中心（旋转/镜像后都不许跑偏）。

对照的旧实现（只 postRotate + 曾报 bug）也一并算，输出用于回归说明。

用法：python fit_matrix_check.py
"""

import math

# ---------------- Android Matrix 语义（3x3，列向量 p' = M·p） ----------------


def ident():
    return [[1.0, 0, 0], [0, 1.0, 0], [0, 0, 1.0]]


def mul(a, b):
    """a·b（先作用 b，再作用 a）"""
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def post(m, t):
    """android 的 postConcat：M' = t·m"""
    return mul(t, m)


def t_scale(sx, sy, px=0.0, py=0.0):
    return [[sx, 0, px - px * sx], [0, sy, py - py * sy], [0, 0, 1.0]]


def t_rotate(deg, px=0.0, py=0.0):
    r = math.radians(deg)
    c, s = math.cos(r), math.sin(r)
    # y 轴向下 → 正角 = 顺时针
    rot = [[c, -s, 0], [s, c, 0], [0, 0, 1.0]]
    return mul(mul([[1, 0, px], [0, 1, py], [0, 0, 1]], rot), [[1, 0, -px], [0, 1, -py], [0, 0, 1]])


def t_translate(dx, dy):
    return [[1, 0, dx], [0, 1, dy], [0, 0, 1.0]]


def apply(m, x, y):
    return (m[0][0] * x + m[0][1] * y + m[0][2], m[1][0] * x + m[1][1] * y + m[1][2])


# ---------------- 被测实现 ----------------

# TextureView 的默认映射：缓冲区被**各向异性拉伸铺满**整个 View
def stretch(vw, vh, bw, bh):
    return ident(), (vw / bw, vh / bh)  # 只是记号，实际在下面直接用


def fit_matrix(vw, vh, bw, bh, rot, mirror=False):
    """与 ChatScreen.kt::videoFitMatrix 一一对应"""
    m = ident()
    if vw <= 0 or vh <= 0 or bw <= 0 or bh <= 0:
        return m
    r = ((rot % 360) + 360) % 360
    swap = r in (90, 270)
    m = post(m, t_scale(bw / vw, bh / vh))            # ① 抵消拉伸
    cw = (bh if swap else bw)
    ch = (bw if swap else bh)
    k = min(vw / cw, vh / ch)                          # ② contain
    m = post(m, t_scale(k, k, bw / 2.0, bh / 2.0))
    m = post(m, t_rotate(r, bw / 2.0, bh / 2.0))       # ③
    m = post(m, t_translate((vw - bw) / 2.0, (vh - bh) / 2.0))  # ④
    if mirror:
        m = post(m, t_scale(-1, 1, vw / 2.0, vh / 2.0))  # ⑤
    return m


def old_matrix(vw, vh, bw, bh, rot, mirror=False):
    """旧实现：只绕 View 中心旋转（+镜像），不做等比"""
    m = ident()
    if vw <= 0 or vh <= 0:
        return m
    m = post(m, t_rotate(rot, vw / 2.0, vh / 2.0))
    if mirror:
        m = post(m, t_scale(-1, 1, vw / 2.0, vh / 2.0))
    return m


# ---------------- 检查 ----------------

def measure(vw, vh, bw, bh, m):
    """把缓冲区四角走一遍 TextureView 的完整链路：p_view = M · Stretch · p_buf"""
    sx, sy = vw / bw, vh / bh
    corners_buf = [(0, 0), (bw, 0), (bw, bh), (0, bh)]
    pts = [apply(m, x * sx, y * sy) for (x, y) in corners_buf]
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    w = max(xs) - min(xs)
    h = max(ys) - min(ys)
    return pts, w, h


def check_degenerate(name, vw, vh, rot):
    """缓冲区尺寸未定（video_cfg 未到）：必须回落成**单位矩阵**（禁用变换），不许瞎猜比例"""
    m = fit_matrix(vw, vh, 0, 0, rot)
    is_ident = all(abs(m[i][j] - (1.0 if i == j else 0.0)) < 1e-9 for i in range(3) for j in range(3))
    print(f"\n[新实现] {name}  view={vw}x{vh} buf=0x0 rot={rot}")
    print(f"  单位矩阵 {'OK（不加变换，等 cfg 到了再算）' if is_ident else 'FAIL 参数不全仍改了画面'}")
    return is_ident


def check(name, vw, vh, bw, bh, rot, mirror=False, old=False):
    m = old_matrix(vw, vh, bw, bh, rot, mirror) if old else fit_matrix(vw, vh, bw, bh, rot, mirror)
    pts, w, h = measure(vw, vh, bw, bh, m)
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    cx, cy = (max(xs) + min(xs)) / 2.0, (max(ys) + min(ys)) / 2.0
    swap = rot % 180 == 90
    want_aspect = (bh / bw) if swap else (bw / bh)      # 旋转后的期望宽高比
    got_aspect = w / h if h else float("inf")
    in_bounds = all(-0.5 <= x <= vw + 0.5 and -0.5 <= y <= vh + 0.5 for (x, y) in pts)
    flush = min(vw - w, vh - h)                          # 贴边量（contain 时应 ≈0）
    centered = abs(cx - vw / 2.0) < 0.01 and abs(cy - vh / 2.0) < 0.01
    aspect_ok = abs(got_aspect - want_aspect) / want_aspect < 0.01

    tag = "旧实现" if old else "新实现"
    print(f"\n[{tag}] {name}  view={vw}x{vh} buf={bw}x{bh} rot={rot} mirror={mirror}")
    print(f"  内容框 {w:.1f}x{h:.1f}  期望宽高比 {want_aspect:.4f} 实际 {got_aspect:.4f}  "
          f"{'OK' if aspect_ok else 'FAIL 被压扁'}")
    print(f"  四角 {[(round(x, 1), round(y, 1)) for (x, y) in pts]}")
    print(f"  在 View 内 {'OK' if in_bounds else 'FAIL 越界裁切'}   贴边余量 {flush:.1f}px "
          f"{'OK' if abs(flush) < 0.5 else 'FAIL 未贴边/超出'}")
    print(f"  中心 ({cx:.1f},{cy:.1f}) vs View 中心 ({vw / 2.0:.1f},{vh / 2.0:.1f}) "
          f"{'OK' if centered else 'FAIL 跑偏'}")
    return aspect_ok and in_bounds and centered and abs(flush) < 0.5


if __name__ == "__main__":
    # 真实尺寸：a16 1080x2400 全屏远端区 / 本地小窗 104x150dp@2.75 ≈ 286x412
    cases = [
        ("远端主区(竖持)", 1080, 2400, 1280, 720, 90, False),
        ("远端主区(横持)", 1080, 2400, 1280, 720, 0, False),
        ("远端主区 180°", 1080, 2400, 1280, 720, 180, False),
        ("本地小窗(前摄)", 286, 412, 1280, 720, 90, True),
        ("本地小窗(后摄)", 286, 412, 1280, 720, 90, False),
        ("本地小窗 rot=0", 286, 412, 1280, 720, 0, True),
    ]
    ok = True
    for (n, vw, vh, bw, bh, rot, mir) in cases:
        ok &= check(n, vw, vh, bw, bh, rot, mir)
    ok &= check_degenerate("尺寸未定(回落)", 286, 412, 90)
    print("\n--- 对照：旧实现（只旋转）暴露的问题 ---")
    for (n, vw, vh, bw, bh, rot, mir) in [cases[0], cases[3]]:
        check(n, vw, vh, bw, bh, rot, mir, old=True)
    print("\n=== 全部通过 ===" if ok else "\n=== 有 FAIL ===")
