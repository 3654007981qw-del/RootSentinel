// check_selinux.cpp — F SELinux 与命名空间域
#include "rs_checks.h"

#include <unistd.h>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

void checkSelinux(ScanReport& r) {
    // ---------------------------------------------------------- 自身域
    const std::string domain = trim(readFile(RSs("/proc/self/attr/current")));
    if (domain.empty()) {
        r.add(makeUnknown(RSs("F.SELINUX_DOMAIN"), Category::SELINUX,
                          Severity::MEDIUM,
                          RSs("/proc/self/attr/current 不可读")));
    } else {
        Evidence e = makePass(RSs("F.SELINUX_DOMAIN"), Category::SELINUX,
                              RSs("SELinux 域已读取"));
        e.addVector(RSs("domain"), domain);

        // 进程不该处于这些域
        static const std::vector<std::string> badDomains = {
            RSs(":su:"), RSs(":ksu:"), RSs(":magisk:"), RSs(":u:r:shell:s0"),
        };
        bool bad = false;
        for (const auto& d : badDomains) {
            if (domain.find(d) != std::string::npos) { bad = true; break; }
        }
        if (bad) {
            e.verdict = Verdict::HIT;
            e.severity = Severity::CRITICAL;
            e.confidence = Confidence::HIGH;
            e.title = RSs("进程运行在高权限 SELinux 域");
        }
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- enforce
    const std::string enforce = trim(readFile(RSs("/sys/fs/selinux/enforce")));
    if (enforce == "1") {
        Evidence e = makePass(RSs("F.SELINUX_ENFORCE"), Category::SELINUX,
                              RSs("SELinux = Enforcing"));
        e.addVector(RSs("enforce"), enforce);
        r.add(std::move(e));
    } else if (enforce == "0") {
        Evidence e = makeHit(RSs("F.SELINUX_ENFORCE"), Category::SELINUX,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("SELinux 处于 Permissive 模式"));
        e.addVector(RSs("enforce"), enforce);
        r.add(std::move(e));
    } else {
        // 读不到：原厂 App 也可能读不到（部分机型 selinuxfs 的 enforce 是 0600）
        r.add(makeUnknown(RSs("F.SELINUX_ENFORCE"), Category::SELINUX,
                          Severity::LOW,
                          RSs("enforce 不可读（原厂部分机型同样如此）")));
    }

    // ---------------------------------------------------------- getenforce 语义
    // 注意：我们无法在此调用 Java 的 getenforce，只用文件路径判断
    // 若 enforce 可读但 policyvers 不可读，说明 selinuxfs 被部分过滤
    const std::string policyvers = trim(readFile(RSs("/sys/fs/selinux/policyvers")));
    if (!enforce.empty() && policyvers.empty()) {
        Evidence e = makeHit(RSs("F.SELINUX_FILTERED"), Category::SELINUX,
                             Severity::MEDIUM, Confidence::MEDIUM,
                             RSs("selinuxfs 部分节点不可读 ← 策略被改过"));
        e.addVector(RSs("enforce"), enforce);
        e.addVector(RSs("policyvers"), RSs("N/A"));
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- policy 二进制
    const std::string policy = readFile(RSs("/sys/fs/selinux/policy"), 4096);
    if (!policy.empty()) {
        Evidence e = makeHit(RSs("F.SELINUX_POLICY"), Category::SELINUX,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("SELinux policy 可读 ← 检测器能反编译策略"));
        e.addVector(RSs("size"), std::to_string(policy.size()));
        r.add(std::move(e));
    } else {
        // v2.22.41：不可读不能直接 PASS。
        // policy 读不到有两种完全不同的原因：
        //   · Enforcing 的原厂系统按策略拒绝读取（正常加固状态）
        //   · SELinux 未启用 / selinuxfs 状态不明（policy 同样读不到）
        // 只有在本函数前面已实测 enforce == "1"（确凿观察到 Enforcing）
        // 时，「不可读」才能解释为加固预期 → PASS；否则按 UNKNOWN，
        // 不把「没测到」翻译成「没问题」。enforce 的实际状态已由
        // F.SELINUX_ENFORCE 单独记录，这里不会掩盖 Permissive 命中。
        if (enforce == "1") {
            r.add(makePass(RSs("F.SELINUX_POLICY"), Category::SELINUX,
                           RSs("SELinux policy 不可读（Enforcing 已实测确认，符合加固预期）")));
        } else {
            r.add(makeUnknown(RSs("F.SELINUX_POLICY"), Category::SELINUX,
                              Severity::LOW,
                              RSs("SELinux policy 不可读且 enforce 状态未确认，无法判定")));
        }
    }

    // ---------------------------------------------------------- 基线节点
    // 原厂 App 应该能读这些；读不到即为过度隐藏
    const std::vector<std::string> baseline = {
        RSs("/proc/version"), RSs("/proc/stat"), RSs("/proc/uptime"),
        RSs("/proc/loadavg"), RSs("/proc/misc"), RSs("/proc/devices"),
        RSs("/proc/partitions"), RSs("/proc/filesystems"),
    };

    int denied = 0;
    std::string firstDenied;
    for (const auto& p : baseline) {
        const int st = accessState(p, R_OK);
        if (st == 1) {
            ++denied;
            if (firstDenied.empty()) firstDenied = p;
        } else if (st == 2) {
            // 不存在也算异常：原厂必有
            ++denied;
            if (firstDenied.empty()) firstDenied = p + RSs(" (ENOENT)");
        }
    }
    if (denied > 0) {
        Evidence e = makeHit(RSs("F.OVER_HIDING"), Category::SELINUX,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("原厂必可读的节点被拒绝 ← 过度隐藏"));
        e.addVector(RSs("denied"), std::to_string(denied) + "/" +
                                   std::to_string(baseline.size()));
        e.addVector(RSs("first"), firstDenied);
        e.detail = RSs("读不到比读得到更可疑：检测器一行代码即可判定异常");
        r.add(std::move(e));
    } else {
        r.add(makePass(RSs("F.OVER_HIDING"), Category::SELINUX,
                       RSs("8 个基线节点全部可读（符合原厂）")));
    }
}

}  // namespace rs