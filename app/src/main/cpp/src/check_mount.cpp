// check_mount.cpp — C 挂载与命名空间
// 覆盖 v2 增强包【一.3】元数据互验、【一.4】挂载三视角
#include "rs_checks.h"

#include <sys/statfs.h>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

struct MountRec {
    int         id = 0;
    std::string point;
    std::string fstype;
    std::string source;
};

std::vector<MountRec> parseMountinfo() {
    std::vector<MountRec> out;
    auto lines = readLines(RSs("/proc/self/mountinfo"), 4096);
    for (const auto& line : lines) {
        auto f = split(line, ' ');
        if (f.size() < 10) continue;

        MountRec m;
        m.id = std::atoi(f[0].c_str());
        m.point = f[4];

        // 找 " - " 分隔符
        for (size_t i = 6; i + 2 < f.size(); ++i) {
            if (f[i] == "-") {
                m.fstype = f[i + 1];
                m.source = f[i + 2];
                break;
            }
        }
        out.push_back(std::move(m));
    }
    return out;
}

std::vector<std::string> parseMountsPoints() {
    std::vector<std::string> out;
    auto lines = readLines(RSs("/proc/mounts"), 4096);
    for (const auto& line : lines) {
        auto f = split(line, ' ');
        if (f.size() >= 2) out.push_back(f[1]);
    }
    return out;
}

}  // namespace

void checkMount(ScanReport& r) {
    // ---------------------------------------------------------- 三视角
    auto fromMountinfo = parseMountinfo();
    auto fromMounts    = parseMountsPoints();

    if (fromMountinfo.empty()) {
        r.add(makeUnknown(RSs("C.MOUNTINFO"), Category::MOUNT, Severity::MEDIUM,
                          RSs("/proc/self/mountinfo 不可读")));
        return;
    }

    // mountinfo 与 mounts 的挂载点集合应一致
    size_t mismatch = 0;
    std::string firstDiff;
    for (const auto& m : fromMountinfo) {
        bool found = false;
        for (const auto& p : fromMounts) {
            if (p == m.point) { found = true; break; }
        }
        if (!found) {
            if (mismatch == 0) firstDiff = m.point;
            ++mismatch;
        }
    }
    // v2.22.43：反方向也要比。之前只查 mountinfo→mounts，
    // mounts 里多出、mountinfo 里没有的挂载点完全看不见 ——
    // 隐藏方案把条目从 mountinfo 摘掉时恰好走这个方向。
    size_t extra = 0;
    std::string firstExtra;
    for (const auto& p : fromMounts) {
        bool found = false;
        for (const auto& m : fromMountinfo) {
            if (m.point == p) { found = true; break; }
        }
        if (!found) {
            if (extra == 0) firstExtra = p;
            ++extra;
        }
    }
    if (mismatch > 0 || extra > 0) {
        Evidence e = makeHit(RSs("C.MOUNT_MULTI"), Category::MOUNT,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("mountinfo 与 mounts 不一致"));
        e.addVector(RSs("mountinfo"), std::to_string(fromMountinfo.size()));
        e.addVector(RSs("mounts"), std::to_string(fromMounts.size()));
        e.addVector(RSs("missing_in_mounts"), std::to_string(mismatch));
        e.addVector(RSs("extra_in_mounts"), std::to_string(extra));
        if (mismatch > 0) e.addVector(RSs("first"), firstDiff);
        if (extra > 0) e.addVector(RSs("first_extra"), firstExtra);
        e.detail = RSs("有挂载被从其中一个视图里摘掉");
        r.add(std::move(e));
    } else {
        Evidence e = makePass(RSs("C.MOUNT_MULTI"), Category::MOUNT,
                              RSs("mountinfo 与 mounts 一致"));
        e.addVector(RSs("count"), std::to_string(fromMountinfo.size()));
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 可疑 source
    const std::vector<std::string> badKeywords = {
        RSs("magisk"), RSs("kernelsu"), RSs("ksu"), RSs("apatch"),
        RSs("susfs"), RSs("nomount"), RSs("zygisk"), RSs("lsposed"),
    };
    int badSrc = 0;
    for (const auto& m : fromMountinfo) {
        const std::string lower = toLower(m.source);
        if (containsAny(lower, badKeywords)) {
            ++badSrc;
            Evidence e = makeHit(RSs("C.MOUNT_SOURCE"), Category::MOUNT,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("挂载源暴露 root 组件: ") + m.point);
            e.addVector(RSs("source"), m.source);
            e.addVector(RSs("fstype"), m.fstype);
            r.add(std::move(e));
        }
    }
    if (badSrc == 0) {
        r.add(makePass(RSs("C.MOUNT_SOURCE"), Category::MOUNT,
                       RSs("挂载源无 root 关键字")));
    }

    // ---------------------------------------------------------- overlay / 覆盖
    int overlays = 0;
    for (const auto& m : fromMountinfo) {
        if (m.fstype == RSs("overlay")) {
            ++overlays;
            // v2.22.44 分档（第四份审查 P2-05）：旧版把 /product、/apex
            // 也放进 HIGH 档，但本项自己的 fp_note 就写着 Android 10+
            // 的 /product、/system_ext 原厂就可能用 overlay 做 RRO ——
            // 口径自相矛盾。现分三档：/system、/vendor 仍是强信号；
            // /product、/apex、/system_ext 降为 MEDIUM + heuristic；
            // 其余 LOW。
            const bool midTier = startsWith(m.point, RSs("/product")) ||
                                 startsWith(m.point, RSs("/apex")) ||
                                 startsWith(m.point, RSs("/system_ext"));
            const bool critical = !midTier &&
                (startsWith(m.point, RSs("/system")) ||
                 startsWith(m.point, RSs("/vendor")));
            Evidence e = makeHit(RSs("C.OVERLAY"), Category::MOUNT,
                                 critical ? Severity::HIGH
                                          : (midTier ? Severity::MEDIUM
                                                     : Severity::LOW),
                                 critical ? Confidence::HIGH
                                          : Confidence::MEDIUM,
                                 RSs("存在 overlay 挂载: ") + m.point);
            e.heuristic = !critical;
            e.addVector(RSs("source"), m.source);
            e.fp_note = RSs("Android 10+ 的 /product、/system_ext 本身可能用 "
                            "overlay 做 RRO，需结合 source 判断");
            r.add(std::move(e));
        }
    }
    if (overlays == 0) {
        r.add(makePass(RSs("C.OVERLAY"), Category::MOUNT, RSs("无 overlay 挂载")));
    }

    // ---------------------------------------------------------- /data 子挂载
    int dataSub = 0;
    for (const auto& m : fromMountinfo) {
        if (startsWith(m.point, RSs("/data/adb")) ||
            startsWith(m.point, RSs("/data/local")) ||
            startsWith(m.point, RSs("/data/misc"))) {
            ++dataSub;
            // v2.22.44（P2-05）：/data/misc 下的厂商正常子挂载（如
            // wifi）是 fp_note 里承认的已知情形，不再只写说明 ——
            // 直接降为 LOW + heuristic；/data/adb、/data/local
            // 维持 MEDIUM。
            const bool vendorArea = startsWith(m.point, RSs("/data/misc"));
            Evidence e = makeHit(RSs("C.DATA_SUBMOUNT"), Category::MOUNT,
                                 vendorArea ? Severity::LOW : Severity::MEDIUM,
                                 Confidence::MEDIUM,
                                 RSs("/data 下存在子挂载: ") + m.point);
            e.heuristic = vendorArea;
            e.addVector(RSs("source"), m.source);
            e.fp_note = RSs("部分厂商在 /data/misc 下有正常子挂载（如 wifi）");
            r.add(std::move(e));
        }
    }
    if (dataSub == 0) {
        r.add(makePass(RSs("C.DATA_SUBMOUNT"), Category::MOUNT,
                       RSs("/data 下无敏感子挂载")));
    }

    // ---------------------------------------------------------- statfs 交叉
    // 同一分区的文件应返回相同 f_type；不同则说明被 bind mount 覆盖
    const std::vector<std::pair<std::string, std::string>> probes = {
        {RSs("/system/bin/sh"),   RSs("/system")},
        {RSs("/system/build.prop"), RSs("/system")},
        {RSs("/vendor/build.prop"), RSs("/vendor")},
    };

    for (const auto& p : probes) {
        struct statfs sf{};
        if (sys::statfs_(p.first.c_str(), &sf) != 0) continue;
        Evidence e = makePass(RSs("C.STATFS"), Category::MOUNT,
                              RSs("statfs 可读: ") + p.first);
        e.addVector(RSs("f_type"), hex64((uint64_t)sf.f_type));
        e.addVector(RSs("f_bsize"), std::to_string(sf.f_bsize));
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 元数据互验
    // 同一文件两个真正独立的视角：按路径 statx vs 打开后对 fd 做
    // fstat，st_dev+st_ino 应一致。
    // v2.22.44 修正（P2-04）：旧版第二视角用 fstatat_raw，而它内部
    // 先调 statx_ —— 正常路径上两个视角同源，「互验」是自己比自己，
    // 注释里写的第三个视角（open+fstat）也根本不存在。现在第二
    // 视角换成 sys::fstat_open_raw（openat + NR_fstat）。
    const std::vector<std::string> metaPaths = {
        RSs("/system/build.prop"), RSs("/system/bin/sh"),
        RSs("/vendor/build.prop"),
    };
    for (const auto& p : metaPaths) {
        sys::rawstat rs1{};
        if (sys::fstat_open_raw(p.c_str(), &rs1) != 0) continue;

        sys::statx_buf sx{};
        if (sys::statx_(p.c_str(), &sx, nullptr, 0) != 0) continue;

        const uint64_t dev2 = ((uint64_t)sx.stx_dev_major << 32) | sx.stx_dev_minor;
        const bool same = (rs1.dev == dev2) && (rs1.ino == sx.stx_ino);

        if (!same) {
            Evidence e = makeHit(RSs("C.META_MISMATCH"), Category::MOUNT,
                                 Severity::HIGH, Confidence::HIGH,
                                 RSs("元数据视角矛盾: ") + p);
            e.addVector(RSs("fstat(fd)"), hex64(rs1.dev) + "/" + hex64(rs1.ino));
            e.addVector(RSs("statx"), hex64(dev2) + "/" + hex64(sx.stx_ino));
            e.detail = RSs("同一路径两个视角给出不同的 dev/ino");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- mount ID 空洞
    // 只作为 INFO：Android 原厂 init 也会 unmount，跳号本身不是证据
    int gaps = 0;
    for (size_t i = 1; i < fromMountinfo.size(); ++i) {
        if (fromMountinfo[i].id != fromMountinfo[i - 1].id + 1) ++gaps;
    }
    Evidence g = makePass(RSs("C.MOUNT_ID_GAP"), Category::MOUNT,
                          RSs("mount ID 分布已记录"));
    g.addVector(RSs("mounts"), std::to_string(fromMountinfo.size()));
    g.addVector(RSs("gaps"), std::to_string(gaps));
    g.detail = RSs("跳号在原厂设备上同样存在，仅作参考");
    r.add(std::move(g));
}

}  // namespace rs