// check_syscall_anomaly.cpp — syscall 层矛盾检测（M5）
//
// 这一层是「检测器的检测器」：
//   所有隐藏方案最终都要落到某个 syscall 上做拦截。
//   如果同一路径在 openat / faccessat / statx 三个入口上
//   返回互相矛盾的结果，说明拦截只做了一部分 —— 隐藏没做全。
#include "rs_checks.h"

#include <cerrno>
#include <fcntl.h>
#include <string>
#include <unistd.h>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

std::string openErrno(const std::string& p) {
    const int fd = sys::openat_(p.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        sys::close_(fd);
        return RSs("OK");
    }
    return errnoName(errno);
}

std::string accessErrno(const std::string& p) {
    const int st = accessState(p, 4);
    if (st == 0) return RSs("OK");
    if (st == 1) return RSs("EACCES");
    return RSs("ENOENT");
}

std::string statxErrno(const std::string& p) {
    sys::statx_buf sx{};
    if (sys::statx_(p.c_str(), &sx, nullptr, 0) == 0) return RSs("OK");
    return errnoName(errno);
}

}  // namespace

void checkSyscallAnomaly(ScanReport& r) {
    // ---------------------------------------------------------- X.ERRNO_TRIAD
    {
        const std::vector<std::string> tests = {
            RSs("/data/adb"),                          // 有 root 时应为 EACCES
            RSs("/system/bin/.rs_probe_not_exist"),    // 恒定 ENOENT
            RSs("/system/bin/sh"),                     // 恒定 OK
        };

        int checked = 0, conflict = 0;
        std::vector<std::string> lines;

        for (const auto& p : tests) {
            const auto e1 = openErrno(p);
            const auto e2 = accessErrno(p);
            const auto e3 = statxErrno(p);

            ++checked;

            // 判定矛盾：openat 与 statx 对「是否可打开」的结论不同
            const bool c1 = ((e1 == RSs("OK")) != (e3 == RSs("OK")));
            // faccessat 说存在（EACCES 也算存在）但 openat 说 ENOENT
            const bool c2 = (e2 != RSs("ENOENT")) && (e1 == RSs("ENOENT"));

            if (c1 || c2) {
                ++conflict;
                lines.push_back(p + RSs(": openat=") + e1 +
                                RSs(" faccess=") + e2 +
                                RSs(" statx=") + e3);
            } else if (lines.size() < 6) {
                lines.push_back(p + RSs(": ") + e1 + RSs("/") + e2 +
                                RSs("/") + e3);
            }
        }

        if (checked == 0) {
            r.add(makeUnknown(RSs("X.ERRNO_TRIAD"), Category::CROSSCHECK,
                              Severity::LOW, RSs("三桩探测不可用")));
        } else if (conflict == 0) {
            Evidence e = makePass(RSs("X.ERRNO_TRIAD"), Category::CROSSCHECK,
                                  RSs("三种 syscall 的 errno 互相一致"));
            e.addVector(RSs("checked"), std::to_string(checked));
            for (const auto& s : lines) e.addVector(RSs("probe"), s);
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("X.ERRNO_TRIAD"), Category::CROSSCHECK,
                                 Severity::HIGH, Confidence::HIGH,
                                 RSs("同一路径在不同 syscall 下结论矛盾"));
            for (const auto& s : lines) e.addVector(RSs("probe"), s);
            e.detail = RSs("隐藏方案只拦截了部分 syscall 入口");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- X.EXE_LINK
    // Android App 的 /proc/self/exe 必须是 app_process。
    // 若指向别的二进制 → 进程被替换过（非常规注入）。
    {
        const auto exe = readLink(RSs("/proc/self/exe"));
        const auto cmd = readFile(RSs("/proc/self/cmdline"), 8192);

        std::string first;
        for (const char c : cmd) {
            if (c == '\0') break;
            first += c;
        }

        if (exe.empty()) {
            r.add(makeUnknown(RSs("X.EXE_LINK"), Category::CROSSCHECK,
                              Severity::LOW, RSs("/proc/self/exe 不可读")));
        } else if (contains(exe, RSs("app_process"))) {
            Evidence e = makePass(RSs("X.EXE_LINK"), Category::CROSSCHECK,
                                  RSs("进程 exe 为 app_process"));
            e.addVector(RSs("exe"), exe);
            e.addVector(RSs("cmdline"), first);
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("X.EXE_LINK"), Category::CROSSCHECK,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("进程 exe 不是 app_process"));
            e.addVector(RSs("exe"), exe);
            e.addVector(RSs("cmdline"), first);
            e.detail = RSs("进程被非标准方式启动或替换");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- X.CMDLINE_PID
    // /proc/self/stat 首字段必须等于 getpid()。
    // 若不等 → /proc 被重定向（少见但极强）。
    {
        const auto pid = getpid();
        const auto stat = readFile(RSs("/proc/self/stat"), 8192);

        if (stat.empty()) {
            r.add(makeUnknown(RSs("X.CMDLINE_PID"), Category::CROSSCHECK,
                              Severity::LOW, RSs("/proc/self/stat 不可读")));
        } else {
            // 格式："pid (comm) state ..."
            const auto sp = stat.find(' ');
            const std::string pidStr =
                sp == std::string::npos ? std::string() : stat.substr(0, sp);

            if (pidStr == std::to_string(pid)) {
                Evidence e = makePass(RSs("X.CMDLINE_PID"), Category::CROSSCHECK,
                                      RSs("/proc/self/stat 的 pid 与 getpid 一致"));
                e.addVector(RSs("getpid"), std::to_string(pid));
                e.addVector(RSs("stat"), pidStr);
                r.add(std::move(e));
            } else {
                Evidence e = makeHit(RSs("X.CMDLINE_PID"), Category::CROSSCHECK,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("/proc/self/stat 的 pid 与 getpid 不一致"));
                e.addVector(RSs("getpid"), std::to_string(pid));
                e.addVector(RSs("stat"), pidStr);
                e.detail = RSs("/proc 目录被重定向到其他进程");
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- X.PROC_INODE
    // /proc/self 的目录 inode 应等于 /proc/<getpid> 的 inode。
    {
        sys::rawstat a{}, b{};
        const auto pid = getpid();
        const bool ok1 = sys::fstatat_raw("/proc/self", &a, 0) == 0;
        const bool ok2 = sys::fstatat_raw(
            (RSs("/proc/") + std::to_string(pid)).c_str(), &b, 0) == 0;

        if (!ok1 || !ok2) {
            r.add(makeUnknown(RSs("X.PROC_INODE"), Category::CROSSCHECK,
                              Severity::LOW, RSs("/proc 目录不可 stat")));
        } else if (a.ino == b.ino) {
            Evidence e = makePass(RSs("X.PROC_INODE"), Category::CROSSCHECK,
                                  RSs("/proc/self 与 /proc/<pid> 同 inode"));
            e.addVector(RSs("ino"), std::to_string(a.ino));
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("X.PROC_INODE"), Category::CROSSCHECK,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("/proc/self 与 /proc/<pid> 不同 inode"));
            e.addVector(RSs("self_ino"), std::to_string(a.ino));
            e.addVector(RSs("pid_ino"), std::to_string(b.ino));
            r.add(std::move(e));
        }
    }
}

}  // namespace rs