// check_fs_meta.cpp — 文件系统元数据检测（M2）
//
// 覆盖 M1 没做的三个角度：
//   1. overlay 的 upperdir 落在哪里（模块改系统分区的核心手法）
//   2. SELinux 扩展属性能否读到（App 视角下 xattr 的可见性）
//   3. inode 号是否存在 overlay 特征（合成 inode 通常异常大）
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

struct OverlayInfo {
    std::string mountPoint;
    std::string lowerdir;
    std::string upperdir;
    std::string workdir;
};

std::string extractOpt(const std::string& superOpts, const std::string& key) {
    std::string pat = key + "=";
    size_t p = superOpts.find(pat);
    if (p == std::string::npos) return {};
    std::string rest = superOpts.substr(p + pat.size());
    size_t comma = rest.find(',');
    return (comma == std::string::npos) ? rest : rest.substr(0, comma);
}

}  // namespace

void checkFsMeta(ScanReport& r) {
    // ---------------------------------------------------------- overlay upperdir
    auto lines = readLines(RSs("/proc/self/mountinfo"), 8192);
    std::vector<OverlayInfo> overlays;

    for (const auto& line : lines) {
        auto f = split(line, ' ');
        if (f.size() < 8) continue;

        size_t dash = 0;
        bool found = false;
        for (size_t i = 0; i < f.size(); ++i) {
            if (f[i] == "-") { dash = i; found = true; break; }
        }
        if (!found || dash + 3 >= f.size()) continue;

        const std::string fstype = f[dash + 1];
        if (fstype != RSs("overlay")) continue;

        OverlayInfo oi;
        oi.mountPoint = f[4];
        oi.lowerdir = extractOpt(f[dash + 3], RSs("lowerdir"));
        oi.upperdir = extractOpt(f[dash + 3], RSs("upperdir"));
        oi.workdir  = extractOpt(f[dash + 3], RSs("workdir"));
        overlays.push_back(std::move(oi));
    }

    int overlayHit = 0;
    for (const auto& o : overlays) {
        if (o.upperdir.empty()) continue;
        ++overlayHit;

        const bool inData = contains(o.upperdir, RSs("/data/adb")) ||
                            contains(o.upperdir, RSs("/data/local")) ||
                            contains(o.upperdir, RSs("/data/misc"));

        Evidence e = makeHit(RSs("C.OVERLAY_UPPER"), Category::MOUNT,
                             inData ? Severity::CRITICAL : Severity::MEDIUM,
                             inData ? Confidence::HIGH : Confidence::MEDIUM,
                             RSs("overlay 挂载带 upperdir: ") + o.mountPoint);
        e.addVector(RSs("upperdir"), o.upperdir);
        if (!o.lowerdir.empty()) e.addVector(RSs("lowerdir"), o.lowerdir);
        if (inData) {
            e.detail = RSs("upperdir 位于 /data 的 root 方案目录 —— 模块正在改写系统分区");
        }
        e.fp_note = RSs("Android 10+ 的 /product、/system_ext 可能用 overlay 做 RRO，"
                        "upperdir 通常在 /data/resource-cache 或 /mnt");
        r.add(std::move(e));
    }
    if (overlayHit == 0) {
        r.add(makePass(RSs("C.OVERLAY_UPPER"), Category::MOUNT,
                       RSs("无带 upperdir 的 overlay 挂载")));
    }

    // ---------------------------------------------------------- xattr 可见性
    {
        char buf[512] = {0};
        long n = sys::raw(sys::NR_getxattr, (long)RSs("/system").c_str(),
                          (long)RSs("security.selinux").c_str(),
                          (long)buf, (long)sizeof(buf) - 1, 0, 0);
        if (n > 0) {
            buf[n] = '\0';
            Evidence e = makePass(RSs("C.XATTR"), Category::MOUNT,
                                  RSs("/system 的 SELinux 标签可读"));
            e.addVector(RSs("security.selinux"), buf);
            r.add(std::move(e));
        } else if (-n == EPERM || -n == EACCES) {
            r.add(makePass(RSs("C.XATTR"), Category::MOUNT,
                           RSs("xattr 读取被拒（App 视角正常）")));
        } else {
            r.add(makeUnknown(RSs("C.XATTR"), Category::MOUNT, Severity::LOW,
                              RSs("xattr 读取返回未知结果")));
        }
    }

    // ---------------------------------------------------------- inode 特征
    {
        static const std::vector<std::string> paths = {
            RSs("/system/bin/sh"),
            RSs("/system/build.prop"),
            RSs("/vendor/build.prop"),
        };
        for (const auto& p : paths) {
            sys::statx_buf sx{};
            if (sys::statx_(p.c_str(), &sx, nullptr, 0) != 0) continue;

            Evidence e = makePass(RSs("C.INODE"), Category::MOUNT,
                                  RSs("inode 已记录: ") + p);
            e.addVector(RSs("ino"), hex64(sx.stx_ino));
            e.addVector(RSs("dev"), std::to_string(sx.stx_dev_major) + ":" +
                                    std::to_string(sx.stx_dev_minor));

            // overlayfs 的合成 inode 通常很大（低 32 位来自 lower/upper 拼接）
            if (sx.stx_ino > 0xFFFFFFFFULL) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::LOW;
                e.confidence = Confidence::LOW;
                e.heuristic = true;
                e.title = RSs("inode 号大于 32 位（可能是 overlay 合成 inode）: ") + p;
                e.fp_note = RSs("f2fs/ext4 的 inode 也可能很大，软信号");
            }
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- 硬链接计数
    {
        static const std::vector<std::string> paths = {
            RSs("/system/bin/sh"),
            RSs("/system/build.prop"),
        };
        for (const auto& p : paths) {
            sys::statx_buf sx{};
            if (sys::statx_(p.c_str(), &sx, nullptr, 0) != 0) continue;
            if (sx.stx_nlink > 4) {
                Evidence e = makeHit(RSs("C.NLINK"), Category::MOUNT,
                                     Severity::LOW, Confidence::LOW,
                                     RSs("硬链接数异常: ") + p);
                e.addVector(RSs("nlink"), std::to_string(sx.stx_nlink));
                e.heuristic = true;
                e.fp_note = RSs("overlay 下层文件会有额外链接；也可能是 busybox 多调用");
                r.add(std::move(e));
            }
        }
    }
}

}  // namespace rs