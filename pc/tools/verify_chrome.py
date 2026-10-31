# -*- coding: utf-8 -*-
"""
T11 像素验收（2026-09-28）：chrome 四窗标题栏 / 四角圆角 + 滚动条颜色 / 宽度。

输入（shots/ 下，先跑探针生成）：
  chrome_dark_{settings,incoming,voice,video}.png   ChromeProbe --dark
  chrome_light_{settings,incoming,voice,video}.png  ChromeProbe（无 --dark）
  ui_long_dark.png / ui_long_light.png             UiWinProbe --long

判据（TASKLIST §3-V2；2026-09-28 按离屏实测真值修订）：
  1) 标题栏区 y∈[0,32) 众数色：深 #313338 / 浅 #F0F1F4，且该色占比 >85%
     （旧"亮像素占比<0.5%"作废：深色标题栏文字 #E8EAED 本身就是亮的，占比必然 >0.5%）
  2) 四角：radius-10 圆角 → 角对角 (2,2) 在弧外 alpha==0、内侧 (9,9) 在弧内 alpha>0
     （全不透明 = 环境不支持逐像素透明，按 T1-4b 跳过）
  3) 聊天区滚动条（深图）：绿滑块定位 → 轨黑 / 滑块绿 / 上下 14px 箭头区白占比 >3%
     且无非黑白灰杂色；轨道上下缘用顶栏/底栏两条水平分割线锚定
     （避免顶栏「清空」按钮、底栏「发送」按钮的白字干扰）
  4) 滚动条宽度（浅图，滑块色列跨度量）：聊天区 12 / 设备列表 10
     （滑块 = 轨宽 - 4，左右各 2px 内缩；深图轨与背景同纯黑、浅图轨 #F2F3F5
      与背景 #F4F5F7 只差 2，都无法按轨道色量宽 → 用滑块色锚定）

运行：py pc/tools/verify_chrome.py   （仓库根目录）
"""
import sys
from collections import Counter

from PIL import Image

DARK_CHROME = (0x31, 0x33, 0x38)
LIGHT_CHROME = (0xF0, 0xF1, 0xF4)
BLACK = (0x00, 0x00, 0x00)
WHITE = (0xFF, 0xFF, 0xFF)
THUMBS_DARK = ((0x43, 0xA0, 0x47), (0x4C, 0xAF, 0x50))
THUMB_LIGHT = (0xB4, 0xBA, 0xC2)
DIVIDER_DARK = (0x2A, 0x2C, 0x30)

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append(ok)
    print(("PASS  " if ok else "FAIL  ") + name + ("  " + detail if detail else ""))


def load(path):
    return Image.open(path).convert("RGBA")


def near(c, target, tol=24):
    return all(abs(a - b) <= tol for a, b in zip(c[:3], target))


def is_thumb(c):
    return any(near(c, t) for t in THUMBS_DARK)


def neutral(c):
    """白三角在黑轨道上的抗锯齿边缘 = 纯灰（r≈g≈b），不算杂色"""
    return max(c[:3]) - min(c[:3]) <= 16


def title_bar(path, expect, label):
    img = load(path)
    w, h = img.size
    px = img.load()
    region = [px[x, y][:3] for y in range(min(32, h)) for x in range(w)]
    m = Counter(region).most_common(1)[0][0]
    match_frac = sum(1 for p in region if near(p, expect, 24)) / len(region)
    ok = m == expect and match_frac > 0.85
    check(label + " 标题栏", ok,
          "mode=#%02X%02X%02X expect=#%02X%02X%02X match_frac=%.3f"
          % (m[0], m[1], m[2], expect[0], expect[1], expect[2], match_frac))


def corners(path, label):
    img = load(path)
    w, h = img.size
    px = img.load()

    def frac(x0, y0, dx, dy):
        n = sum(1 for i in range(10) for j in range(10)
                if px[x0 + i * dx, y0 + j * dy][3] == 0)
        return n / 100.0

    fr = [frac(0, 0, 1, 1), frac(w - 1, 0, -1, 1),
          frac(0, h - 1, 1, -1), frac(w - 1, h - 1, -1, -1)]
    all_opaque = all(f < 0.05 for f in fr)
    if all_opaque:
        check(label + " 四角圆角 alpha", True,
              "fracs=%s（全不透明=环境无逐像素透明，按 T1-4b 跳过）"
              % [round(f, 2) for f in fr])
        return
    # radius-10 圆角：角对角 (2,2) 在弧外 → 透明；内侧 (9,9) 在弧内 → 不透明
    outside = [px[2, 2][3] == 0, px[w - 3, 2][3] == 0,
               px[2, h - 3][3] == 0, px[w - 3, h - 3][3] == 0]
    inside = [px[9, 9][3] > 0, px[w - 10, 9][3] > 0,
              px[9, h - 10][3] > 0, px[w - 10, h - 10][3] > 0]
    ok = all(outside) and all(inside)
    check(label + " 四角圆角 alpha", ok,
          "outside=%s inside=%s fracs=%s" % (outside, inside, [round(f, 2) for f in fr]))

def chat_scrollbar_dark(path, label):
    """深图：绿滑块定位聊天区滚动条列 → 轨黑 / 滑块绿 / 箭头白"""
    img = load(path)
    w, h = img.size
    px = img.load()
    xs = [x for y in range(100, h - 100) for x in range(w - 60, w) if is_thumb(px[x, y][:3])]
    if not xs:
        check(label + " 聊天区滚动条", False, "未找到绿滑块像素")
        return
    x1, x2 = min(xs), max(xs)
    xm = (x1 + x2) // 2
    # 列 xm 上的水平分割线（顶栏底线 y≈57 / 顶栏+滚动区 y≈102 / 底栏顶线 y≈664）。
    # 滚动区上下缘 = **最后两组**：倒数第二组末行+1 = 轨顶，最后一组首行-1 = 轨底
    #（第一组是 42px 标题栏的底线，不能用——曾误把它当轨顶，箭头检测全落在顶栏黑底上）
    divs = [y for y in range(50, h - 50) if near(px[xm, y][:3], DIVIDER_DARK, 16)]
    clusters = []
    for y in divs:
        if clusters and y - clusters[-1][-1] <= 2:
            clusters[-1].append(y)
        else:
            clusters.append([y])
    if len(clusters) < 3:
        check(label + " 聊天区滚动条", False, "分割线不足（divs=%s）" % (divs[:8],))
        return
    track_top = clusters[-2][-1] + 1
    track_bot = clusters[-1][0] - 1
    # 滑块众数色
    thumb = Counter(px[x, y][:3] for y in range(track_top, track_bot + 1)
                    for x in range(x1, x2 + 1) if is_thumb(px[x, y][:3]))
    tm = thumb.most_common(1)[0][0]
    thumb_ok = is_thumb(tm)
    # 轨道 = 列内非滑块像素的众数色
    track = Counter(px[x, y][:3] for y in range(track_top, track_bot + 1)
                    for x in range(x1, x2 + 1) if not is_thumb(px[x, y][:3]))
    tr = track.most_common(1)[0][0]
    # 箭头：轨道顶部 / 底部各 14px 区
    arrow_ok, detail = True, []
    for y0 in (track_top, track_bot - 14 + 1):
        n_white = n_other = total = 0
        for y in range(y0, y0 + 14):
            for x in range(x1, x2 + 1):
                c = px[x, y][:3]
                total += 1
                if near(c, WHITE):
                    n_white += 1
                elif not near(c, BLACK, 48) and not neutral(c):
                    n_other += 1
        frac = n_white / total
        arrow_ok = arrow_ok and frac > 0.03 and n_other / total < 0.15
        detail.append("arrow_white=%.2f other=%.2f" % (frac, n_other / total))
    ok = thumb_ok and near(tr, BLACK) and arrow_ok
    check(label + " 聊天区滚动条", ok,
          "col=[%d,%d] track=[%d,%d] track=#%02X%02X%02X thumb=#%02X%02X%02X %s"
          % (x1, x2, track_top, track_bot, tr[0], tr[1], tr[2],
             tm[0], tm[1], tm[2], " ".join(detail)))


def thumb_col_width(px, w, h, x_lo, x_hi, thumb, label, expect):
    """找滑块色列最长连续段 → 轨道宽 = 段宽 + 4（左右各 2px 内缩）。
    阈值 15：滑块两端圆角列像素少（短滑块端列仅 ~25px），阈值高了会丢端列量错宽"""
    cols = []
    for x in range(x_lo, min(x_hi, w)):
        n = sum(1 for y in range(60, h - 60) if near(px[x, y][:3], thumb, 16))
        if n > 15:
            cols.append(x)
    if not cols:
        check(label, False, "未找到滑块色列")
        return
    # 取最长连续列段（防杂散像素拉宽跨度）
    best = run = [cols[0]]
    for x in cols[1:]:
        if x == run[-1] + 1:
            run.append(x)
        else:
            if len(run) > len(best):
                best = run
            run = [x]
    if len(run) > len(best):
        best = run
    x1, x2 = best[0], best[-1]
    width = x2 - x1 + 1 + 4
    check(label, abs(width - expect) <= 1,
          "col=[%d,%d] thumb_span=%d track=%d expect=%d"
          % (x1, x2, x2 - x1 + 1, width, expect))


def scrollbar_widths_light(path, label):
    img = load(path)
    w, h = img.size
    px = img.load()
    # 聊天区：右缘内 40px 内找滑块列（右缘外 16px 阴影 + 12px 条）
    thumb_col_width(px, w, h, w - 40, w - 16, THUMB_LIGHT, label + " 聊天区条宽", 12)
    # 设备列表：左栏右缘附近（16 阴影 + 200 栏宽 → 滑块列约在 x≈207..212）
    thumb_col_width(px, w, h, 100, 300, THUMB_LIGHT, label + " 设备列表条宽", 10)


def main():
    for theme, expect in (("dark", DARK_CHROME), ("light", LIGHT_CHROME)):
        for name in ("settings", "incoming", "voice", "video"):
            path = "shots/chrome_%s_%s.png" % (theme, name)
            title_bar(path, expect, "chrome %s/%s" % (theme, name))
            corners(path, "chrome %s/%s" % (theme, name))
    chat_scrollbar_dark("shots/ui_long_dark.png", "ui_long_dark")
    scrollbar_widths_light("shots/ui_long_light.png", "ui_long_light")

    failed = RESULTS.count(False)
    print()
    print("TOTAL %d checks, %d failed" % (len(RESULTS), failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()