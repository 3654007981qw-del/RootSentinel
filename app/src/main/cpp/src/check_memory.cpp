// check_memory.cpp — D 内存与注入
// 覆盖 v2 增强包【二.1】三视角、【二.2】mincore 主动探测、【二.3】安全内存扫描
#include "rs_checks.h"

#include <cstdio>
#include <cstring>
#include <link.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>
#include <vector>

#include "rs_str.h"
#include "rs_str.h"   // hotKeywords()
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

extern const std::vector<std::string>& hotKeywords();

namespace {

struct MapsEntry {
    uint64_t    start = 0;
    uint64_t    end   = 0;
    std::string perms;
    uint64_t    offset = 0;
    uint64_t    dev    = 0;
    uint64_t    inode  = 0;
    std::string path;
};

std::vector<MapsEntry> parseMaps() {
    std::vector<MapsEntry> out;
    auto lines = readLines(RSs("/proc/self/maps"), 20000);
    for (const auto& line : lines) {
        MapsEntry e;
        char perms[8] = {0};
        unsigned long long start = 0, end = 0, off = 0, ino = 0;
        char dev[16] = {0};
        char path[512] = {0};

        // 格式: start-end perms offset dev inode path
        int n = std::sscanf(line.c_str(), "%llx-%llx %7s %llx %15s %llu %511[^\n]",
                            &start, &end, perms, &off, dev, &ino, path);
        if (n < 6) continue;

        e.start = start;
        e.end = end;
        e.perms = perms;
        e.offset = off;
        e.inode = ino;
        if (n >= 7) e.path = trim(std::string(path));
        out.push_back(std::move(e));
    }
    return out;
}

// ---------------------------------------------------------------- ART 归属判定
//
// Android ART 运行时本身就会用 memfd 承载 JIT 代码，这是官方设计：
//   · memfd:jit-cache           JIT 编译产物（可执行）
//   · memfd:jit-zygote-cache    zygote 预热的 JIT 缓存
//   · memfd:jit-profile         采样配置文件（不可执行）
//   · memfd:decompressed-apk    从 apk 解压的 dex
//   · memfd:gralloc / dmabuf    图形栈
//   · memfd:perfetto            系统追踪
//   · memfd:scudo               Scudo 分配器
// 参考：art/MEMORY_MAPPING_GUIDE.md
//
// 旧实现把「路径含 memfd:」或「含 (deleted)」直接判 HIGH HIT，
// 在原厂未 root 的设备上必然误报 —— 那是 ART 的正常映射，不是注入。
//
// 现在改成联合验证：只有「不是 ART 归属」且「确实可疑」才命中。
bool isArtOwnedMemfd(const std::string& path) {
    static const std::vector<std::string> kArtMemfd = {
        RSs("memfd:jit-cache"),
        RSs("memfd:jit-zygote-cache"),
        RSs("memfd:jit-zygote"),
        RSs("memfd:jit-profile"),
        RSs("memfd:decompressed-apk"),
        RSs("memfd:gralloc"),
        RSs("memfd:dmabuf"),
        RSs("memfd:perfetto"),
        RSs("memfd:scudo"),
        RSs("memfd:userfaultfd"),
        RSs("memfd:dalvik"),
        RSs("memfd:oat"),
        RSs("memfd:dex"),
        RSs("memfd:dex2oat"),
        RSs("memfd:profile"),
    };
    return containsAny(path, kArtMemfd);
}

// ART 的常规落盘路径：dalvik-cache 与 oat 目录
bool isArtOwnedPath(const std::string& path) {
    static const std::vector<std::string> kArtPaths = {
        RSs("/data/dalvik-cache/"),
        RSs("/system/framework/oat/"),
        RSs("/system/framework/arm64/"),
        RSs("/system/framework/arm/"),
        RSs("/system/lib64/"),
        RSs("/system/lib/"),
        RSs("/system_ext/lib64/"),
        RSs("/system_ext/lib/"),
        RSs("/product/lib64/"),
        RSs("/product/lib/"),
        RSs("/vendor/lib64/"),
        RSs("/vendor/lib/"),
        RSs("/apex/"),
    };
    return containsAny(path, kArtPaths);
}

// 从 maps 行里取 (deleted) 之前的真实路径
std::string stripDeleted(const std::string& path) {
    const std::string tag = RSs("(deleted)");
    const size_t p = path.find(tag);
    if (p == std::string::npos) return path;
    return trim(path.substr(0, p));
}

// dl_iterate_phdr 收集的库列表
struct PhdrCtx {
    std::vector<std::string> names;
    std::vector<uint64_t>    bases;
};

int phdrCb(struct dl_phdr_info* info, size_t size, void* data) {
    auto* ctx = (PhdrCtx*)data;
    if (info->dlpi_name && info->dlpi_name[0] != '\0') {
        ctx->names.emplace_back(info->dlpi_name);
        ctx->bases.push_back((uint64_t)info->dlpi_addr);
    } else {
        ctx->names.emplace_back(RSs("<main>"));
        ctx->bases.push_back((uint64_t)info->dlpi_addr);
    }
    return 0;
}

}  // namespace

void checkMemory(ScanReport& r) {
    const auto maps = parseMaps();
    if (maps.empty()) {
        r.add(makeUnknown(RSs("D.MAPS"), Category::MEMORY, Severity::MEDIUM,
                          RSs("/proc/self/maps 不可读")));
        return;
    }

    // ---------------------------------------------------------- 二.1 三视角
    // 视角 1: maps   视角 2: dl_iterate_phdr   视角 3: map_files
    PhdrCtx ctx;
    dl_iterate_phdr(phdrCb, &ctx);

    // 从 maps 里提取所有 .so 路径
    std::vector<std::string> mapsLibs;
    for (const auto& m : maps) {
        if (!m.path.empty() && m.path[0] == '/') {
            if (endsWith(m.path, RSs(".so")) ||
                contains(m.path, RSs(".so.")) ||
                endsWith(m.path, RSs("linker64")) ||
                endsWith(m.path, RSs("linker"))) {
                mapsLibs.push_back(m.path);
            }
        }
    }

    // 视角 2 里出现、但视角 1 完全没有的库 → 摘链或 maps 过滤
    int missingInMaps = 0;
    std::string firstMissing;
    for (const auto& n : ctx.names) {
        if (n.empty() || n[0] != '/') continue;
        bool found = false;
        for (const auto& m : mapsLibs) {
            if (m == n) { found = true; break; }
        }
        if (!found) {
            if (missingInMaps == 0) firstMissing = n;
            ++missingInMaps;
        }
    }
    if (missingInMaps > 0) {
        Evidence e = makeHit(RSs("D.LIB_MULTI"), Category::MEMORY,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("dl_iterate_phdr 与 maps 不一致"));
        e.addVector(RSs("phdr_only"), std::to_string(missingInMaps));
        e.addVector(RSs("first"), firstMissing);
        e.detail = RSs("有库被从 maps 中摘掉（典型 Zygisk 行为）");
        e.addVector(RSs("maps_libs"), std::to_string(mapsLibs.size()));
        r.add(std::move(e));
    } else {
        Evidence e = makePass(RSs("D.LIB_MULTI"), Category::MEMORY,
                              RSs("dl_iterate_phdr 与 maps 一致"));
        e.addVector(RSs("libs"), std::to_string(mapsLibs.size()));
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 可疑库名
    const auto& kw = hotKeywords();
    int badLibs = 0;
    for (const auto& m : maps) {
        if (m.path.empty()) continue;
        const std::string lower = toLower(m.path);
        if (containsAny(lower, kw)) {
            ++badLibs;
            Evidence e = makeHit(RSs("D.SUSPECT_LIB"), Category::MEMORY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("maps 中出现可疑库: ") + m.path);
            e.addVector(RSs("range"), hex64(m.start) + "-" + hex64(m.end));
            e.addVector(RSs("perms"), m.perms);
            r.add(std::move(e));
        }
    }
    if (badLibs == 0) {
        r.add(makePass(RSs("D.SUSPECT_LIB"), Category::MEMORY,
                       RSs("maps 中无 root 组件库")));
    }

    // ---------------------------------------------------------- memfd / deleted
    //
    // 判定改成三级：
    //   ART 归属          → 计入 benign，不产生 HIT
    //   非 ART + 可执行    → HIGH HIT（真正的注入面）
    //   非 ART + 不可执行  → 只记 LOW 软证据（可能是临时文件，不足以判注入）
    int memfdTotal = 0;
    int memfdBenign = 0;
    int memfdSuspect = 0;
    int memfdSoft = 0;

    for (const auto& m : maps) {
        const bool hasMemfd = contains(m.path, RSs("memfd:"));
        const bool hasDeleted = contains(m.path, RSs("(deleted)"));
        if (!hasMemfd && !hasDeleted) continue;
        ++memfdTotal;

        const std::string real = stripDeleted(m.path);
        const bool exec = m.perms.find('x') != std::string::npos;

        // ART 正常映射：直接放行
        if (isArtOwnedMemfd(real) || isArtOwnedPath(real)) {
            ++memfdBenign;
            continue;
        }

        // 非 ART 归属的可执行 memfd / deleted 映射 → 高度可疑
        if (exec) {
            ++memfdSuspect;
            if (memfdSuspect <= 3) {
                Evidence e = makeHit(RSs("D.MEMFD"), Category::MEMORY,
                                     Severity::HIGH, Confidence::HIGH,
                                     RSs("非 ART 的可执行匿名映射: ") + m.path);
                e.addVector(RSs("perms"), m.perms);
                e.addVector(RSs("range"),
                            hex64(m.start) + RSs("-") + hex64(m.end));
                e.addVector(RSs("inode"), std::to_string(m.inode));
                e.detail = RSs("可执行且不属于 ART / dalvik-cache / oat / apex，"
                               "常见于 dlopen 内存加载或注入器");
                e.fp_note = RSs("少数加固壳与热修复框架也会产生此类映射，"
                                "需结合 E.SYMBOL_HOOK 与 I.TEXT_HASH 一起判断");
                r.add(std::move(e));
            }
            continue;
        }

        // 非 ART 归属但不可执行 → 只做软证据
        ++memfdSoft;
        if (memfdSoft <= 2) {
            Evidence e = makeHit(RSs("D.MEMFD_SOFT"), Category::MEMORY,
                                 Severity::LOW, Confidence::LOW,
                                 RSs("非 ART 的匿名映射（不可执行）: ") + m.path);
            e.addVector(RSs("perms"), m.perms);
            e.heuristic = true;
            e.detail = RSs("不可执行的匿名映射多为临时缓冲区，"
                           "单凭此项不足以判定注入");
            e.fp_note = RSs("大量正常应用（媒体、下载、压缩）都会创建临时匿名映射");
            r.add(std::move(e));
        }
    }

    if (memfdSuspect == 0) {
        Evidence e = makePass(RSs("D.MEMFD"), Category::MEMORY,
                              RSs("无可疑匿名可执行映射"));
        e.addVector(RSs("total"), std::to_string(memfdTotal));
        e.addVector(RSs("art_benign"), std::to_string(memfdBenign));
        e.addVector(RSs("suspect"), std::to_string(memfdSuspect));
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 匿名可执行
    int anonX = 0;
    for (const auto& m : maps) {
        if (m.path.empty() && m.perms.find('x') != std::string::npos) {
            ++anonX;
            if (anonX <= 2) {
                Evidence e = makeHit(RSs("D.ANON_EXEC"), Category::MEMORY,
                                     Severity::MEDIUM, Confidence::LOW,
                                     RSs("匿名可执行映射（需与 JIT 区分）"));
                e.addVector(RSs("range"), hex64(m.start) + "-" + hex64(m.end));
                e.heuristic = true;
                e.fp_note = RSs("ART 的 JIT code cache 也是匿名可执行，"
                                "必须结合 smaps 的 [anon:jit] 标记区分");
                r.add(std::move(e));
            }
        }
    }
    if (anonX == 0) {
        r.add(makePass(RSs("D.ANON_EXEC"), Category::MEMORY,
                       RSs("无匿名可执行映射")));
    }

    // ---------------------------------------------------------- 二.2 mincore 探测
    // 用 mincore 检查 maps 中每个可读段的驻留状态；
    // 若某段 mincore 返回 ENOMEM（不属于本进程地址空间）→ 说明 maps 与实际不符
    size_t pageSz = pageSize();
    int mincoreBad = 0;
    for (const auto& m : maps) {
        if (m.perms.find('r') == std::string::npos) continue;
        if (m.end <= m.start) continue;

        void* addr = (void*)(m.start & ~(uint64_t)(pageSz - 1));
        size_t len = (size_t)((m.end - (uint64_t)addr + pageSz - 1) & ~(pageSz - 1));
        if (len == 0 || len > (16u << 20)) continue;   // 跳过超大段，控制耗时

        std::vector<unsigned char> vec((len / pageSz) + 1);
        int rc = mincore(addr, len, vec.data());
        if (rc != 0) {
            ++mincoreBad;
        }
    }
    // v2.4.0：阈值从 >0 提到 >=3，severity 从 HIGH 降到 MEDIUM + heuristic。
    // mincore 对 [vvar] / guard 段 / PROT_NONE 子页返回 ENOMEM 是正常现象，
    // 单段失败就报 HIGH 会稳定误报。真被伪造 maps 时通常是一批段一起失败。
    if (mincoreBad >= 3) {
        Evidence e = makeHit(RSs("D.MINCORE"), Category::MEMORY,
                             Severity::MEDIUM, Confidence::MEDIUM,
                             RSs("mincore 与 maps 大面积不一致"));
        e.addVector(RSs("bad_segments"), std::to_string(mincoreBad));
        e.heuristic = true;
        e.detail = RSs("多段 mincore 返回 ENOMEM，maps 与实际地址空间不符 → "
                       "疑似 maps 被伪造");
        e.fp_note = RSs("1~2 段失败属正常（vvar / guard 页），"
                        "本项只在 ≥3 段时才判 HIT");
        r.add(std::move(e));
    } else if (mincoreBad > 0) {
        Evidence e = makePass(RSs("D.MINCORE"), Category::MEMORY,
                              RSs("mincore 与 maps 基本一致"));
        e.addVector(RSs("bad_segments"), std::to_string(mincoreBad));
        e.addVector(RSs("note"), RSs("少量 ENOMEM 属 vvar/guard 段正常行为"));
        r.add(std::move(e));
    } else {
        r.add(makePass(RSs("D.MINCORE"), Category::MEMORY,
                       RSs("mincore 与 maps 一致")));
    }

    // ---------------------------------------------------------- 二.3 内存残留扫描
    // 只通过 process_vm_readv 读自身，绝不直接解引用未知地址
    const pid_t self = sys::getpid_();
    int residueHits = 0;
    size_t scanned = 0;

    for (const auto& m : maps) {
        if (m.perms.find('r') == std::string::npos) continue;
        if (!m.path.empty()) continue;             // 只看匿名段
        if (m.end <= m.start) continue;

        size_t len = (size_t)(m.end - m.start);
        if (len > (2u << 20)) len = 2u << 20;      // 每段最多读 2MB
        if (scanned > (16u << 20)) break;          // 总预算 16MB
        scanned += len;

        std::vector<char> buf(len);
        struct iovec local { buf.data(), len };
        struct iovec remote { (void*)m.start, len };

        ssize_t got = sys::process_vm_readv_(self, &local, 1, &remote, 1, 0);
        if (got <= 0) continue;

        std::string view(buf.data(), (size_t)got);
        for (const auto& k : kw) {
            if (k.size() < 5) continue;            // 太短的串误报率高
            if (contains(view, k)) {
                ++residueHits;
                Evidence e = makeHit(RSs("D.MEM_RESIDUE"), Category::MEMORY,
                                     Severity::HIGH, Confidence::MEDIUM,
                                     RSs("匿名内存残留特征串: ") + k);
                e.addVector(RSs("segment"), hex64(m.start));
                e.heuristic = true;
                e.fp_note = RSs("检测器自身的字符串常量也会命中，"
                                "需与 watchedLibs 交叉后才能定论");
                r.add(std::move(e));
                break;
            }
        }
    }
    if (residueHits == 0) {
        Evidence e = makePass(RSs("D.MEM_RESIDUE"), Category::MEMORY,
                              RSs("匿名内存未发现注入残留"));
        e.addVector(RSs("bytes_scanned"), std::to_string(scanned));
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 线程名
    //
    // 这里原本也扫一遍线程名，与 check_threads.cpp 的 D.THREAD_NAME
    // 产生**同一个 ID**。评分器按 Evidence 逐条累加，
    // 于是「一个 frida 线程」会被算两次（HIGH 20 + CRITICAL 40 = 60），
    // 单条事实直接越过 ROOTED 阈值。
    //
    // 现在线程名判定统一交给 check_threads.cpp（特征表更全、定级 CRITICAL），
    // 这里只统计线程总数，不再产出 D.THREAD_NAME。

    // ---------------------------------------------------------- fd
    int badFd = 0;
    for (const auto& e : listDirRaw(RSs("/proc/self/fd"))) {
        const std::string target = readLink(RSs("/proc/self/fd/") + e.name);
        if (target.empty()) continue;
        if (contains(target, RSs("/data/adb")) || contains(target, RSs("memfd"))) {
            ++badFd;
            Evidence ev = makeHit(RSs("D.FD"), Category::MEMORY,
                                  Severity::HIGH, Confidence::MEDIUM,
                                  RSs("fd 指向可疑目标: ") + target);
            ev.addVector(RSs("fd"), e.name);
            r.add(std::move(ev));
        }
    }
    if (badFd == 0) {
        r.add(makePass(RSs("D.FD"), Category::MEMORY, RSs("fd 无可疑目标")));
    }
}

}  // namespace rs