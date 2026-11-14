// check_hiding_deep.cpp — SELinux 伪装行为交叉验证 / 安全补丁级别 / 内核 EOL
//
// 本文件对应 2026-11-10 周报里三条「检测缺口」：
//
// ① KernelSU v3.3.1 增加了 SELinux Hide 状态页与 AVD sequence number 伪装，
//    并修复了 attr/current 相关问题。也就是说：
//      **/proc/self/attr/current 这个字符串已经不能单独作为判断依据**。
//    对策不是继续读更多字符串，而是做「行为交叉验证」——
//    域声明自己是 untrusted_app，那就必须真的受 untrusted_app 约束。
//    这里直接去 open 一批「untrusted_app 必然被拒」的路径，
//    若字符串说是受限域、实际却能读到 → 域被伪装。
//
// ② Android 2026-11 安全公告涉及 Framework / System / pKVM 多个 Critical。
//    补丁级别长期滞后本身就是风险，但它**不等于 root**，
//    所以归 INTEGRITY 维度、严重度最高只到 MEDIUM。
//
// ③ 内核 EOL：Android 16 用 6.12，本机是 6.1.145。
//    6.1 的 upstream LTS 已接近/超过维护窗口。
//    这同样是「系统维护状态」，不是 root 证据，定级 INFO/LOW。
#include "rs_checks.h"

#include <android/log.h>
#include <cerrno>
#include <fcntl.h>
#include <sys/utsname.h>

#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

// 诊断输出。
//
// native 侧一直**完全没有日志**：Java 阶段每条证据都会打
// "ev <id> <verdict> ..."，但 38 个 native 模块产出的证据一条都不打。
// 结果就是排查「某项到底跑没跑、结论是什么」时只能靠猜 ——
// 本轮验证这三个新检测项时就卡在这里。
//
// 这里只打关键结论（HIT 全打，PASS 只打一行），不刷屏。
// v2.22.44（P2-07）：级别从 INFO 降到 DEBUG，与 Kotlin 侧逐项
// 日志的策略对齐 —— release 默认不在 INFO 通道暴露逐项检测细节。
#define RS_LOG(fmt, ...) \
    __android_log_print(ANDROID_LOG_DEBUG, "RootSentinel", \
                        "nv " fmt, ##__VA_ARGS__)

// ---------------------------------------------------------------- 日期工具

// "2026-11-05" → 年月日；失败返回 false
bool parseYmd(const std::string& s, int* y, int* m, int* d) {
    if (s.size() < 10) return false;
    if (s[4] != '-' || s[7] != '-') return false;
    *y = std::atoi(s.substr(0, 4).c_str());
    *m = std::atoi(s.substr(5, 2).c_str());
    *d = std::atoi(s.substr(8, 2).c_str());
    return *y >= 2010 && *y <= 2100 && *m >= 1 && *m <= 12 && *d >= 1 && *d <= 31;
}

// 月份差（粗略，够用）
int monthsBetween(int y1, int m1, int y2, int m2) {
    return (y2 - y1) * 12 + (m2 - m1);
}

// 当前 UTC 年月（用 time() 而非系统属性，属性可能被改）
void nowYearMonth(int* y, int* m) {
    const std::time_t t = std::time(nullptr);
    std::tm tmv{};
#if defined(_WIN32)
    gmtime_s(&tmv, &t);
#else
    gmtime_r(&t, &tmv);
#endif
    *y = tmv.tm_year + 1900;
    *m = tmv.tm_mon + 1;
}

// ---------------------------------------------------------------- 内核版本

struct KernelVer {
    int major = -1;
    int minor = -1;
    int patch = -1;
    bool ok = false;
};

KernelVer parseKernel(const std::string& release) {
    KernelVer v;
    int a = -1, b = -1, c = -1;
    if (std::sscanf(release.c_str(), "%d.%d.%d", &a, &b, &c) >= 2) {
        v.major = a;
        v.minor = b;
        v.patch = (c >= 0 ? c : 0);
        v.ok = true;
    }
    return v;
}

// Android 使用的 LTS 内核分支及其 upstream 维护截止（年, 月）。
//
// 数据来源：kernel.org 的 longterm 发布列表（releases.html 中的 projected EOL）。
//
// ---------------------------------------------------------------- 修正记录
// v2.22.38 及之前这里有两处错误，会把仍在维护的设备提前报成过期：
//   5.15  写成 2026-10  →  实际 2026-12
//   6.1   写成 2026-10  →  实际 2027-12   ← 偏差 14 个月，影响最大
// 6.1 是 Android 14/15 大量设备在用的分支，误报面很广。
//
// ---------------------------------------------------------------- 重要限制
// 厂商（尤其高通 / MTK / 各家 ROM）会自行 backport 补丁，
// upstream EOL **不等于**设备停止收到安全修复。
// 所以本表只用于「维护状态提示」，绝不作为漏洞结论，
// 也绝不参与 Root 判定（已在 ScoringModel 里归 INTEGRITY 维度）。
struct KernelEol {
    int major;
    int minor;
    int eolYear;
    int eolMonth;
};

const std::vector<KernelEol>& kernelEolTable() {
    static const std::vector<KernelEol> t = {
        {4, 14, 2024, 1},
        {4, 19, 2024, 12},
        {5, 4, 2025, 12},
        {5, 10, 2026, 12},
        {5, 15, 2026, 12},   // 修正：原 2026-10
        {6, 1, 2027, 12},    // 修正：原 2026-10
        {6, 6, 2027, 12},
        {6, 12, 2028, 12},
    };
    return t;
}

}  // namespace

// ================================================================
// ① SELinux 伪装行为交叉验证
// ================================================================
//
// 判定逻辑：
//   1. 读 /proc/self/attr/current 拿到声明的域
//   2. 若声明的域属于「受限域」（untrusted_app / platform / system_app 等），
//      则下列路径**必须**被拒绝。任何一条成功打开 → 域字符串是伪造的
//   3. 反向：若声明的域是 permissive / 无域，但实际所有操作都被拒 →
//      说明策略被反向伪装（少见，但也记一条）
//
// 为什么这能抓到 KernelSU 的 SELinux Hide：
//   它改的是「你看到的字符串」和 selinuxfs 的状态页，
//   但真正的访问控制仍由内核里的 avc 决策执行。
//   改字符串不会让 openat 真的成功 —— 所以行为验证不受伪装影响。
void checkSelinuxXcheck(ScanReport& r) {
    const std::string domain = trim(readFile(RSs("/proc/self/attr/current")));

    if (domain.empty()) {
        r.add(makeUnknown(RSs("F.SELINUX_XCHECK"), Category::SELINUX,
                          Severity::MEDIUM,
                          RSs("attr/current 不可读，无法做行为交叉验证")));
        return;
    }

    // ============================================================
    // 域分类：不同域的权限基线**不同**，不能混在一起判
    // ============================================================
    //
    // v2.22.38 及之前的错误：把 untrusted_app / platform_app /
    // system_app / shell 塞进同一个 restrictedDomains 集合，
    // 用同一套 mustBeDenied 判据 —— 但 platform_app 对
    // /proc/kallsyms、/system/bin/su 的权限与 untrusted_app 完全不同，
    // 结果就是「同一份策略，换个域就得出不同结论」。
    //
    // 现在按域分三档：
    //   APP     普通第三方 App（untrusted_app / isolated_app）
    //   PRIV    系统特权 App（platform_app / system_app / priv_app）
    //   SHELL   adb shell
    enum DomainClass { APP, PRIV, SHELL, OTHER };
    DomainClass cls = OTHER;

    if (contains(domain, RSs("untrusted_app")) ||
        contains(domain, RSs("isolated_app"))) {
        cls = APP;
    } else if (contains(domain, RSs("platform_app")) ||
               contains(domain, RSs("system_app")) ||
               contains(domain, RSs("priv_app"))) {
        cls = PRIV;
    } else if (contains(domain, RSs(":r:shell:"))) {
        cls = SHELL;
    }

    if (cls == OTHER) {
        Evidence e = makePass(RSs("F.SELINUX_XCHECK"), Category::SELINUX,
                              RSs("域不属于可判定的应用域，行为验证跳过"));
        e.addVector(RSs("domain"), domain);
        e.detail = RSs("该域没有内置的权限基线（可能是 su / ksu / kernel 等），"
                       "交叉验证不适用；请参考 F.SELINUX_DOMAIN 的结论");
        r.add(std::move(e));
        return;
    }

    // ============================================================
    // 按域建立权限基线
    // ============================================================
    //
    // 每一项标注「期望结果」：DENY = 必须 EACCES/EPERM，ALLOW = 允许成功。
    // 只有 DENY 项真的成功打开，才是「域被伪装」的证据。
    struct Probe {
        std::string path;
        bool expectDeny;   // true = 该域应被拒绝
    };

    static const std::vector<Probe> probesApp = {
        // 第三方 App 对以下路径必须全部被拒
        {"/data/adb/ksu",           true},
        {"/data/adb/magisk",        true},
        {"/data/adb/modules",       true},
        {"/system/bin/su",          true},
        {"/sys/kernel/debug",       true},
        {"/proc/kallsyms",          true},
        // 对照项：/proc/self/cmdline 任何域都能读，
        // 若连它也读不到，说明探测本身失效，不能据此判 PASS
        {"/proc/self/cmdline",      false},
    };

    // platform_app / system_app 持有平台签名，对部分路径**有**权限，
    // 所以基线里这些项不参与「伪装」判定，只做记录。
    static const std::vector<Probe> probesPriv = {
        {"/data/adb/ksu",           true},
        {"/data/adb/magisk",        true},
        {"/data/adb/modules",       true},
        {"/system/bin/su",          true},
        {"/proc/self/cmdline",      false},
    };

    // adb shell 对 /data/adb 通常无权限，但对 /system/bin/su 可能可读
    static const std::vector<Probe> probesShell = {
        {"/data/adb/ksu",           true},
        {"/data/adb/magisk",        true},
        {"/data/adb/modules",       true},
        {"/proc/self/cmdline",      false},
    };

    const std::vector<Probe>& probes =
        (cls == APP) ? probesApp : (cls == PRIV ? probesPriv : probesShell);

    // ============================================================
    // 逐项探测并**区分 errno**
    // ============================================================
    //
    // v2.22.38 及之前的错误：只要 open 失败就计入「被拒」。
    // 但失败可能是：
    //   ENOENT   路径不存在（厂商裁剪 / debugfs 未挂载）
    //   ENOTDIR  中间路径不是目录
    //   EROFS    只读文件系统
    //   ELOOP    符号链接层数过多
    // 这些都不是「SELinux 拒绝」，把它们算作拒绝会让
    // 「6/6 全被拒」这个结论失去意义 —— 一个节点都没挂载的
    // 精简系统也会得到 6/6。
    //
    // 现在只认 EACCES / EPERM 为「真正的拒绝」，
    // 其余错误单独统计，且不计入伪装判定。
    int denied = 0;          // EACCES / EPERM
    int opened = 0;          // 成功打开（DENY 项成功 = 可疑）
    int missing = 0;         // ENOENT / ENOTDIR
    int otherErr = 0;        // 其它 errno
    int denyExpected = 0;    // 期望被拒的总项数

    std::vector<std::string> suspicious;   // 期望被拒但打开了
    std::vector<std::string> errDetail;    // 非 EACCES 的错误摘要
    bool controlOk = false;                // 对照项是否正常

    for (const auto& p : probes) {
        if (p.expectDeny) ++denyExpected;

        const int fd = sys::openat_(p.path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            sys::close_(fd);
            ++opened;
            if (!p.expectDeny) controlOk = true;
            else suspicious.push_back(p.path);
            continue;
        }

        const int e = errno;
        if (e == EACCES || e == EPERM) {
            ++denied;
        } else if (e == ENOENT || e == ENOTDIR) {
            ++missing;
            if (errDetail.size() < 3) {
                errDetail.push_back(p.path + RSs("=") + errnoName(e));
            }
        } else {
            ++otherErr;
            if (errDetail.size() < 3) {
                errDetail.push_back(p.path + RSs("=") + errnoName(e));
            }
        }
    }

    // ============================================================
    // 判定
    // ============================================================
    //
    // 关键原则：**只有多条相互独立的证据同时异常，才升为高危**。
    // 单条路径能打开可能是误报（例如厂商把 /system/bin/su 留成可读的占位文件），
    // 所以：
    //   suspicious >= 2            → CRITICAL（域被改写）
    //   suspicious == 1            → MEDIUM  （需人工复核）
    //   suspicious == 0 且 有效拒绝 >= 3 → PASS
    //   有效拒绝 < 3（探测本身不可用）  → UNKNOWN
    if (suspicious.size() >= 2) {
        Evidence e = makeHit(RSs("F.SELINUX_XCHECK"), Category::SELINUX,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("SELinux 域被伪装：多条特权路径可访问"));
        e.addVector(RSs("domain"), domain);
        e.addVector(RSs("class"), cls == APP ? RSs("APP")
                              : (cls == PRIV ? RSs("PRIV") : RSs("SHELL")));
        e.addVector(RSs("suspicious"), std::to_string(suspicious.size()) + "/" +
                                       std::to_string(denyExpected));
        for (const auto& sp : suspicious) e.addVector(RSs("reachable"), sp);
        e.detail = RSs("域字符串声称 ") + domain +
                   RSs("，该域本应对上述路径全部 EACCES。"
                       "实际有 ") + std::to_string(suspicious.size()) +
                   RSs(" 条成功打开 —— attr/current 被改写"
                       "（KernelSU SELinux Hide 等）。");
        e.fp_note = RSs("个别厂商 ROM 会把 /system/bin/su 留成可读占位文件，"
                        "所以单条命中只算 MEDIUM；本项要求 ≥2 条独立路径同时异常");
        r.add(std::move(e));
        RS_LOG("F.SELINUX_XCHECK HIT domain=%s suspicious=%zu/%d",
               domain.c_str(), suspicious.size(), denyExpected);
        return;
    }

    if (suspicious.size() == 1) {
        Evidence e = makeHit(RSs("F.SELINUX_XCHECK"), Category::SELINUX,
                             Severity::MEDIUM, Confidence::MEDIUM,
                             RSs("单条特权路径可访问，需人工复核"));
        e.addVector(RSs("domain"), domain);
        e.addVector(RSs("reachable"), suspicious.front());
        e.addVector(RSs("denied"), std::to_string(denied));
        e.detail = RSs("仅 1 条路径成功打开，不足以判定域被改写 —— "
                       "厂商 ROM 有时会留下可读的占位文件。"
                       "需结合 F.SELINUX_DOMAIN 与 F.SELINUX_ENFORCE 一起判断。");
        e.fp_note = RSs("这是弱信号，不应单独作为提权结论");
        e.heuristic = true;
        r.add(std::move(e));
        RS_LOG("F.SELINUX_XCHECK HIT(weak) domain=%s reachable=%s",
               domain.c_str(), suspicious.front().c_str());
        return;
    }

    // ---- 无成功打开：看探测是否有效
    //
    // 有效拒绝数 < 3 时，说明大部分路径根本不存在或挂载缺失，
    // 这种情况下「全部被拒」不能推出「域未被伪装」。
    if (denied < 3) {
        Evidence e = makeUnknown(RSs("F.SELINUX_XCHECK"), Category::SELINUX,
                                 Severity::LOW,
                                 RSs("行为验证的探测面不足，无法得出结论"));
        e.addVector(RSs("domain"), domain);
        e.addVector(RSs("denied_eacces"), std::to_string(denied));
        e.addVector(RSs("missing"), std::to_string(missing));
        e.addVector(RSs("other_err"), std::to_string(otherErr));
        for (const auto& d : errDetail) e.addVector(RSs("err"), d);
        e.detail = RSs("只有 ") + std::to_string(denied) +
                   RSs(" 条路径返回 EACCES/EPERM，其余多为 ENOENT 或其它错误。"
                       "说明这些节点在本设备上不存在（debugfs 未挂载 / "
                       "厂商裁剪），探测面不足，不能据此判定域未被伪装。");
        e.fp_note = RSs("精简 ROM、工程机、部分模拟器会命中此状态；"
                        "这是「测不了」，不是「没问题」");
        r.add(std::move(e));
        RS_LOG("F.SELINUX_XCHECK UNKNOWN domain=%s denied=%d missing=%d other=%d",
               domain.c_str(), denied, missing, otherErr);
        return;
    }

    // ---- 正常通过
    Evidence e = makePass(RSs("F.SELINUX_XCHECK"), Category::SELINUX,
                          RSs("SELinux 域声明与行为一致"));
    e.addVector(RSs("domain"), domain);
    e.addVector(RSs("class"), cls == APP ? RSs("APP")
                          : (cls == PRIV ? RSs("PRIV") : RSs("SHELL")));
    e.addVector(RSs("denied_eacces"), std::to_string(denied) + "/" +
                                      std::to_string(denyExpected));
    e.addVector(RSs("missing"), std::to_string(missing));
    e.addVector(RSs("control"), controlOk ? RSs("ok") : RSs("failed"));
    e.detail = RSs("声明为受限域，") + std::to_string(denied) +
               RSs(" 条特权路径返回 EACCES/EPERM（真正的 SELinux 拒绝），"
                   "0 条成功打开 —— attr/current 未被伪装。");
    r.add(std::move(e));
    RS_LOG("F.SELINUX_XCHECK PASS domain=%s denied=%d/%d missing=%d",
           domain.c_str(), denied, denyExpected, missing);
}

// ================================================================
// ② 安全补丁级别
// ================================================================
//
// 归属 INTEGRITY 维度：补丁滞后是「系统维护状态」，
// 不等于设备被 root，最高只到 MEDIUM。
void checkSecurityPatch(ScanReport& r) {
    const std::string sysPatch =
        trim(getProp(RSs("ro.build.version.security_patch")));
    const std::string vendorPatch =
        trim(getProp(RSs("ro.vendor.build.security_patch")));

    if (sysPatch.empty() && vendorPatch.empty()) {
        r.add(makeUnknown(RSs("B.SEC_PATCH"), Category::PROPERTY,
                          Severity::LOW,
                          RSs("security_patch 属性不可读")));
        return;
    }

    int y = 0, m = 0, d = 0;
    const std::string& use = !sysPatch.empty() ? sysPatch : vendorPatch;
    if (!parseYmd(use, &y, &m, &d)) {
        Evidence e = makeUnknown(RSs("B.SEC_PATCH"), Category::PROPERTY,
                                 Severity::LOW,
                                 RSs("security_patch 格式异常"));
        e.addVector(RSs("value"), use);
        r.add(std::move(e));
        return;
    }

    int ny = 0, nm = 0;
    nowYearMonth(&ny, &nm);
    const int lag = monthsBetween(y, m, ny, nm);

    Evidence e = makePass(RSs("B.SEC_PATCH"), Category::PROPERTY,
                          RSs("安全补丁级别 ") + use);
    e.addVector(RSs("system"), sysPatch.empty() ? RSs("-") : sysPatch);
    e.addVector(RSs("vendor"), vendorPatch.empty() ? RSs("-") : vendorPatch);
    e.addVector(RSs("lag_months"), std::to_string(lag));
    e.detail = RSs("补丁级别滞后 ") + std::to_string(lag) + RSs(" 个月");

    // 阈值：≤3 月正常，4~11 月提示，≥12 月明确滞后
    if (lag >= 12) {
        e.verdict = Verdict::HIT;
        e.severity = Severity::MEDIUM;
        e.confidence = Confidence::HIGH;
        e.title = RSs("安全补丁已滞后 ") + std::to_string(lag) + RSs(" 个月");
        e.fp_note = RSs("部分厂商只在季度更新推补丁，滞后 1 年属常见；"
                        "本项归「系统完整性」，不计入 Root 判定");
    } else if (lag >= 4) {
        e.verdict = Verdict::HIT;
        e.severity = Severity::LOW;
        e.confidence = Confidence::HIGH;
        e.title = RSs("安全补丁滞后 ") + std::to_string(lag) + RSs(" 个月");
    }
    RS_LOG("B.SEC_PATCH %s patch=%s lag=%dmo",
           verdictName(e.verdict), use.c_str(), lag);
    r.add(std::move(e));
}

// ================================================================
// ③ 内核 EOL
// ================================================================
//
// 归属 INTEGRITY 维度、定级 INFO/LOW：
// 内核过保是「不再收 upstream 修复」，不是 root 证据。
void checkKernelEol(ScanReport& r) {
    // ---------------------------------------------------------- 多来源取内核版本
    //
    // 只用 /proc/sys/kernel/osrelease 会踩两个坑：
    //   1. 部分 ROM 把该节点设为 0400 root-only，App 读不到
    //   2. 某些隐藏方案会针对这个节点做过滤
    // 所以这里按可靠性依次尝试三个来源，取第一个能用的。
    std::string release = trim(readFile(RSs("/proc/sys/kernel/osrelease")));
    std::string source = RSs("osrelease");

    if (release.empty()) {
        // 来源 2：uname() 系统调用（不依赖 /proc）
        struct utsname u{};
        if (uname(&u) == 0) {
            release = trim(u.release);
            source = RSs("uname");
        }
    }
    if (release.empty()) {
        // 来源 3：/proc/version 第一段
        const std::string pv = readFile(RSs("/proc/version"), 512);
        const std::string tag = RSs("Linux version ");
        const size_t p = pv.find(tag);
        if (p != std::string::npos) {
            const size_t start = p + tag.size();
            const size_t end = pv.find(' ', start);
            if (end != std::string::npos && end > start) {
                release = trim(pv.substr(start, end - start));
                source = RSs("proc_version");
            }
        }
    }

    const KernelVer kv = parseKernel(release);

    if (!kv.ok) {
        Evidence e = makeUnknown(RSs("F.KERNEL_EOL"), Category::KERNEL,
                                 Severity::LOW,
                                 RSs("内核版本无法解析"));
        e.addVector(RSs("release"), release.empty() ? RSs("<empty>") : release);
        e.addVector(RSs("tried"), RSs("osrelease,uname,proc_version"));
        r.add(std::move(e));
        RS_LOG("F.KERNEL_EOL UNKNOWN 三个来源都失败");
        return;
    }

    int ny = 0, nm = 0;
    nowYearMonth(&ny, &nm);

    const KernelEol* hit = nullptr;
    for (const auto& k : kernelEolTable()) {
        if (k.major == kv.major && k.minor == kv.minor) {
            hit = &k;
            break;
        }
    }

    Evidence e = makePass(RSs("F.KERNEL_EOL"), Category::KERNEL,
                          RSs("内核 ") + std::to_string(kv.major) + RSs(".") +
                              std::to_string(kv.minor) + RSs(" 仍在维护窗口内"));
    e.addVector(RSs("release"), release);
    e.addVector(RSs("source"), source);

    if (hit == nullptr) {
        // ---------------------------------------------------------- 未命中表
        //
        // 之前这里直接 r.add(Pass) 返回 —— 等于把「没有维护周期数据」
        // 谎报成「仍在维护窗口内」。这是明确的逻辑错误：
        // 缺数据必须落 UNKNOWN，不能落 PASS。
        //
        // 常见未命中情形：
        //   · 更新的分支（6.17 / 6.18 等），表还没来得及补
        //   · 厂商自维护分支（如 5.15.xxx-vendor）
        //   · 解析出的 major.minor 是厂商魔改号
        Evidence u = makeUnknown(RSs("F.KERNEL_EOL"), Category::KERNEL,
                                 Severity::LOW,
                                 RSs("缺少该内核分支的维护周期数据"));
        u.addVector(RSs("release"), release);
        u.addVector(RSs("source"), source);
        u.addVector(RSs("branch"), std::to_string(kv.major) + RSs(".") +
                                   std::to_string(kv.minor));
        u.addVector(RSs("in_table"), RSs("no"));
        u.detail = RSs("内核分支 ") + std::to_string(kv.major) + RSs(".") +
                   std::to_string(kv.minor) +
                   RSs(" 不在内置的 LTS 维护周期表里。"
                       "可能是更新的 upstream 分支、厂商自维护分支，"
                       "或厂商改过版本号。缺少数据不能判定为「仍在维护」。");
        u.fp_note = RSs("此状态表示「无法判定」，不是风险信号；"
                        "需要人工核对 kernel.org longterm 列表后更新内置表");
        r.add(std::move(u));
        RS_LOG("F.KERNEL_EOL UNKNOWN kernel=%d.%d 不在表内 src=%s",
               kv.major, kv.minor, source.c_str());
        return;
    }

    const int overMonths = monthsBetween(hit->eolYear, hit->eolMonth, ny, nm);
    e.addVector(RSs("eol"), std::to_string(hit->eolYear) + RSs("-") +
                            (hit->eolMonth < 10 ? RSs("0") : RSs("")) +
                            std::to_string(hit->eolMonth));
    e.addVector(RSs("over_months"), std::to_string(overMonths));

    if (overMonths > 0) {
        e.verdict = Verdict::HIT;
        e.severity = Severity::LOW;
        e.confidence = Confidence::MEDIUM;
        e.title = RSs("内核 ") + std::to_string(kv.major) + RSs(".") +
                  std::to_string(kv.minor) + RSs(" 已超出 upstream 维护窗口 ") +
                  std::to_string(overMonths) + RSs(" 个月");
        e.detail = RSs("upstream LTS 已停止该分支的常规修复。"
                       "Android 设备通常由厂商自行 backport，"
                       "因此本项只作维护状态提示，不代表存在可利用漏洞。");
        e.fp_note = RSs("几乎所有 Android 设备的内核都落后于 upstream LTS，"
                        "本项归「系统完整性」，不计入 Root 判定");
        e.heuristic = true;
    } else {
        e.detail = RSs("距 upstream EOL 还有 ") +
                   std::to_string(-overMonths) + RSs(" 个月");
    }
    RS_LOG("F.KERNEL_EOL %s kernel=%d.%d over=%dmo src=%s",
           verdictName(e.verdict), kv.major, kv.minor, overMonths, source.c_str());
    r.add(std::move(e));
}

}  // namespace rs