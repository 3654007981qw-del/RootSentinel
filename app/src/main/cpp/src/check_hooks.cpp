// check_hooks.cpp — E Hook 框架
// 覆盖 v2 增强包【二.5】JNIEnv 表指针、【二.6】ArtMethod 入口落点
#include "rs_checks.h"

#include <cstdio>
#include <cstdlib>
#include <link.h>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

// 从 /proc/self/maps 找出某个库的所有可执行段
struct ModRange {
    uint64_t    start = 0;
    uint64_t    end = 0;
    std::string path;
};

std::vector<ModRange> findModules(const std::string& nameHint) {
    std::vector<ModRange> out;
    auto lines = readLines(RSs("/proc/self/maps"), 20000);
    for (const auto& line : lines) {
        unsigned long long start = 0, end = 0;
        char perms[8] = {0};
        char path[512] = {0};
        if (std::sscanf(line.c_str(), "%llx-%llx %7s %*s %*s %*s %511[^\n]",
                        &start, &end, perms, path) < 3) {
            continue;
        }
        std::string p = trim(path);
        if (p.empty()) continue;
        if (p.find(nameHint) == std::string::npos) continue;
        if (perms[2] != 'x') continue;    // 只收可执行段
        out.push_back(ModRange{start, end, p});
    }
    return out;
}

bool inAnyRange(uint64_t addr, const std::vector<ModRange>& ranges) {
    for (const auto& r : ranges) {
        if (addr >= r.start && addr < r.end) return true;
    }
    return false;
}

}  // namespace

// 供 jni.cpp 复用：判断一个函数指针是否落在某个模块内
bool isAddressInModule(void* addr, const char* moduleHint) {
    if (!addr) return false;
    auto ranges = findModules(moduleHint);
    return inAnyRange((uint64_t)(uintptr_t)addr, ranges);
}

// 不限权限的版本。
//
// 为什么需要它：
//   isAddressInModule 只收 r-xp 段，那是给「函数指针」用的（代码在可执行段）。
//   但 JNIEnv 的第一字段是 JNINativeInterface* —— 这是个**函数表结构体**，
//   位于 libart.so 的 .data.rel.ro（r--p 只读段），不是可执行段。
//   用 execOnly 的版本去查它必然失败 → E.JNIENV_TABLE 恒报 CRITICAL。
bool isAddressInModuleAnyPerm(void* addr, const char* moduleHint) {
    if (!addr) return false;
    const uint64_t a = (uint64_t)(uintptr_t)addr;
    const std::string hint(moduleHint);

    const auto lines = readLines(RSs("/proc/self/maps"), 40000);
    for (const auto& line : lines) {
        if (line.find(hint) == std::string::npos) continue;
        unsigned long long s = 0, e = 0;
        if (std::sscanf(line.c_str(), "%llx-%llx", &s, &e) != 2) continue;
        if (a >= s && a < e) return true;
    }
    return false;
}

void checkHooks(ScanReport& r) {
    // ---------------------------------------------------------- TracerPid
    const std::string status = readFile(RSs("/proc/self/status"));
    int tracer = 0;
    {
        size_t p = status.find(RSs("TracerPid:"));
        if (p != std::string::npos) tracer = std::atoi(status.c_str() + p + 10);
    }
    if (tracer != 0) {
        Evidence e = makeHit(RSs("E.TRACER"), Category::HOOK,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("进程被 ptrace 附加"));
        e.addVector(RSs("TracerPid"), std::to_string(tracer));
        r.add(std::move(e));
    } else {
        r.add(makePass(RSs("E.TRACER"), Category::HOOK, RSs("TracerPid = 0")));
    }

    // ---------------------------------------------------------- Seccomp
    int seccomp = -1;
    {
        size_t p = status.find(RSs("Seccomp:"));
        if (p != std::string::npos) seccomp = std::atoi(status.c_str() + p + 8);
    }
    if (seccomp == 0) {
        Evidence e = makeHit(RSs("E.SECCOMP"), Category::HOOK,
                             Severity::HIGH, Confidence::MEDIUM,
                             RSs("Seccomp 过滤器未启用"));
        e.addVector(RSs("Seccomp"), "0");
        e.fp_note = RSs("部分低版本 ROM 的 App 进程本来就没有 seccomp filter");
        r.add(std::move(e));
    } else if (seccomp == 2) {
        r.add(makePass(RSs("E.SECCOMP"), Category::HOOK, RSs("Seccomp = 2")));
    }

    // ---------------------------------------------------------- SIGSYS
    // SigCgt 中若包含 SIGSYS，说明有代码在拦截非法 syscall（可能是 hook 框架，
    // 也可能是正常的安全加固）
    {
        size_t p = status.find(RSs("SigCgt:"));
        if (p != std::string::npos) {
            std::string hex = trim(status.substr(p + 7, 16));
            uint64_t mask = std::strtoull(hex.c_str(), nullptr, 16);
            // SIGSYS = 31 → bit 30
            if (mask & (1ULL << 30)) {
                Evidence e = makeHit(RSs("E.SIGSYS"), Category::HOOK,
                                     Severity::LOW, Confidence::LOW,
                                     RSs("SIGSYS 被捕获（可能是 seccomp 违规处理）"));
                e.addVector(RSs("SigCgt"), hex);
                e.heuristic = true;
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- 注入框架库
    const std::vector<std::string> hookLibs = {
        RSs("libzygisk"), RSs("libriru"), RSs("liblspd"), RSs("libedxp"),
        RSs("libfrida"), RSs("libsubstrate"), RSs("libshamiko"),
        RSs("libgum"), RSs("libxposed"),
    };
    int foundLib = 0;
    for (const auto& lib : hookLibs) {
        auto ranges = findModules(lib);
        if (ranges.empty()) continue;
        ++foundLib;
        Evidence e = makeHit(RSs("E.HOOK_LIB"), Category::HOOK,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("加载了 hook 框架库: ") + lib);
        e.addVector(RSs("path"), ranges[0].path);
        e.addVector(RSs("base"), hex64(ranges[0].start));
        r.add(std::move(e));
    }
    if (foundLib == 0) {
        r.add(makePass(RSs("E.HOOK_LIB"), Category::HOOK,
                       RSs("未发现 hook 框架库")));
    }

    // ---------------------------------------------------------- 二.5 JNIEnv 表
    // 由 jni.cpp 传入 JNIEnv 后调用 verifyJniEnv()
    // 这里只做 maps 层面的准备：确认 libart 的可执行段存在
    auto artRanges = findModules(RSs("libart.so"));
    if (artRanges.empty()) {
        r.add(makeUnknown(RSs("E.JNIENV"), Category::HOOK, Severity::MEDIUM,
                          RSs("未在 maps 中找到 libart 可执行段")));
    } else {
        Evidence e = makePass(RSs("E.JNIENV"), Category::HOOK,
                              RSs("libart 可执行段已定位"));
        e.addVector(RSs("base"), hex64(artRanges[0].start));
        e.addVector(RSs("segments"), std::to_string(artRanges.size()));
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 二.6 ArtMethod 落点
    // 已迁移到 check_art_method.cpp（通过 jmethodID 地址归属校验实现，
    // 比按 SDK 版本硬编码结构偏移更稳）

    // ---------------------------------------------------------- /proc/self/fd 里的 so
    // ---------------------------------------------------------- E.FD_SO
    //
    // 旧逻辑：endsWith(t, ".so") || contains(t, "(deleted)")
    //   → 任何打开着的 .so 都算命中！
    //   而 ART 在启动时会把几十个系统 .so 全部 mmap 并保持 fd，
    //   结果原厂未 root 的设备上这一项必然报一堆 MEDIUM 命中。
    //
    // 正确判据：**指向已删除文件**的 so fd 才可疑
    // （dlopen 内存加载 → unlink 后 fd 仍有效，这是注入器的典型手法）。
    // 未删除的 .so 是正常加载行为，不是证据。
    int fdSo = 0;
    int fdSoNormal = 0;
    for (const auto& e : listDirRaw(RSs("/proc/self/fd"))) {
        const std::string t = readLink(RSs("/proc/self/fd/") + e.name);
        if (t.empty()) continue;
        if (!endsWith(t, RSs(".so")) && !contains(t, RSs(".so (deleted)"))) continue;

        const bool deleted = contains(t, RSs("(deleted)"));
        if (!deleted) {
            // 正常加载的系统库：只统计，不产生证据
            ++fdSoNormal;
            continue;
        }

        ++fdSo;
        if (fdSo > 3) continue;
        Evidence ev = makeHit(RSs("E.FD_SO"), Category::HOOK,
                              Severity::MEDIUM, Confidence::MEDIUM,
                              RSs("fd 指向已删除的 so（典型内存加载）: ") + t);
        ev.addVector(RSs("fd"), e.name);
        ev.heuristic = true;
        ev.detail = RSs("so 文件已被 unlink 但 fd 仍持有 → "
                        "常见于 dlopen 内存加载、Zygisk 模块注入");
        ev.fp_note = RSs("应用自身热更新 / 插件化框架卸载插件时也会留下此类 fd，"
                         "需结合 E.SYMBOL_HOOK 判断");
        r.add(std::move(ev));
    }
    if (fdSo == 0) {
        Evidence ev = makePass(RSs("E.FD_SO"), Category::HOOK,
                               RSs("无指向已删除 so 的 fd"));
        ev.addVector(RSs("normal_so_fds"), std::to_string(fdSoNormal));
        r.add(std::move(ev));
    }
}

}  // namespace rs