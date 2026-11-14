#!/usr/bin/env bash
#
# native.sh — 编译 librootsentinel.so（三 ABI）
#
# 与旧版的区别：不再把工具链路径写死成 /root/work、/root/android-sdk。
# 现在按优先级解析，任何人都能在自己机器上复现构建。
#
# 用法：
#   ./native.sh              # 三 ABI 全编
#   ./native.sh arm64-v8a    # 只编一个 ABI
#   ANDROID_NDK_HOME=/opt/ndk ./native.sh
#
set -u

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_DIR="$ROOT_DIR/app/src/main/cpp"
OUT_BASE="$ROOT_DIR/app/src/main/jniLibs"

# ---------------------------------------------------------------- NDK 解析
resolve_ndk() {
    # 1) 显式环境变量
    for v in ANDROID_NDK_HOME ANDROID_NDK_ROOT ANDROID_NDK; do
        val="${!v:-}"
        if [ -n "$val" ] && [ -f "$val/build/cmake/android.toolchain.cmake" ]; then
            echo "$val"; return 0
        fi
    done

    # 2) local.properties 的 ndk.dir
    if [ -f "$ROOT_DIR/local.properties" ]; then
        ndkdir=$(grep -E '^ndk\.dir=' "$ROOT_DIR/local.properties" 2>/dev/null | cut -d= -f2-)
        if [ -n "${ndkdir:-}" ] && [ -f "$ndkdir/build/cmake/android.toolchain.cmake" ]; then
            echo "$ndkdir"; return 0
        fi
    fi

    # 3) SDK 下按版本号取最新的 ndk
    local sdk_roots=()
    if [ -f "$ROOT_DIR/local.properties" ]; then
        sdk=$(grep -E '^sdk\.dir=' "$ROOT_DIR/local.properties" 2>/dev/null | cut -d= -f2-)
        [ -n "${sdk:-}" ] && sdk_roots+=("$sdk")
    fi
    [ -n "${ANDROID_SDK_ROOT:-}" ] && sdk_roots+=("$ANDROID_SDK_ROOT")
    [ -n "${ANDROID_HOME:-}" ] && sdk_roots+=("$ANDROID_HOME")
    sdk_roots+=("$HOME/Android/Sdk" "/root/android-sdk" "$HOME/Library/Android/sdk")

    for s in "${sdk_roots[@]}"; do
        [ -d "$s/ndk" ] || continue
        # 版本号最大的那个
        local latest
        latest=$(ls -1 "$s/ndk" 2>/dev/null | sort -V | tail -1)
        if [ -n "$latest" ] && [ -f "$s/ndk/$latest/build/cmake/android.toolchain.cmake" ]; then
            echo "$s/ndk/$latest"; return 0
        fi
    done

    return 1
}

NDK="$(resolve_ndk)" || {
    echo "[native] 找不到 Android NDK。" >&2
    echo "         请设置 ANDROID_NDK_HOME，或在 local.properties 里写 ndk.dir=..." >&2
    echo "         已尝试：ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_NDK /" >&2
    echo "                 local.properties:ndk.dir / \$ANDROID_SDK_ROOT/ndk / ~/Android/Sdk/ndk" >&2
    exit 1
}

echo "[native] NDK = $NDK"

# ---------------------------------------------------------------- 主机架构检查
#
# NDK 27 的 prebuilt 只有 linux-x86_64（Google 只发 x86_64 宿主版本）。
# 在 aarch64 设备上直接用它会在 try-compile 阶段报
#   "clang: No such file or directory"
# 因为那个二进制是 x86_64 的，内核根本没法执行。
#
# 对策：主机不是 x86_64 时，改用**系统原生 clang** + NDK 的 sysroot 交叉编译。
# clang 是天然交叉编译器，--target 指定目标三元组即可。
HOST_ARCH="$(uname -m)"
NDK_PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
USE_SYS_CLANG=0
NDK_SYSROOT=""

if [ "$HOST_ARCH" != "x86_64" ]; then
    if command -v clang >/dev/null 2>&1; then
        USE_SYS_CLANG=1
        echo "[native] 主机架构 = $HOST_ARCH，NDK 的 clang 是 x86_64 二进制"
        echo "[native] 改用系统 clang 交叉编译"
    else
        echo "[native] 主机架构 = $HOST_ARCH，但找不到系统 clang，无法交叉编译" >&2
        exit 1
    fi
fi

if [ "$USE_SYS_CLANG" = "1" ]; then
    NDK_SYSROOT="$NDK_PREBUILT/sysroot"
    if [ ! -d "$NDK_SYSROOT" ]; then
        echo "[native] 找不到 NDK sysroot：$NDK_SYSROOT" >&2
        exit 1
    fi
    echo "[native] sysroot = $NDK_SYSROOT"
fi

# ---------------------------------------------------------------- CMake 解析
CMAKE_BIN="${CMAKE_BIN:-}"
if [ -z "$CMAKE_BIN" ]; then
    # 优先用 NDK 自带的 cmake（版本匹配，最稳）
    for c in "$NDK"/../../cmake/*/bin/cmake "$NDK/build/cmake/android.toolchain.cmake"; do
        [ -x "$c" ] && { CMAKE_BIN="$c"; break; }
    done
fi
if [ -z "$CMAKE_BIN" ]; then
    if command -v cmake >/dev/null 2>&1; then
        CMAKE_BIN="$(command -v cmake)"
    else
        # SDK 里下载的 cmake
        for s in "$HOME/Android/Sdk" "/root/android-sdk"; do
            [ -d "$s/cmake" ] || continue
            latest=$(ls -1 "$s/cmake" 2>/dev/null | sort -V | tail -1)
            if [ -n "$latest" ] && [ -x "$s/cmake/$latest/bin/cmake" ]; then
                CMAKE_BIN="$s/cmake/$latest/bin/cmake"; break
            fi
        done
    fi
fi

[ -n "$CMAKE_BIN" ] || { echo "[native] 找不到 cmake" >&2; exit 1; }
echo "[native] cmake = $CMAKE_BIN"

# ---------------------------------------------------------------- 构建器
# 优先 ninja（快），没有就回退到 Unix Makefiles。
# 旧版硬依赖 ninja，在只有 make 的环境里直接失败。
NINJA_BIN="${NINJA_BIN:-}"
if [ -z "$NINJA_BIN" ]; then
    if command -v ninja >/dev/null 2>&1; then
        NINJA_BIN="$(command -v ninja)"
    else
        for s in "$HOME/Android/Sdk" "/root/android-sdk"; do
            [ -d "$s/cmake" ] || continue
            latest=$(ls -1 "$s/cmake" 2>/dev/null | sort -V | tail -1)
            if [ -n "$latest" ] && [ -x "$s/cmake/$latest/bin/ninja" ]; then
                NINJA_BIN="$s/cmake/$latest/bin/ninja"; break
            fi
        done
    fi
fi

if [ -n "$NINJA_BIN" ]; then
    GENERATOR="Ninja"
    echo "[native] 构建器 = ninja ($NINJA_BIN)"
else
    if command -v make >/dev/null 2>&1; then
        GENERATOR="Unix Makefiles"
        echo "[native] 构建器 = make（未找到 ninja，已回退）"
    else
        echo "[native] ninja 和 make 都没有，无法构建" >&2
        exit 1
    fi
fi

# ---------------------------------------------------------------- ABI 列表
if [ $# -gt 0 ]; then
    ABIS=("$@")
else
    ABIS=(arm64-v8a armeabi-v7a x86_64)
fi

API_LEVEL="${RS_MIN_SDK:-26}"
BUILD_ROOT="${RS_NATIVE_BUILD:-$ROOT_DIR/.native-build}"

fail=0

# ================================================================
# 路线 A：系统 clang 直接编译（ARM64 等非 x86_64 主机）
# ================================================================
#
# 为什么不走 CMake：
#   CMake 的 Platform/Android 模块看到 CMAKE_SYSTEM_NAME=Android 且
#   没有 NDK toolchain 文件时，会强制走 "Standalone Toolchain" 分支，
#   要求目录里有 aarch64-linux-android-gcc —— NDK 27 早就删掉 gcc 了。
#   即使显式指定 CMAKE_C_COMPILER 也绕不过这一步。
#
#   所以这里直接调 clang++ 把 44 个 .cpp 一次编完再链接，
#   参数与 CMakeLists 里保持一致（-O2、16KB 页对齐、隐藏符号、静态 libc++）。
build_with_clang() {
    local ABI="$1"
    local TRIPLE TARGET

    case "$ABI" in
        arm64-v8a)   TRIPLE="aarch64-linux-android" ;;
        armeabi-v7a) TRIPLE="armv7a-linux-androideabi" ;;
        x86_64)      TRIPLE="x86_64-linux-android" ;;
        *) echo "[native] 未知 ABI: $ABI" >&2; return 1 ;;
    esac
    TARGET="${TRIPLE}${API_LEVEL}"

    local CXX
    CXX="$(command -v clang++)" || CXX="$(command -v clang)"
    [ -n "$CXX" ] || { echo "[native] 找不到 clang++" >&2; return 1; }

    # 16 KB 页对齐：arm64 / x86_64 用 16384，ARM32 仍是 4096
    local PAGE=16384
    [ "$ABI" = "armeabi-v7a" ] && PAGE=4096

    local OUT="$BUILD_ROOT/$ABI"
    rm -rf "$OUT"; mkdir -p "$OUT"

    echo "[native] target = $TARGET"
    echo "[native] cxx    = $CXX"

    # ---- 解析 libc++ 与 clang builtins ----
    #
    # 系统 clang 默认会去链接 -lgcc（GCC 运行时），
    # 但 Android 用的是 libclang_rt.builtins，NDK 里根本没有 libgcc。
    # 所以必须：
    #   1. 找到 NDK 里对应 ABI/API 的 libc++.a
    #   2. 找到 clang 自带的 libclang_rt.builtins-<arch>-android.a
    #   3. 用 -nostdlib++ 关掉自动 libc++，手动指定
    #   4. 用 -Wl,--no-dependent-libraries 避免拉起 libgcc
    local ARCH_TRIPLE SYS_ARCH
    case "$ABI" in
        arm64-v8a)   SYS_ARCH="aarch64-linux-android" ; CLANG_ARCH="aarch64" ;;
        armeabi-v7a) SYS_ARCH="arm-linux-androideabi" ; CLANG_ARCH="arm" ;;
        x86_64)      SYS_ARCH="x86_64-linux-android"  ; CLANG_ARCH="x86_64" ;;
    esac

    # libc++ 静态库：优先 API 级别目录，没有就退到最低可用
    local LIBCXX=""
    local API_TRY="$API_LEVEL"
    while [ "$API_TRY" -ge 21 ]; do
        if [ -f "$NDK_SYSROOT/usr/lib/$SYS_ARCH/$API_TRY/libc++.a" ]; then
            LIBCXX="$NDK_SYSROOT/usr/lib/$SYS_ARCH/$API_TRY/libc++.a"
            break
        fi
        API_TRY=$((API_TRY - 1))
    done
    if [ -z "$LIBCXX" ]; then
        echo "[native] 找不到 libc++.a（ABI=$ABI）" >&2
        return 1
    fi

    local LIBCXXABI="${LIBCXX%libc++.a}libc++abi.a"
    [ -f "$LIBCXXABI" ] || LIBCXXABI=""

    local BUILTINS=""
    BUILTINS=$(ls "$NDK_PREBUILT"/lib/clang/*/lib/linux/libclang_rt.builtins-"$CLANG_ARCH"-android.a 2>/dev/null | head -1)

    echo "[native] libc++  = $LIBCXX"
    [ -n "$BUILTINS" ] && echo "[native] builtins = $BUILTINS"

    # 收集源文件（与 CMakeLists.txt 的 add_library 列表一致：src/*.cpp）
    local SRCS=()
    while IFS= read -r f; do SRCS+=("$f"); done < <(find "$CPP_DIR/src" -maxdepth 1 -name '*.cpp' | sort)
    echo "[native] 源文件 = ${#SRCS[@]} 个"

    # 先逐个编译成 .o（便于并行与错误定位）
    local OBJS=()
    local FAILED=0
    local pids=()
    local MAXJ
    MAXJ="$(nproc 2>/dev/null || echo 4)"

    compile_one() {
        local src="$1"
        local obj="$OUT/$(basename "${src%.cpp}").o"
        "$CXX" \
            --target="$TARGET" \
            --sysroot="$NDK_SYSROOT" \
            -std=c++20 \
            -O2 \
            -fPIC \
            -shared \
            -Wall -Wextra -Wno-unused-parameter \
            -fvisibility=hidden \
            -fstack-protector-strong \
            -fno-asynchronous-unwind-tables \
            -I"$CPP_DIR/include" \
            -DRS_VERSION=\"$RS_VERSION_STR\" \
            $SIGNER_DEF \
            -c "$src" -o "$obj" 2> "$OUT/$(basename "${src%.cpp}").err"
    }

    # 签名基线（v2.22.41）：RS_SIGNER_SHA256 已由主流程解析并 export
    local SIGNER_DEF=""
    if [ -n "${RS_SIGNER_SHA256:-}" ]; then
        SIGNER_DEF='-DRS_SIGNER_SHA256="'"${RS_SIGNER_SHA256}"'"'
    fi
    export -f compile_one
    export CXX NDK_SYSROOT TARGET CPP_DIR OUT RS_VERSION_STR SIGNER_DEF

    # 用 xargs 并行编译
    printf '%s\n' "${SRCS[@]}" | xargs -P "$MAXJ" -I{} bash -c 'compile_one "$@"' _ {}

    # 检查错误
    local ERRS
    ERRS=$(grep -l 'error:' "$OUT"/*.err 2>/dev/null | wc -l)
    if [ "$ERRS" -gt 0 ]; then
        echo "[native] $ERRS 个文件编译失败：" >&2
        grep -h 'error:' "$OUT"/*.err 2>/dev/null | head -20 >&2
        return 1
    fi

    while IFS= read -r o; do OBJS+=("$o"); done < <(ls "$OUT"/*.o 2>/dev/null | sort)
    if [ "${#OBJS[@]}" -eq 0 ]; then
        echo "[native] 没有产出任何 .o" >&2
        return 1
    fi

    # 链接
    #
    # -nostdlib++     关掉 clang 自动加 libc++（我们要手动控制顺序）
    # -nodefaultlibs  关掉自动加 -lgcc / -lstdc++（Android 上没有）
    # 然后按 lld 需要的顺序手动列出：c++ → c++abi → builtins
    local LINK_LIBS=()
    LINK_LIBS+=("$LIBCXX")
    [ -n "$LIBCXXABI" ] && LINK_LIBS+=("$LIBCXXABI")
    [ -n "$BUILTINS" ] && LINK_LIBS+=("$BUILTINS")

    "$CXX" \
        --target="$TARGET" \
        --sysroot="$NDK_SYSROOT" \
        -shared \
        -fuse-ld=lld \
        -nostdlib++ \
        -nodefaultlibs \
        -Wl,-z,max-page-size="$PAGE" \
        -Wl,-z,common-page-size="$PAGE" \
        -Wl,-soname,librootsentinel.so \
        "${OBJS[@]}" \
        "${LINK_LIBS[@]}" \
        -lc -lm \
        -llog -landroid -ldl \
        -o "$OUT/librootsentinel.so" \
        2> "$OUT/link.err"

    if [ ! -f "$OUT/librootsentinel.so" ]; then
        echo "[native] 链接失败：" >&2
        head -25 "$OUT/link.err" >&2
        return 1
    fi

    mkdir -p "$OUT_BASE/$ABI"
    cp -f "$OUT/librootsentinel.so" "$OUT_BASE/$ABI/librootsentinel.so"
    strip_so "$OUT_BASE/$ABI/librootsentinel.so"
    echo "[native] $ABI OK → $(ls -l "$OUT_BASE/$ABI/librootsentinel.so" | awk '{print $5}') 字节"
    return 0
}

# ================================================================
# 路线 B：CMake + NDK toolchain（x86_64 主机）
# ================================================================
build_with_cmake() {
    local ABI="$1"
    local BUILD_DIR="$BUILD_ROOT/$ABI"
    rm -rf "$BUILD_DIR"; mkdir -p "$BUILD_DIR"

    local GEN_ARGS=()
    if [ "$GENERATOR" = "Ninja" ]; then
        GEN_ARGS=(-G Ninja -DCMAKE_MAKE_PROGRAM="$NINJA_BIN")
    else
        GEN_ARGS=(-G "Unix Makefiles")
    fi

    local SIGNER_ARGS=()
    [ -n "${RS_SIGNER_SHA256:-}" ] && SIGNER_ARGS=(-DRS_SIGNER_SHA256="$RS_SIGNER_SHA256")

    # v2.22.43：armeabi-v7a 必须显式 ARM 模式。默认 Thumb 模式下 R7 是
    # 保留寄存器，syscall_asm.cpp 的内联汇编直接编译失败
    # （write to reserved register 'R7'）—— 之前的仓库构建一直靠
    # 外部管线手工补这个参数，native.sh 自己跑不通 v7a。
    local ARM_ARGS=()
    [ "$ABI" = "armeabi-v7a" ] && ARM_ARGS=(-DANDROID_ARM_MODE=arm)

    "$CMAKE_BIN" -S "$CPP_DIR" -B "$BUILD_DIR" "${GEN_ARGS[@]}" "${SIGNER_ARGS[@]}" "${ARM_ARGS[@]}" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API_LEVEL" \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        > "$BUILD_DIR/cmake.log" 2>&1 || {
            echo "[native] cmake 配置失败，见 $BUILD_DIR/cmake.log" >&2
            tail -25 "$BUILD_DIR/cmake.log" >&2
            return 1
        }

    "$CMAKE_BIN" --build "$BUILD_DIR" -j"$(nproc 2>/dev/null || echo 4)" \
        > "$BUILD_DIR/build.log" 2>&1 || {
            echo "[native] 编译失败，见 $BUILD_DIR/build.log" >&2
            grep -E 'error:' "$BUILD_DIR/build.log" | head -20 >&2
            return 1
        }

    local SO="$BUILD_DIR/librootsentinel.so"
    [ -f "$SO" ] || { echo "[native] 产物不存在：$SO" >&2; return 1; }

    mkdir -p "$OUT_BASE/$ABI"
    cp -f "$SO" "$OUT_BASE/$ABI/librootsentinel.so"
    strip_so "$OUT_BASE/$ABI/librootsentinel.so"
    echo "[native] $ABI OK → $(ls -l "$OUT_BASE/$ABI/librootsentinel.so" | awk '{print $5}') 字节"
    return 0
}

# v2.22.43：入库的 .so 一律 strip（审查 P2-08）。之前仓库里的
# 预置库带完整符号表 —— 体积白白放大，内部符号名也全部暴露。
# v2.22.44（审查 P3-01）：strip 改为失败关闭 —— 找不到 llvm-strip
# 或 strip 失败都直接中止构建，不再只警告。确实要在无 strip 的
# 环境编临时库时，显式 RS_ALLOW_UNSTRIPPED=1 放行。
strip_so() {
    local so="$1"
    local s=""
    s="$(command -v llvm-strip 2>/dev/null || true)"
    if [ -z "$s" ]; then
        for base in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${NDK:-}"; do
            [ -n "$base" ] && [ -x "$base/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" ] && \
                s="$base/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" && break
        done
    fi
    if [ -n "$s" ]; then
        if ! "$s" "$so" 2>/dev/null; then
            echo "[native] 错误：strip 失败：$so" >&2
            exit 1
        fi
    elif [ "${RS_ALLOW_UNSTRIPPED:-0}" = "1" ]; then
        echo "[native] 警告：未找到 llvm-strip，.so 未 strip（RS_ALLOW_UNSTRIPPED=1）：$so" >&2
    else
        echo "[native] 错误：未找到 llvm-strip，中止构建（临时放行：RS_ALLOW_UNSTRIPPED=1）" >&2
        exit 1
    fi
}

# ================================================================
# 主循环
# ================================================================
RS_VERSION_STR="$(grep -oE 'RS_VERSION=\"[^\"]+\"' "$CPP_DIR/CMakeLists.txt" | head -1 | cut -d'"' -f2)"
[ -n "$RS_VERSION_STR" ] || RS_VERSION_STR="unknown"
echo "[native] RS_VERSION = $RS_VERSION_STR"

# ------------------------------------------------------------ 签名基线
# v2.22.41 修复：之前 native.sh 完全不注入 RS_SIGNER_SHA256，
# 独立构建出的 .so 里没有签名基线，I.APK_SIGNATURE 恒为 UNKNOWN。
# 解析顺序：环境变量 RS_SIGNER_SHA256 → 从 RS_KS_PATH 的 keystore
# 用 keytool 读证书 SHA-256（与 build.gradle.kts 的 signerSha256 同口径）
# → 都没有则不注入，但必须明确警告，不再静默缺失。
if [ -z "${RS_SIGNER_SHA256:-}" ] && [ -n "${RS_KS_PATH:-}" ] && [ -n "${RS_KS_ALIAS:-}" ] \
    && command -v keytool >/dev/null 2>&1; then
    # v2.22.44（P2-13）：密码改走 -storepass:env，不进 argv
    # （/proc/<pid>/cmdline 对同 uid 进程可读）。
    _fp=$(keytool -list -v -keystore "$RS_KS_PATH" -alias "$RS_KS_ALIAS" \
        -storepass:env RS_KS_PASS 2>/dev/null \
        | grep -m1 'SHA256:' | sed 's/.*SHA256://' | tr -d ':[:space:]')
    if [ -n "$_fp" ] && [ "${#_fp}" -eq 64 ]; then
        RS_SIGNER_SHA256="$_fp"
    fi
    unset _fp
fi
if [ -n "${RS_SIGNER_SHA256:-}" ]; then
    echo "[native] 签名基线已注入 (SHA-256 前 8 位: ${RS_SIGNER_SHA256:0:8})"
elif [ "${RS_ALLOW_NO_SIGNER:-}" = "1" ]; then
    echo "[native] 警告：未提供签名基线，本次构建的 I.APK_SIGNATURE 将为 UNKNOWN（RS_ALLOW_NO_SIGNER=1，仅限本地调试）" >&2
else
    # v2.22.43：之前只警告不中断，照样产出无基线的 .so，
    # 装机后 I.APK_SIGNATURE 恒为 UNKNOWN。现在默认直接失败，
    # 先按 README 第 0 步 export RS_KS_* 或 RS_SIGNER_SHA256 再构建。
    echo "[native] 错误：未提供签名基线（RS_SIGNER_SHA256 / RS_KS_PATH 均未设置），拒绝构建无基线的 .so。仅本地调试可加 RS_ALLOW_NO_SIGNER=1 放行。" >&2
    exit 1
fi
export RS_SIGNER_SHA256

for ABI in "${ABIS[@]}"; do
    echo "================================================================"
    echo "[native] 构建 $ABI (API $API_LEVEL)"
    if [ "$USE_SYS_CLANG" = "1" ]; then
        build_with_clang "$ABI" || fail=1
    else
        build_with_cmake "$ABI" || fail=1
    fi
done

echo "================================================================"
if [ "$fail" -ne 0 ]; then
    echo "[native] 有 ABI 构建失败" >&2
    exit 1
fi
echo "[native] 全部完成"
ls -l "$OUT_BASE"/*/librootsentinel.so 2>/dev/null | awk '{print $5, $9}'
