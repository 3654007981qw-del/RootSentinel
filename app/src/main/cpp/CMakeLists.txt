cmake_minimum_required(VERSION 3.22.1)
project(rootsentinel LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# 所有源文件显式列出，避免 GLOB 带来的增量构建不确定性
add_library(rootsentinel SHARED
        src/jni.cpp
        src/syscall_asm.cpp
        src/str_obf.cpp
        src/json_builder.cpp
        src/util.cpp
        src/check_artifacts.cpp
        src/check_props.cpp
        src/check_mount.cpp
        src/check_memory.cpp
        src/check_hooks.cpp
        src/check_selinux.cpp
        src/check_hiding_deep.cpp
        src/check_kernel.cpp
        src/check_integrity.cpp
        src/check_timing.cpp
        src/check_bpf.cpp
        src/check_antidebug.cpp
        src/check_cross_process.cpp
        src/check_fs_meta.cpp
        src/check_art_deep.cpp
        src/check_dex.cpp
        src/check_init_chain.cpp
        src/check_env.cpp
        src/check_text_hash.cpp
        src/check_threads.cpp
        src/check_fs_deep.cpp
        src/check_kernel_deep.cpp
        src/check_net.cpp
        src/check_emulator.cpp
        src/check_syscall_anomaly.cpp
        src/check_magisk_family.cpp
        src/check_anon_exec.cpp
        src/check_proc_anomaly.cpp
        src/check_cred.cpp
        src/check_kernel_taint.cpp
        src/check_art_method.cpp
        src/check_sandbox.cpp
        src/check_compat.cpp
        src/check_fs_time.cpp
        src/check_kernel_ext.cpp
        src/check_proc_ext.cpp
        src/check_hw_ext.cpp
        src/check_self_ext.cpp
        src/check_plt.cpp
        src/analyze.cpp
)

target_include_directories(rootsentinel PRIVATE include)

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)

target_link_libraries(rootsentinel
        ${log-lib}
        ${android-lib}
        ${dl-lib}
)

target_compile_options(rootsentinel PRIVATE
        -O2
        -Wall
        -Wextra
        -Wno-unused-parameter
        -fvisibility=hidden
        -fstack-protector-strong
        -fno-asynchronous-unwind-tables
)

# 强制内联汇编走独立编译单元，防止 LTO 把 syscall 包装“优化”成 libc 调用
set_source_files_properties(src/syscall_asm.cpp PROPERTIES COMPILE_OPTIONS "-fno-lto")

# ---------------------------------------------------------------- 16 KB 页对齐
# Android 15 起，arm64 / x86_64 的 LOAD 段要求 16 KB 对齐。
# v2.4.0 之前这里没有显式声明，完全依赖 NDK 默认值（NDK r27 默认 16384，
# 但换 NDK 版本就可能回退到 4 KB，导致在 16 KB 页设备上装不上）。
# 现在显式写死，构建脚本改用别的 NDK 也不会漂。
if (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    target_link_options(rootsentinel PRIVATE "-Wl,-z,max-page-size=16384")
    target_link_options(rootsentinel PRIVATE "-Wl,-z,common-page-size=16384")
else()
    # 32 位 ARM 仍是 4 KB 页
    target_link_options(rootsentinel PRIVATE "-Wl,-z,max-page-size=4096")
    target_link_options(rootsentinel PRIVATE "-Wl,-z,common-page-size=4096")
endif()

target_compile_definitions(rootsentinel PRIVATE
        RS_VERSION="2.22.45"
)

# 签名基线（v2.22.41 修复）：构建方通过 -DRS_SIGNER_SHA256=<64 位 hex>
# 传入（Gradle 的 arguments / native.sh 的两种路线都走这里）。
# 之前 CMakeLists 从不消费这个缓存变量，宏永远不会被定义，
# jni.cpp 的 sigCheck() 恒定返回 -1，I.APK_SIGNATURE 永远 UNKNOWN ——
# Gradle 侧看似注入了，实际从未生效。现在转发为编译定义（带引号，
# jni.cpp 的 RSs() 需要字符串字面量）。
if(DEFINED RS_SIGNER_SHA256 AND NOT "${RS_SIGNER_SHA256}" STREQUAL "")
    target_compile_definitions(rootsentinel PRIVATE
            RS_SIGNER_SHA256="${RS_SIGNER_SHA256}"
    )
endif()
