// check_threads.cpp — 进程 / 线程层深度（M5）
//
// 为什么单开一层：
//   大部分 detector 只扫 /proc/self/maps 和 fd，不看线程。
//   但 Frida 会注入 gmain / gdbus / pool-frida 线程，
//   注入框架的 loader 线程也常有固定名字；
//   而隐藏型 root 为了隐藏自身，会从 getdents 里摘掉线程目录条目，
//   与 status 里的 Threads 字段对不上。
#include "rs_checks.h"

#include <cstdlib>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

bool isDigits(const std::string& s) {
    if (s.empty()) return false;
    for (const char c : s) {
        if (c < '0' || c > '9') return false;
    }
    return true;
}

}  // namespace

void checkThreads(ScanReport& r) {
    auto tasks = listDirRaw(RSs("/proc/self/task"));

    // ---------------------------------------------------------- D.TASK_COUNT
    int nTask = 0;
    for (const auto& d : tasks) {
        if (isDigits(d.name)) ++nTask;
    }

    int nStatus = 0;
    {
        const auto st = readFile(RSs("/proc/self/status"), 64 * 1024);
        for (const auto& line : split(st, '\n')) {
            if (startsWith(line, RSs("Threads:"))) {
                nStatus = std::atoi(line.c_str() + 8);
                break;
            }
        }
    }

    // v2.22.45（审查 A P1-9）：getdents 与 status 不是原子快照，线程
    // 创建/退出瞬间 ±1 很常见，旧实现差 1 即 HIGH HIT。现：差 <=1 按
    // 瞬时差异处理；差 >=2 先重采样一次，两次都差 >=2 才判 HIT。
    bool decided = false;
    if (nTask > 0 && nStatus > 0 &&
        (nTask - nStatus >= 2 || nStatus - nTask >= 2)) {
        int nTask2 = 0;
        for (const auto& d : listDirRaw(RSs("/proc/self/task"))) {
            if (isDigits(d.name)) ++nTask2;
        }
        int nStatus2 = 0;
        const auto st2 = readFile(RSs("/proc/self/status"), 64 * 1024);
        for (const auto& line : split(st2, '\n')) {
            if (startsWith(line, RSs("Threads:"))) {
                nStatus2 = std::atoi(line.c_str() + 8);
                break;
            }
        }
        if (nTask2 > 0 && nStatus2 > 0 &&
            (nTask2 - nStatus2 >= 2 || nStatus2 - nTask2 >= 2)) {
            Evidence e = makeHit(RSs("D.TASK_COUNT"), Category::CROSSCHECK,
                                 Severity::HIGH, Confidence::HIGH,
                                 RSs("线程枚举与 status 字段持续不一致"));
            e.addVector(RSs("task_dir"), std::to_string(nTask2));
            e.addVector(RSs("status"), std::to_string(nStatus2));
            e.detail = RSs("两次采样两视角线程数都差 >=2 → 有线程从 getdents 中被摘除");
            r.add(std::move(e));
            decided = true;
        } else if (nTask2 > 0 && nStatus2 > 0) {
            nTask = nTask2;
            nStatus = nStatus2;
        }
    }

    if (!decided) {
        if (nTask <= 0 || nStatus <= 0) {
            r.add(makeUnknown(RSs("D.TASK_COUNT"), Category::MEMORY,
                              Severity::LOW, RSs("线程枚举不可用")));
        } else if (nTask == nStatus || nTask - nStatus == 1 ||
                   nStatus - nTask == 1) {
            Evidence e = makePass(RSs("D.TASK_COUNT"), Category::MEMORY,
                                  nTask == nStatus
                                      ? RSs("线程枚举与 status 一致")
                                      : RSs("线程枚举与 status 差 1（瞬时差异）"));
            e.addVector(RSs("task_dir"), std::to_string(nTask));
            e.addVector(RSs("status"), std::to_string(nStatus));
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("D.TASK_COUNT"), Category::CROSSCHECK,
                                 Severity::HIGH, Confidence::HIGH,
                                 RSs("线程枚举与 status 字段不一致"));
            e.addVector(RSs("task_dir"), std::to_string(nTask));
            e.addVector(RSs("status"), std::to_string(nStatus));
            e.detail = RSs("两视角线程数不同 → 有线程从 getdents 中被摘除");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- D.THREAD_NAME
    static const std::vector<std::string> benign = {
        RSs("zygote"), RSs("jit"), RSs("gc"), RSs("signal"),
    };
    static const std::vector<std::string> bad = {
        RSs("frida"), RSs("gmain"), RSs("gdbus"), RSs("linjector"),
        RSs("pool-frida"), RSs("shamiko"), RSs("zygisk"),
        RSs("xposed"), RSs("substrate"),
    };

    std::vector<std::string> found;
    std::string sample;
    for (const auto& d : tasks) {
        if (!isDigits(d.name)) continue;
        const auto comm = trim(readFile(
            RSs("/proc/self/task/") + d.name + RSs("/comm"), 4096));
        if (comm.empty()) continue;
        const auto low = toLower(comm);
        for (const auto& k : bad) {
            if (contains(low, k) && !containsAny(low, benign)) {
                found.push_back(d.name + RSs(":") + comm);
                break;
            }
        }
        if (sample.size() < 120) {
            if (!sample.empty()) sample += RSs(", ");
            sample += comm;
        }
    }

    if (!found.empty()) {
        Evidence e = makeHit(RSs("D.THREAD_NAME"), Category::MEMORY,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("线程名命中注入框架特征"));
        for (const auto& f : found) e.addVector(RSs("thread"), f);
        e.detail = RSs("Frida / 注入器会留下固定的线程名");
        r.add(std::move(e));
    } else {
        Evidence e = makePass(RSs("D.THREAD_NAME"), Category::MEMORY,
                              RSs("线程名无注入特征"));
        e.addVector(RSs("sample"), sample);
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- D.ANON_FD
    // anon_inode 本身合法（binder / eventfd / sync_file / ion / dmabuf / ashmem）
    // 只有「既不是这些、又是无名匿名 inode」的才可疑
    static const std::vector<std::string> knownAnon = {
        RSs("binder"), RSs("eventfd"), RSs("eventpoll"), RSs("sync_file"),
        RSs("ion"), RSs("dmabuf"), RSs("ashmem"), RSs("[io_uring]"),
        RSs("inotify"), RSs("signalfd"), RSs("timerfd"), RSs("pidfd"),
        RSs("userfaultfd"), RSs("memfd:jit-cache"), RSs("memfd:jit-zygote"),
        RSs("memfd:jit-profile"), RSs("memfd:gralloc"),
        // MTK / 高通的图形栈会注册私有 anon_inode 名字
        RSs("malitl"), RSs("mali"), RSs("v4l2"), RSs("vb2"),
        RSs("dma_heap"), RSs("dma-buf"), RSs("gpu"), RSs("mali_"),
        RSs("kgsl"), RSs("fdpp"), RSs("sde"), RSs("mdss"),
        RSs("gralloc"), RSs("gralloc_extra"), RSs("dmabuf_"),
    };

    auto fds = listDirRaw(RSs("/proc/self/fd"));
    int anonOdd = 0;
    std::vector<std::string> oddList;
    for (const auto& d : fds) {
        if (!isDigits(d.name)) continue;
        const auto tgt = readLink(RSs("/proc/self/fd/") + d.name);
        if (!contains(tgt, RSs("anon_inode:"))) continue;
        if (containsAny(tgt, knownAnon)) continue;
        ++anonOdd;
        if (oddList.size() < 6) oddList.push_back(d.name + RSs("->") + tgt);
    }

    if (anonOdd == 0) {
        r.add(makePass(RSs("D.ANON_FD"), Category::MEMORY,
                       RSs("无未知匿名 inode")));
    } else {
        Evidence e = makeHit(RSs("D.ANON_FD"), Category::MEMORY,
                             Severity::LOW, Confidence::LOW,
                             RSs("存在未知匿名 inode 描述符"));
        e.addVector(RSs("count"), std::to_string(anonOdd));
        for (const auto& s : oddList) e.addVector(RSs("fd"), s);
        e.fp_note = RSs("新版内核会为更多子系统引入 anon_inode 名称，"
                        "单一命中不构成结论");
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- D.TASK_NS
    // 线程级命名空间：注入器常以 setns 把自己的线程放进目标 ns
    std::vector<std::string> nsSet;
    {
        auto items = listDirRaw(RSs("/proc/self/task"));
        for (const auto& d : items) {
            if (!isDigits(d.name)) continue;
            const auto n = readLink(RSs("/proc/self/task/") + d.name + RSs("/ns/pid"));
            if (n.empty()) continue;
            if (nsSet.size() < 4) nsSet.push_back(d.name + RSs("=") + n);
            break;  // 只看第一个，避免噪音
        }
        // v2.22.66：任务目录枚举拿不到时，直接读进程自身的 ns 链接
        // （同一个数据，省掉被拦的那步枚举）。
        if (nsSet.empty()) {
            const auto self = readLink(RSs("/proc/self/ns/pid"));
            if (!self.empty()) nsSet.push_back(RSs("self=") + self);
        }
    }
    if (!nsSet.empty()) {
        Evidence e = makePass(RSs("D.TASK_NS"), Category::MEMORY,
                              RSs("线程命名空间可读"));
        for (const auto& s : nsSet) e.addVector(RSs("ns"), s);
        r.add(std::move(e));
    } else {
        r.add(makeUnknown(RSs("D.TASK_NS"), Category::MEMORY, Severity::LOW,
                          RSs("线程 ns 链接不可读")));
    }
}

}  // namespace rs