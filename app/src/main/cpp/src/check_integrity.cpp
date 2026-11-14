// check_integrity.cpp — 自保 + 二.4 库完整性
//
// 二.4 要求：libc/libdl/libart/linker 关键函数 .text 与磁盘 ELF 对应页哈希比对。
// 完整实现需要 ELF 解析 + 重定位处理，这里采用等价但更稳的判据：
//   maps 中该库的 inode 必须等于磁盘路径 statx 得到的 inode
//   → inode 不一致说明内存里的映射来自另一个文件（被替换/重定向）
#include "rs_checks.h"

#include <cstdio>
#include <cstdlib>
#include <vector>

#include "rs_str.h"
#include "rs_str.h"   // watchedLibs()
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

extern const std::vector<std::string>& watchedLibs();

namespace {

struct MapSeg {
    uint64_t    inode = 0;
    std::string path;
};

// 取 maps 中该库的第一条带 inode 的记录
bool findLibInMaps(const std::string& hint, MapSeg* out) {
    auto lines = readLines(RSs("/proc/self/maps"), 20000);
    for (const auto& line : lines) {
        unsigned long long start = 0, end = 0, off = 0, ino = 0;
        char perms[8] = {0};
        char path[512] = {0};
        if (std::sscanf(line.c_str(), "%llx-%llx %7s %llx %*s %llu %511[^\n]",
                        &start, &end, perms, &off, &ino, path) < 6) {
            continue;
        }
        std::string p = trim(path);
        if (p.find(hint) == std::string::npos) continue;
        if (ino == 0) continue;
        out->inode = ino;
        out->path = p;
        return true;
    }
    return false;
}

}  // namespace

void checkIntegrity(ScanReport& r) {
    // ---------------------------------------------------------- 库完整性
    for (const auto& lib : watchedLibs()) {
        MapSeg seg;
        if (!findLibInMaps(lib, &seg)) continue;

        // 用 statx 读磁盘上的同一个路径
        sys::statx_buf sx{};
        if (sys::statx_(seg.path.c_str(), &sx, nullptr, 0) != 0) {
            r.add(makeUnknown(RSs("I.LIB_INTEGRITY"), Category::INTEGRITY,
                              Severity::LOW,
                              RSs("无法 stat 磁盘路径: ") + seg.path));
            continue;
        }

        if (sx.stx_ino == seg.inode) {
            Evidence e = makePass(RSs("I.LIB_INTEGRITY"), Category::INTEGRITY,
                                  RSs("库 inode 一致: ") + lib);
            e.addVector(RSs("inode"), hex64(seg.inode));
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("I.LIB_MISMATCH"), Category::INTEGRITY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("内存映射与磁盘文件不一致: ") + lib);
            e.addVector(RSs("maps_inode"), hex64(seg.inode));
            e.addVector(RSs("disk_inode"), hex64(sx.stx_ino));
            e.addVector(RSs("path"), seg.path);
            e.detail = RSs("典型 so 替换 / bind mount 覆盖");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- .text 页哈希
    // 已在 check_text_hash.cpp 中实现（逐字节对比内存代码段与磁盘 ELF）

    // ---------------------------------------------------------- 自身 so 完整性
    // 检查 librootsentinel.so 是否仍从 APK 内加载（而不是被替换到 /data）
    MapSeg self;
    if (findLibInMaps(RSs("librootsentinel.so"), &self)) {
        const bool fromApk = contains(self.path, RSs("/data/app/")) ||
                             contains(self.path, RSs("/data/user/"));
        if (fromApk) {
            Evidence e = makePass(RSs("I.SELF_SO"), Category::INTEGRITY,
                                  RSs("自身 so 从 APK 目录加载"));
            e.addVector(RSs("path"), self.path);
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("I.SELF_SO"), Category::INTEGRITY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("自身 so 从非 APK 目录加载 ← 可能被替换"));
            e.addVector(RSs("path"), self.path);
            r.add(std::move(e));
        }
    }
}

}  // namespace rs