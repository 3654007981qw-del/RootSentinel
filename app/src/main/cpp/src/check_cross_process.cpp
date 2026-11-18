// check_cross_process.cpp — 父子进程交叉验证（M2）
//
// 反绕过核心：
//   Zygisk / Frida 的注入通常只发生在「特定进程」。
//   fork 一个子进程做同样的观测，若父子结果不一致，
//   就说明存在进程级 hook —— 这是最难伪造的证据。
//
// v2.22.82（联合审查 N2）定位修正：fork 子进程会继承父进程
// 的地址空间与已注入状态，它不是一个真正独立启动的进程。
// 本项只作启发式互证（证据带 heuristic 标记）；主跨进程通道
// 是 Java 侧 CrossProcessProbe（:probe 独立进程服务）。
//
// 实现约束：
//   子进程只调用 raw syscall（不 malloc、不碰锁），
//   避免多线程 fork 引发的死锁。
#include "rs_checks.h"

#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <poll.h>
#include <unistd.h>
#include <atomic>
#include <mutex>
#include <vector>
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
    // 只做一次，避免每次检测都 fork；成功结果缓存，后续扫描重放缓存。
    // v2.22.45（审查 A P2-20）：改用 atomic —— 当前 nativeScan 有
    // 互斥串行化所以旧 bool 写法没出过问题，但放开并发后即是数据
    // 竞争，先把隐患堵上。
    // v2.22.58（第九轮审计 P3-02）：旧写法在 pipe/fork 之前就把 done
    // 置真，首次瞬时失败会永久关掉这项检查，后续扫描连 UNKNOWN 都
    // 不再产生。现在只有拿到完整观测才缓存并置 done；失败只给本次
    // 扫描补一条 UNKNOWN，下次扫描重试。
    static std::atomic<bool> done{false};
    static std::atomic<long long> cachedAtMs{0};
    static std::mutex cacheMutex;
    static std::vector<Evidence> cachedEvidence;
    static std::vector<CrossCheck> cachedCrosses;
    // v2.22.82（联合审查 N2）：缓存加 60 秒有效期。旧版进程生命
    // 周期内永不过期，后续扫描一直重放首次观测，节点状态变化
    // （如扫描期间环境改变）完全看不到。过期则重新 fork 观测。
    const long long nowMs = (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    if (done.load(std::memory_order_acquire) &&
        nowMs - cachedAtMs.load(std::memory_order_acquire) <= 60000) {
        std::lock_guard<std::mutex> lk(cacheMutex);
        for (const auto& cc : cachedCrosses) r.addCross(cc);
        for (const auto& e : cachedEvidence) r.add(e);
        return;
    }

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
    // v2.22.82（联合审查 N2）：旧版裸 read() 无超时——子进程若
    // 卡死（被注入环境拖住、写管异常），父进程会连同整轮扫描
    // 一起挂死。改 poll 限时读满 3 字节；超时杀子进程并出
    // UNKNOWN（不落缓存，下轮重试，沿用 P3-02 语义）。
    ssize_t got = 0;
    bool timedOut = false;
    while (got < 3) {
        pollfd pfd{};
        pfd.fd = fds[0];
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 2000);
        if (pr == 0) { timedOut = true; break; }
        if (pr < 0) {
            if (errno == EINTR) continue;
            break;
        }
        ssize_t n = read(fds[0], buf + got, (size_t)(3 - got));
        if (n <= 0) break;
        got += n;
    }
    close(fds[0]);

    if (timedOut) {
        kill(pid, SIGKILL);
        int st2 = 0;
        waitpid(pid, &st2, 0);
        r.add(makeUnknown(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                          Severity::LOW, RSs("子进程观测超时，跳过子进程交叉验证")));
        return;
    }

    int status = 0;
    waitpid(pid, &status, 0);

    // 子进程异常退出（被杀/崩溃）时管道里的数据不可信，一并作废。
    if (got != 3 || !WIFEXITED(status) || WEXITSTATUS(status) != 0) {
        r.add(makeUnknown(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                          Severity::LOW, RSs("子进程未返回完整观测结果")));
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

    // v2.22.45（审查 B P2-04）：结构化 CrossCheck 生产者（证据本体
    // 仍是下方 X.CHILD_PROC；这里只登记 crosses 供评分层使用）。
    std::vector<CrossCheck> crosses;
    {
        CrossCheck cc;
        cc.id = RSs("X.CHILD_PROC");
        cc.subject = RSs("父子进程三点观测（version/stat/data_adb）");
        cc.observe(RSs("child"), childStr);
        cc.observe(RSs("parent"), parentStr);
        cc.consistent = same;
        crosses.push_back(cc);
    }

    std::vector<Evidence> out;
    if (same) {
        Evidence e = makePass(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                              RSs("父子进程观测一致"));
        e.heuristic = true;  // N2：fork 继承注入状态，只作启发式互证
        e.addVector(RSs("child"), childStr);
        e.addVector(RSs("parent"), parentStr);
        e.detail = RSs("v/s/a = version可读/stat可读/data_adb(V=可见 E=EACCES N=不存在)");
        out.push_back(std::move(e));
    } else {
        Evidence e = makeHit(RSs("X.CHILD_PROC"), Category::CROSSCHECK,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("父子进程观测不一致 ← 进程级 hook"));
        e.heuristic = true;  // N2：同上，启发式互证（主通道为 Java 独立进程探针）
        e.addVector(RSs("child"), childStr);
        e.addVector(RSs("parent"), parentStr);
        e.detail = RSs("同一 uid、同一 SELinux 域，两个进程看到不同结果，"
                       "只可能是 hook 只作用在其中一个进程上");
        e.fp_note = RSs("极少数内核在 fork 后短暂继承不同的挂载视图，"
                        "若出现请连续跑三次确认可复现");
        out.push_back(std::move(e));
    }

    // 观测完整才落缓存并置 done（P3-02）；60 秒内扫描直接重放
    // 缓存，过期后下轮重新观测（N2）。
    {
        std::lock_guard<std::mutex> lk(cacheMutex);
        cachedEvidence = out;
        cachedCrosses = crosses;
    }
    cachedAtMs.store(nowMs, std::memory_order_release);
    done.store(true, std::memory_order_release);
    for (const auto& cc : crosses) r.addCross(cc);
    for (const auto& e : out) r.add(e);
}

}  // namespace rs