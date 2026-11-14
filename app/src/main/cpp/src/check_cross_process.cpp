// check_cross_process.cpp — 父子进程交叉验证（M2）
//
// 反绕过核心：
//   Zygisk / Frida 的注入通常只发生在「特定进程」。
//   fork 一个子进程做同样的观测，若父子结果不一致，
//   就说明存在进程级 hook —— 这是最难伪造的证据。
//
// 实现约束：
//   子进程只调用 raw syscall（不 malloc、不碰锁），
//   避免多线程 fork 引发的死锁。
#include "rs_checks.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <unistd.h>
#include <sys/wait.h>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

// 三字节观测：version / stat / data_adb
struct Probe3 {
    char versionReadable;   // '1' / '0'
    char statReadable;      // '1' / '0'
    char dataAdb;           // 'V' 可见 / 'E' EACCES / 'N' 不存在
};

// 纯 syscall 实现，可在 fork 后的子进程里安全调用
Probe3 probeNoAlloc() {
    Probe3 p{'0', '0', 'N'};

    int fd = sys::openat_("/proc/version", 0);
    if (fd >= 0) {
        p.versionReadable = '1';
        sys::close_(fd);
    }

    fd = sys::openat_("/proc/stat", 0);
    if (fd >= 0) {
        p.statReadable = '1';
        sys::close_(fd);
    }

    if (sys::faccessat_("/data/adb", 0, 0) == 0) {
        p.dataAdb = 'V';
    } else if (errno == EACCES || errno == EPERM) {
        p.dataAdb = 'E';
    } else {
        p.dataAdb = 'N';
    }

    return p;
}

}  // namespace

void checkCrossProcess(ScanReport& r) {
    // 只做一次，避免每次检测都 fork
    static bool done = false;
    if (done) return;
    done = true;

    int fds[2];
    if (pipe(fds) != 0) {
        r.add(makeUnknown(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                          Severity::LOW, RSs("pipe() 失败，跳过子进程交叉验证")));
        return;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        r.add(makeUnknown(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                          Severity::LOW, RSs("fork() 失败，跳过子进程交叉验证")));
        return;
    }

    if (pid == 0) {
        // ---- 子进程：只做 raw syscall ----
        close(fds[0]);
        Probe3 c = probeNoAlloc();
        char buf[3] = {c.versionReadable, c.statReadable, c.dataAdb};
        ssize_t w = write(fds[1], buf, sizeof(buf));
        (void)w;
        _exit(0);
    }

    // ---- 父进程 ----
    close(fds[1]);
    char buf[4] = {0, 0, 0, 0};
    ssize_t n = read(fds[0], buf, 3);
    close(fds[0]);

    int status = 0;
    waitpid(pid, &status, 0);

    if (n != 3) {
        r.add(makeUnknown(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                          Severity::LOW, RSs("子进程未返回观测结果")));
        return;
    }

    Probe3 parent = probeNoAlloc();

    const bool same = (buf[0] == parent.versionReadable) &&
                      (buf[1] == parent.statReadable) &&
                      (buf[2] == parent.dataAdb);

    std::string childStr;
    childStr += buf[0]; childStr += buf[1]; childStr += buf[2];

    std::string parentStr;
    parentStr += parent.versionReadable;
    parentStr += parent.statReadable;
    parentStr += parent.dataAdb;

    if (same) {
        Evidence e = makePass(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                              RSs("父子进程观测一致"));
        e.addVector(RSs("child"), childStr);
        e.addVector(RSs("parent"), parentStr);
        e.detail = RSs("v/s/a = version可读/stat可读/data_adb(V=可见 E=EACCES N=不存在)");
        r.add(std::move(e));
    } else {
        Evidence e = makeHit(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("父子进程观测不一致 ← 进程级 hook"));
        e.addVector(RSs("child"), childStr);
        e.addVector(RSs("parent"), parentStr);
        e.detail = RSs("同一 uid、同一 SELinux 域，两个进程看到不同结果，"
                       "只可能是 hook 只作用在其中一个进程上");
        e.fp_note = RSs("极少数内核在 fork 后短暂继承不同的挂载视图，"
                        "若出现请连续跑三次确认可复现");
        r.add(std::move(e));
    }
}

}  // namespace rs