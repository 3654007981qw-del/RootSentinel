// check_fs_deep.cpp — 文件系统物理层（M5）
//
// 这一层不看「路径是否存在」——那太容易被藏。
// 它看的是同一批文件在不同视角下的 **底层身份**：
//   · st_dev 是否被拆开（overlay / 单独 bind mount）
//   · 同一文件通过 /proc/self/root 与直接路径看到的 inode 是否一致
//   · statx 的 VERITY 属性（dm-verity 文件带 FS_VERITY_FL）
#include "rs_checks.h"

#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

// STATX_ATTR_VERITY：文件受 fs-verity 保护。
// v2.22.44 修正（第四份审查 P1-02）：之前误写 0x00200000 —— 那是
// STATX_ATTR_DAX 的值；Linux UAPI 里 VERITY 是 0x00100000。
// 结果是这一直在测 DAX 位：真 verity 文件测不出来，DAX 文件被误报。
constexpr uint64_t kStatxAttrVerity = 0x00100000ULL;
// STATX_ATTR_IMMUTABLE / APPEND
constexpr uint64_t kStatxAttrImmutable = 0x00000010ULL;

bool statOf(const std::string& path, sys::rawstat& out) {
    return sys::fstatat_raw(path.c_str(), &out, 0) == 0;
}

}  // namespace

void checkFsDeep(ScanReport& r) {
    // ---------------------------------------------------------- C.DEV_SPLIT
    // /system 下同一分区里的两个文件必然同 st_dev。
    // 若被单独 bind mount 覆盖（模块化 ROM、Magisk 的 overlay），dev 会不同。
    {
        sys::rawstat a{}, b{}, c{};
        const bool ok1 = statOf(RSs("/system/bin/sh"), a);
        const bool ok2 = statOf(RSs("/system/build.prop"), b);
        const bool ok3 = statOf(RSs("/system/bin/app_process64"), c);

        if (ok1 && ok2) {
            const bool same = (a.dev == b.dev);
            if (same && (!ok3 || a.dev == c.dev)) {
                Evidence e = makePass(RSs("C.DEV_SPLIT"), Category::MOUNT,
                                      RSs("/system 各文件 st_dev 一致"));
                e.addVector(RSs("bin_sh"), hex64(a.dev));
                e.addVector(RSs("build_prop"), hex64(b.dev));
                if (ok3) e.addVector(RSs("app_process64"), hex64(c.dev));
                r.add(std::move(e));
            } else {
                Evidence e = makeHit(RSs("C.DEV_SPLIT"), Category::MOUNT,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("/system 被拆成多个设备"));
                e.addVector(RSs("bin_sh"), hex64(a.dev));
                e.addVector(RSs("build_prop"), hex64(b.dev));
                if (ok3) e.addVector(RSs("app_process64"), hex64(c.dev));
                e.detail = RSs("同一分区内文件分属不同 st_dev → 存在局部 bind/overlay 覆盖");
                e.fp_note = RSs("部分厂商 ROM 会把 /system/etc 单独挂载，"
                                "此时需人工确认是否原厂设计");
                r.add(std::move(e));
            }
        } else {
            r.add(makeUnknown(RSs("C.DEV_SPLIT"), Category::MOUNT, Severity::LOW,
                              RSs("/system 文件不可 stat")));
        }
    }

    // ---------------------------------------------------------- C.SAME_INODE
    // /proc/self/root 与直接路径应看到同一个 inode。
    // 若不同 → 说明 /proc 或某个中间层被做了重定向（path-based 隐藏的典型裂缝）。
    {
        const std::vector<std::pair<std::string, std::string>> pairs = {
            {RSs("/system/build.prop"), RSs("/proc/self/root/system/build.prop")},
            {RSs("/system/bin/sh"), RSs("/proc/self/root/system/bin/sh")},
        };

        int checked = 0, mismatched = 0;
        std::vector<std::string> detailLines;

        for (const auto& pr : pairs) {
            sys::rawstat x{}, y{};
            if (!statOf(pr.first, x)) continue;
            if (!statOf(pr.second, y)) continue;
            ++checked;
            // v2.22.44（P2-04）：文件身份是 (st_dev, st_ino) 二元组，
            // 只比 inode 会漏掉跨设备同号 inode 的重定向。
            if (x.ino != y.ino || x.dev != y.dev) {
                ++mismatched;
                detailLines.push_back(pr.first + RSs(": dev/ino ") +
                                      std::to_string(x.dev) + RSs("/") +
                                      std::to_string(x.ino) + RSs(" != ") +
                                      std::to_string(y.dev) + RSs("/") +
                                      std::to_string(y.ino));
            }
        }

        if (checked == 0) {
            r.add(makeUnknown(RSs("C.SAME_INODE"), Category::MOUNT, Severity::LOW,
                              RSs("/proc/self/root 不可访问")));
        } else if (mismatched == 0) {
            Evidence e = makePass(RSs("C.SAME_INODE"), Category::MOUNT,
                                  RSs("直接路径与 /proc/self/root 的 dev/ino 一致"));
            e.addVector(RSs("checked"), std::to_string(checked));
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("C.SAME_INODE"), Category::CROSSCHECK,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("同一文件在不同路径下 dev/ino 不同"));
            for (const auto& d : detailLines) e.addVector(RSs("mismatch"), d);
            e.detail = RSs("路径级隐藏会露出这个裂缝");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- C.VERITY
    // 原厂 /system 关键文件一般带 fs-verity 或 immutable 属性。
    // 被 overlay 覆盖后的副本会丢掉这些属性。
    {
        static const std::vector<std::string> targets = {
            RSs("/system/build.prop"),
            RSs("/system/bin/sh"),
        };
        // 注意：/apex 下的文件走 dm-verity（块级），不会带 STATX_ATTR_VERITY，
        // 不能拿它当 fs-verity 的判据 —— 会恒定误报。

        int withAttr = 0, withoutAttr = 0, unreadable = 0;
        std::vector<std::string> lines;

        for (const auto& t : targets) {
            sys::statx_buf sx{};
            bool usedFallback = false;
            if (sys::statx_(t.c_str(), &sx, &usedFallback, 0) != 0) {
                ++unreadable;
                continue;
            }
            // 旧内核（statx 不存在）走了 fstatat 回退，
            // 此时拿不到 stx_attributes —— 属性「不可知」而不是「缺失」
            if (usedFallback) {
                ++unreadable;
                continue;
            }
            // v2.22.44（P1-02）：先看 stx_attributes_mask —— 内核/文件
            // 系统不报告某个属性时，对应位恒为 0，那是「不可观测」，
            // 不是「属性缺失」。两个属性都不可观测的文件按 unreadable
            // 计；只支持其一时只按其一判定。
            const bool verityObservable =
                (sx.stx_attributes_mask & kStatxAttrVerity) != 0;
            const bool immutObservable =
                (sx.stx_attributes_mask & kStatxAttrImmutable) != 0;
            if (!verityObservable && !immutObservable) {
                ++unreadable;
                continue;
            }
            const bool verity = verityObservable &&
                ((sx.stx_attributes & kStatxAttrVerity) != 0);
            const bool immut = immutObservable &&
                ((sx.stx_attributes & kStatxAttrImmutable) != 0);
            if (verity || immut) ++withAttr;
            else ++withoutAttr;

            if (lines.size() < 4) {
                lines.push_back(t + RSs(" verity=") + (verity ? "1" : "0") +
                                RSs(" immut=") + (immut ? "1" : "0"));
            }
        }

        // v2.22.45（审查 B P2-02）：判定口径重构。旧逻辑里 unreadable
        // 被统计却不参与结论 —— 1 个有属性 + 1 个读不到也能 PASS；
        // 且「全部文件都无属性」直接判 HIT，但那同样符合「该 ROM 根
        // 本没启用 fs-verity」。现在：
        //   有带属性也有可观测地缺失 → 属性被剥离的实证 → HIT
        //   全部可观测文件都有属性且无 unreadable → PASS
        //   其余（部分不可读 / 全部缺失 / 全部不可读）→ UNKNOWN
        if (withAttr > 0 && withoutAttr > 0) {
            Evidence e = makeHit(RSs("C.VERITY"), Category::MOUNT,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("部分系统文件丢失 verity/immutable 属性"));
            for (const auto& s : lines) e.addVector(RSs("attr"), s);
            e.addVector(RSs("unreadable"), std::to_string(unreadable));
            e.detail = std::to_string(withoutAttr) + RSs(" 个文件属性被剥离，"
                                                          "通常是 overlay 副本");
            r.add(std::move(e));
        } else if (withAttr > 0 && unreadable == 0) {
            Evidence e = makePass(RSs("C.VERITY"), Category::MOUNT,
                                  RSs("系统文件保留 verity/immutable 属性"));
            for (const auto& s : lines) e.addVector(RSs("attr"), s);
            r.add(std::move(e));
        } else if (withAttr + withoutAttr == 0) {
            r.add(makeUnknown(RSs("C.VERITY"), Category::MOUNT, Severity::LOW,
                              RSs("statx 属性不可读（旧内核无 statx）")));
        } else if (withoutAttr > 0) {
            Evidence e = makeUnknown(RSs("C.VERITY"), Category::MOUNT,
                                     Severity::LOW,
                                     RSs("系统文件均无 verity/immutable 属性（该 ROM 可能未启用 fs-verity）"));
            for (const auto& s : lines) e.addVector(RSs("attr"), s);
            e.addVector(RSs("unreadable"), std::to_string(unreadable));
            r.add(std::move(e));
        } else {
            Evidence e = makeUnknown(RSs("C.VERITY"), Category::MOUNT,
                                     Severity::LOW,
                                     RSs("部分系统文件属性不可读，无法完整判定"));
            for (const auto& s : lines) e.addVector(RSs("attr"), s);
            e.addVector(RSs("unreadable"), std::to_string(unreadable));
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- C.APEX_MOUNT
    // APEX 挂载点：/apex/com.android.* 每个都是独立 mount。
    // /apex 目录本身对 untrusted_app 不保证可 readdir，
    // 但 /proc/self/mountinfo 一定可读 —— 用它做第二视角。
    {
        const auto mi = readFile(RSs("/proc/self/mountinfo"), 1u << 20);

        int n = 0;
        std::vector<std::string> names;
        for (const auto& line : split(mi, '\n')) {
            const auto pos = line.find(RSs(" /apex/"));
            if (pos == std::string::npos) continue;
            const auto rest = line.substr(pos + 1);
            const auto sp = rest.find(' ');
            const auto mnt = rest.substr(0, sp == std::string::npos ? rest.size() : sp);
            ++n;
            if (names.size() < 5) names.push_back(mnt);
        }

        if (n == 0) {
            r.add(makeUnknown(RSs("C.APEX_MOUNT"), Category::MOUNT, Severity::LOW,
                              RSs("mountinfo 中无 APEX 挂载")));
        } else {
            Evidence e = makePass(RSs("C.APEX_MOUNT"), Category::MOUNT,
                                  RSs("APEX 挂载点正常"));
            e.addVector(RSs("count"), std::to_string(n));
            e.addVector(RSs("view"), RSs("mountinfo"));
            for (const auto& s : names) e.addVector(RSs("apex"), s);
            r.add(std::move(e));
        }
    }
}

}  // namespace rs