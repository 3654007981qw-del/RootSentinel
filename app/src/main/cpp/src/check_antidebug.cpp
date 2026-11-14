// check_antidebug.cpp — 反调试与进程来源检测（M2）
//
// 为什么单独一层：
//   Zygisk / Frida / 调试器注入都会改变「进程从哪里来」
//   —— 父进程不是 zygote、exe 指向非 APK 路径、代码段被插入断点。
#include "rs_checks.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <cstdlib>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

struct ProcStat {
    int    ppid = -1;
    std::string state;
};

// /proc/self/stat 的 comm 字段可能含空格/括号，必须从最后一个 ')' 之后解析
ProcStat parseStat(const std::string& line) {
    ProcStat ps;
    size_t rp = line.rfind(')');
    if (rp == std::string::npos) return ps;
    std::string rest = line.substr(rp + 1);
    auto f = split(trim(rest), ' ');
    if (f.size() >= 2) {
        ps.state = f[0];
        ps.ppid = std::atoi(f[1].c_str());
    }
    return ps;
}

}  // namespace

void checkAntiDebug(ScanReport& r) {
    const std::string statLine = readFile(RSs("/proc/self/stat"));
    ProcStat ps = parseStat(statLine);

    // ---------------------------------------------------------- 父进程
    if (ps.ppid > 0) {
        const std::string pc = readFile(RSs("/proc/") + std::to_string(ps.ppid) + RSs("/cmdline"));
        Evidence e = makePass(RSs("E.PARENT"), Category::HOOK,
                              RSs("父进程已识别"));
        e.addVector(RSs("ppid"), std::to_string(ps.ppid));
        e.addVector(RSs("parent_cmdline"), trim(pc));

        if (!pc.empty() && !contains(pc, RSs("zygote"))) {
            // 正常 App 的父进程必然是 zygote（或 zygote64）
            e.verdict = Verdict::HIT;
            e.severity = Severity::HIGH;
            e.confidence = Confidence::MEDIUM;
            e.title = RSs("父进程不是 zygote ← 可能被调试器/注入器启动");
            e.fp_note = RSs("部分 ROM 的 app_process 启动方式不同；"
                            "通过 am start 启动时应为 zygote");
        }
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- /proc/self/exe
    {
        const std::string exe = readLink(RSs("/proc/self/exe"));
        if (!exe.empty()) {
            Evidence e = makePass(RSs("E.EXE"), Category::HOOK, RSs("exe 已解析"));
            e.addVector(RSs("exe"), exe);
            if (!contains(exe, RSs("app_process"))) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::MEDIUM;
                e.confidence = Confidence::MEDIUM;
                e.title = RSs("/proc/self/exe 不是 app_process");
            }
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- 代码段断点扫描
    // 扫描自身 so 的可执行段，统计 BRK 指令（arm64: 0xD4200000）
    // 只作为软信号：abort() 等正常路径也会编译出 BRK
    {
        int brkCount = 0;
        size_t scanned = 0;
        auto lines = readLines(RSs("/proc/self/maps"), 20000);
        for (const auto& line : lines) {
            if (line.find(RSs("librootsentinel.so")) == std::string::npos) continue;
            if (line.find(RSs("r-x")) == std::string::npos) continue;

            unsigned long long start = 0, end = 0;
            if (std::sscanf(line.c_str(), "%llx-%llx", &start, &end) != 2) continue;

            size_t bytes = (size_t)(end - start);
            if (bytes == 0 || bytes > (8u << 20)) continue;
            scanned += bytes;

#if defined(__aarch64__)
            const uint32_t* p = reinterpret_cast<const uint32_t*>(start);
            size_t n = bytes / 4;
            for (size_t i = 0; i < n; ++i) {
                if (p[i] == 0xD4200000u) ++brkCount;
            }
#endif
        }

        if (scanned > 0) {
            Evidence e = makePass(RSs("E.BREAKPOINT"), Category::HOOK,
                                  RSs("自身代码段已扫描"));
            e.addVector(RSs("bytes"), std::to_string(scanned));
            e.addVector(RSs("brk_count"), std::to_string(brkCount));
            if (brkCount > 8) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::LOW;
                e.confidence = Confidence::LOW;
                e.heuristic = true;
                e.title = RSs("自身代码段 BRK 指令数量异常偏高");
                e.fp_note = RSs("软信号：异常处理/断言也会产生 BRK；单独不能作为结论");
            }
            r.add(std::move(e));
        } else {
            r.add(makeUnknown(RSs("E.BREAKPOINT"), Category::HOOK, Severity::LOW,
                              RSs("未能定位自身可执行段")));
        }
    }

    // ---------------------------------------------------------- ptrace_scope
    {
        const std::string scope = trim(readFile(RSs("/proc/sys/kernel/yama/ptrace_scope")));
        if (!scope.empty()) {
            Evidence e = makePass(RSs("E.PTRACE_SCOPE"), Category::HOOK,
                                  RSs("ptrace_scope 已读"));
            e.addVector(RSs("value"), scope);
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- 调试器进程
    // App 只能看到同 uid 进程，这里做「能看多少」的记录
    {
        int visible = 0;
        for (const auto& e : listDirRaw(RSs("/proc"))) {
            if (e.name.empty() || e.name[0] < '0' || e.name[0] > '9') continue;
            ++visible;
        }
        Evidence e = makePass(RSs("E.PROC_VISIBLE"), Category::HOOK,
                              RSs("/proc 可见进程数已记录"));
        e.addVector(RSs("count"), std::to_string(visible));
        e.fp_note = RSs("Android 10+ 限制 App 只能看到自身进程，"
                        "数量少属正常");
        r.add(std::move(e));
    }
}

}  // namespace rs