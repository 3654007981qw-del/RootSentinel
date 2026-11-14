// check_proc_anomaly.cpp — /proc 自洽性与时间基线（M6）
//
// 思路：/proc 是由内核动态生成的，内部各文件之间存在强数学关系。
// 隐藏方案通常只改其中一个（比如伪造 /proc/version），
// 但这些关系没改 → 一对账就露馅。
#include "rs_checks.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <string>
#include <vector>

#ifndef CLOCK_BOOTTIME
#define CLOCK_BOOTTIME 7
#endif

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

void checkProcAnomaly(ScanReport& r) {

    // ---------------------------------------------------------- X.STAT_FIELDS
    // /proc/self/stat 自 Linux 2.6 起就是 52 个字段。
    // 字段数变少 → 内核被裁剪或伪造；字段数凭空变多 → 被注入内容。
    {
        const auto stat = readFile(RSs("/proc/self/stat"), 8192);
        const auto rp = stat.rfind(')');

        if (stat.empty() || rp == std::string::npos) {
            r.add(makeUnknown(RSs("X.STAT_FIELDS"), Category::CROSSCHECK,
                              Severity::LOW, RSs("/proc/self/stat 不可读")));
        } else {
            const auto rest = stat.substr(rp + 2);
            const int nFields = 2 + (int)split(rest, ' ').size();

            if (nFields >= 52) {
                Evidence e = makePass(RSs("X.STAT_FIELDS"), Category::CROSSCHECK,
                                      RSs("stat 字段数完整"));
                e.addVector(RSs("fields"), std::to_string(nFields));
                r.add(std::move(e));
            } else if (nFields >= 44) {
                Evidence e = makeUnknown(RSs("X.STAT_FIELDS"), Category::CROSSCHECK,
                                         Severity::LOW,
                                         RSs("stat 字段数偏少"));
                e.addVector(RSs("fields"), std::to_string(nFields));
                e.detail = RSs("旧内核（< 3.x）字段数会更少，属正常差异");
                r.add(std::move(e));
            } else {
                Evidence e = makeHit(RSs("X.STAT_FIELDS"), Category::CROSSCHECK,
                                     Severity::MEDIUM, Confidence::MEDIUM,
                                     RSs("stat 字段数异常偏少"));
                e.addVector(RSs("fields"), std::to_string(nFields));
                e.detail = RSs("字段被裁剪 → /proc/self/stat 可能是伪造的");
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- X.BTIME
    // btime（/proc/stat）应与「当前时间 − uptime」一致。
    // 单独伪造 /proc/version 或 /proc/uptime 而不动 btime，会对不上。
    {
        const auto statData = readFile(RSs("/proc/stat"), 512 * 1024);
        const auto uptime   = readFile(RSs("/proc/uptime"), 4096);

        long long btime = 0;
        for (const auto& line : split(statData, '\n')) {
            if (startsWith(line, RSs("btime "))) {
                btime = std::atoll(line.c_str() + 6);
                break;
            }
        }

        const double up = std::atof(uptime.c_str());
        const long long now = (long long)std::time(nullptr);

        if (btime <= 0 || up <= 0.0) {
            r.add(makeUnknown(RSs("X.BTIME"), Category::CROSSCHECK,
                              Severity::LOW, RSs("btime / uptime 不可读")));
        } else {
            long long diff = (now - (long long)up) - btime;
            if (diff < 0) diff = -diff;

            if (diff <= 60) {
                Evidence e = makePass(RSs("X.BTIME"), Category::CROSSCHECK,
                                      RSs("btime 与 uptime 推算一致"));
                e.addVector(RSs("btime"), std::to_string(btime));
                e.addVector(RSs("derived"), std::to_string(now - (long long)up));
                e.addVector(RSs("diff_s"), std::to_string(diff));
                r.add(std::move(e));
            } else if (diff <= 3600) {
                Evidence e = makeUnknown(RSs("X.BTIME"), Category::CROSSCHECK,
                                         Severity::LOW,
                                         RSs("btime 推算偏差中等"));
                e.addVector(RSs("diff_s"), std::to_string(diff));
                e.detail = RSs("系统刚校时或 NTP 跳变时会出现，"
                               "重启后马上扫描属正常");
                r.add(std::move(e));
            } else {
                Evidence e = makeHit(RSs("X.BTIME"), Category::CROSSCHECK,
                                     Severity::MEDIUM, Confidence::MEDIUM,
                                     RSs("btime 与 uptime 推算严重不符"));
                e.addVector(RSs("btime"), std::to_string(btime));
                e.addVector(RSs("derived"), std::to_string(now - (long long)up));
                e.addVector(RSs("diff_s"), std::to_string(diff));
                e.detail = RSs("/proc/stat 或 /proc/uptime 有一个被改过");
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- X.CLOCK_SKEW
    // CLOCK_BOOTTIME 与 /proc/uptime 在正常内核上必须几乎相等
    // （两者都包含 suspend 时间，差值是内核启动到 wall-to-monotonic
    //  切换的那点时间，通常 < 1 秒）。
    {
        struct timespec ts {};
        if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) {
            r.add(makeUnknown(RSs("X.CLOCK_SKEW"), Category::CROSSCHECK,
                              Severity::LOW, RSs("CLOCK_BOOTTIME 不可用")));
        } else {
            const auto uptime = readFile(RSs("/proc/uptime"), 4096);
            const double up = std::atof(uptime.c_str());

            if (up <= 0.0) {
                r.add(makeUnknown(RSs("X.CLOCK_SKEW"), Category::CROSSCHECK,
                                  Severity::LOW, RSs("/proc/uptime 不可读")));
            } else {
                const double boot = (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
                const double skew = std::fabs(boot - up);

                char buf[64];
                std::snprintf(buf, sizeof(buf), "%.3f", skew);

                if (skew <= 2.0) {
                    Evidence e = makePass(RSs("X.CLOCK_SKEW"), Category::CROSSCHECK,
                                          RSs("CLOCK_BOOTTIME 与 uptime 一致"));
                    e.addVector(RSs("skew_s"), buf);
                    r.add(std::move(e));
                } else if (skew <= 60.0) {
                    Evidence e = makeUnknown(RSs("X.CLOCK_SKEW"), Category::CROSSCHECK,
                                             Severity::LOW,
                                             RSs("时钟基线有偏差"));
                    e.addVector(RSs("skew_s"), buf);
                    r.add(std::move(e));
                } else {
                    Evidence e = makeHit(RSs("X.CLOCK_SKEW"), Category::CROSSCHECK,
                                         Severity::HIGH, Confidence::HIGH,
                                         RSs("CLOCK_BOOTTIME 与 /proc/uptime 严重偏离"));
                    e.addVector(RSs("boottime"), buf);
                    e.addVector(RSs("diff_s"), buf);
                    e.detail = RSs("时间被虚拟化，或 /proc/uptime 被单独改写");
                    e.fp_note = RSs("深度休眠唤醒后个别内核存在秒级偏差，"
                                    "但不会到分钟级");
                    r.add(std::move(e));
                }
            }
        }
    }

    // ---------------------------------------------------------- X.LOADAVG
    {
        const auto la = trim(readFile(RSs("/proc/loadavg"), 4096));
        if (la.empty()) {
            r.add(makeUnknown(RSs("X.LOADAVG"), Category::CROSSCHECK,
                              Severity::LOW, RSs("/proc/loadavg 不可读")));
        } else {
            const auto f = split(la, ' ');
            if (f.size() != 5) {
                Evidence e = makeHit(RSs("X.LOADAVG"), Category::CROSSCHECK,
                                     Severity::LOW, Confidence::LOW,
                                     RSs("loadavg 字段数异常"));
                e.addVector(RSs("raw"), la.substr(0, 64));
                e.addVector(RSs("fields"), std::to_string(f.size()));
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("X.LOADAVG"), Category::CROSSCHECK,
                                      RSs("loadavg 格式正常"));
                e.addVector(RSs("raw"), la.substr(0, 64));
                r.add(std::move(e));
            }
        }
    }

    // ------------------------------------------------------ X.PROC_SELECTIVE
    // 这是本轮最有价值的推理：
    //   /proc/uptime、/proc/loadavg、/proc/stat 在原厂 Android 上都是 0444，
    //   任何 App 都能读；/proc/self/* 当然也能读。
    //
    //   如果「全局文件不可读」而「自身文件可读」，
    //   说明有人在 /proc 上做了**选择性的访问控制** ——
    //   这不是 SELinux 的默认策略，而是隐藏方案的定制规则。
    //
    //   这种「过度隐藏」比不藏更容易被判定为异常。
    {
        int blocked = 0;
        std::vector<std::string> blockedList;

        if (readFile(RSs("/proc/uptime"), 4096).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/uptime"));
        }
        if (readFile(RSs("/proc/loadavg"), 4096).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/loadavg"));
        }
        if (readFile(RSs("/proc/stat"), 512 * 1024).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/stat"));
        }
        if (readFile(RSs("/proc/version"), 8192).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/version"));
        }
        if (readFile(RSs("/proc/meminfo"), 64 * 1024).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/meminfo"));
        }
        if (readFile(RSs("/proc/sys/kernel/tainted"), 4096).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/sys/kernel/tainted"));
        }
        if (readFile(RSs("/proc/sys/kernel/kptr_restrict"), 4096).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/sys/kernel/kptr_restrict"));
        }
        if (readFile(RSs("/proc/sys/kernel/dmesg_restrict"), 4096).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/sys/kernel/dmesg_restrict"));
        }
        if (readFile(RSs("/proc/sys/kernel/unprivileged_bpf_disabled"), 4096).empty()) {
            ++blocked;
            blockedList.push_back(RSs("/proc/sys/kernel/unprivileged_bpf_disabled"));
        }

        const bool selfOk = !readFile(RSs("/proc/self/status"), 64 * 1024).empty();
        const int total = 9;

        if (blocked == 0) {
            Evidence e = makePass(RSs("X.PROC_SELECTIVE"), Category::CROSSCHECK,
                                  RSs("/proc 全局文件均可读（正常）"));
            e.addVector(RSs("checked"), std::to_string(total));
            r.add(std::move(e));
        } else if (selfOk && blocked >= 2) {
            Evidence e = makeHit(RSs("X.PROC_SELECTIVE"), Category::CROSSCHECK,
                                 Severity::HIGH, Confidence::HIGH,
                                 RSs("/proc 被选择性隐藏"));
            e.addVector(RSs("blocked"), std::to_string(blocked) + RSs("/") +
                                        std::to_string(total));
            e.addVector(RSs("self_readable"), "1");
            for (const auto& p : blockedList) e.addVector(RSs("denied"), p);
            e.detail = RSs("全局 /proc 文件原厂是 0444 任何 App 都能读；"
                           "这里被单独拒绝，而 /proc/self 仍可读 → "
                           "存在针对性的访问控制规则");
            e.fp_note = RSs("新版 Android 的个别 SELinux 策略可能收紧，"
                            "需对比同版本原厂机");
            r.add(std::move(e));
        } else if (!selfOk) {
            r.add(makeUnknown(RSs("X.PROC_SELECTIVE"), Category::CROSSCHECK,
                              Severity::LOW,
                              RSs("/proc 整体不可读")));
        } else {
            Evidence e = makeUnknown(RSs("X.PROC_SELECTIVE"), Category::CROSSCHECK,
                                     Severity::LOW,
                                     RSs("部分 /proc 文件不可读"));
            e.addVector(RSs("blocked"), std::to_string(blocked));
            for (const auto& p : blockedList) e.addVector(RSs("denied"), p);
            r.add(std::move(e));
        }
    }
}

}  // namespace rs