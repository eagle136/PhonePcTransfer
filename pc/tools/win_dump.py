"""诊断用：列出所有可见顶层窗口的 类名/标题/矩形，以及屏幕尺寸、置顶状态。

用法：python win_dump.py
"""

import ctypes
import ctypes.wintypes as wt

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception:
    pass

u = ctypes.windll.user32
GWL_EXSTYLE = -20
WS_EX_TOPMOST = 0x00000008

print("screen =", u.GetSystemMetrics(0), "x", u.GetSystemMetrics(1), "(physical)")

CB = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)


def _cb(hwnd, _):
    if not u.IsWindowVisible(hwnd):
        return True
    cls = ctypes.create_unicode_buffer(256)
    u.GetClassNameW(hwnd, cls, 256)
    title = ctypes.create_unicode_buffer(512)
    u.GetWindowTextW(hwnd, title, 512)
    r = wt.RECT()
    u.GetWindowRect(hwnd, ctypes.byref(r))
    pid = ctypes.c_ulong()
    u.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    ex = u.GetWindowLongW(hwnd, GWL_EXSTYLE)
    top = "TOPMOST" if (ex & WS_EX_TOPMOST) else ""
    print("pid=%-6d %-28s %5d,%-5d %4dx%-5d %s | %s"
          % (pid.value, cls.value, r.left, r.top, r.right - r.left, r.bottom - r.top,
             top, title.value[:40]))
    return True


u.EnumWindows(CB(_cb), 0)
