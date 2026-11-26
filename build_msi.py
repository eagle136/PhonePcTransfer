#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PhonePcTransfer PC MSI 打包（jpackage + WiX，经 Git Bash 调用 tools/make-msi.sh）。

版本号统一来自项目根目录的 var 文件：对外版本（文件名/应用列表外的交付口径）取自 var，
MSI 内部 ProductVersion 仍由 make-msi.sh 按构建时刻递增（Windows Installer 要求）。

用法（在项目根目录、普通用户权限执行；不要在"以管理员身份运行"的窗口里跑）：
    py build_msi.py              先编译 jar，再打 MSI
    py build_msi.py --skip-jar   直接用现有的 pc/dist/PhonePcTransfer-PC.jar 打 MSI

产物：
    out/手机电脑互传-<版本号>.msi
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "out"
LOG_FILE = OUT / "build-msi.log"
MSI_SH = ROOT / "tools" / "make-msi.sh"
DIST_JAR = ROOT / "pc" / "dist" / "PhonePcTransfer-PC.jar"


def log(msg: str = "") -> None:
    print(msg, flush=True)


def fail(msg: str) -> None:
    log("[ERROR] " + msg)
    raise SystemExit(1)


def read_version() -> tuple[str, int]:
    """从 var 读取版本号，返回 (versionName, versionCode)。"""
    var = ROOT / "var"
    if not var.is_file():
        fail(f"找不到版本文件: {var}（请新建内容为 VERSION=x.y.z 的 var 文件）")

    values: dict[str, str] = {}
    for raw in var.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        line = raw.strip()
        if not line or line[0] in "#;":
            continue
        if "=" in line:
            key, val = line.split("=", 1)
            values[key.strip().upper()] = val.strip().strip("\"'")
        else:
            values.setdefault("VERSION", line.strip().strip("\"'"))

    version = values.get("VERSION", "")
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
    if not m:
        fail(f"var 里的版本号必须形如 主.次.补，当前读到: '{version}'")
    major, minor, patch = (int(g) for g in m.groups())
    explicit = values.get("VERSION_CODE", "")
    if explicit:
        if not explicit.isdigit():
            fail(f"var 里的 VERSION_CODE 必须是整数，当前: '{explicit}'")
        code = int(explicit)
    else:
        code = major * 10000 + minor * 100 + patch
    return version, code


def is_admin() -> bool:
    try:
        import ctypes
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:  # noqa: BLE001
        return False


def find_package_jdk() -> Path | None:
    """找一份**能出包**的 JDK（jpackage + 21.0.7 以上）。

    为什么不能直接用 tools/jdk21：仓库内置那份是 21.0.2，非 100% 显示缩放下 JDialog /
    JFileChooser 客户区整片空白（make-msi.sh 会直接拒绝），所以这里按「本机装了哪个新
    版本」来挑，挑不到才回落到内置版（由 make-msi.sh 报错说明原因）。
    顺序：PP_JDK_HOME > 用户级 Adoptium（%LOCALAPPDATA%\\Programs\\Eclipse Adoptium）
    > 系统级 Adoptium（Program Files）> 内置 tools/jdk21。
    """
    cands: list[Path] = []
    if os.environ.get("PP_JDK_HOME"):
        cands.append(Path(os.environ["PP_JDK_HOME"]))
    for base in (
        Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Eclipse Adoptium",
        Path(r"C:\Program Files\Eclipse Adoptium"),
        Path(r"C:\Program Files\Java"),
        ROOT / "pc" / "tools" / "jdk21",
    ):
        if base.is_dir():
            cands.extend(sorted((d for d in base.iterdir() if d.is_dir()), reverse=True))

    best: Path | None = None
    best_key = -1
    for c in cands:
        if not (c / "bin" / "jpackage.exe").is_file():
            continue
        rel = c / "release"
        ver = ""
        if rel.is_file():
            for line in rel.read_text(encoding="utf-8", errors="replace").splitlines():
                if line.startswith("JAVA_VERSION="):
                    ver = line.split("=", 1)[1].strip().strip('"').strip()
                    break
        m = re.match(r"(\d+)\.(\d+)\.(\d+)", ver)
        if not m:
            continue
        major, minor, patch = (int(x) for x in m.groups())
        # 21 系低于 21.0.7 有空白弹窗缺陷；其它主版本（25 等）直接可用。
        if major == 21 and patch < 7:
            continue
        key = major * 1000000 + minor * 1000 + patch
        if key > best_key:
            best, best_key = c, key
    return best


def find_git_bash() -> tuple[Path, Path] | None:
    """返回 (bash.exe, Git 安装根)。必须在 Git 的 usr\\bin 前置进 PATH。"""
    standard = Path(r"C:\Program Files\Git\bin\bash.exe")
    if standard.is_file():
        return standard, standard.parent.parent
    git = shutil.which("git")
    if git:
        gitroot = Path(git).resolve().parent.parent
        bash = gitroot / "bin" / "bash.exe"
        if bash.is_file():
            return bash, gitroot
    return None


def run_stream(cmd: list, env: dict) -> int:
    """执行命令，stdout/stderr 合并实时打印，同时写进 out\\build-msi.log。"""
    log("> " + " ".join(str(x) for x in cmd))
    with LOG_FILE.open("a", encoding="utf-8", newline="\n") as logf:
        proc = subprocess.Popen(
            [str(x) for x in cmd],
            cwd=str(ROOT),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        assert proc.stdout is not None
        for line in proc.stdout:
            text = line.rstrip("\r\n")
            log(text)
            logf.write(text + "\n")
            logf.flush()
        return proc.wait()


def main() -> int:
    try:
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    except Exception:
        pass

    version, _code = read_version()
    log(f"[i] 版本号来自 var: {version}")

    if is_admin() and os.environ.get("PP_ALLOW_ADMIN") != "1":
        fail(
            "当前窗口是管理员权限 —— Git Bash / jpackage 在 UAC 提权下路径解析会失败。\n"
            "        请换成普通用户窗口再跑（或设 PP_ALLOW_ADMIN=1 强制继续）"
        )

    if not MSI_SH.is_file():
        fail(f"找不到 MSI 打包内核: {MSI_SH}")

    gitpair = find_git_bash()
    if gitpair is None:
        fail("没找到 Git Bash：安装 Git for Windows（需 bin\\bash.exe），或手动执行 bash tools/make-msi.sh")
    bash, gitroot = gitpair
    log(f"[i] Git Bash: {bash}")

    skip_jar = any(a.lower() in ("--skip-jar", "skip-jar") for a in sys.argv[1:])
    if skip_jar:
        if not DIST_JAR.is_file():
            fail(f"--skip-jar 但 {DIST_JAR} 不存在，先跑 py build_jar.py")
        log("[i] 跳过 jar 编译，直接用现有 pc\\dist\\PhonePcTransfer-PC.jar")
    else:
        log("[1/2] 编译 PC jar ...")
        rc = subprocess.run([sys.executable, str(ROOT / "build_jar.py")], cwd=str(ROOT)).returncode
        if rc != 0:
            fail("jar 编译失败，MSI 构建中止")

    if not DIST_JAR.is_file():
        fail(f"{DIST_JAR} 不存在")

    env = os.environ.copy()
    env["APP_VER"] = version
    env["PATH"] = str(gitroot / "usr" / "bin") + os.pathsep + env.get("PATH", "")

    # 出包用的 JDK：本机装了 Adoptium 21.0.7+ / JDK 25 就用它（仓库内置那份 21.0.2
    # 会让 JDialog 客户区整片空白，make-msi.sh 会直接拒绝出包）。
    jdk = find_package_jdk()
    if jdk is not None:
        env["PP_JDK_HOME"] = str(jdk)
        log(f"[i] 出包 JDK: {jdk}")
    else:
        log("[!] 没找到 21.0.7+ 的 JDK（jpackage），将回落 pc\\tools\\jdk21\\jdk-21.0.2")

    OUT.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with LOG_FILE.open("w", encoding="utf-8", newline="\n") as logf:
        logf.write(f"[{stamp}] build_msi.py start (APP_VER={version})\n")

    log("[2/2] jpackage + WiX -> MSI（日志同步写 out\\build-msi.log）...")
    rc = run_stream([bash, str(MSI_SH).replace("\\", "/")], env)
    if rc != 0:
        log("----- 日志末尾 -----")
        try:
            tail = LOG_FILE.read_text(encoding="utf-8", errors="replace").splitlines()[-30:]
            log("\n".join(tail))
        except OSError:
            pass
        fail(f"MSI 构建失败（退出码 {rc}）")

    msi = OUT / f"手机电脑互传-{version}.msi"
    if not msi.is_file():
        fail(f"构建结束但没找到 MSI: {msi}")

    inner = ""
    try:
        for line in LOG_FILE.read_text(encoding="utf-8", errors="replace").splitlines():
            if "内部 ProductVersion" in line:
                inner = line.split("内部 ProductVersion", 1)[1].strip().lstrip(":").strip()
    except OSError:
        pass

    log()
    log(f"[OK] MSI {version} -> {msi}  ({msi.stat().st_size} 字节)")
    if inner:
        log(f"     MSI 内部 ProductVersion: {inner}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
