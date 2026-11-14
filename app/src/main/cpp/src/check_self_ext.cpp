// check_self_ext.cpp — 证书存储 / seccomp / 自身完整性 / jiffies（M9）
#include "rs_checks.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

int countDir(const std::string& dir) {
    auto es = listDirRaw(dir);
    int n = 0;
    for (const auto& d : es) {
        if (d.name.empty() || d.name[0] == '.') continue;
        ++n;
    }
    return n;
}

}  // namespace

void checkSelfExt(ScanReport& r) {

    // ------------------------------------------------------------ I.CA_STORE
    // 抓包必须先往系统信任库塞入自定义 CA。
    // 系统库在 /system/etc/security/cacerts（只读），
    // 追加的证书在 /data/misc/keychain/cacerts-added。
    {
        const int sysCa = countDir(RSs("/system/etc/security/cacerts"));
        const int added = countDir(RSs("/data/misc/keychain/cacerts-added"));

        std::vector<std::string> notes;
        if (sysCa > 0) notes.push_back(RSs("sys_ca=") + std::to_string(sysCa));
        if (added > 0) notes.push_back(RSs("user_ca=") + std::to_string(added));

        if (sysCa == 0 && added == 0) {
            r.add(makeUnknown(RSs("I.CA_STORE"), Category::INTEGRITY,
                              Severity::LOW, RSs("CA 目录均不可枚举")));
        } else if (added > 0) {
            Evidence e = makeHit(RSs("I.CA_STORE"), Category::INTEGRITY,
                                 Severity::HIGH, Confidence::HIGH,
                                 RSs("系统信任库被追加了用户 CA"));
            for (const auto& s : notes) e.addVector(RSs("ca"), s);
            e.detail = RSs("用户 CA 会作为系统根证书被信任 —— "
                           "这是 HTTPS 中间人抓包的前提条件");
            e.fp_note = RSs("企业 MDM / 内网 CA 场景属正常");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("I.CA_STORE"), Category::INTEGRITY,
                                  RSs("系统信任库未被追加"));
            for (const auto& s : notes) e.addVector(RSs("ca"), s);
            r.add(std::move(e));
        }
    }

    // ------------------------------------------------- I.SECCOMP_FILTERS
    // Android 的 zygote 会装 seccomp-bpf 过滤器。
    // Seccomp=2 且 Seccomp_filters>=1 是正常态；0 说明沙箱被摘掉。
    {
        const auto status = readFile(RSs("/proc/self/status"), 64 * 1024);
        std::string mode, filters;

        for (const auto& l : split(status, '\n')) {
            if (startsWith(l, RSs("Seccomp:"))) {
                mode = trim(l.substr(8));
            } else if (startsWith(l, RSs("Seccomp_filters:"))) {
                filters = trim(l.substr(16));
            }
        }

        if (mode.empty()) {
            r.add(makeUnknown(RSs("I.SECCOMP_FILTERS"), Category::INTEGRITY,
                              Severity::LOW, RSs("Seccomp 字段缺失")));
        } else if (mode == RSs("2") && !filters.empty() && filters != RSs("0")) {
            Evidence e = makePass(RSs("I.SECCOMP_FILTERS"), Category::INTEGRITY,
                                  RSs("seccomp 过滤器已安装"));
            e.addVector(RSs("mode"), mode);
            e.addVector(RSs("filters"), filters);
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("I.SECCOMP_FILTERS"), Category::INTEGRITY,
                                 Severity::HIGH, Confidence::HIGH,
                                 RSs("seccomp 过滤器缺失"));
            e.addVector(RSs("mode"), mode);
            e.addVector(RSs("filters"), filters.empty() ? "?" : filters);
            e.detail = RSs("Android 为每个 App 强制装 seccomp-bpf；"
                           "filters=0 说明沙箱被移除");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------- I.SELF_SO_PATH
    // 自己的 so 必须从 APK 内映射（路径以 base.apk!/lib/ 形式出现在
    // /proc/self/maps 里，或指向 /data/app/.../lib/...）。
    {
        const auto maps = readFile(RSs("/proc/self/maps"), 8u << 20);
        std::string soPath;
        for (const auto& l : split(maps, '\n')) {
            if (contains(l, RSs("librootsentinel"))) {
                const auto sp = l.find(RSs("/"));
                if (sp != std::string::npos) {
                    soPath = trim(l.substr(sp));
                    break;
                }
            }
        }

        if (soPath.empty()) {
            r.add(makeUnknown(RSs("I.SELF_SO_PATH"), Category::INTEGRITY,
                              Severity::LOW, RSs("未找到自身 so 映射")));
        } else {
            const bool normal =
                contains(soPath, RSs("base.apk")) ||
                contains(soPath, RSs("/data/app/"));
            if (normal) {
                Evidence e = makePass(RSs("I.SELF_SO_PATH"), Category::INTEGRITY,
                                      RSs("自身 so 从 APK 内加载"));
                e.addVector(RSs("path"), soPath.substr(0, 100));
                r.add(std::move(e));
            } else {
                Evidence e = makeHit(RSs("I.SELF_SO_PATH"), Category::INTEGRITY,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("自身 so 不在 APK 内"));
                e.addVector(RSs("path"), soPath.substr(0, 100));
                e.detail = RSs("检测器自己的 native 库被替换或旁加载");
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- X.JIFFIES
    // /proc/stat 的 cpu 行是「所有 cpu 的时间片总和」，单位 USER_HZ（100）。
    // 换算成秒后应约等于 uptime × 核数。
    {
        const auto stat = readFile(RSs("/proc/stat"), 512 * 1024);
        const auto uptime = readFile(RSs("/proc/uptime"), 4096);

        long long totalJiffies = 0;
        int cpuCount = 0;
        for (const auto& l : split(stat, '\n')) {
            if (!startsWith(l, RSs("cpu"))) continue;
            if (l.size() > 3 && l[3] >= '0' && l[3] <= '9') {
                ++cpuCount;
                continue;  // cpu0/cpu1... 行不计
            }
            // 汇总行 "cpu  user nice system idle ..."
            for (const auto& tok : split(l.substr(3), ' ')) {
                if (tok.empty()) continue;
                totalJiffies += std::atoll(tok.c_str());
            }
        }

        const double up = std::atof(uptime.c_str());

        if (totalJiffies <= 0 || up <= 0.0) {
            r.add(makeUnknown(RSs("X.JIFFIES"), Category::CROSSCHECK,
                              Severity::LOW, RSs("stat / uptime 不可读")));
        } else {
            const double totalSec = (double)totalJiffies / 100.0;
            const int cores = cpuCount > 0 ? cpuCount : 1;
            const double perCore = totalSec / (double)cores;
            const double delta = perCore - up;
            const double absDelta = delta < 0 ? -delta : delta;

            char buf[48];
            std::snprintf(buf, sizeof(buf), "%.0f", perCore);

            if (absDelta > up * 0.5 + 600.0) {
                Evidence e = makeHit(RSs("X.JIFFIES"), Category::CROSSCHECK,
                                     Severity::MEDIUM, Confidence::LOW,
                                     RSs("jiffies 与 uptime 严重不符"));
                e.addVector(RSs("per_core_sec"), buf);
                e.addVector(RSs("uptime"), std::to_string((long long)up));
                e.addVector(RSs("cores"), std::to_string(cores));
                e.detail = RSs("/proc/stat 的 cpu 时间片总和与 uptime 对不上");
                e.fp_note = RSs("CPU 热插拔会让核数变化，需多次采样判断");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("X.JIFFIES"), Category::CROSSCHECK,
                                      RSs("jiffies 与 uptime 基本一致"));
                e.addVector(RSs("per_core_sec"), buf);
                e.addVector(RSs("cores"), std::to_string(cores));
                r.add(std::move(e));
            }
        }
    }
}

}  // namespace rs