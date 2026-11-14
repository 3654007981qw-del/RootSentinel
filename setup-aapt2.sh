#!/bin/sh
#
# setup-aapt2.sh —— 在 arm64 主机上让 AGP 能用 aapt2
#
# ================================================================
# 背景
# ================================================================
# AGP 依赖的 aapt2 只有 x86_64 的 linux 构建产物。
# 在 arm64 主机（本机 proot 环境）上，AGP 会先跑
#   aapt2 version
# 做一次可用性校验 —— x86_64 ELF 在 arm64 内核上直接 exec 会失败，
# 于是构建在 "Failed to load aapt2" 处中断。
#
# ================================================================
# 做法（两步）
# ================================================================
# 1. 编译 aapt2shim.c 成 **arm64 ELF**：
#      · `version` / `--version` 子命令直接应答固定字符串（骗过 AGP 校验）
#      · 其余命令 exec 到 `qemu-x86_64-static -L /usr/x86_64-linux-gnu <真 aapt2>`
# 2. 把该程序打成 jar，塞进**本地 Maven 仓库**，坐标与 AGP 期望的完全一致：
#      com.android.tools.build:aapt2:8.9.1-12782657:linux
#
# 这样 AGP 从本地仓库解析 aapt2 时拿到的就是我们的 shim，
# 校验通过、实际工作交给 qemu 里的真 aapt2。
#
# ================================================================
# 前提
# ================================================================
#   · 主机是 aarch64
#   · 已装 qemu-user-static（qemu-x86_64-static）
#   · 已装 x86_64 的 glibc 运行库（/usr/x86_64-linux-gnu）
#   · Android SDK 里有 build-tools/35.0.0/aapt2
#
# 用法：
#   sh setup-aapt2.sh
#
# 只需要在 arm64 主机上跑一次；x86_64 主机不用管这个脚本。
set -u

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/root/android-sdk}}"
BT_VERSION="${RS_BUILD_TOOLS:-35.0.0}"
REAL_AAPT2="$SDK/build-tools/$BT_VERSION/aapt2"
QEMU="/usr/bin/qemu-x86_64-static"
LDP="/usr/x86_64-linux-gnu"

AGP_AAPT2_VER="${RS_AGP_AAPT2_VER:-8.9.1-12782657}"
# v2.22.44 统一（第四份审查 P1-04）：本地仓库只有一个真源 ——
# settings.gradle.kts 里写死的 /root/localMaven。此前脚本默认写
# $HOME/.m2/repository，两边不是同一目录，脚本显示成功、Gradle
# 却找不到 shim。改路径时两边一起改（或用 RS_LOCAL_MAVEN 覆盖，
# 并同步 settings.gradle.kts）。
LOCAL_MAVEN="${RS_LOCAL_MAVEN:-/root/localMaven}"
M2_DIR="${RS_M2_DIR:-$LOCAL_MAVEN/com/android/tools/build/aapt2/$AGP_AAPT2_VER}"

echo "================================================================"
echo "[aapt2] SDK        = $SDK"
echo "[aapt2] build-tools= $BT_VERSION"
echo "[aapt2] 目标坐标   = com.android.tools.build:aapt2:$AGP_AAPT2_VER:linux"
echo "[aapt2] 本地仓库   = $M2_DIR"
echo "================================================================"

# ---------------------------------------------------------------- 架构检查
HOST_ARCH="$(uname -m)"
if [ "$HOST_ARCH" = "x86_64" ]; then
    echo "[aapt2] 主机是 x86_64，不需要 shim，直接退出"
    exit 0
fi
echo "[aapt2] 主机架构 = $HOST_ARCH，需要 shim"

# ---------------------------------------------------------------- 前提检查
if [ ! -f "$REAL_AAPT2" ]; then
    echo "[aapt2] 找不到真 aapt2：$REAL_AAPT2" >&2
    echo "        请用 RS_BUILD_TOOLS=<版本> 指定，或确认 SDK 路径" >&2
    exit 1
fi
if [ ! -x "$QEMU" ]; then
    echo "[aapt2] 找不到 $QEMU" >&2
    echo "        请先安装 qemu-user-static" >&2
    exit 1
fi
if [ ! -d "$LDP" ]; then
    echo "[aapt2] 找不到 x86_64 运行库目录 $LDP" >&2
    echo "        请先安装 libc6:amd64 等 x86_64 运行库" >&2
    exit 1
fi

# ---------------------------------------------------------------- 编译 shim
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC_DIR="$(cd "$(dirname "$0")" && pwd)"

if [ ! -f "$SRC_DIR/aapt2shim.c" ]; then
    echo "[aapt2] 找不到 aapt2shim.c（应在脚本同目录）" >&2
    exit 1
fi

CC_BIN="${CC:-}"
if [ -z "$CC_BIN" ]; then
    for c in clang gcc cc; do
        if command -v "$c" >/dev/null 2>&1; then CC_BIN="$c"; break; fi
    done
fi
[ -n "$CC_BIN" ] || { echo "[aapt2] 找不到 C 编译器" >&2; exit 1; }

# v2.22.44（P1-04）：shim 里的真 aapt2 路径不再写死在 aapt2shim.c
# 里 —— 源码只留 __REAL_AAPT2__ / __QEMU__ / __LDP__ 占位符，
# 由本脚本按当前 SDK / build-tools 配置生成后再编译。改 SDK
# 路径只改一处，两边不会再对不上。
echo "[aapt2] 生成 shim 源码（REAL=$REAL_AAPT2）..."
sed -e "s|__REAL_AAPT2__|$REAL_AAPT2|g" \
    -e "s|__QEMU__|$QEMU|g" \
    -e "s|__LDP__|$LDP|g" \
    "$SRC_DIR/aapt2shim.c" > "$WORK/aapt2shim.gen.c"

echo "[aapt2] 编译 shim（$CC_BIN）..."
"$CC_BIN" -O2 -static -o "$WORK/aapt2" "$WORK/aapt2shim.gen.c" || {
    echo "[aapt2] 编译失败" >&2
    exit 1
}
chmod 755 "$WORK/aapt2"

# 自检：version 子命令应直接应答
VER_OUT="$("$WORK/aapt2" version 2>&1 || true)"
case "$VER_OUT" in
    *"Android Asset Packaging Tool"*)
        echo "[aapt2] shim 自检通过：$VER_OUT"
        ;;
    *)
        echo "[aapt2] shim 自检失败，输出：$VER_OUT" >&2
        exit 1
        ;;
esac

# ---------------------------------------------------------------- 打进本地 Maven
echo "[aapt2] 写入本地 Maven 仓库..."
mkdir -p "$M2_DIR"

# jar 里只需要一个可执行文件，文件名固定为 aapt2
JAR="$M2_DIR/aapt2-$AGP_AAPT2_VER-linux.jar"
( cd "$WORK" && jar cf "$JAR" aapt2 ) 2>/dev/null || {
    # 没有 jar 命令时用 zip 兜底
    ( cd "$WORK" && zip -q "$JAR" aapt2 ) || {
        echo "[aapt2] 打包 jar 失败（需要 jar 或 zip）" >&2
        exit 1
    }
}

# v2.22.44（P1-04）：不再往 SDK 的 extras/m2repository 同步副本 ——
# settings.gradle.kts 的仓库列表里根本没有那个路径，多写一份只会
# 让人误以为它生效。本地仓库只有 /root/localMaven 一处（见上）。
# ---------------------------------------------------------------- 校验
if [ -f "$JAR" ]; then
    echo "[aapt2] 完成：$JAR ($(wc -c < "$JAR") 字节)"
else
    echo "[aapt2] 产物不存在：$JAR" >&2
    exit 1
fi

echo "[aapt2] 现在可以执行 ./gradlew :app:assembleRelease"
