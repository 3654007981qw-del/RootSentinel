// check_artifacts.cpp — A 经典痕迹
// v2 增强包【一.1】要求同一路径用多条独立 syscall 互验：
//   statx(raw) / faccessat(raw) / libc access / readlinkat
#include "rs_checks.h"

#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

void checkArtifacts(ScanReport& r) {
    // ---------------------------------------------------------- su 路径
    const std::vector<std::string> paths = {
        RSs("/system/bin/su"),       RSs("/system/xbin/su"),
        RSs("/sbin/su"),             RSs("/su"),
        RSs("/system/bin/ksu"),      RSs("/system/bin/ksud"),
        RSs("/system/bin/magisk"),   RSs("/system/bin/busybox"),
        RSs("/system/bin/daemonsu"), RSs("/debug_ramdisk/su"),
        RSs("/debug_ramdisk/.magisk"), RSs("/vendor/bin/su"),
        RSs("/system/sbin/su"),
    };

    bool found = false;
    for (const auto& p : paths) {
        const bool existsRaw  = fileExistsRaw(p);
        const int  facc       = accessState(p, R_OK);
        const bool existsLibc = (::access(p.c_str(), F_OK) == 0);

        // 三视角任一命中即记录；视角之间的差异本身也是证据
        if (!existsRaw && !existsLibc && facc == 2) continue;

        found = true;
        const bool disagrees = (existsRaw != existsLibc) || (facc == 1);

        Evidence e = makeHit(
            RSs("A.SU_PATH"), Category::ARTIFACT,
            disagrees ? Severity::CRITICAL : Severity::HIGH,
            disagrees ? Confidence::HIGH : Confidence::MEDIUM,
            RSs("发现 root 二进制或残留: ") + p);
        e.addVector(RSs("statx"), existsRaw ? RSs("exists") : RSs("absent"));
        e.addVector(RSs("faccessat"),
                    facc == 0 ? RSs("readable")
                              : (facc == 1 ? RSs("EACCES") : RSs("ENOENT")));
        e.addVector(RSs("libc_access"), existsLibc ? RSs("exists") : RSs("absent"));
        if (disagrees) {
            e.detail = RSs("多个视角结论不一致 —— 存在路径级过滤");
            e.heuristic = false;
        }
        e.fp_note = RSs("少数定制 ROM 会预置 busybox 作为系统工具；"
                        "这种情况下 /system/bin/busybox 可读但无 su 同目录");
        r.add(std::move(e));
    }
    if (!found) {
        r.add(makePass(RSs("A.SU_PATH"), Category::ARTIFACT,
                       RSs("13 条经典 su 路径在所有视角下均不可见")));
    }

    // ---------------------------------------------------------- /data/adb
    // 这是唯一有意义的 EACCES：/data 是 0771，App 有 x 位能 stat 子项
    const std::string kAdb = RSs("/data/adb");
    const int st = accessState(kAdb, 0 /* F_OK */);

    if (st == 0) {
        sys::statx_buf sx{};
        const bool isDir = (sys::statx_(kAdb.c_str(), &sx, nullptr, 0) == 0);
        Evidence e = makeHit(RSs("A.DATA_ADB"), Category::ARTIFACT,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("/data/adb 可访问 ← root 方案数据目录外露"));
        e.addVector(RSs("faccessat"), RSs("visible"));
        e.addVector(RSs("statx"), isDir ? RSs("dir") : RSs("unknown"));
        r.add(std::move(e));
    } else if (st == 1) {
        Evidence e = makeHit(RSs("A.DATA_ADB"), Category::ARTIFACT,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("/data/adb 返回 EACCES ← 目录存在（原厂没有此目录）"));
        e.addVector(RSs("faccessat"), RSs("EACCES"));
        e.addVector(RSs("statx"),
                    fileExistsRaw(kAdb) ? RSs("exists") : RSs("hidden"));
        e.fp_note = RSs("极少数老设备 ROM 会预置 /data/adb；"
                        "vivo/OPPO/小米原厂均无");
        r.add(std::move(e));
    } else {
        r.add(makePass(RSs("A.DATA_ADB"), Category::ARTIFACT,
                       RSs("/data/adb 返回 ENOENT（隐藏完整）")));
    }

    // ---------------------------------------------------------- 目录枚举互验
    // v2 一.2：getdents64 列得出但 stat 拿不到（或反之）即为过滤证据
    const std::vector<std::string> dirs = {
        RSs("/system/bin"), RSs("/system/xbin"), RSs("/sbin"),
        RSs("/system/etc/init"),
    };

    for (const auto& d : dirs) {
        auto rawEntries = listDirRaw(d);
        if (rawEntries.empty()) {
            // 目录本身不可读，跳过（不是证据）
            continue;
        }
        int missing = 0;
        std::string firstMissing;
        for (const auto& e : rawEntries) {
            const std::string full = d + "/" + e.name;
            if (!fileExistsRaw(full)) {
                if (missing == 0) firstMissing = full;
                ++missing;
            }
        }
        if (missing > 0) {
            Evidence ev = makeHit(
                RSs("A.DIR_ENUM_MISMATCH"), Category::ARTIFACT,
                Severity::HIGH, Confidence::HIGH,
                RSs("目录枚举与单点查询矛盾: ") + d);
            ev.addVector(RSs("getdents64"), std::to_string(rawEntries.size()));
            ev.addVector(RSs("statx_missing"), std::to_string(missing));
            ev.addVector(RSs("first_missing"), firstMissing);
            ev.detail = RSs("典型 getdents 过滤或 stat 层过滤");
            r.add(std::move(ev));
        } else {
            Evidence ev = makePass(RSs("A.DIR_ENUM_MISMATCH"), Category::ARTIFACT,
                                   RSs("目录枚举与单点查询一致: ") + d);
            ev.addVector(RSs("entries"), std::to_string(rawEntries.size()));
            r.add(std::move(ev));
        }
    }
}

}  // namespace rs
