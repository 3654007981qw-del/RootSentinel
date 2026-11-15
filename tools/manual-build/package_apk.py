#!/usr/bin/env python3
"""把 dex、native .so、JAR 里的服务资源、AAR assets 装进 linked.apk 的副本。

用系统 zip 命令追加（zipfile 追加模式会被 aapt2 产物的 1980 时间戳噎住，
zip CLI 与项目 build.sh 的做法一致，没有这个问题）。
"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

WORK = os.path.expanduser("~/workspace/rs2241-build/work")
mode = sys.argv[1]
apk = os.path.join(WORK, f"rs-{mode}.apk")
stage = os.path.join(WORK, f"stage-{mode}")
shutil.rmtree(stage, ignore_errors=True)
os.makedirs(stage)

# dex
for dex in sorted(glob.glob(os.path.join(WORK, f"dex-{mode}", "classes*.dex"))):
    shutil.copy(dex, stage)

# native libs（先 strip，与 AGP release 行为一致，显著减小体积）
strip = os.path.expanduser(
    "~/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    so = os.path.join(WORK, "native", abi, "librootsentinel.so")
    if os.path.exists(so):
        dest_dir = os.path.join(stage, "lib", abi)
        os.makedirs(dest_dir, exist_ok=True)
        subprocess.run([strip, "-o", os.path.join(dest_dir, "librootsentinel.so"), so],
                       check=True)

# AAR 自带 jni / assets（只收项目三 ABI，避免多出无引擎库的 ABI 目录）
for so in glob.glob(os.path.join(WORK, "aar", "*", "jni", "*", "*.so")):
    parts = os.path.relpath(so, os.path.join(WORK, "aar")).split(os.sep)
    if parts[2] not in ("arm64-v8a", "armeabi-v7a", "x86_64"):
        continue
    dest = os.path.join(stage, "lib", parts[2], parts[3])
    if not os.path.exists(dest):
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.copy(so, dest)
for f in glob.glob(os.path.join(WORK, "aar", "*", "assets", "**", "*"), recursive=True):
    if os.path.isfile(f):
        parts = os.path.relpath(f, os.path.join(WORK, "aar")).split(os.sep)
        dest = os.path.join(stage, "assets", *parts[2:])
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.copy(f, dest)

# JAR 服务资源（协程 MainDispatcherFactory 等靠它发现；同名保留先出现的一份）
svc_root = os.path.join(stage, "META-INF", "services")
for jar in sorted(glob.glob(os.path.join(WORK, "jars", "*.jar"))):
    with zipfile.ZipFile(jar) as jz:
        for info in jz.infolist():
            n = info.filename
            if n.startswith("META-INF/services/") and not info.is_dir():
                dest = os.path.join(stage, n)
                if os.path.exists(dest):
                    continue
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with open(dest, "wb") as fh:
                    fh.write(jz.read(n))

# 追加进 APK：dex/服务文件压缩，.so 存储（zipalign 在后续步骤对齐）
entries = []
for root, _dirs, files in os.walk(stage):
    for fn in files:
        full = os.path.join(root, fn)
        entries.append(os.path.relpath(full, stage))
assert entries, "staging 为空"
so_entries = [e for e in entries if e.endswith(".so")]
other = [e for e in entries if not e.endswith(".so")]
if other:
    subprocess.run(["zip", "-q", apk] + other, cwd=stage, check=True)
if so_entries:
    subprocess.run(["zip", "-q", "-0", apk] + so_entries, cwd=stage, check=True)
print(f"added {len(entries)} entries")
