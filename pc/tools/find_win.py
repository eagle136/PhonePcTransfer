"""找 PC 端主窗口（Swing 的 SunAwtFrame）并打印其屏幕矩形。

用法：
    python find_win.py [标题关键字] [topmost|restore]

`topmost` 把窗口临时抬到最顶层（HWND_TOPMOST）—— 只调 SetForegroundWindow 是**不够**的：
从后台进程调用它，Windows 只会让任务栏图标闪一下，窗口仍被别的窗口（如 IDE）压着，
抓出来的图就是别的程序。抓完记得 `restore` 还原成非置顶。
输出：`PID left top width height title`

为什么不用 PowerShell：本机沙箱禁止 Add-Type（编译/加载 .NET 代码），
拿不到 user32 的 GetWindowRect。ctypes 调用 windll 不需要编译，可以直接用。

注意 DPI：本机 125% 缩放，进程若不声明 DPI 感知，系统会把坐标"缩放后"返回
（数值比物理像素小 1.25 倍），抓出来的图就会偏。所以先调 SetProcessDpiAwareness(1)。
"""

import ctypes
import ctypes.wintypes as wt
import sys

try:
    ctypes.windll.shcore.SetProcessDpiAwareness(1)  # 1 = SYSTEM_DPI_AWARE
except Exception:
    try:
        ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass

u = ctypes.windll.user32
keyword = sys.argv[1] if len(sys.argv) > 1 else "互传"
action = sys.argv[2] if len(sys.argv) > 2 else ""
HWND_TOPMOST = -1
HWND_NOTOPMOST = -2
SWP_NOMOVE, SWP_NOSIZE = 0x0002, 0x0001

found = []
CB = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)


def _cb(hwnd, _):
    if not u.IsWindowVisible(hwnd):
        return True
    cls = ctypes.create_unicode_buffer(256)
    u.GetClassNameW(hwnd, cls, 256)
    title = ctypes.create_unicode_buffer(512)
    u.GetWindowTextW(hwnd, title, 512)
    if cls.value == "SunAwtFrame" and keyword in title.value:
        r = wt.RECT()
        u.GetWindowRect(hwnd, ctypes.byref(r))
        pid = ctypes.c_ulong()
        u.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        found.append((pid.value, r.left, r.top, r.right - r.left, r.bottom - r.top,
                      title.value, hwnd))
    return True


u.EnumWindows(CB(_cb), 0)
if not found:
    print("NOT_FOUND")
    sys.exit(1)
for pid, x, y, w, h, title, hwnd in found:
    if action == "topmost":
        u.SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE)
    elif action == "restore":
        u.SetWindowPos(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE)
    else:
        u.SetForegroundWindow(hwnd)
    print(pid, x, y, w, h, title)

