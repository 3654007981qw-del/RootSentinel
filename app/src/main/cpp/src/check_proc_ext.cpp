// check_proc_ext.cpp — /proc 深度扩展（M9）
//
// 这一层看的是「进程内部结构是否符合真机形态」：
//   SigCgt      被捕获的信号位图（hook 常改）
//   pagemap     物理页探测能力
//   numa_maps   第二个 maps 视角
//   VMA 数      maps vs smaps 条目一致性
//   fd 数量     句柄泄漏 / 注入留下的管道
//   vdso        vDSO 映射是否被替换
//   库加载顺序  libc 必须最早加载
#include "rs_checks.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

std::string statusField(const std::vector<std::string>& lines,
                        const std::string& key) {
    const auto prefix = key + RSs(":");
    for (const auto& l : lines) {
        if (startsWith(l, prefix)) return trim(l.substr(prefix.size()));
    }
    return {};
}

int countLinesIn(const std::string& path, size_t maxBytes) {
    const auto d = readFile(path, maxBytes);
    if (d.empty()) return -1;
    int n = 0;
    for (const char c : d) {
        if (c == '\n') ++n;
    }
    return n;
}

}  // namespace

void checkProcExt(ScanReport& r) {
    const auto status = readFile(RSs("/proc/self/status"), 64 * 1024);
    const auto stLines = split(status, '\n');

    // 正常 App 会捕获一批信号 —— ART 的 crash handler 要处理
// SIGSEGV/SIGBUS/SIGILL/SIGFPE/SIGABRT 来生成 tombstone。
// 所以「捕获了调试类信号」不能作为判据。
    // 真正的异常是捕获信号数量极多（覆盖几乎全部 64 个位）。
    {
        const auto sigCgt = statusField(stLines, RSs("SigCgt"));
        const auto sigBlk = statusField(stLines, RSs("SigBlk"));

        if (sigCgt.empty()) {
            r.add(makeUnknown(RSs("D.SIGNAL_HANDLERS"), Category::MEMORY,
                              Severity::LOW, RSs("SigCgt 字段缺失")));
        } else {
            unsigned long long v = 0;
            try {
                v = std::stoull(sigCgt, nullptr, 16);
            } catch (...) {
                v = 0;
            }

            int bits = 0;
            for (int i = 0; i < 64; ++i) {
                if ((v >> i) & 1ULL) ++bits;
            }

            if (bits > 30) {
                Evidence e = makeHit(RSs("D.SIGNAL_HANDLERS"), Category::MEMORY,
                                     Severity::MEDIUM, Confidence::LOW,
                                     RSs("捕获信号数量极多"));
                e.addVector(RSs("SigCgt"), sigCgt);
                e.addVector(RSs("bits"), std::to_string(bits));
                e.detail = RSs("ART 的 crash handler 约占 5~10 个位；"
                               "超过 30 个说明有人接管了几乎所有信号");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("D.SIGNAL_HANDLERS"), Category::MEMORY,
                                      RSs("信号处理器正常"));
                e.addVector(RSs("SigCgt"), sigCgt);
                e.addVector(RSs("bits"), std::to_string(bits));
                if (!sigBlk.empty()) e.addVector(RSs("SigBlk"), sigBlk);
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- D.PAGEMAP
    // /proc/self/pagemap 能反查虚拟页对应的物理页帧号。
    // 内核 4.0+ 要求 CAP_SYS_ADMIN 才能读真实 PFN，
    // 普通 App 读到的全是 0，但**能打开**本身是正常的。
    {
        const int st = accessState(RSs("/proc/self/pagemap"), 4);
        if (st == 0) {
            r.add(makePass(RSs("D.PAGEMAP"), Category::MEMORY,
                           RSs("pagemap 可打开（内容受内核限制）")));
        } else if (st == 1) {
            r.add(makeUnknown(RSs("D.PAGEMAP"), Category::MEMORY, Severity::LOW,
                              RSs("pagemap 被拒绝")));
        } else {
            r.add(makePass(RSs("D.PAGEMAP"), Category::MEMORY,
                           RSs("pagemap 不存在（旧内核，正常）")));
        }
    }

    // -------------------------------------------------------- D.NUMA_MAPS
    // numa_maps 是 maps 的第二个视角。
    // susfs 之类的隐藏方案常只过滤 maps，漏掉 numa_maps。
    {
        const int mapsN  = countLinesIn(RSs("/proc/self/maps"), 8u << 20);
        const int numaN  = countLinesIn(RSs("/proc/self/numa_maps"), 8u << 20);

        if (mapsN < 0 || numaN < 0) {
            r.add(makeUnknown(RSs("D.NUMA_MAPS"), Category::MEMORY, Severity::LOW,
                              RSs("numa_maps 不可读")));
        } else {
            // numa_maps 会把连续同属性区间合并，通常比 maps 少
            const double ratio = mapsN > 0 ? (double)numaN / (double)mapsN : 0.0;
            Evidence e = makePass(RSs("D.NUMA_MAPS"), Category::MEMORY,
                                  RSs("maps 与 numa_maps 双视角已读取"));
            e.addVector(RSs("maps_lines"), std::to_string(mapsN));
            e.addVector(RSs("numa_lines"), std::to_string(numaN));
            {
                char buf[32];
                std::snprintf(buf, sizeof(buf), "%.2f", ratio);
                e.addVector(RSs("ratio"), buf);
            }
            if (numaN > mapsN) {
                e.detail = RSs("numa_maps 条目多于 maps → "
                               "可能有一侧被过滤过");
            }
            r.add(std::move(e));
        }
    }

    // --------------------------------------------------------- D.VMA_COUNT
    {
        const int mapsN  = countLinesIn(RSs("/proc/self/maps"), 8u << 20);
        const int smapsN = countLinesIn(RSs("/proc/self/smaps"), 16u << 20);

        if (mapsN < 0 || smapsN < 0) {
            r.add(makeUnknown(RSs("D.VMA_COUNT"), Category::MEMORY, Severity::LOW,
                              RSs("smaps 不可读")));
        } else {
            Evidence e = makePass(RSs("D.VMA_COUNT"), Category::MEMORY,
                                  RSs("VMA 数量已统计"));
            e.addVector(RSs("maps"), std::to_string(mapsN));
            e.addVector(RSs("smaps_head"), std::to_string(smapsN));
            e.detail = RSs("smaps 每个 VMA 有 20+ 行属性，行数远多于 maps 属正常");
            r.add(std::move(e));
        }
    }

    // ----------------------------------------------------------- D.FD_COUNT
    {
        int n = 0;
        for (const auto& d : listDirRaw(RSs("/proc/self/fd"))) {
            if (d.name.empty() || d.name[0] == '.') continue;
            ++n;
        }

        if (n <= 0) {
            r.add(makeUnknown(RSs("D.FD_COUNT"), Category::MEMORY, Severity::LOW,
                              RSs("/proc/self/fd 不可枚举")));
        } else if (n > 300) {
            Evidence e = makeHit(RSs("D.FD_COUNT"), Category::MEMORY,
                                 Severity::MEDIUM, Confidence::LOW,
                                 RSs("文件描述符数量异常多"));
            e.addVector(RSs("count"), std::to_string(n));
            e.detail = RSs("正常 App 约 60~150 个 fd；过多可能是句柄泄漏"
                           "或注入框架留下的管道/socket");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("D.FD_COUNT"), Category::MEMORY,
                                  RSs("文件描述符数量正常"));
            e.addVector(RSs("count"), std::to_string(n));
            r.add(std::move(e));
        }
    }

    // --------------------------------------------------------------- D.VDSO
    // vDSO 是内核映射进来的只读代码页，名字恒为 [vdso]。
    // 被替换后可能改名或变成可写。
    {
        const auto maps = readFile(RSs("/proc/self/maps"), 8u << 20);
        std::string vdsoLine;
        for (const auto& l : split(maps, '\n')) {
            if (contains(l, RSs("[vdso]"))) { vdsoLine = l; break; }
        }

        if (vdsoLine.empty()) {
            r.add(makeUnknown(RSs("D.VDSO"), Category::MEMORY, Severity::LOW,
                              RSs("未找到 vdso 映射")));
        } else {
            const bool writable = contains(vdsoLine, RSs("rw"));
            if (writable) {
                Evidence e = makeHit(RSs("D.VDSO"), Category::MEMORY,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("vDSO 映射可写"));
                e.addVector(RSs("line"), vdsoLine.substr(0, 90));
                e.detail = RSs("vDSO 必须只读可执行；可写说明被改写");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("D.VDSO"), Category::MEMORY,
                                      RSs("vDSO 映射正常"));
                e.addVector(RSs("line"), vdsoLine.substr(0, 90));
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- D.LIB_ORDER
    // Android 14+ 的 APEX 库（如 libmainlinecronet）会在 libc 之前映射，
    // 所以「libc 必须最早」这个假设在 Android 14+ 上不成立。
    // 真正可疑的是：libc 之前出现**非规范路径**的 .so。
    {
        const auto lines = split(readFile(RSs("/proc/self/maps"), 8u << 20), '\n');
        std::string firstSo;
        std::vector<std::string> beforeLibc;
        bool sawLibc = false;

        for (const auto& l : lines) {
            if (!contains(l, RSs(".so"))) continue;
            const auto sp = l.find(RSs("/"));
            if (sp == std::string::npos) continue;
            const auto path = trim(l.substr(sp));

            if (firstSo.empty()) firstSo = path;

            if (contains(path, RSs("libc.so"))) {
                sawLibc = true;
                break;
            }
            if (beforeLibc.size() < 8) beforeLibc.push_back(path);
        }

        std::vector<std::string> odd;
        for (const auto& p : beforeLibc) {
            const bool normal =
                startsWith(p, RSs("/apex/")) ||
                startsWith(p, RSs("/system/")) ||
                startsWith(p, RSs("/system_ext/")) ||
                startsWith(p, RSs("/product/")) ||
                startsWith(p, RSs("/vendor/")) ||
                startsWith(p, RSs("/data/app/"));
            if (!normal) odd.push_back(p);
        }

        if (!sawLibc) {
            r.add(makeUnknown(RSs("D.LIB_ORDER"), Category::MEMORY, Severity::LOW,
                              RSs("未找到 libc 映射")));
        } else if (!odd.empty()) {
            Evidence e = makeHit(RSs("D.LIB_ORDER"), Category::MEMORY,
                                 Severity::HIGH, Confidence::MEDIUM,
                                 RSs("libc 之前加载了非规范路径的 .so"));
            for (const auto& s : odd) e.addVector(RSs("odd"), s.substr(0, 90));
            e.addVector(RSs("first"), firstSo.substr(0, 80));
            e.detail = RSs("ld-android / libc / APEX 库之外的 so "
                           "出现在 libc 之前 → 有预加载注入");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("D.LIB_ORDER"), Category::MEMORY,
                                  RSs("库加载顺序正常"));
            e.addVector(RSs("before_libc"), std::to_string(beforeLibc.size()));
            e.addVector(RSs("first"), firstSo.substr(0, 80));
            r.add(std::move(e));
        }
    }
}

}  // namespace rs