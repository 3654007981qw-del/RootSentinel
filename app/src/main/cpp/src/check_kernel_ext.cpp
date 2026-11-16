// check_kernel_ext.cpp — 内核安全子系统扩展（M9）
//
// 这一层全是「内核暴露的安全状态」，几乎不可被用户态伪造：
//   LSM        当前激活的安全模块列表（SELinux 必须在里面）
//   lockdown   内核 lockdown 级别
//   BPF pinned 已固定到 /sys/fs/bpf 的程序
//   kprobes    动态插桩点
//   se_bools   SELinux 布尔开关
//   se_classes SELinux 对象类
//   netfilter  nf_log 内核模块
//   kallsyms   内核符号地址是否被隐藏
//   sysctl     更多加固开关
#include "rs_checks.h"

#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

#include <unistd.h>

namespace rs {

namespace {

bool readTrim(const std::string& p, std::string& out) {
    out = trim(readFile(p, 64 * 1024));
    return !out.empty();
}

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

void checkKernelExt(ScanReport& r) {

    // --------------------------------------------------------------- F.LSM
    // /sys/kernel/security/lsm 列出所有激活的 LSM。
    // SELinux 必须在；缺了说明被编译掉了或用 lsm= 参数关过。
    {
        std::string v;
        if (!readTrim(RSs("/sys/kernel/security/lsm"), v)) {
            // v2.22.66 推断通道：securityfs 被墙时，本题（SELinux 是否
            // 在活动 LSM 中）可由两条独立证据推断——进程自身的
            // 安全上下文只有 SELinux 启用时才存在，selinuxfs 状态页
            // 同理。两者都在 → SELinux 必然在活动列表中（只能证
            // 「在」，不能证「不在」，所以只接 PASS 分支）。
            const auto ctx = trim(readFile(RSs("/proc/self/attr/current"), 256));
            const auto st = readFile(RSs("/sys/fs/selinux/status"), 64);
            if (ctx.rfind(RSs("u:r:"), 0) == 0 && st.size() >= 12) {
                Evidence e = makePass(RSs("F.LSM"), Category::KERNEL,
                                      RSs("SELinux 处于激活状态（由自身上下文与状态页推断）"));
                e.confidence = Confidence::MEDIUM;
                e.addVector(RSs("lsm"), RSs("selinux（推断）"));
                e.addVector(RSs("domain"), ctx);
                r.add(std::move(e));
            } else {
                r.add(makeUnknown(RSs("F.LSM"), Category::KERNEL, Severity::LOW,
                                  RSs("/sys/kernel/security/lsm 不可读")));
            }
        } else if (!contains(v, RSs("selinux"))) {
            Evidence e = makeHit(RSs("F.LSM"), Category::KERNEL,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("激活的 LSM 里没有 SELinux"));
            e.addVector(RSs("lsm"), v);
            e.detail = RSs("Android 必须启用 SELinux；缺失说明被内核参数关掉了");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("F.LSM"), Category::KERNEL,
                                  RSs("SELinux 在激活的 LSM 列表中"));
            e.addVector(RSs("lsm"), v);
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- F.LOCKDOWN
    {
        std::string v;
        if (!readTrim(RSs("/sys/kernel/security/lockdown"), v)) {
            r.add(makeUnknown(RSs("F.LOCKDOWN"), Category::KERNEL, Severity::LOW,
                              RSs("lockdown 节点不可读")));
        } else {
            Evidence e = makePass(RSs("F.LOCKDOWN"), Category::KERNEL,
                                  RSs("内核 lockdown 状态已读取"));
            e.addVector(RSs("state"), v);
            if (contains(v, RSs("[integrity]")) || contains(v, RSs("[confidentiality]"))) {
                e.detail = RSs("lockdown 已启用（比原厂更严）");
            } else {
                e.detail = RSs("lockdown 未启用（Android 常态）");
            }
            r.add(std::move(e));
        }
    }

    // --------------------------------------------------------- F.BPF_PROGS
    // /sys/fs/bpf 下如果出现内核枚举/隐藏类程序，通常是 eBPF rootkit。
    {
        const int n = countDir(RSs("/sys/fs/bpf"));
        if (n == 0) {
            // v2.22.41：countDir 在目录不可枚举时也返回 0，
            // 「空目录」与「读不到」不能混为一谈。
            // v2.22.42（P2-02）：再细分 ENOENT —— bpffs 没挂载
            // 不等于没有 BPF 程序，同样只能 UNKNOWN。
            const int ae = accessErrnoRaw(RSs("/sys/fs/bpf"), 4 /* R_OK */);
            if (ae == EACCES || ae == EPERM) {
                Evidence u = makeUnknown(RSs("F.BPF_PROGS"), Category::KERNEL,
                                         Severity::LOW,
                                         RSs("/sys/fs/bpf 不可读，无法确认是否存在 pinned BPF 程序"));
                u.addVector(RSs("errno"), std::to_string(ae));
                r.add(std::move(u));
            } else if (ae == ENOENT) {
                Evidence u = makeUnknown(RSs("F.BPF_PROGS"), Category::KERNEL,
                                         Severity::LOW,
                                         RSs("/sys/fs/bpf 不存在（bpffs 未挂载），无法枚举 pinned 程序"));
                u.addVector(RSs("errno"), std::to_string(ae));
                r.add(std::move(u));
            } else {
                r.add(makePass(RSs("F.BPF_PROGS"), Category::KERNEL,
                               RSs("无 pinned BPF 程序")));
            }
        } else {
            // v2.22.42（P2-02）：先分系统自带与未知来源。
            // Android 自身（netd / tethering / timeinstate）会 pin
            // 一批 BPF 程序做流量统计与管控，把它们一律当 rootkit
            // 命中是误报源。只有出现未知来源的 pinned 程序才 HIT。
            static const char* kSysPrefixes[] = {
                "prog_netd", "map_netd", "netd_shared",
                "prog_tethering", "map_tethering", "tethering_shared",
                "prog_timeinstate", "map_timeinstate",
            };
            std::vector<std::string> unknownNames;
            auto es = listDirRaw(RSs("/sys/fs/bpf"));
            for (const auto& d : es) {
                if (d.name.empty() || d.name[0] == '.') continue;
                bool sys = false;
                for (const char* p : kSysPrefixes) {
                    if (d.name.rfind(p, 0) == 0) { sys = true; break; }
                }
                if (!sys && unknownNames.size() < 6) unknownNames.push_back(d.name);
            }
            if (unknownNames.empty()) {
                Evidence e = makePass(RSs("F.BPF_PROGS"), Category::KERNEL,
                                      RSs("仅见 Android 系统自带 pinned BPF 程序"));
                e.addVector(RSs("count"), std::to_string(n));
                e.detail = RSs("netd / tethering / timeinstate 的流量统计与管控程序，属系统常态");
                r.add(std::move(e));
            } else {
                Evidence e = makeHit(RSs("F.BPF_PROGS"), Category::KERNEL,
                                     Severity::HIGH, Confidence::MEDIUM,
                                     RSs("存在未知来源的 pinned BPF 程序"));
                e.addVector(RSs("count"), std::to_string(n));
                for (const auto& s : unknownNames) e.addVector(RSs("prog"), s);
                e.detail = RSs("非系统自带的 pinned BPF 程序可在内核里拦截系统调用 —— "
                               "是 eBPF rootkit 的落地形态");
                r.add(std::move(e));
            }
        }
    }

    // ------------------------------------------------------------ F.KPROBES
    {
        const auto list = readFile(RSs("/sys/kernel/debug/kprobes/list"), 256 * 1024);
        if (list.empty()) {
            // v2.22.41：区分「读到空列表」与「根本读不到」。
            // 之前不可读直接 PASS 并标注（正常）—— 读不到只能说明
            // 观测失败（debugfs 未挂载 / 权限拒绝 / 被隐藏），
            // 不能翻译成「没有 kprobe」。可读且为空才是真 PASS。
            const int ae = accessErrnoRaw(RSs("/sys/kernel/debug/kprobes/list"), 4 /* R_OK */);
            if (ae == 0) {
                r.add(makePass(RSs("F.KPROBES"), Category::KERNEL,
                               RSs("kprobe 列表为空")));
            } else {
                Evidence u = makeUnknown(RSs("F.KPROBES"), Category::KERNEL,
                                         Severity::LOW,
                                         RSs("kprobe 列表不可读，无法确认是否存在激活的 kprobe"));
                u.addVector(RSs("errno"), std::to_string(ae));
                r.add(std::move(u));
            }
        } else {
            const auto lines = split(list, '\n');
            int n = 0;
            std::vector<std::string> sample;
            for (const auto& l : lines) {
                if (l.empty()) continue;
                ++n;
                if (sample.size() < 5) sample.push_back(l.substr(0, 90));
            }
            Evidence e = makeHit(RSs("F.KPROBES"), Category::KERNEL,
                                 Severity::HIGH, Confidence::MEDIUM,
                                 RSs("存在激活的 kprobe"));
            e.addVector(RSs("count"), std::to_string(n));
            for (const auto& s : sample) e.addVector(RSs("probe"), s);
            e.detail = RSs("kprobe 可以对任意内核函数插桩 —— "
                           "这是最隐蔽的 hook 方式之一");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- F.SE_BOOLS
    {
        const auto data = readFile(RSs("/sys/fs/selinux/booleans"), 256 * 1024);
        if (data.empty()) {
            // v2.22.66：booleans 在 selinuxfs 里是目录，readFile 对
            // 目录必然失败（旧实现因此恒 UNKNOWN）——改用枚举。
            const int nb = countDir(RSs("/sys/fs/selinux/booleans"));
            if (nb > 0) {
                Evidence e = makePass(RSs("F.SE_BOOLS"), Category::SELINUX,
                                      RSs("SELinux booleans 可枚举"));
                e.addVector(RSs("lines"), std::to_string(nb));
                e.addVector(RSs("via"), RSs("dir"));
                r.add(std::move(e));
            } else {
                r.add(makeUnknown(RSs("F.SE_BOOLS"), Category::SELINUX, Severity::LOW,
                                  RSs("SELinux booleans 不可读")));
            }
        } else {
            const auto lines = split(data, '\n');
            Evidence e = makePass(RSs("F.SE_BOOLS"), Category::SELINUX,
                                  RSs("SELinux booleans 可读"));
            e.addVector(RSs("lines"), std::to_string(lines.size()));
            r.add(std::move(e));
        }
    }

    // -------------------------------------------------------- F.SE_CLASSES
    {
        const int n = countDir(RSs("/sys/fs/selinux/class"));
        if (n == 0) {
            // v2.22.66：枚举被拦时逐个探标准对象类目录——这些类是
            // 基础策略必备，≥3 个可达即可确认类表存在且可探测。
            static const char* knownClasses[] = {
                "file", "dir", "lnk_file", "fd", "process",
                "tcp_socket", "udp_socket", "capability", "security", "system",
            };
            int okClasses = 0;
            for (const char* c : knownClasses) {
                if (accessState(RSs("/sys/fs/selinux/class/") + std::string(c), R_OK) == 0) {
                    ++okClasses;
                }
            }
            if (okClasses >= 3) {
                Evidence e = makePass(RSs("F.SE_CLASSES"), Category::SELINUX,
                                      RSs("SELinux 对象类可探测（枚举被拦）"));
                e.addVector(RSs("classes"), std::to_string(okClasses));
                e.addVector(RSs("via"), RSs("probe"));
                r.add(std::move(e));
            } else {
                r.add(makeUnknown(RSs("F.SE_CLASSES"), Category::SELINUX, Severity::LOW,
                                  RSs("SELinux class 目录不可枚举")));
            }
        } else {
            Evidence e = makePass(RSs("F.SE_CLASSES"), Category::SELINUX,
                                  RSs("SELinux 对象类可枚举"));
            e.addVector(RSs("classes"), std::to_string(n));
            r.add(std::move(e));
        }
    }

    // --------------------------------------------------------- F.NETFILTER
    {
        const auto nf = readFile(RSs("/proc/net/netfilter/nf_log"), 8192);
        if (nf.empty()) {
            r.add(makePass(RSs("F.NETFILTER"), Category::KERNEL,
                           RSs("nf_log 不可读（正常）")));
        } else {
            Evidence e = makePass(RSs("F.NETFILTER"), Category::KERNEL,
                                  RSs("nf_log 表可读"));
            e.addVector(RSs("lines"), std::to_string(split(nf, '\n').size()));
            r.add(std::move(e));
        }
    }

    // ------------------------------------------------------ F.KALLSYMS_ADDR
    // kptr_restrict=1/2 时，普通进程看到的符号地址全是 0。
    // 若能看到真实地址 → 内核指针保护被关掉了。
    {
        const auto ks = readFile(RSs("/proc/kallsyms"), 2u << 20);
        if (ks.empty()) {
            r.add(makeUnknown(RSs("F.KALLSYMS_ADDR"), Category::KERNEL,
                              Severity::LOW, RSs("/proc/kallsyms 不可读")));
        } else {
            int nonZero = 0, total = 0;
            for (const auto& line : split(ks, '\n')) {
                if (line.size() < 17) continue;
                ++total;
                // 前 16 字符是地址
                bool allZero = true;
                for (int i = 0; i < 16 && i < (int)line.size(); ++i) {
                    const char c = line[(size_t)i];
                    if (c != '0' && c != ' ') { allZero = false; break; }
                }
                if (!allZero) ++nonZero;
                if (total > 4000) break;
            }

            if (nonZero > 0) {
                Evidence e = makeHit(RSs("F.KALLSYMS_ADDR"), Category::KERNEL,
                                     Severity::HIGH, Confidence::HIGH,
                                     RSs("kallsyms 暴露了真实内核地址"));
                e.addVector(RSs("non_zero"), std::to_string(nonZero));
                e.addVector(RSs("sampled"), std::to_string(total));
                e.detail = RSs("kptr_restrict 生效时地址应全为 0；"
                               "出现真实地址说明指针保护被关闭");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("F.KALLSYMS_ADDR"), Category::KERNEL,
                                      RSs("kallsyms 地址已隐藏"));
                e.addVector(RSs("sampled"), std::to_string(total));
                r.add(std::move(e));
            }
        }
    }

    // ------------------------------------------------------- F.SYSCTL_MORE
    // 一批加固开关的整体快照。值不是 0/1 这种二元判据，
    // 而是「与 Android 默认值对比」。
    {
        struct Entry {
            const char* path;
            const char* expected;
        };
        static const Entry entries[] = {
            {"/proc/sys/kernel/randomize_va_space", "2"},
            {"/proc/sys/vm/mmap_min_addr", "4096"},
            // 注意：perf_event_paranoid 在 Android 上默认就是 -1，
            // 单独看它没有意义（已有 G.PERF_PARANOID 专项看）。这里移除。
            {"/proc/sys/kernel/yama/ptrace_scope", "1"},
        };

        int readable = 0, deviating = 0;
        std::vector<std::string> lines;

        for (const auto& e0 : entries) {
            // 注意：e0.path 是运行时指针，不能用 RSs()（它需要编译期字面量）
            const auto v = trim(readFile(e0.path, 4096));
            if (v.empty()) continue;
            ++readable;
            if (v != e0.expected) {
                ++deviating;
                if (lines.size() < 6) {
                    lines.push_back(std::string(e0.path) + "=" + v +
                                    " (期望 " + e0.expected + ")");
                }
            }
        }

        if (readable == 0) {
            r.add(makeUnknown(RSs("F.SYSCTL_MORE"), Category::KERNEL,
                              Severity::LOW, RSs("加固 sysctl 均不可读")));
        } else if (deviating > 0) {
            Evidence e = makeHit(RSs("F.SYSCTL_MORE"), Category::KERNEL,
                                 Severity::MEDIUM, Confidence::LOW,
                                 RSs("部分加固 sysctl 偏离默认值"));
            for (const auto& s : lines) e.addVector(RSs("sysctl"), s);
            e.addVector(RSs("readable"), std::to_string(readable));
            e.detail = RSs("与原厂默认值不一致，可能是厂商定制或被人调过");
            e.fp_note = RSs("厂商 ROM 可能本来就改过这些值，需对比同型号");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("F.SYSCTL_MORE"), Category::KERNEL,
                                  RSs("加固 sysctl 均为默认值"));
            e.addVector(RSs("readable"), std::to_string(readable));
            r.add(std::move(e));
        }
    }
}

}  // namespace rs