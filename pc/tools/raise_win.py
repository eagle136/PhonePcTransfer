"""把 PC 端主窗口抬到最前面（供抓屏用）。

用法：python raise_win.py [标题关键字] [restore]

为什么不能只调 SetForegroundWindow：从后台进程调它，Windows 的前台锁只会让任务栏
图标闪一下。本机上 WorkBuddy 客户端是全屏窗口（2578x1398 @ -9,-9），普通窗口一律被
它压住 —— 光"置前"抓不到，必须显式置顶（HWND_TOPMOST），抓完再 restore 还原。

输出：`RECT x y w h` 与置顶是否成功的诊断行。
"""

import ctypes
import ctypes.wintypes as wt
import sys

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception:
    pass

u = ctypes.windll.user32
keyword = sys.argv[1] if len(sys.argv) > 1 else "互传"
action = sys.argv[2] if len(sys.argv) > 2 else "topmost"

HWND_TOPMOST, HWND_NOTOPMOST = -1, -2
SWP_NOMOVE, SWP_NOSIZE = 0x0002, 0x0001
GWL_EXSTYLE, WS_EX_TOPMOST = -20, 0x00000008
SW_RESTORE = 9

hit = []
CB = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)


def _cb(hwnd, _):
    if not u.IsWindowVisible(hwnd):
        return True
    cls = ctypes.create_unicode_buffer(256)
    u.GetClassNameW(hwnd, cls, 256)
    title = ctypes.create_unicode_buffer(512)
    u.GetWindowTextW(hwnd, title, 512)
    if cls.value == "SunAwtFrame" and keyword in title.value:
        hit.append(hwnd)
    return True


u.EnumWindows(CB(_cb), 0)
if not hit:
    print("NOT_FOUND")
    sys.exit(1)

hwnd = hit[0]
r = wt.RECT()
u.GetWindowRect(hwnd, ctypes.byref(r))
pid = ctypes.c_ulong()
u.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
print("PID", pid.value)
print("RECT", r.left, r.top, r.right - r.left, r.bottom - r.top)

if action == "topmost":
    u.ShowWindow(hwnd, SW_RESTORE)
    u.BringWindowToTop(hwnd)
    u.SetForegroundWindow(hwnd)
    ctypes.set_last_error(0)
    ok = u.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE)
    print("SetWindowPos(topmost) ->", ok, "| GetLastError =", ctypes.get_last_error())
    ex = u.GetWindowLongW(hwnd, GWL_EXSTYLE)
    print("WS_EX_TOPMOST now =", bool(ex & WS_EX_TOPMOST))
elif action == "restore":
    ctypes.set_last_error(0)
    ok = u.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE)
    print("SetWindowPos(notopmost) ->", ok, "| GetLastError =", ctypes.get_last_error())
    ex = u.GetWindowLongW(hwnd, GWL_EXSTYLE)
    print("WS_EX_TOPMOST now =", bool(ex & WS_EX_TOPMOST))
