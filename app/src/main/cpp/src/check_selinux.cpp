// check_selinux.cpp — F SELinux 与命名空间域
#include "rs_checks.h"

#include <cstdint>
#include <cstring>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
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
            // v2.22.45（审查 A P1-7）：shell 域旧写法 ":u:r:shell:s0"
            // 带前导冒号，而真实上下文以 "u:r:shell:s0" 开头，永远匹配
            // 不到；改用与其他条目一致的字段夹逼写法 ":shell:"。
            RSs(":su:"), RSs(":ksu:"), RSs(":magisk:"), RSs(":shell:"),
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
    // 主通道：/sys/fs/selinux/enforce（内容 "1"/"0"）。
    // 备用通道：/sys/fs/selinux/status —— 内核给用户态的状态页
    // （libselinux 的 selinux_status_open 读的就是它），布局是 5 个
    // u32：version / sequence / enforcing / policyload / deny_unknown，
    // enforcing 在偏移 8。该节点通常 0644 世界可读；enforce 节点在
    // 部分厂商机型上是 0600、App 读不到，此时靠 status 页确认状态，
    // 不再一律落 UNKNOWN（v2.22.65，用户反馈「SELinux 状态检测不到」）。
    const std::string enforceFile = trim(readFile(RSs("/sys/fs/selinux/enforce")));
    std::string enforce = enforceFile;
    bool enforceViaStatus = false;
    bool enforceViaCanary = false;
    if (enforce != "1" && enforce != "0") {
        // v2.22.66：status 页是为 mmap 设计的接口（libselinux 的
        // selinux_status_open 就是 open + mmap），部分内核对它
        // 的 read() 不返回内容——.65 用 readFile 读在这类内核上
        // 拿不到数据（真机 .65 仍报未知）。改走 mmap，再以普通
        // 读作兜底。若 open 本身被策略拒绝，则是整棵 selinuxfs
        // 被墙，两个通道都无解，保持 UNKNOWN 不硬猜。
        const int sfd = open(RSs("/sys/fs/selinux/status").c_str(),
                             O_RDONLY | O_CLOEXEC);
        if (sfd >= 0) {
            void* page = mmap(nullptr, 4096, PROT_READ, MAP_SHARED, sfd, 0);
            if (page != MAP_FAILED) {
                uint32_t flag = 0;
                memcpy(&flag, static_cast<const char*>(page) + 8,
                       sizeof(flag));
                if (flag == 1) { enforce = "1"; enforceViaStatus = true; }
                else if (flag == 0) { enforce = "0"; enforceViaStatus = true; }
                munmap(page, 4096);
            }
            close(sfd);
        }
        if (!enforceViaStatus) {
            const std::string status = readFile(RSs("/sys/fs/selinux/status"), 64);
            if (status.size() >= 12) {
                uint32_t flag = 0;
                memcpy(&flag, status.data() + 8, sizeof(flag));
                if (flag == 1) { enforce = "1"; enforceViaStatus = true; }
                else if (flag == 0) { enforce = "0"; enforceViaStatus = true; }
            }
        }
    }
    if (enforce != "1" && enforce != "0") {
        // v2.22.67 金丝雀推断（思路来自 KernelSU 管理器的
        // SELinuxChecker：它跑 getenforce，被 Permission denied 时
        // 判定 Enforcing——无需 root。这里把前提做严谨）：
        //   Permissive 模式下 SELinux 的拒绝只记日志、不拦截，
        //   被拒的操作会成功放行。所以若某节点「按 DAC 权限位，
        //   本进程本可读」（stat 显示 other 可读、且属主不是自己）
        //   却仍被 EACCES/EPERM 实际拦下，拦截只可能来自正在
        //   生效的 MAC —— 即 Enforcing（至少对本应用域成立）。
        //   DAC 本身不给读的节点不参与推断，避免把普通文件
        //   权限不足误判成 Enforcing。
        auto canaryDenied = [](const std::string& path) -> bool {
            struct stat st {};
            if (stat(path.c_str(), &st) != 0) return false;
            if ((st.st_mode & S_IROTH) == 0) return false;
            if (st.st_uid == getuid()) return false;
            const int e = accessErrnoRaw(path, R_OK);
            return e == EACCES || e == EPERM;
        };
        if (canaryDenied(RSs("/sys/fs/selinux/enforce")) ||
            canaryDenied(RSs("/sys/fs/selinux/status"))) {
            enforce = "1";
            enforceViaCanary = true;
        }
    }
    if (enforce == "1") {
        Evidence e = makePass(RSs("F.SELINUX_ENFORCE"), Category::SELINUX,
                              RSs("SELinux = Enforcing"));
        e.addVector(RSs("enforce"), enforce);
        if (enforceViaStatus) e.addVector(RSs("via"), RSs("/sys/fs/selinux/status"));
        if (enforceViaCanary) {
            e.addVector(RSs("via"), RSs("canary"));
            e.detail = RSs("enforce/status 节点按权限位本应用可读，却被实际拒绝——"
                           "Permissive 模式下 SELinux 拒绝只记日志、不拦截，"
                           "能真正拦住说明 Enforcing 正在生效");
        }
        r.add(std::move(e));
    } else if (enforce == "0") {
        Evidence e = makeHit(RSs("F.SELINUX_ENFORCE"), Category::SELINUX,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("SELinux 处于 Permissive 模式"));
        e.addVector(RSs("enforce"), enforce);
        if (enforceViaStatus) e.addVector(RSs("via"), RSs("/sys/fs/selinux/status"));
        r.add(std::move(e));
    } else {
        // 两个通道都读不到：原厂 App 也可能读不到（部分机型 selinuxfs 受限）
        r.add(makeUnknown(RSs("F.SELINUX_ENFORCE"), Category::SELINUX,
                          Severity::LOW,
                          RSs("enforce 与 status 均不可读（原厂部分机型同样如此）")));
    }

    // ---------------------------------------------------------- getenforce 语义
    // 注意：我们无法在此调用 Java 的 getenforce，只用文件路径判断
    // 若 enforce 可读但 policyvers 不可读，说明 selinuxfs 被部分过滤
    const std::string policyvers = trim(readFile(RSs("/sys/fs/selinux/policyvers")));
    if (!enforceFile.empty() && policyvers.empty()) {
        Evidence e = makeHit(RSs("F.SELINUX_FILTERED"), Category::SELINUX,
                             Severity::MEDIUM, Confidence::MEDIUM,
                             RSs("selinuxfs 部分节点不可读 ← 策略被改过"));
        e.addVector(RSs("enforce"), enforceFile);
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
                           RSs("SELinux policy 不可读（Enforcing 已确认，符合加固预期）")));
        } else {
            r.add(makeUnknown(RSs("F.SELINUX_POLICY"), Category::SELINUX,
                              Severity::LOW,
                              RSs("SELinux policy 不可读且 enforce 状态未确认，无法判定")));
        }
    }

    // ---------------------------------------------------------- 基线节点
    // 原厂 App 应该能读这些；读不到即为过度隐藏
    // v2.22.45（审查 A P1-8）：旧口径 denied>=1 即 HIGH HIT，且
    // EACCES 与 ENOENT 不分 —— 单个节点在个别厂商/版本策略下本来
    // 就不可读时必然误报。现在：EACCES 与 ENOENT 分开计数，
    // denied>=2 才 HIGH；恰 1 个降 LOW + heuristic 只作提示。
    const std::vector<std::string> baseline = {
        RSs("/proc/version"), RSs("/proc/stat"), RSs("/proc/uptime"),
        RSs("/proc/loadavg"), RSs("/proc/misc"), RSs("/proc/devices"),
        RSs("/proc/partitions"), RSs("/proc/filesystems"),
    };

    int deniedAccess = 0;
    int deniedMissing = 0;
    int otherErr = 0;
    int readable = 0;
    std::string firstDenied;
    for (const auto& p : baseline) {
        // v2.22.82（联合审查 N1）：改用 errno 精确探针。旧版把
        // accessState 的 2 一律当 ENOENT 计入 deniedMissing，
        // EIO/ENOSYS/ENOTDIR 也能凑出「过度隐藏」HIT。现在只有
        // 真 ENOENT 算缺席，其余错误单列且不计入 denied。
        const int err = accessErrnoRaw(p, R_OK);
        if (err == 0) {
            ++readable;
        } else if (err == EACCES || err == EPERM) {
            ++deniedAccess;
            if (firstDenied.empty()) firstDenied = p;
        } else if (err == ENOENT) {
            ++deniedMissing;
            if (firstDenied.empty()) firstDenied = p + RSs(" (ENOENT)");
        } else {
            ++otherErr;
        }
    }
    const int denied = deniedAccess + deniedMissing;
    if (denied >= 2) {
        Evidence e = makeHit(RSs("F.OVER_HIDING"), Category::SELINUX,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("原厂必可读的节点被拒绝 ← 过度隐藏"));
        e.addVector(RSs("denied"), std::to_string(denied) + "/" +
                                   std::to_string(baseline.size()));
        e.addVector(RSs("eacces"), std::to_string(deniedAccess));
        e.addVector(RSs("enoent"), std::to_string(deniedMissing));
        if (otherErr > 0) e.addVector(RSs("other"), std::to_string(otherErr));
        e.addVector(RSs("first"), firstDenied);
        e.detail = RSs("读不到比读得到更可疑：检测器一行代码即可判定异常");
        r.add(std::move(e));
    } else if (denied == 1) {
        Evidence e = makeHit(RSs("F.OVER_HIDING"), Category::SELINUX,
                             Severity::LOW, Confidence::LOW,
                             RSs("单个基线节点不可读（弱信号）"));
        e.addVector(RSs("denied"), std::to_string(denied) + "/" +
                                   std::to_string(baseline.size()));
        e.addVector(RSs("first"), firstDenied);
        e.heuristic = true;
        e.fp_note = RSs("个别厂商 / Android 版本的 SELinux 策略会让单个"
                        "/proc 节点对 App 不可读，单点不足以判定过度隐藏");
        r.add(std::move(e));
    } else if (readable == (int)baseline.size()) {
        r.add(makePass(RSs("F.OVER_HIDING"), Category::SELINUX,
                       RSs("8 个基线节点全部可读（符合原厂）")));
    } else {
        // 既无拒绝也非全可读：有节点返回了 EACCES/ENOENT 之外的
        // 错误，观测不完整，不下「全部可读」的 PASS。
        Evidence e = makeUnknown(RSs("F.OVER_HIDING"), Category::SELINUX,
                                 Severity::LOW,
                                 RSs("基线节点观测不完整（不可验）"));
        e.addVector(RSs("other"), std::to_string(otherErr));
        e.addVector(RSs("readable"), std::to_string(readable));
        r.add(std::move(e));
    }
}

}  // namespace rs