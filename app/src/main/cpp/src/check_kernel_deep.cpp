// check_kernel_deep.cpp — 内核设备 / 模块 / 调试接口（M5）
//
// 这一层不看内核版本号（那太容易伪造），
// 看的是「内核向外暴露了哪些结构」：
//   · /sys/module 里的模块名
//   · /proc/devices 里的字符设备
//   · /proc/kallsyms 里的 [kernelsu] / [susfs] 符号归属
//   · debugfs（debugfs 可写 + kprobe 存在 = 可动态插桩）
#include "rs_checks.h"

#include <string>
#include <vector>
#include <algorithm>
#include <cerrno>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

const std::vector<std::string>& badModuleNames() {
    static const std::vector<std::string> v = {
        RSs("kernelsu"), RSs("susfs"), RSs("apatch"), RSs("magisk"),
        RSs("shamiko"), RSs("hidemount"), RSs("ksu"), RSs("zksu"),
        RSs("sukisu"), RSs("milk"), RSs("nomount"),
    };
    return v;
}

// v2.22.45（审查 A P2-19）：模块名按词边界匹配。旧实现 contains()
// 子串匹配，"ksu"/"milk" 这类短词会误伤无关模块名。现在只接受
// 全名相等，或以 "_<k>" 结尾、"<k>_" 开头、含 "_<k>_" 的名字
// （内核模块名以 _ 分隔词元）。
bool moduleNameMatches(const std::string& name, const std::string& k) {
    if (name == k) return true;
    if (startsWith(name, k + RSs("_"))) return true;
    if (contains(name, RSs("_") + k + RSs("_"))) return true;
    const std::string suf = RSs("_") + k;
    return name.size() > suf.size() &&
           name.compare(name.size() - suf.size(), suf.size(), suf) == 0;
}

}  // namespace

void checkKernelDeep(ScanReport& r) {
    // ---------------------------------------------------------- F.SYS_MODULE
    {
        auto mods = listDirRaw(RSs("/sys/module"));
        std::vector<std::string> found;
        int total = 0;

        for (const auto& d : mods) {
            if (d.name.empty() || d.name[0] == '.') continue;
            ++total;
            const auto low = toLower(d.name);
            for (const auto& k : badModuleNames()) {
                if (moduleNameMatches(low, k)) {
                    found.push_back(d.name);
                    break;
                }
            }
        }

        if (total == 0) {
            // /sys/module 对 untrusted_app 常被 SELinux 拦 readdir，
            // 用 /proc/modules 做第二视角
            const auto modsText = readFile(RSs("/proc/modules"), 512 * 1024);
            if (modsText.empty()) {
                // v2.22.66 第三视角：枚举与 /proc/modules 都被拦时，
                // 逐个探测已知 root 模块名的 /sys/module/<name> 节点。
                // ENOENT 是文件系统给出的确定答复（不存在）；EACCES
                // 等含糊结果无法区分「存在但被拒绝」，只要有一个
                // 名字含糊就不能下「不存在」的结论。
                std::vector<std::string> probeHits;
                int probeDenied = 0;
                for (const auto& k : badModuleNames()) {
                    std::vector<std::string> cands;
                    cands.push_back(k);
                    std::string alt = k;
                    std::replace(alt.begin(), alt.end(), '-', '_');
                    if (alt != k) cands.push_back(alt);
                    alt = k;
                    std::replace(alt.begin(), alt.end(), '_', '-');
                    if (alt != k) cands.push_back(alt);
                    int state = -1;  // 0 存在 / 1 确定不存在 / 2 含糊
                    for (const auto& cand : cands) {
                        const int er = accessErrnoRaw(
                            RSs("/sys/module/") + cand, 0);
                        if (er == 0) {
                            state = 0;
                            probeHits.push_back(cand);
                            break;
                        }
                        if (er == ENOENT) {
                            if (state == -1) state = 1;
                        } else {
                            state = 2;
                        }
                    }
                    if (state == 2) ++probeDenied;
                }
                if (!probeHits.empty()) {
                    Evidence e = makeHit(RSs("F.SYS_MODULE"), Category::KERNEL,
                                         Severity::CRITICAL, Confidence::HIGH,
                                         RSs("/sys/module 逐个探测发现 root 模块"));
                    for (const auto& h : probeHits) e.addVector(RSs("module"), h);
                    e.addVector(RSs("view"), RSs("targeted_probe"));
                    e.detail = RSs("LKM 型 root 会在 /sys/module 留下模块条目");
                    e.fp_note = RSs("厂商可能自带同名模块，需核对模块路径");
                    r.add(std::move(e));
                } else if (probeDenied == 0) {
                    Evidence e = makePass(RSs("F.SYS_MODULE"), Category::KERNEL,
                                          RSs("已知 root 模块名逐个探测均不存在（枚举被系统拦截）"));
                    e.confidence = Confidence::LOW;
                    e.addVector(RSs("view"), RSs("targeted_probe"));
                    r.add(std::move(e));
                } else {
                    r.add(makeUnknown(RSs("F.SYS_MODULE"), Category::KERNEL,
                                      Severity::LOW,
                                      RSs("/sys/module 与 /proc/modules 均不可读")));
                }
            } else {
                std::vector<std::string> hits;
                int nLines = 0;
                for (const auto& line : split(modsText, '\n')) {
                    if (line.empty()) continue;
                    ++nLines;
                    const auto low = toLower(line);
                    for (const auto& k : badModuleNames()) {
                        if (contains(low, k)) {
                            hits.push_back(trim(line.substr(0, 32)));
                            break;
                        }
                    }
                }
                if (!hits.empty()) {
                    Evidence e = makeHit(RSs("F.SYS_MODULE"), Category::KERNEL,
                                         Severity::CRITICAL, Confidence::HIGH,
                                         RSs("/proc/modules 出现 root 模块"));
                    for (const auto& h : hits) e.addVector(RSs("module"), h);
                    e.addVector(RSs("view"), RSs("proc_modules"));
                    r.add(std::move(e));
                } else {
                    Evidence e = makePass(RSs("F.SYS_MODULE"), Category::KERNEL,
                                          RSs("/proc/modules 无 root 模块"));
                    e.addVector(RSs("count"), std::to_string(nLines));
                    e.addVector(RSs("view"), RSs("proc_modules"));
                    r.add(std::move(e));
                }
            }
        } else if (!found.empty()) {
            Evidence e = makeHit(RSs("F.SYS_MODULE"), Category::KERNEL,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("/sys/module 出现 root 模块名"));
            for (const auto& f : found) e.addVector(RSs("module"), f);
            e.addVector(RSs("total"), std::to_string(total));
            e.detail = RSs("LKM 型 root 会在 /sys/module 留下模块条目");
            e.fp_note = RSs("厂商可能自带同名模块，需核对模块路径");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("F.SYS_MODULE"), Category::KERNEL,
                                  RSs("/sys/module 无 root 模块"));
            e.addVector(RSs("total"), std::to_string(total));
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- F.PROC_DEVICES
    {
        const auto dev = readFile(RSs("/proc/devices"), 64 * 1024);
        if (dev.empty()) {
            r.add(makeUnknown(RSs("F.PROC_DEVICES"), Category::KERNEL, Severity::LOW,
                              RSs("/proc/devices 不可读")));
        } else {
            std::vector<std::string> found;
            for (const auto& line : split(dev, '\n')) {
                const auto low = toLower(line);
                for (const auto& k : badModuleNames()) {
                    if (contains(low, k)) {
                        found.push_back(trim(line));
                        break;
                    }
                }
            }
            if (!found.empty()) {
                Evidence e = makeHit(RSs("F.PROC_DEVICES"), Category::KERNEL,
                                     Severity::HIGH, Confidence::MEDIUM,
                                     RSs("/proc/devices 出现 root 字符设备"));
                for (const auto& f : found) e.addVector(RSs("dev"), f);
                r.add(std::move(e));
            } else {
                r.add(makePass(RSs("F.PROC_DEVICES"), Category::KERNEL,
                               RSs("/proc/devices 无 root 设备")));
            }
        }
    }

    // ---------------------------------------------------------- F.KALLSYMS_SCAN
    {
        const auto ks = readFile(RSs("/proc/kallsyms"), 8u << 20);
        if (ks.empty()) {
            r.add(makeUnknown(RSs("F.KALLSYMS_SCAN"), Category::KERNEL,
                              Severity::LOW, RSs("/proc/kallsyms 不可读")));
        } else {
            int nKsu = 0, nSusfs = 0, nMagisk = 0, nApatch = 0;
            for (const auto& line : split(ks, '\n')) {
                if (contains(line, RSs("[kernelsu]"))) ++nKsu;
                if (contains(line, RSs("[susfs"))) ++nSusfs;
                if (contains(line, RSs("[magisk"))) ++nMagisk;
                if (contains(line, RSs("[apatch"))) ++nApatch;
            }

            const int total = nKsu + nSusfs + nMagisk + nApatch;
            if (total > 0) {
                Evidence e = makeHit(RSs("F.KALLSYMS_SCAN"), Category::KERNEL,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("kallsyms 中残留 root 模块符号"));
                e.addVector(RSs("kernelsu"), std::to_string(nKsu));
                e.addVector(RSs("susfs"), std::to_string(nSusfs));
                e.addVector(RSs("magisk"), std::to_string(nMagisk));
                e.addVector(RSs("apatch"), std::to_string(nApatch));
                e.detail = RSs("kallsyms 的模块归属标记无法被路径级隐藏改写");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("F.KALLSYMS_SCAN"), Category::KERNEL,
                                      RSs("kallsyms 无 root 模块符号"));
                e.addVector(RSs("lines"), std::to_string(split(ks, '\n').size()));
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- F.DEBUGFS
    {
        const int st = accessState(RSs("/sys/kernel/debug"), 4);
        const auto entries = listDirRaw(RSs("/sys/kernel/debug"));

        if (st == 0 && !entries.empty()) {
            int n = 0;
            std::vector<std::string> names;
            for (const auto& d : entries) {
                if (d.name.empty() || d.name[0] == '.') continue;
                ++n;
                if (names.size() < 6) names.push_back(d.name);
            }
            Evidence e = makeHit(RSs("F.DEBUGFS"), Category::KERNEL,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("debugfs 对普通 App 可读"));
            e.addVector(RSs("entries"), std::to_string(n));
            for (const auto& s : names) e.addVector(RSs("entry"), s);
            e.detail = RSs("debugfs 可读意味着可枚举 ftrace / kprobe，"
                           "原厂 user 版本通常完全屏蔽");
            e.fp_note = RSs("userdebug / eng 版本默认开放 debugfs，"
                            "需先确认 build type");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("F.DEBUGFS"), Category::KERNEL,
                                  RSs("debugfs 不可访问（正常）"));
            e.addVector(RSs("access"),
                        st == 1 ? RSs("EACCES") : RSs("ENOENT"));
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- F.FTRACE
    {
        const auto trace = readFile(RSs("/sys/kernel/tracing/available_filter_functions"),
                                   512 * 1024);
        if (trace.empty()) {
            // v2.22.41：不可读 ≠ 正常。读不到只能说明观测失败
            // （tracing 未挂载 / 权限拒绝 / 被隐藏），按 UNKNOWN 处理；
            // 只有确认可读（内容为空）才算 PASS。
            const int ae = accessErrnoRaw(RSs("/sys/kernel/tracing/available_filter_functions"), 4 /* R_OK */);
            if (ae == 0) {
                r.add(makePass(RSs("F.FTRACE"), Category::KERNEL,
                               RSs("ftrace 过滤函数表可读但为空")));
            } else {
                Evidence u = makeUnknown(RSs("F.FTRACE"), Category::KERNEL,
                                         Severity::LOW,
                                         RSs("ftrace 过滤函数表不可读，无法判定其暴露状态"));
                u.addVector(RSs("errno"), std::to_string(ae));
                r.add(std::move(u));
            }
        } else {
            // v2.22.42（P2-01）：函数表可读 ≠ 正在被 hook。
            // available_filter_functions 只是「内核支持过滤哪些函数」
            // 的目录，正常内核也可读、也可很长。只有活动证据才 HIT：
            // current_tracer 不是 nop，或 enabled_functions 非空。
            const auto tracerRaw = readFile(RSs("/sys/kernel/tracing/current_tracer"), 4096);
            const auto enabled = readFile(RSs("/sys/kernel/tracing/enabled_functions"), 64 * 1024);
            std::string tracerName;
            if (!tracerRaw.empty()) {
                tracerName = split(tracerRaw, '\n')[0];
                while (!tracerName.empty() &&
                       (tracerName.back() == ' ' || tracerName.back() == '\r' ||
                        tracerName.back() == '\t')) {
                    tracerName.pop_back();
                }
            }
            const bool tracerActive = !tracerName.empty() && tracerName != RSs("nop");
            if (tracerActive || !enabled.empty()) {
                Evidence e = makeHit(RSs("F.FTRACE"), Category::KERNEL,
                                     Severity::MEDIUM, Confidence::MEDIUM,
                                     RSs("ftrace 存在活动插桩"));
                e.addVector(RSs("tracer"), tracerName);
                e.addVector(RSs("enabled_lines"),
                            std::to_string(split(enabled, '\n').size()));
                e.detail = RSs("current_tracer 已启用或有函数被 ftrace 启用，"
                               "存在内核级插桩活动");
                r.add(std::move(e));
            } else if (!tracerRaw.empty()) {
                Evidence e = makePass(RSs("F.FTRACE"), Category::KERNEL,
                                      RSs("ftrace 函数表可读但无活动 tracer"));
                e.addVector(RSs("lines"), std::to_string(split(trace, '\n').size()));
                e.addVector(RSs("tracer"), tracerName);
                e.detail = RSs("可过滤函数表可读只是能力暴露，"
                               "current_tracer=nop 且无已启用函数，不构成 hook 证据");
                r.add(std::move(e));
            } else {
                Evidence u = makeUnknown(RSs("F.FTRACE"), Category::KERNEL,
                                         Severity::LOW,
                                         RSs("ftrace 函数表可读，但活动状态不可读，无法判定"));
                u.addVector(RSs("lines"), std::to_string(split(trace, '\n').size()));
                r.add(std::move(u));
            }
        }
    }
}

}  // namespace rs