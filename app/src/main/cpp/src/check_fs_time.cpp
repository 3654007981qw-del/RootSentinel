// check_fs_time.cpp — 文件时间戳 / 物理块 / 挂载传播（M9）
//
// 三条都很硬：
//   1) /system 是只读分区，原厂文件的 mtime 恒为构建时间（通常 2009-01-01）。
//      任何工具要改里面的文件，mtime 必然变成"最近"。
//   2) FIEMAP 能问内核「这个文件的第 N 个字节落在哪个物理块上」。
//      overlayfs 的副本没有真实物理块（fe_physical == 0）。
//   3) 挂载传播标记（shared:X / master:X）能暴露 bind mount 造成的父子挂载关系。
#include "rs_checks.h"

#include <cstdio>
#include <ctime>
#include <fcntl.h>
#include <linux/fiemap.h>
#include <linux/fs.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

// 返回文件 mtime 距今天数；失败返回 -1
double fileAgeDays(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return -1.0;
    const double now = (double)std::time(nullptr);
    return (now - (double)st.st_mtime) / 86400.0;
}

}  // namespace

void checkFsTime(ScanReport& r) {

    // ------------------------------------------------------ C.SYSFILE_MTIME
    // /system 是只读分区。原厂文件的 mtime 是构建时间（很旧）。
    // 若某个系统文件最近被改过 → 一定有人挂载覆盖过它。
    {
        static const std::vector<std::string> targets = {
            RSs("/system/bin/sh"),
            RSs("/system/bin/app_process64"),
            RSs("/system/build.prop"),
        };

        int checked = 0, recent = 0;
        std::vector<std::string> lines;

        for (const auto& t : targets) {
            const double age = fileAgeDays(t);
            if (age < 0.0) continue;
            ++checked;

            char buf[96];
            std::snprintf(buf, sizeof(buf), "%s age=%.0fd", t.c_str(), age);

            if (age < 60.0) {
                ++recent;
                if (lines.size() < 4) lines.push_back(buf);
            } else if (lines.size() < 4) {
                lines.push_back(buf);
            }
        }

        if (checked == 0) {
            r.add(makeUnknown(RSs("C.SYSFILE_MTIME"), Category::MOUNT,
                              Severity::LOW, RSs("系统文件不可 stat")));
        } else if (recent > 0) {
            Evidence e = makeHit(RSs("C.SYSFILE_MTIME"), Category::MOUNT,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("系统文件最近被修改过"));
            for (const auto& s : lines) e.addVector(RSs("file"), s);
            e.detail = RSs("/system 是只读分区，原厂文件 mtime 恒为构建时间；"
                           "出现「近 60 天内修改」说明被挂载覆盖");
            e.fp_note = RSs("刚做完 OTA 升级后系统文件时间会更新，"
                            "此时需结合升级记录判断");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("C.SYSFILE_MTIME"), Category::MOUNT,
                                  RSs("系统文件时间戳正常"));
            for (const auto& s : lines) e.addVector(RSs("file"), s);
            r.add(std::move(e));
        }
    }

    // -------------------------------------------------------- A.PROP_MTIME
    // build.prop 的 mtime 单独看：原厂恒为 2009-01-01（epoch 0 + 时区）。
    // resetprop 只改内存属性不动文件，但改文件的话 mtime 会暴露。
    {
        const double age = fileAgeDays(RSs("/system/build.prop"));
        if (age < 0.0) {
            r.add(makeUnknown(RSs("A.PROP_MTIME"), Category::ARTIFACT,
                              Severity::LOW, RSs("build.prop 不可 stat")));
        } else {
            char buf[48];
            std::snprintf(buf, sizeof(buf), "%.0f", age);

            if (age < 60.0) {
                Evidence e = makeHit(RSs("A.PROP_MTIME"), Category::ARTIFACT,
                                     Severity::HIGH, Confidence::MEDIUM,
                                     RSs("build.prop 最近被修改"));
                e.addVector(RSs("age_days"), buf);
                e.detail = RSs("原厂 build.prop 的 mtime 是构建时间（很旧）；"
                               "近期修改说明属性文件被改写");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("A.PROP_MTIME"), Category::ARTIFACT,
                                      RSs("build.prop 时间戳正常"));
                e.addVector(RSs("age_days"), buf);
                r.add(std::move(e));
            }
        }
    }

    // ------------------------------------------------------------- C.FIEMAP
    // 用 FS_IOC_FIEMAP 问内核物理块映射。
    // overlayfs 的 upper 层副本在底层文件系统上是真实文件（有物理块），
    // 但如果读到 fe_physical == 0 且 fe_flags 含 UNKNOWN/DELALLOC，
    // 说明这个区间是稀疏/未落盘的。
    {
        const int fd = sys::openat_(RSs("/system/bin/sh").c_str(),
                                    O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            r.add(makeUnknown(RSs("C.FIEMAP"), Category::MOUNT, Severity::LOW,
                              RSs("/system/bin/sh 不可打开")));
        } else {
            struct {
                struct fiemap fm;
                struct fiemap_extent ext[8];
            } req {};

            req.fm.fm_start = 0;
            req.fm.fm_length = 4096;
            req.fm.fm_flags = FIEMAP_FLAG_SYNC;
            req.fm.fm_extent_count = 8;

            const int rc = ioctl(fd, FS_IOC_FIEMAP, &req);
            sys::close_(fd);

            if (rc != 0) {
                r.add(makeUnknown(RSs("C.FIEMAP"), Category::MOUNT, Severity::LOW,
                                  RSs("FS_IOC_FIEMAP 不支持")));
            } else if (req.fm.fm_mapped_extents == 0) {
                Evidence e = makeHit(RSs("C.FIEMAP"), Category::MOUNT,
                                     Severity::MEDIUM, Confidence::MEDIUM,
                                     RSs("系统文件无物理块映射"));
                e.addVector(RSs("mapped"), "0");
                e.detail = RSs("文件前 4KB 没有任何物理 extent → "
                               "可能是 overlay / 稀疏副本");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("C.FIEMAP"), Category::MOUNT,
                                      RSs("系统文件物理块正常"));
                e.addVector(RSs("mapped"),
                            std::to_string(req.fm.fm_mapped_extents));
                e.addVector(RSs("physical"), hex64(req.ext[0].fe_physical));
                e.addVector(RSs("flags"),
                            std::to_string(req.ext[0].fe_flags));
                r.add(std::move(e));
            }
        }
    }

    // -------------------------------------------------- C.MOUNT_PROPAGATION
    // Android 从 init 的挂载命名空间派生所有挂载，
    // 所以几乎每个挂载点都带 master:X —— 这本身完全正常。
    // 真正可疑的是：挂载**源**指向临时目录（/data/local/tmp、/tmp）。
    {
        const auto mi = readFile(RSs("/proc/self/mountinfo"), 1u << 20);
        if (mi.empty()) {
            r.add(makeUnknown(RSs("C.MOUNT_PROPAGATION"), Category::MOUNT,
                              Severity::LOW, RSs("mountinfo 不可读")));
        } else {
            int withMaster = 0;
            std::vector<std::string> odd;

            for (const auto& line : split(mi, '\n')) {
                if (line.empty()) continue;
                if (contains(line, RSs(" master:"))) ++withMaster;

                // mountinfo 格式：... - <fstype> <source> <superopts>
                const auto dash = line.find(RSs(" - "));
                if (dash == std::string::npos) continue;
                const auto tail = line.substr(dash + 3);
                const auto sp1 = tail.find(' ');
                if (sp1 == std::string::npos) continue;
                const auto sp2 = tail.find(' ', sp1 + 1);
                if (sp2 == std::string::npos) continue;
                const auto src = tail.substr(sp1 + 1, sp2 - sp1 - 1);

                if (startsWith(src, RSs("/data/local/tmp")) ||
                    startsWith(src, RSs("/tmp/")) ||
                    startsWith(src, RSs("/sdcard/"))) {
                    if (odd.size() < 5) odd.push_back(src);
                }
            }

            if (!odd.empty()) {
                Evidence e = makeHit(RSs("C.MOUNT_PROPAGATION"), Category::MOUNT,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("挂载源位于临时目录"));
                for (const auto& s : odd) e.addVector(RSs("source"), s);
                e.addVector(RSs("master_count"), std::to_string(withMaster));
                e.detail = RSs("把临时目录挂到系统路径是典型的 overlay 注入手法");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("C.MOUNT_PROPAGATION"), Category::MOUNT,
                                      RSs("无临时目录挂载源"));
                e.addVector(RSs("master_count"), std::to_string(withMaster));
                e.detail = RSs("Android 从 init 派生所有挂载，master 标记属正常");
                r.add(std::move(e));
            }
        }
    }
}

}  // namespace rs