// check_magisk_family.cpp — 主流 root 方案专项指纹（M6）
//
// 这一层是「清单核对」，不是推理。
// Momo / Native Detector 的检出率很大一部分来自这份清单够全。
//
// 判定原则（关键）：
//   ENOENT  → 该路径不存在（PASS 的一个视角）
//   EXISTS  → App 能访问，几乎必然是权限被改过（HIT）
//   EACCES  → 分两种情况，必须区分：
//             · 路径不在被屏蔽的父目录下 → 目录存在，强信号（HIT）
//             · 路径在 /data/adb 下 → 父目录本身就没 x 权限，
//               子路径无论如何都是 EACCES，无信息量（不计入）
#include "rs_checks.h"

#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

bool underBlockedParent(const std::string& p) {
    // 这些目录对 untrusted_app 本身就是「可 stat 不可进入」，
    // 其下任何子路径都会返回 EACCES —— 不能据此推断「子项存在」。
    static const std::vector<std::string> blocked = {
        RSs("/data/adb"),
        RSs("/data/user"),
        RSs("/data/data"),
        RSs("/cache"),      // 0770 system:cache
        RSs("/config"),     // 0750 root:system
        RSs("/metadata"),
        RSs("/root"),
        RSs("/data/misc"),
        RSs("/data/system"),
        RSs("/data/tombstones"),
    };
    for (const auto& b : blocked) {
        if (startsWith(p, b + RSs("/"))) return true;
    }
    return false;
}

struct Scheme {
    std::string              id;
    std::string              name;
    std::vector<std::string> paths;
};

}  // namespace

void checkMagiskFamily(ScanReport& r) {

    // ---------------------------------------------------------- A.DEBUG_RAMDISK
    // 原厂 /debug_ramdisk 是空目录。
    // Magisk / KernelSU / APatch 都会在这里放引导文件。
    //
    // 注意版本差异：/debug_ramdisk 是 Android 11 才引入的
    // （之前 ramdisk 内容挂在 /sbin）。
    // 因此在 Android 8~10 上这个目录根本不存在，
    // 此时必须记 UNKNOWN，不能因为「枚举为空」就判 PASS。
    {
        const int dirState = accessState(RSs("/debug_ramdisk"), 4);
        auto entries = listDirRaw(RSs("/debug_ramdisk"));
        std::vector<std::string> names;
        for (const auto& d : entries) {
            if (d.name.empty() || d.name[0] == '.') continue;
            names.push_back(d.name);
        }

        if (!names.empty()) {
            Evidence e = makeHit(RSs("A.DEBUG_RAMDISK"), Category::ARTIFACT,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("/debug_ramdisk 非空"));
            for (const auto& n : names) e.addVector(RSs("entry"), n);
            e.detail = RSs("原厂该目录为空；LKM / ramdisk 型 root 会在此放置引导文件");
            r.add(std::move(e));
        } else if (dirState != 0 && dirState != 1) {
            // ENOENT：该 Android 版本没有这个目录
            r.add(makeUnknown(RSs("A.DEBUG_RAMDISK"), Category::ARTIFACT,
                              Severity::LOW,
                              RSs("/debug_ramdisk 不存在（Android 10 及以下）")));
        } else if (dirState == 1) {
            r.add(makeUnknown(RSs("A.DEBUG_RAMDISK"), Category::ARTIFACT,
                              Severity::LOW,
                              RSs("/debug_ramdisk 不可读")));
        } else {
            Evidence e = makePass(RSs("A.DEBUG_RAMDISK"), Category::ARTIFACT,
                                  RSs("/debug_ramdisk 为空（正常）"));
            e.addVector(RSs("entries"), "0");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- 各方案路径清单
    static const std::vector<Scheme> schemes = {
        {
            RSs("A.MAGISK_TRACE"), RSs("Magisk"),
            {
                RSs("/debug_ramdisk/.magisk"),
                RSs("/debug_ramdisk/magisk"),
                RSs("/debug_ramdisk/magisk64"),
                RSs("/sbin/.magisk"),
                RSs("/init.magisk.rc"),
                RSs("/cache/.disable_magisk"),
                RSs("/dev/.magisk.unblock"),
                RSs("/system/etc/init/magisk"),
                RSs("/vendor/etc/init/magisk"),
            },
        },
        {
            RSs("A.KSU_TRACE"), RSs("KernelSU"),
            {
                RSs("/debug_ramdisk/ksud"),
                RSs("/debug_ramdisk/init"),
                RSs("/sbin/ksud"),
                RSs("/dev/kernelsu"),
                RSs("/proc/kernelsu"),
                RSs("/system/etc/init/ksu.rc"),
            },
        },
        {
            RSs("A.APATCH_TRACE"), RSs("APatch"),
            {
                RSs("/debug_ramdisk/apd"),
                RSs("/sbin/apd"),
                RSs("/dev/apatch"),
                RSs("/proc/apatch"),
                RSs("/system/etc/init/apatch.rc"),
            },
        },
        {
            RSs("A.SHAMIKO_TRACE"), RSs("Shamiko"),
            {
                RSs("/debug_ramdisk/shamiko"),
                RSs("/dev/shamiko"),
                RSs("/proc/shamiko"),
            },
        },
        {
            RSs("A.SUSFS_TRACE"), RSs("SUSFS"),
            {
                RSs("/proc/susfs"),
                RSs("/proc/susfs_path"),
                RSs("/proc/susfs_status"),
                RSs("/sys/module/susfs"),
                RSs("/sys/module/susfs_guard_lkm"),
            },
        },
        {
            RSs("A.MAGISK_MODULE_MNT"), RSs("Magisk module mount"),
            {
                RSs("/sbin/.magisk/mirror"),
                RSs("/sbin/.magisk/block"),
                RSs("/sbin/.magisk/mnt"),
            },
        },
    };

    for (const auto& s : schemes) {
        int exists = 0, denied = 0, missing = 0, blocked = 0;
        std::vector<std::string> lines;

        for (const auto& p : s.paths) {
            const int st = accessState(p, 0);

            if (st == 0) {
                ++exists;
                lines.push_back(p + RSs("=EXISTS"));
            } else if (st == 1) {
                if (underBlockedParent(p)) {
                    ++blocked;  // 父目录不可进 → 无信息量
                } else {
                    ++denied;
                    lines.push_back(p + RSs("=EACCES"));
                }
            } else {
                ++missing;
            }
        }

        if (exists > 0) {
            Evidence e = makeHit(s.id, Category::ARTIFACT, Severity::CRITICAL,
                                 Confidence::HIGH,
                                 s.name + RSs(" 痕迹存在"));
            for (const auto& l : lines) e.addVector(RSs("path"), l);
            e.addVector(RSs("missing"), std::to_string(missing));
            e.detail = RSs("路径对普通 App 竟然可访问 → 权限被改过");
            r.add(std::move(e));
        } else if (denied > 0) {
            // v2.22.42（P1-03）：EACCES-only 不再推断「目录存在」。
            // EACCES 只说明这条路径走不通：中间目录 DAC、SELinux
            // 路径遍历拒绝、厂商 LSM 策略在正常 ROM 上同样产生它；
            // 多个方案共享同一拒绝原因时还会重复计分。
            // 改判 UNKNOWN，拿到独立存在性证据才允许升 HIT。
            Evidence e = makeUnknown(s.id, Category::ARTIFACT, Severity::LOW,
                                     s.name + RSs(" 路径被拒绝访问（存在性未确认）"));
            for (const auto& l : lines) e.addVector(RSs("path"), l);
            e.addVector(RSs("denied"), std::to_string(denied));
            e.addVector(RSs("missing"), std::to_string(missing));
            e.detail = RSs("EACCES 可能来自中间目录权限或 SELinux 遍历拒绝，"
                           "不作为目标存在的证据");
            r.add(std::move(e));
        } else if (missing == (int)s.paths.size()) {
            Evidence e = makePass(s.id, Category::ARTIFACT,
                                  s.name + RSs(" 路径全部不存在"));
            e.addVector(RSs("checked"), std::to_string(missing));
            r.add(std::move(e));
        } else {
            Evidence e = makeUnknown(s.id, Category::ARTIFACT, Severity::LOW,
                                     s.name + RSs(" 路径位于受限父目录下"));
            e.addVector(RSs("blocked_by_parent"), std::to_string(blocked));
            e.addVector(RSs("missing"), std::to_string(missing));
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- A.MODULES_DIR
    // /data/adb/modules 是 Magisk / KernelSU 共用的模块目录。
    // EACCES = /data/adb 存在（原厂无此目录，返回 ENOENT）。
    {
        const int st = accessState(RSs("/data/adb/modules"), 0);
        // v2.22.45（审查 A P2-18）：与父目录口径互证。/data/adb 本身
        // 返回 ENOENT 而子路径却 EACCES 是自相矛盾的观测（更像过滤
        // 伪造或策略噪声），记 UNKNOWN 不下 HIT；父目录存在（可达或
        // EACCES）时子路径 EACCES 的「存在」推断才成立。
        const int parentSt = accessState(RSs("/data/adb"), 0);
        if (st == 1 && parentSt == 2) {
            r.add(makeUnknown(RSs("A.MODULES_DIR"), Category::ARTIFACT,
                              Severity::LOW,
                              RSs("/data/adb 不存在但 modules 返回 EACCES，观测矛盾")));
        } else if (st == 1) {
            Evidence e = makeHit(RSs("A.MODULES_DIR"), Category::ARTIFACT,
                                 Severity::HIGH, Confidence::MEDIUM,
                                 RSs("/data/adb/modules 存在"));
            e.addVector(RSs("state"), RSs("EACCES（存在但不可访问）"));
            e.detail = RSs("原厂设备不存在 /data/adb，返回的应是 ENOENT");
            e.fp_note = RSs("极少数企业定制 ROM 会预置 /data/adb 用于自身管理");
            r.add(std::move(e));
        } else if (st == 0) {
            auto entries = listDirRaw(RSs("/data/adb/modules"));
            std::vector<std::string> names;
            for (const auto& d : entries) {
                if (d.name.empty() || d.name[0] == '.') continue;
                names.push_back(d.name);
            }
            Evidence e = makeHit(RSs("A.MODULES_DIR"), Category::ARTIFACT,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("/data/adb/modules 可枚举"));
            e.addVector(RSs("state"), RSs("EXISTS"));
            e.addVector(RSs("count"), std::to_string(names.size()));
            for (const auto& n : names) e.addVector(RSs("module"), n);
            r.add(std::move(e));
        } else {
            r.add(makePass(RSs("A.MODULES_DIR"), Category::ARTIFACT,
                           RSs("/data/adb/modules 不存在")));
        }
    }
}

}  // namespace rs