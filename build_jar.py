#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PhonePcTransfer PC jar 打包：javac 编译 + 自包含可运行 jar。

版本号统一来自项目根目录的 var 文件（改完 var 直接跑本脚本，不再传版本参数）。

用法（在项目根目录执行）：
    py build_jar.py

产物：
    pc/dist/PhonePcTransfer-PC.jar   run.bat 直接启动的那份（覆盖旧文件）
    out/PhonePcTransfer-PC.jar       交付副本（jar 清单里带 Implementation-Version）
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PC = ROOT / "pc"
OUT = ROOT / "out"
BUILD = PC / "build"
CLASSES = BUILD / "classes"
SOURCES_TXT = BUILD / "sources.txt"
MANIFEST = BUILD / "MANIFEST.MF"
CONCENTUS = PC / "lib" / "concentus-1.0.2.jar"
DIST_JAR = PC / "dist" / "PhonePcTransfer-PC.jar"
OUT_JAR = OUT / "PhonePcTransfer-PC.jar"
MAIN_CLASS = "com.phonepctransfer.pc.PcMain"


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
    if minor > 99 or patch > 99:
        fail("次版本号/补丁号不能超过 99（Android versionCode 按 主*10000+次*100+补丁 编码）")

    explicit = values.get("VERSION_CODE", "")
    if explicit:
        if not explicit.isdigit():
            fail(f"var 里的 VERSION_CODE 必须是整数，当前: '{explicit}'")
        code = int(explicit)
    else:
        code = major * 10000 + minor * 100 + patch
    return version, code


def find_jdk() -> Path | None:
    """按 JAVA_HOME > Android Studio JBR > Adoptium/Java 目录 的顺序找 JDK 17+。"""
    candidates: list[Path] = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    candidates.append(Path(r"C:\Program Files\Android\Android Studio\jbr"))
    for base in (
        r"C:\Program Files\Eclipse Adoptium",
        r"C:\Program Files\Java",
        r"C:\Program Files\Microsoft",
    ):
        b = Path(base)
        if b.is_dir():
            candidates.extend(sorted((d for d in b.iterdir() if "jdk" in d.name.lower()), reverse=True))
    for c in candidates:
        if (c / "bin" / "javac.exe").is_file() and (c / "bin" / "jar.exe").is_file():
            return c
    return None


def run(cmd: list, cwd: Path) -> None:
    log("> " + " ".join(str(x) for x in cmd))
    proc = subprocess.Popen(
        [str(x) for x in cmd],
        cwd=str(cwd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
    )
    assert proc.stdout is not None
    for line in proc.stdout:
        log(line.rstrip("\r\n"))
    rc = proc.wait()
    if rc != 0:
        fail(f"命令失败（退出码 {rc}）")


def main() -> int:
    try:
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    except Exception:
        pass

    version, code = read_version()
    log(f"[i] 版本号来自 var: {version}（Android versionCode={code}）")

    jdk = find_jdk()
    if jdk is None:
        fail("找不到 JDK 17+：设置 JAVA_HOME，或安装 Android Studio（自带 jbr）")
    log(f"[i] JDK: {jdk}")

    if not CONCENTUS.is_file():
        fail(f"缺少 {CONCENTUS}（语音通话依赖）")
    src = PC / "src"
    if not src.is_dir():
        fail(f"找不到源码目录 {src}")

    sources = sorted(src.rglob("*.java"))
    if not sources:
        fail("pc/src 下没有任何 .java 源文件")

    if CLASSES.exists():
        shutil.rmtree(CLASSES)
    BUILD.mkdir(parents=True, exist_ok=True)
    CLASSES.mkdir()
    SOURCES_TXT.write_text("\n".join(str(s) for s in sources) + "\n", encoding="utf-8")

    log(f"[1/2] 编译 {len(sources)} 个 Java 源文件 ...")
    run(
        [
            jdk / "bin" / "javac.exe",
            "-J-Duser.language=en",
            "-J-Duser.country=US",
            "-encoding", "UTF-8",
            "--release", "17",
            "-Xlint:none",
            "-cp", CONCENTUS,
            "-d", CLASSES,
            "@" + str(SOURCES_TXT),
        ],
        PC,
    )

    log("[2/2] 打包自包含 jar（内置 concentus 的 io 包）...")
    # 运行时版本号：写进 jar 资源（从 classes 目录直跑时也能读到；见 Version.current()）
    (CLASSES / "version.properties").write_text(f"version={version}\n", encoding="utf-8")
    with zipfile.ZipFile(CONCENTUS) as zf:
        entries = [n for n in zf.namelist() if n.startswith("io/")]
        if not entries:
            fail(f"{CONCENTUS} 里没有 io/ 包")
        zf.extractall(CLASSES, members=entries)

    MANIFEST.write_bytes(
        (
            "Manifest-Version: 1.0\r\n"
            "Implementation-Title: PhonePcTransfer\r\n"
            f"Implementation-Version: {version}\r\n"
            f"Main-Class: {MAIN_CLASS}\r\n"
            "\r\n"
        ).encode("utf-8")
    )

    OUT.mkdir(parents=True, exist_ok=True)
    if OUT_JAR.exists():
        OUT_JAR.unlink()
    run(
        [jdk / "bin" / "jar.exe", "--create", "--file", OUT_JAR,
         "--manifest", MANIFEST, "-C", CLASSES, "."],
        PC,
    )

    (PC / "dist").mkdir(parents=True, exist_ok=True)
    try:
        shutil.copyfile(OUT_JAR, DIST_JAR)
    except PermissionError:
        fail(
            "pc\\dist\\PhonePcTransfer-PC.jar 被占用（电脑端程序还在运行？右键托盘退出后再跑）\n"
            f"        新 jar 已生成: {OUT_JAR}"
        )

    log()
    log(f"[OK] jar {version} -> {DIST_JAR}  ({DIST_JAR.stat().st_size} 字节)")
    log(f"     交付副本 -> {OUT_JAR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
