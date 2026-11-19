// check_kernel_taint.cpp — 内核 taint 与安全 sysctl（M7）
//
// /proc/sys/kernel/tainted 是内核的「不干净」位图，任何进程都能读。
//
// 关键两位（Linux 标准定义）：
//   bit 12  0x1000  TAINT_OOT_MODULE        ← 加载了树外模块
//   bit 13  0x2000  TAINT_UNSIGNED_MODULE   ← 加载了未签名模块
//
// 原厂 Android 的内核模块全部在树内、且由厂商签名，
// 这两位恒为 0。一旦置位，说明有人 insmod 了自己编译/签名的 ko ——
// 这是 LKM 型 root（KernelSU / SUSFS / APatch）的**直接证据**，
// 而且它由内核自己维护，用户态 hook 改不掉。
#include "rs_checks.h"

#include <cstdlib>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

// Linux 标准 taint 位
constexpr long long TAINT_PROPRIETARY_MODULE = 1LL << 0;
constexpr long long TAINT_FORCED_MODULE      = 1LL << 1;
constexpr long long TAINT_FORCED_UNLOAD      = 1LL << 2;
constexpr long long TAINT_OOT_MODULE         = 1LL << 12;
constexpr long long TAINT_UNSIGNED_MODULE    = 1LL << 13;

std::vector<std::string> describeTaint(long long v) {
    std::vector<std::string> out;
    if (v & TAINT_PROPRIETARY_MODULE) out.push_back(RSs("P=专有模块"));
    if (v & TAINT_FORCED_MODULE)      out.push_back(RSs("F=强制加载模块"));
    if (v & TAINT_FORCED_UNLOAD)      out.push_back(RSs("U=强制卸载模块"));
    if (v & TAINT_OOT_MODULE)         out.push_back(RSs("O=树外模块"));
    if (v & TAINT_UNSIGNED_MODULE)    out.push_back(RSs("E=未签名模块"));
    return out;
}

// v2.23.0（安全架构阶段四·通道交叉验证）：sysctl 读取改走
// readFile3 三视角（RAW openat / libc fopen / stream）。判定口径
// 与旧 readFile 严格一致——只认 RAW 视角的内容，RAW 读不到即
// 「不可读」；三视角的可见性/一致性结论只进证据附注（views），
// 不改变任何判定。设施在 util 里建好已久，本文件是首个接入方：
// 它每个结论只靠一次读取，是全仓最薄的一层。
std::string g_lastViewsNote;

bool readSysctl(const std::string& path, std::string& out) {
    const Read3 r3 = readFile3(path, 4096);
    g_lastViewsNote.clear();
    if (!r3.consistent) {
        g_lastViewsNote = r3.note + RSs("（可读视角 ") +
                          std::to_string(r3.readableViews) + RSs("/3）");
    } else if (r3.crossVerified) {
        g_lastViewsNote = RSs("三视角可读性一致（") +
                          std::to_string(r3.readableViews) + RSs("/3）");
    }
    if (!r3.ok_raw) return false;
    out = trim(r3.data_raw);
    return !out.empty();
}

void attachViews(Evidence& e) {
    if (!g_lastViewsNote.empty()) e.addVector(RSs("views"), g_lastViewsNote);
}

}  // namespace

void checkKernelTaint(ScanReport& r) {

    // ----------------------------------------------------------- F.TAINTED
    {
        std::string raw;
        if (!readSysctl("/proc/sys/kernel/tainted", raw)) {
            r.add(makeUnknown(RSs("F.TAINTED"), Category::KERNEL, Severity::LOW,
                              RSs("/proc/sys/kernel/tainted 不可读")));
        } else {
            const long long v = std::atoll(raw.c_str());
            const bool oot = (v & TAINT_OOT_MODULE) != 0;
            const bool unsignedMod = (v & TAINT_UNSIGNED_MODULE) != 0;

            if (oot || unsignedMod) {
                Evidence e = makeHit(RSs("F.TAINTED"), Category::KERNEL,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("内核被加载了树外 / 未签名模块"));
                e.addVector(RSs("tainted"), raw);
                for (const auto& d : describeTaint(v)) e.addVector(RSs("flag"), d);
                e.detail = RSs("原厂 Android 的模块全部在树内且已签名，"
                               "这两位恒为 0。置位说明有人 insmod 了自编译 ko —— "
                               "LKM 型 root 的直接证据");
                attachViews(e);
                r.add(std::move(e));
            } else if (v == 0) {
                Evidence e = makePass(RSs("F.TAINTED"), Category::KERNEL,
                                      RSs("内核 taint 为 0（完全干净）"));
                e.addVector(RSs("tainted"), "0");
                attachViews(e);
                r.add(std::move(e));
            } else {
                Evidence e = makeUnknown(RSs("F.TAINTED"), Category::KERNEL,
                                         Severity::LOW,
                                         RSs("内核有非关键 taint"));
                e.addVector(RSs("tainted"), raw);
                for (const auto& d : describeTaint(v)) e.addVector(RSs("flag"), d);
                e.detail = RSs("taint 非 0 但不含 OOT/UNSIGNED 位，"
                               "可能来自驱动异常或厂商调试配置");
                attachViews(e);
                r.add(std::move(e));
            }
        }
    }

    // ------------------------------------------------------ F.KPTR_RESTRICT
    // Android 默认 >= 1（甚至 2），让内核指针在 /proc 里显示为 0。
    // 为 0 说明有人为了让 kallsyms 可读而关掉了它。
    {
        std::string v;
        if (!readSysctl("/proc/sys/kernel/kptr_restrict", v)) {
            r.add(makeUnknown(RSs("F.KPTR_RESTRICT"), Category::KERNEL,
                              Severity::LOW,
                              RSs("kptr_restrict 不可读")));
        } else if (v == RSs("0")) {
            Evidence e = makeHit(RSs("F.KPTR_RESTRICT"), Category::KERNEL,
                                 Severity::HIGH, Confidence::MEDIUM,
                                 RSs("kptr_restrict = 0（内核指针未隐藏）"));
            e.addVector(RSs("value"), v);
            e.detail = RSs("原厂 Android 恒 >= 1；为 0 通常是 root 方案"
                           "为了让 kallsyms 可读而改的");
            e.fp_note = RSs("userdebug / eng 版本可能默认 0");
            attachViews(e);
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("F.KPTR_RESTRICT"), Category::KERNEL,
                                  RSs("kptr_restrict 已启用"));
            e.addVector(RSs("value"), v);
            attachViews(e);
            r.add(std::move(e));
        }
    }

    // ----------------------------------------------------- F.DMESG_RESTRICT
    {
        std::string v;
        if (!readSysctl("/proc/sys/kernel/dmesg_restrict", v)) {
            r.add(makeUnknown(RSs("F.DMESG_RESTRICT"), Category::KERNEL,
                              Severity::LOW,
                              RSs("dmesg_restrict 不可读")));
        } else if (v == RSs("0")) {
            Evidence e = makeHit(RSs("F.DMESG_RESTRICT"), Category::KERNEL,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("dmesg_restrict = 0（内核日志对 App 开放）"));
            e.addVector(RSs("value"), v);
            e.detail = RSs("Android 原厂恒为 1；为 0 可让普通 App 读内核日志");
            attachViews(e);
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("F.DMESG_RESTRICT"), Category::KERNEL,
                                  RSs("dmesg_restrict 已启用"));
            e.addVector(RSs("value"), v);
            attachViews(e);
            r.add(std::move(e));
        }
    }

    // --------------------------------------------------- F.UNPRIV_BPF
    {
        std::string v;
        if (!readSysctl("/proc/sys/kernel/unprivileged_bpf_disabled", v)) {
            r.add(makeUnknown(RSs("F.UNPRIV_BPF"), Category::KERNEL,
                              Severity::LOW,
                              RSs("unprivileged_bpf_disabled 不可读")));
        } else if (v == RSs("0")) {
            Evidence e = makeHit(RSs("F.UNPRIV_BPF"), Category::KERNEL,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("允许非特权进程使用 eBPF"));
            e.addVector(RSs("value"), v);
            e.detail = RSs("Android 默认禁用；开启后可用 eBPF 做内核级 hook");
            attachViews(e);
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("F.UNPRIV_BPF"), Category::KERNEL,
                                  RSs("非特权 eBPF 已禁用"));
            e.addVector(RSs("value"), v);
            attachViews(e);
            r.add(std::move(e));
        }
    }

    // --------------------------------------------------- F.MODULES_DISABLED
    // 原厂通常为 0（模块本来就没法在锁定设备上加载）。
    // 为 1 反而是更安全的状态，但也很少见。
    {
        std::string v;
        if (!readSysctl("/proc/sys/kernel/modules_disabled", v)) {
            r.add(makeUnknown(RSs("F.MODULES_DISABLED"), Category::KERNEL,
                              Severity::LOW,
                              RSs("modules_disabled 不可读")));
        } else {
            Evidence e = makePass(RSs("F.MODULES_DISABLED"), Category::KERNEL,
                                  RSs("modules_disabled 状态已读取"));
            e.addVector(RSs("value"), v);
            if (v == RSs("1")) {
                e.detail = RSs("模块加载已被彻底关闭（比原厂更严）");
            } else {
                e.detail = RSs("模块加载仍可能（需 root 才能 insmod）");
            }
            attachViews(e);
            r.add(std::move(e));
        }
    }
}

}  // namespace rs