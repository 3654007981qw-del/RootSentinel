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
    bool eaccesOnly = false;
    std::string eaccesPath;
    for (const auto& p : paths) {
        const bool existsRaw  = fileExistsRaw(p);
        const int  facc       = accessState(p, R_OK);
        const bool existsLibc = (::access(p.c_str(), F_OK) == 0);

        // 三视角任一命中即记录；视角之间的差异本身也是证据
        if (!existsRaw && !existsLibc && facc == 2) continue;

        // v2.22.43：仅 faccessat 返回 EACCES、statx 与 libc 视角都未确认
        // 存在时，不再判 HIT。EACCES 也可能来自父目录的遍历限制，
        // 与 v2.22.42 对 Magisk 方案路径的口径一致：EACCES-only → UNKNOWN。
        if (!existsRaw && !existsLibc && facc == 1) {
            if (!eaccesOnly) { eaccesOnly = true; eaccesPath = p; }
            continue;
        }

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
        if (eaccesOnly) {
            Evidence e = makeUnknown(RSs("A.SU_PATH"), Category::ARTIFACT,
                                     Severity::MEDIUM,
                                     RSs("su 路径被拒绝访问，无法确认存在: ") + eaccesPath);
            e.addVector(RSs("faccessat"), RSs("EACCES"));
            e.addVector(RSs("statx"), RSs("absent"));
            e.addVector(RSs("libc_access"), RSs("absent"));
            e.detail = RSs("只有 faccessat 一个视角给出 EACCES，"
                           "其余视角均未确认文件存在，不足以判定命中");
            r.add(std::move(e));
        } else {
            r.add(makePass(RSs("A.SU_PATH"), Category::ARTIFACT,
                           RSs("13 条经典 su 路径在所有视角下均不可见")));
        }
    }

    // ---------------------------------------------------------- /data/adb
    // 这是唯一有意义的 EACCES：/data 是 0771，App 有 x 位能 stat 子项
    const std::string kAdb = RSs("/data/adb");
    // v2.22.82（联合审查 N1）：改用 errno 精确探针。旧版 else 分支
    // 把 accessState 的 2（任何非 EACCES 错误）直接判 PASS 并在
    // 文案里断言 ENOENT——EIO/ENOSYS 也会被写成「隐藏完整」。
    // 现在只有真 ENOENT 才 PASS，其余错误 UNKNOWN。
    const int adbErr = accessErrnoRaw(kAdb, 0 /* F_OK */);

    if (adbErr == 0) {
        sys::statx_buf sx{};
        const bool isDir = (sys::statx_(kAdb.c_str(), &sx, nullptr, 0) == 0);
        Evidence e = makeHit(RSs("A.DATA_ADB"), Category::ARTIFACT,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("/data/adb 可访问 ← root 方案数据目录外露"));
        e.sourceGroup = RSs("fs:/data/adb");  // E1：与 A.MODULES_DIR 同源
        e.addVector(RSs("faccessat"), RSs("visible"));
        e.addVector(RSs("statx"), isDir ? RSs("dir") : RSs("unknown"));
        r.add(std::move(e));
    } else if (adbErr == EACCES || adbErr == EPERM) {
        Evidence e = makeHit(RSs("A.DATA_ADB"), Category::ARTIFACT,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("/data/adb 返回 EACCES ← 目录存在（原厂没有此目录）"));
        e.sourceGroup = RSs("fs:/data/adb");
        e.addVector(RSs("faccessat"), RSs("EACCES"));
        e.addVector(RSs("statx"),
                    fileExistsRaw(kAdb) ? RSs("exists") : RSs("hidden"));
        e.fp_note = RSs("极少数老设备 ROM 会预置 /data/adb；"
                        "vivo/OPPO/小米原厂均无");
        r.add(std::move(e));
    } else if (adbErr == ENOENT) {
        r.add(makePass(RSs("A.DATA_ADB"), Category::ARTIFACT,
                       RSs("/data/adb 返回 ENOENT（隐藏完整）"))
                  .withSourceGroup(RSs("fs:/data/adb")));
    } else {
        Evidence e = makeUnknown(RSs("A.DATA_ADB"), Category::ARTIFACT,
                                 Severity::LOW,
                                 RSs("/data/adb 观测失败（不可验）"));
        e.sourceGroup = RSs("fs:/data/adb");
        e.addVector(RSs("errno"), std::to_string(adbErr));
        r.add(std::move(e));
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
        // v2.22.45（审查 A P1-13）：只有 ENOENT 才算「枚举到但查
        // 不到」；EACCES 等错误是「不可验」，不能当作不存在的证据。
        int missing = 0;
        int unverifiable = 0;
        std::string firstMissing;
        for (const auto& e : rawEntries) {
            const std::string full = d + "/" + e.name;
            const int fst = fileStateRaw(full);
            if (fst == 1) {
                if (missing == 0) firstMissing = full;
                ++missing;
            } else if (fst == 2) {
                ++unverifiable;
            }
        }
        if (missing == 0 && unverifiable > 0) {
            Evidence ev = makeUnknown(
                RSs("A.DIR_ENUM_MISMATCH"), Category::ARTIFACT,
                Severity::LOW,
                RSs("目录枚举部分条目不可验（权限）: ") + d);
            ev.addVector(RSs("unverifiable"), std::to_string(unverifiable));
            r.add(std::move(ev));
            continue;
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
