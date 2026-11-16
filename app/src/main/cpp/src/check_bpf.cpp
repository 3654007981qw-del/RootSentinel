// check_bpf.cpp — eBPF 层检测（M2）
//
// 背景：getdents_hide / uname_hide / binder_probe 这类方案都依赖 eBPF。
// 即使它们不留下文件，eBPF 本身也有可观测的副作用：
//   - bpffs 上 pin 的对象
//   - unprivileged_bpf_disabled 的取值
//   - bpf() syscall 是否对 App 可用
//   - BTF 是否暴露（降低写 eBPF 的门槛）
#include "rs_checks.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {
enum : long {
    BPF_MAP_CREATE = 0,
    BPF_MAP_TYPE_ARRAY = 1,
};
}  // namespace

void checkBpf(ScanReport& r) {
    // ---------------------------------------------------------- bpffs pin
    if (fileExistsRaw(RSs("/sys/fs/bpf"))) {
        auto entries = listDirRaw(RSs("/sys/fs/bpf"));
        if (entries.empty()) {
            r.add(makePass(RSs("G.BPF_PINNED"), Category::KERNEL,
                           RSs("bpffs 已挂载但无 pin 对象")));
        } else {
            Evidence e = makeHit(RSs("G.BPF_PINNED"), Category::KERNEL,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("bpffs 中存在 pin 的 BPF 对象"));
            for (size_t i = 0; i < entries.size() && i < 8; ++i) {
                e.addVector(RSs("pin"), entries[i].name);
            }
            e.detail = RSs("eBPF 隐藏模块通常把 map/prog pin 到 bpffs 以便跨进程共享");
            r.add(std::move(e));
        }
    } else {
        r.add(makePass(RSs("G.BPF_PINNED"), Category::KERNEL,
                       RSs("/sys/fs/bpf 不存在")));
    }

    // ---------------------------------------------------------- 非特权 BPF
    const std::string ubd = trim(readFile(RSs("/proc/sys/kernel/unprivileged_bpf_disabled")));
    if (!ubd.empty()) {
        if (ubd == "0") {
            Evidence e = makeHit(RSs("G.BPF_UNPRIV"), Category::KERNEL,
                                 Severity::HIGH, Confidence::MEDIUM,
                                 RSs("unprivileged_bpf_disabled = 0（App 可加载 eBPF）"));
            e.addVector(RSs("value"), ubd);
            e.fp_note = RSs("部分老内核默认为 0；Android 12+ 通常为 2");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("G.BPF_UNPRIV"), Category::KERNEL,
                                  RSs("非特权 BPF 已禁用"));
            e.addVector(RSs("value"), ubd);
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- bpf() syscall
    // 直接尝试创建最小的 ARRAY map，判断内核是否允许 App 使用 BPF
    {
        // v2.22.45（审查 A P2-15）：旧写法 char[128] + reinterpret_cast
        // 写 uint32_t 有未对齐与严格别名问题；改用对齐的 uint32_t 数组。
        alignas(8) uint32_t attr[32] = {0};
        attr[0] = BPF_MAP_TYPE_ARRAY;  // map_type
        attr[1] = 4;                   // key_size
        attr[2] = 4;                   // value_size
        attr[3] = 1;                   // max_entries

        long ret = sys::raw(sys::NR_bpf, BPF_MAP_CREATE,
                            (long)attr, (long)sizeof(attr), 0, 0, 0);

        if (ret >= 0) {
            sys::close_((int)ret);
            Evidence e = makeHit(RSs("G.BPF_SYSCALL"), Category::KERNEL,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("App 可直接创建 BPF map"));
            e.addVector(RSs("fd"), std::to_string(ret));
            e.detail = RSs("说明 BPF 未被 SELinux 限制，隐藏类 eBPF 程序可运行");
            e.fp_note = RSs("部分内核/ROM 未收紧 BPF，属环境配置问题");
            r.add(std::move(e));
        } else if (-ret == EPERM) {
            r.add(makePass(RSs("G.BPF_SYSCALL"), Category::KERNEL,
                           RSs("bpf() 被 EPERM 拒绝（正常）")));
        } else {
            const int e2 = (int)(-ret);
            // EINVAL / EOPNOTSUPP / ENOSYS 都表示「这条路走不通」，
            // 属于正常加固状态，不应记为 UNKNOWN 噪音。
            if (e2 == EACCES) {
                // v2.22.66：内核的非特权 BPF 门（unprivileged_bpf_disabled）
                // 在 map_create 里返回 EACCES，与 EPERM 同义——都是
                // 「拒绝」，属于正常加固。此前未归类，白白落 UNKNOWN。
                Evidence e = makePass(RSs("G.BPF_SYSCALL"), Category::KERNEL,
                                      RSs("bpf() 被 EACCES 拒绝（非特权 BPF 已禁用，正常）"));
                e.addVector(RSs("errno"), errnoName(e2));
                r.add(std::move(e));
            } else if (e2 == EINVAL || e2 == ENOSYS || e2 == EOPNOTSUPP) {
                Evidence e = makePass(RSs("G.BPF_SYSCALL"), Category::KERNEL,
                                      RSs("bpf() 不可用（内核不支持或已禁用）"));
                e.addVector(RSs("errno"), errnoName(e2));
                r.add(std::move(e));
            } else {
                Evidence e = makeUnknown(RSs("G.BPF_SYSCALL"), Category::KERNEL,
                                         Severity::LOW, RSs("bpf() 返回未知错误"));
                e.addVector(RSs("errno"), errnoName(e2));
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- BTF
    {
        std::string btf = readFile(RSs("/sys/kernel/btf/vmlinux"), 64);
        if (!btf.empty()) {
            Evidence e = makeHit(RSs("G.BTF"), Category::KERNEL,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("/sys/kernel/btf/vmlinux 可读"));
            e.heuristic = true;
            e.fp_note = RSs("Android 13+ 默认暴露 BTF，本身不是 root 证据；"
                            "只是显著降低写 eBPF 的门槛");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- perf_event
    {
        const std::string pep = trim(readFile(RSs("/proc/sys/kernel/perf_event_paranoid")));
        if (!pep.empty()) {
            Evidence e = makePass(RSs("G.PERF_PARANOID"), Category::KERNEL,
                                  RSs("perf_event_paranoid 已读"));
            e.addVector(RSs("value"), pep);
            if (pep == "-1") {
                // v2.4.0：MEDIUM → LOW + heuristic。
                // MTK 等厂商内核默认就是 -1，把它当 MEDIUM 硬证据会在原厂机上
                // 白送 6.8 分。它只是"降低写 eBPF 的门槛"，本身不是 root 证据。
                e.verdict = Verdict::HIT;
                e.severity = Severity::LOW;
                e.confidence = Confidence::MEDIUM;
                e.heuristic = true;
                e.title = RSs("perf_event_paranoid = -1（允许所有 perf 事件）");
                e.fp_note = RSs("MTK / 部分厂商内核默认就是 -1，"
                                "这属于内核配置差异，不是 root 证据；"
                                "仅表示写 eBPF 的门槛较低");
            }
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- tracefs
    if (fileExistsRaw(RSs("/sys/kernel/debug/tracing"))) {
        Evidence e = makeHit(RSs("G.TRACEFS"), Category::KERNEL,
                             Severity::LOW, Confidence::LOW,
                             RSs("tracefs 可见（便于 kprobe / tracepoint）"));
        e.heuristic = true;
        r.add(std::move(e));
    } else {
        r.add(makePass(RSs("G.TRACEFS"), Category::KERNEL,
                       RSs("tracefs 不可见")));
    }
}

}  // namespace rs