// check_kernel.cpp — F 内核侧探测
// 覆盖 v2 增强包【一.5】内核版本多源对比、补丁级别自洽
#include "rs_checks.h"

#include <sys/utsname.h>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

// 解析 "Linux version X.Y.Z-... (builder@host) ... #N SMP PREEMPT ..."
struct KernelVer {
    std::string release;      // 6.1.145-android14-11-maybe-dirty
    std::string builder;      // build-user@build-host
    std::string buildDate;    // Thu Jan 1 00:00:00 UTC 1970
    bool        dirty = false;
    bool        selfBuiltHost = false;
    bool        epochTime = false;
};

KernelVer parseProcVersion(const std::string& raw) {
    KernelVer kv;
    if (raw.empty()) return kv;

    // release：第一个空格后的第一段
    size_t vpos = raw.find(RSs("Linux version "));
    if (vpos != std::string::npos) {
        size_t start = vpos + 14;
        size_t end = raw.find(' ', start);
        if (end == std::string::npos) end = raw.size();
        kv.release = raw.substr(start, end - start);
    }

    // builder：括号内
    size_t lp = raw.find('(');
    size_t rp = raw.find(')');
    if (lp != std::string::npos && rp != std::string::npos && rp > lp) {
        kv.builder = raw.substr(lp + 1, rp - lp - 1);
    }

    // 编译时间：从最后一个 ')' 之后到 '#' 之前
    size_t hash = raw.find(RSs("#1"));
    if (hash == std::string::npos) hash = raw.rfind('#');
    if (rp != std::string::npos && hash != std::string::npos && hash > rp) {
        kv.buildDate = trim(raw.substr(rp + 1, hash - rp - 1));
    }

    if (kv.release.find(RSs("dirty")) != std::string::npos) kv.dirty = true;
    if (kv.builder.find(RSs("build-host")) != std::string::npos ||
        kv.builder.find(RSs("build-user")) != std::string::npos) {
        kv.selfBuiltHost = true;
    }
    if (kv.buildDate.find("1970") != std::string::npos) kv.epochTime = true;

    return kv;
}

}  // namespace

void checkKernel(ScanReport& r) {
    // ---------------------------------------------------------- 来源 1: uname
    struct utsname u{};
    std::string unameR, unameV;
    if (uname(&u) == 0) {
        unameR = u.release;
        unameV = u.version;
    }

    // ---------------------------------------------------------- 来源 2: /proc/version
    const std::string procVer = readFile(RSs("/proc/version"));
    KernelVer kv = parseProcVersion(procVer);

    // ---------------------------------------------------------- 来源 3: osrelease
    const std::string osrel = trim(readFile(RSs("/proc/sys/kernel/osrelease")));

    // ---------------------------------------------------------- 来源 4: /proc/sys/kernel/version
    const std::string kver = trim(readFile(RSs("/proc/sys/kernel/version")));

    // ---------------------------------------------------------- 记录
    Evidence rec = makePass(RSs("F.KERNEL_SOURCES"), Category::KERNEL,
                            RSs("内核标识多源采样"));
    rec.addVector(RSs("uname.release"), unameR.empty() ? RSs("N/A") : unameR);
    rec.addVector(RSs("uname.version"), unameV.empty() ? RSs("N/A") : unameV);
    rec.addVector(RSs("proc_version"), procVer.empty() ? RSs("N/A") : RSs("readable"));
    rec.addVector(RSs("osrelease"), osrel.empty() ? RSs("N/A") : osrel);
    rec.addVector(RSs("proc_sys_version"), kver.empty() ? RSs("N/A") : kver);
    r.add(std::move(rec));

    // ---------------------------------------------------------- dirty 标记
    if (!unameR.empty() && unameR.find(RSs("dirty")) != std::string::npos) {
        Evidence e = makeHit(RSs("F.KERNEL_DIRTY"), Category::KERNEL,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("uname 暴露 -dirty ← 内核被 patch 过"));
        e.addVector(RSs("source"), RSs("uname(2)"));
        e.addVector(RSs("release"), unameR);
        e.detail = RSs("SELinux 无法拦截 syscall；只有 hook uname 或改内核串能消除");
        r.add(std::move(e));
    }
    if (!osrel.empty() && osrel.find(RSs("dirty")) != std::string::npos) {
        Evidence e = makeHit(RSs("F.OSRELEASE_DIRTY"), Category::KERNEL,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("/proc/sys/kernel/osrelease 暴露 -dirty"));
        e.addVector(RSs("osrelease"), osrel);
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 自编译 host
    if (kv.selfBuiltHost) {
        Evidence e = makeHit(RSs("F.KERNEL_BUILDER"), Category::KERNEL,
                             Severity::HIGH, Confidence::MEDIUM,
                             RSs("内核由非官方主机编译: ") + kv.builder);
        e.addVector(RSs("builder"), kv.builder);
        e.fp_note = RSs("社区 ROM / 自编译内核会命中；原厂应为 android-build@*");
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 1970 时间戳
    if (kv.epochTime) {
        Evidence e = makeHit(RSs("F.KERNEL_DATE"), Category::KERNEL,
                             Severity::HIGH, Confidence::MEDIUM,
                             RSs("内核编译时间被清零到 1970"));
        e.addVector(RSs("build_date"), kv.buildDate);
        e.fp_note = RSs("部分可复现构建（reproducible build）会固定时间戳；"
                        "需结合 builder 判定");
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 多源一致性
    if (!unameR.empty() && !osrel.empty() && unameR != osrel) {
        Evidence e = makeHit(RSs("F.KERNEL_MISMATCH"), Category::CROSSCHECK,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("uname 与 osrelease 的内核版本不一致"));
        e.addVector(RSs("uname"), unameR);
        e.addVector(RSs("osrelease"), osrel);
        r.add(std::move(e));
    }

    if (!kv.release.empty() && !unameR.empty() && kv.release != unameR) {
        Evidence e = makeHit(RSs("F.KERNEL_MISMATCH"), Category::CROSSCHECK,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("/proc/version 与 uname 的内核版本不一致"));
        e.addVector(RSs("proc_version"), kv.release);
        e.addVector(RSs("uname"), unameR);
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- KSU 侧信道
    // 只做存在性查询，不调用任何授权命令（v2 包要求）
    const std::vector<std::string> ksuPaths = {
        RSs("/data/adb/ksu"), RSs("/data/adb/ksud"),
        RSs("/sys/module/kernelsu"), RSs("/sys/module/kernel_su"),
        RSs("/proc/sys/kernel/ksu"),
    };
    int ksuHits = 0;
    int ksuDenied = 0;
    int ksuOther = 0;
    for (const auto& p : ksuPaths) {
        // v2.22.41：用带 errno 的探测区分「不存在」与「存在但被拒绝」。
        // 之前用 accessState 只认 st==0 为命中，其余一律滑向 PASS ——
        // EACCES 恰恰可能是隐藏/权限管控的结果，不等于节点不存在。
        const int ae = accessErrnoRaw(p, 0);
        if (ae == 0) {
            ++ksuHits;
            Evidence e = makeHit(RSs("F.KSU_NODE"), Category::KERNEL,
                                 Severity::HIGH, Confidence::MEDIUM,
                                 RSs("KSU 相关节点可见: ") + p);
            e.addVector(RSs("access"), RSs("visible"));
            r.add(std::move(e));
        } else if (ae == EACCES || ae == EPERM) {
            ++ksuDenied;
        } else if (ae != ENOENT && ae != ENOTDIR) {
            ++ksuOther;
        }
    }
    if (ksuHits == 0) {
        if (ksuDenied > 0 || ksuOther > 0) {
            Evidence u = makeUnknown(RSs("F.KSU_NODE"), Category::KERNEL,
                                     Severity::LOW,
                                     RSs("KSU 相关节点不可见，但部分路径访问被拒绝或状态不明，无法确认不存在"));
            u.addVector(RSs("denied"), std::to_string(ksuDenied));
            u.addVector(RSs("other"), std::to_string(ksuOther));
            r.add(std::move(u));
        } else {
            r.add(makePass(RSs("F.KSU_NODE"), Category::KERNEL,
                           RSs("KSU 相关节点均不可见")));
        }
    }

    // ---------------------------------------------------------- 安全参数
    struct Param { const char* path; const char* name; };
    const std::vector<std::pair<std::string, std::string>> params = {
        {RSs("/proc/sys/kernel/kptr_restrict"), RSs("kptr_restrict")},
        {RSs("/proc/sys/kernel/dmesg_restrict"), RSs("dmesg_restrict")},
        {RSs("/proc/sys/kernel/perf_event_paranoid"), RSs("perf_event_paranoid")},
    };
    for (const auto& p : params) {
        const std::string v = trim(readFile(p.first));
        if (v.empty()) continue;
        Evidence e = makePass(RSs("F.KERNEL_PARAM"), Category::KERNEL,
                              RSs("内核参数: ") + p.second);
        e.addVector(p.second, v);
        r.add(std::move(e));
    }
}

}  // namespace rs