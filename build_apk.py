#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PhonePcTransfer Android APK 打包（Gradle assembleRelease / R8）。

版本号统一来自项目根目录的 var 文件：
    - versionName  = var 里的 VERSION
    - versionCode  = 主*10000 + 次*100 + 补丁（或用 var 里的 VERSION_CODE 覆盖）

用法（在项目根目录执行）：
    py build_apk.py            默认 R8 release
    py build_apk.py no-r8      不打 R8（真机回归用，体积 ~39MB）

产物：
    out/手机电脑互传-<版本号>.apk
    （中间产物先落到项目根目录 temp/，成品移动到 out/，temp/ 整个移入回收站）

离线约定：Gradle 一律带 --offline，只用本机已解压的发行版，不回退 gradlew 下载。
    前提是本机已经成功联网构建过一次，依赖都在 Gradle 缓存里。
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ANDROID = ROOT / "android"
OUT = ROOT / "out"
LOCAL_PROPS = ANDROID / "local.properties"
APK_PATH = ANDROID / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
META_PATH = ANDROID / "app" / "build" / "outputs" / "apk" / "release" / "output-metadata.json"
# 本次打包的临时目录：项目根目录 temp，打包完整个移入回收站
TEMP_DIR = ROOT / "temp"


def log(msg: str = "") -> None:
    print(msg, flush=True)


def fail(msg: str) -> None:
    log("[ERROR] " + msg)
    raise SystemExit(1)


# ---------------------------------------------------------------------------
# temp 目录 / 回收站
# ---------------------------------------------------------------------------
def _recycle_on_windows(path: Path) -> bool:
    """调 Shell 的 SHFileOperationW 把目录删进回收站（可恢复，不是硬删）。"""
    import ctypes
    from ctypes import wintypes

    class SHFILEOPSTRUCTW(ctypes.Structure):
        _fields_ = [
            ("hwnd", wintypes.HWND),
            ("wFunc", wintypes.UINT),
            ("pFrom", wintypes.LPCWSTR),
            ("pTo", wintypes.LPCWSTR),
            ("fFlags", ctypes.c_uint16),
            ("fAnyOperationsAborted", wintypes.BOOL),
            ("hNameMappings", ctypes.c_void_p),
            ("lpszProgressTitle", wintypes.LPCWSTR),
        ]

    FO_DELETE = 3
    FOF_SILENT = 0x0004
    FOF_NOCONFIRMATION = 0x0010
    FOF_ALLOWUNDO = 0x0040
    FOF_NOERRORUI = 0x0400

    op = SHFILEOPSTRUCTW()
    op.hwnd = None
    op.wFunc = FO_DELETE
    # ctypes 把 str 转 wchar_t* 时会自带一个结束符，这里再补一个凑成列表的双 NUL
    op.pFrom = str(path) + "\0"
    op.pTo = None
    op.fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI
    rc = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(op))
    return rc == 0 and not op.fAnyOperationsAborted


def recycle_dir(path: Path) -> None:
    """把目录整体移入回收站；失败只告警，绝不退化成硬删除。"""
    if not path or not path.exists():
        return
    try:
        if os.name == "nt" and _recycle_on_windows(path):
            log(f"[i] temp 已移入回收站：{path}")
            return
        log(f"[WARN] temp 没能移入回收站，请手动处理：{path}")
    except Exception as exc:  # noqa: BLE001
        log(f"[WARN] 移入回收站失败（{exc}），请手动处理：{path}")


def prepare_temp_dir() -> Path:
    """每次打包重建 temp：上一次残留的整目录先移入回收站，不逐文件删除。"""
    if TEMP_DIR.exists():
        recycle_dir(TEMP_DIR)
    TEMP_DIR.mkdir(parents=True, exist_ok=True)
    return TEMP_DIR


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
        fail("次版本号/补丁号不能超过 99（versionCode 按 主*10000+次*100+补丁 编码）")

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
        if (c / "bin" / "javac.exe").is_file() and (c / "bin" / "java.exe").is_file():
            return c
    return None


def jdk_version(home: Path) -> tuple[int, int, int] | None:
    """从 <jdk>/release 里读 JAVA_VERSION，读不到返回 None。"""
    rel = home / "release"
    if not rel.is_file():
        return None
    for line in rel.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("JAVA_VERSION="):
            v = line.split("=", 1)[1].strip().strip('"').strip()
            m = re.match(r"(\d+)(?:\.(\d+))?(?:\.(\d+))?", v)
            if m:
                return (int(m.group(1)), int(m.group(2) or 0), int(m.group(3) or 0))
    return None


def find_toolchain_jdk() -> Path | None:
    """找 AGP 9.x 需要的 JDK 25（toolchain）。

    gradle.properties 里写死了 `org.gradle.java.installations.paths=C:/Program Files/
    Android/Android Studio/jbr`，但本机 Android Studio 装在被镜像到 D: 的位置（
    D:\\c_disk\\...\\jbr），那条路径不存在 → Gradle 就去 foojay 下载 JDK 25，离线必挂。
    这里先在本机把 JDK 25 找出来，再用 -P 覆盖属性（命令行优先级高于 gradle.properties）。
    """
    cands: list[Path] = []
    if os.environ.get("PP_JDK25_HOME"):
        cands.append(Path(os.environ["PP_JDK25_HOME"]))
    for p in (
        r"C:\Program Files\Android\Android Studio\jbr",
        r"D:\c_disk\Program Files\Android\Android Studio\jbr",
        r"D:\Program Files\Android\Android Studio\jbr",
    ):
        cands.append(Path(p))
    for base in (
        Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Eclipse Adoptium",
        Path(r"C:\Program Files\Eclipse Adoptium"),
        Path(r"C:\Program Files\Java"),
        Path(r"D:\jdk"),
    ):
        if base.is_dir():
            cands.extend(sorted((d for d in base.iterdir() if d.is_dir()), reverse=True))
    if os.environ.get("JAVA_HOME"):
        cands.append(Path(os.environ["JAVA_HOME"]))

    best: Path | None = None
    best_key = -1
    for c in cands:
        if not (c / "bin" / "java.exe").is_file():
            continue
        v = jdk_version(c)
        if v is None:
            continue
        key = v[0] * 1000000 + v[1] * 1000 + v[2]
        if key > best_key:
            best, best_key = c, key
    return best


def ensure_sdk_dir() -> None:
    """local.properties 里的 sdk.dir 失效时，按 ANDROID_HOME / ANDROID_SDK_ROOT / 默认路径重写。"""
    lines: list[str] = []
    sdk = ""
    if LOCAL_PROPS.is_file():
        lines = LOCAL_PROPS.read_text(encoding="utf-8-sig", errors="replace").splitlines()
        for line in lines:
            if line.strip().startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].strip().replace("\\\\", "\\")
                break
    if sdk and Path(sdk).is_dir():
        return

    candidates: list[Path] = []
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        v = os.environ.get(key)
        if v:
            candidates.append(Path(v))
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")

    for c in candidates:
        if c.is_dir():
            escaped = str(c).replace("\\", "\\\\")
            out_lines: list[str] = []
            replaced = False
            for line in lines:
                if line.strip().startswith("sdk.dir="):
                    out_lines.append("sdk.dir=" + escaped)
                    replaced = True
                else:
                    out_lines.append(line)
            if not replaced:
                out_lines.append("sdk.dir=" + escaped)
            LOCAL_PROPS.write_text("\n".join(out_lines) + "\n", encoding="utf-8")
            log(f"[FIX] local.properties 的 sdk.dir 无效，已改写为 {c}")
            return

    fail("local.properties 的 sdk.dir 无效，且没找到 Android SDK：设置 ANDROID_HOME 后重试")


def cache_group_count(home: Path) -> int:
    """数一下这份 Gradle 家目录里缓存了多少个依赖 group（粗略衡量缓存完整度）。"""
    mods = home / "caches" / "modules-2" / "files-2.1"
    if not mods.is_dir():
        return 0
    try:
        return sum(1 for d in mods.iterdir() if d.is_dir())
    except OSError:
        return 0


def read_agp_version() -> str | None:
    """从 android/gradle/libs.versions.toml 读出 AGP 版本，读不到返回 None。"""
    toml = ANDROID / "gradle" / "libs.versions.toml"
    if not toml.is_file():
        return None
    for line in toml.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        m = re.match(r"\s*agp\s*=\s*[\"']([^\"']+)[\"']", line)
        if m:
            return m.group(1)
    return None


def cache_has_agp_jar(home: Path, agp: str | None) -> bool:
    """这份家目录里是否有**AGP 的 jar**（只有 pom/module 元数据不算，那种会逼 Gradle 联网）。"""
    base = home / "caches" / "modules-2" / "files-2.1" / "com.android.tools.build" / "gradle"
    if not base.is_dir():
        return False
    dirs = [base / agp] if agp else [d for d in base.iterdir() if d.is_dir()]
    for d in dirs:
        if d.is_dir() and any(d.rglob("*.jar")):
            return True
    return False


def pick_gradle_home() -> Path | None:
    """挑一份本地已解压好的 Gradle 缓存家目录，避免离线环境下再去联网下载。

    优先级：环境变量 GRADLE_USER_HOME > 含本项目 AGP jar 的那份 > 依赖 group 最多的那份。

    2026-11-23：以前只看 wrapper/dists 里有没有 gradle.bat，于是新装的
    %USERPROFILE%\\.gradle 一出现就被选中——那份家目录里 AGP 9.5.1 只有 pom/module、
    没有 jar，Gradle 只能去 dl.google.com 下载，而本机直连该域名会**无限挂起**
    （表现为「构建卡住、CPU 0%」）。现在优先选真正含 AGP jar 的家目录。
    只有 wrapper/dists 下真的存在 gradle-*/bin/gradle.bat 才算可用。
    """
    agp = read_agp_version()

    env_home = os.environ.get("GRADLE_USER_HOME")
    if env_home:
        h = Path(env_home)
        dists = h / "wrapper" / "dists"
        if dists.is_dir() and any(dists.glob("*/*/gradle-*/bin/gradle.bat")):
            return h  # 用户显式指定，就听他的

    candidates: list[Path] = [
        Path.home() / ".gradle",
        Path(r"D:\cache\gradle-home"),
        Path(r"C:\cache\gradle-home"),
    ]

    usable: list[Path] = []
    for cand in candidates:
        dists = cand / "wrapper" / "dists"
        if dists.is_dir() and any(dists.glob("*/*/gradle-*/bin/gradle.bat")):
            usable.append(cand)
    if not usable:
        return None

    # 关键排序：先看有没有 AGP 的 jar（缺 jar 就会联网 → 本机挂死），再看依赖缓存全不全
    def score(h: Path) -> tuple[int, int]:
        return (1 if cache_has_agp_jar(h, agp) else 0, cache_group_count(h))

    return max(usable, key=score)


def find_gradle_launcher(gradle_home: Path | None) -> Path | None:
    """找一份**已解压**的 gradle.bat，绕开 gradlew 的在线下载。

    gradlew.bat 会按 wrapper 属性里的 distributionUrl 校验/下载发行包；缓存目录的哈希
    与当前属性（尤其是后来加的 distributionSha256Sum）对不上时，它就会重新下 135MB，
    而本机走的是被代理的 TLS，Java 侧 truststore 不认 → PKIX 直接中断构建。既然
    wrapper/dists 里已经有一份完整解压的 9.9.1，就直接用它。
    """
    home = gradle_home or Path.home() / ".gradle"
    dists = home / "wrapper" / "dists"
    if not dists.is_dir():
        return None
    found = sorted(dists.glob("*/*/gradle-*/bin/gradle.bat"))
    for f in reversed(found):
        if (f.parent.parent / "lib").is_dir():
            return f
    return found[-1] if found else None


def run(cmd: list, cwd: Path, env: dict) -> None:
    log("> " + " ".join(str(x) for x in cmd))
    proc = subprocess.Popen(
        [str(x) for x in cmd],
        cwd=str(cwd),
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
        log(line.rstrip("\r\n"))
    rc = proc.wait()
    if rc != 0:
        fail(
            f"Gradle 构建失败（退出码 {rc}）\n"
            "        若 Kotlin 报 Unresolved reference（queuePending / flushPending），"
            "是 release 增量缓存过期：\n"
            "        删掉 android\\app\\build\\kotlin\\compileReleaseKotlin 和 "
            "android\\app\\build\\intermediates\\built_in_kotlinc\\release 后重试"
        )


def main() -> int:
    try:
        sys.stdout.reconfigure(errors="replace")
        sys.stderr.reconfigure(errors="replace")
    except Exception:
        pass

    version, code = read_version()
    log(f"[i] 版本号来自 var: {version}（versionCode={code}）")

    if not (ANDROID / "gradlew.bat").is_file():
        fail(f"找不到 {ANDROID / 'gradlew.bat'}")

    ensure_sdk_dir()

    jdk = find_jdk()
    if jdk is None:
        fail("找不到 JDK 17+：设置 JAVA_HOME，或安装 Android Studio（自带 jbr）")
    log(f"[i] JDK: {jdk}")

    # AGP 9 的 toolchain 要 JDK 25：本机能找到就交给 Gradle（并覆盖 gradle.properties 里
    # 那条指向不存在目录的 installations.paths），找不到就保持原样由 foojay 去下载。
    tc = find_toolchain_jdk()
    if tc is not None and jdk_version(tc) is not None:
        log(f"[i] Toolchain JDK: {tc}（{'.'.join(str(x) for x in jdk_version(tc))}）")
        jdk = tc
    else:
        tc = None
        log("[WARN] 没找到本机 JDK 25，Gradle 会尝试从 foojay 下载")

    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")

    if not env.get("GRADLE_USER_HOME"):
        gradle_home = pick_gradle_home()
        if gradle_home is not None and gradle_home != Path.home() / ".gradle":
            env["GRADLE_USER_HOME"] = str(gradle_home)
            log(f"[i] Gradle 缓存: {gradle_home}")

    # 已有解压好的 gradle.bat 就直接用它，别让 gradlew 再去校验/下载发行包
    launcher = find_gradle_launcher(Path(env["GRADLE_USER_HOME"]) if env.get("GRADLE_USER_HOME") else None)
    if launcher is not None:
        log(f"[i] Gradle: {launcher}（跳过 wrapper 下载）")
    else:
        fail(
            "本地没有已解压的 Gradle 发行包，离线打包无法进行（不会回退 gradlew 联网下载）。\n"
            "        请先联网正常构建一次，或把 gradle-<版本>-bin 解压到 "
            "%USERPROFILE%\\.gradle\\wrapper\\dists"
        )

    extra: list[str] = []
    if any(a.lower() in ("no-r8", "--no-r8") for a in sys.argv[1:]):
        extra.append("-Pr8=false")
        log("[i] 已关闭 R8（真机回归包）")

    # --offline 恒定带上：只用 Gradle 本地缓存，缺依赖直接报错而不是联网
    cmd = [
        os.environ.get("COMSPEC", r"C:\Windows\System32\cmd.exe"),
        "/c", str(launcher), ":app:assembleRelease", "--console=plain", "--offline",
        f"-PppVersionName={version}", f"-PppVersionCode={code}",
    ]
    if tc is not None:
        # 命令行 -P 优先级高于 gradle.properties，直接把存在的 JDK 25 目录交给 toolchain
        cmd.append(f"-Porg.gradle.java.installations.paths={tc}")

    # 选中的家目录里可能残留一条指向不存在路径的 gradle.properties（D:\cache\gradle-home
    # 那份就写死了 C:/Program Files/Android/Android Studio/jbr → 构建直接报 java home invalid），
    # 命令行 -D 优先级更高，覆盖成这里实际找到的 JDK。
    cmd.append(f"-Dorg.gradle.java.home={jdk}")
    # 本机直连 dl.google.com 下载 jar 会无限挂起（表现为卡住、CPU 0%），给个硬超时让它报错。
    cmd.append("-Dorg.gradle.internal.http.connectionTimeout=30000")
    cmd.append("-Dorg.gradle.internal.http.socketTimeout=60000")

    cmd.extend(extra)

    # temp 先建好：构建失败也走 finally 收尾（整个目录移入回收站）
    temp_dir = prepare_temp_dir()
    log(f"[i] 临时目录: {temp_dir}")

    try:
        log("[1/2] Gradle :app:assembleRelease ...")
        run(cmd, ANDROID, env)

        if not APK_PATH.is_file():
            fail(f"构建成功但没找到 APK: {APK_PATH}")

        staged = temp_dir / APK_PATH.name
        shutil.copyfile(APK_PATH, staged)
        log(f"[i] APK 已放进 temp: {staged.name}")

        OUT.mkdir(parents=True, exist_ok=True)
        target = OUT / f"手机电脑互传-{version}.apk"
        if target.exists():
            target.unlink()
        shutil.move(str(staged), str(target))

        if META_PATH.is_file():
            try:
                meta = json.loads(META_PATH.read_text(encoding="utf-8-sig"))
                el = (meta.get("elements") or [{}])[0]
                built_name = el.get("versionName")
                built_code = el.get("versionCode")
                log(f"[i] APK 内置版本: versionName={built_name} versionCode={built_code}")
                if built_name not in (None, version) or built_code not in (None, code):
                    log(f"[WARN] 构建产物版本与 var 不一致（var: {version}/{code}）")
            except Exception as exc:  # noqa: BLE001
                log(f"[WARN] 读取 output-metadata.json 失败: {exc}")

        log()
        log(f"[OK] APK {version} -> {target}  ({target.stat().st_size} 字节)")
        log(f'     安装: adb install -r "{target}"')
        return 0
    finally:
        recycle_dir(temp_dir)


if __name__ == "__main__":
    raise SystemExit(main())
