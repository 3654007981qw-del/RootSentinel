// check_sandbox.cpp — 存储视图与硬件指纹（M7）
//
// 两条思路：
//   1) Android 10+ 的 /sdcard 走 FUSE，同一文件通过不同挂载视图
//      应看到相同的 st_dev / inode。不一致说明挂了自定义视图。
//   2) 真机的 /sys/class/sensors 和 /sys/class/power_supply 有固定条目数；
//      模拟器 / 云手机往往缺失或极少。
#include "rs_checks.h"

#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

int countEntries(const std::string& dir) {
    auto entries = listDirRaw(dir);
    int n = 0;
    for (const auto& d : entries) {
        if (d.name.empty() || d.name[0] == '.') continue;
        ++n;
    }
    return n;
}

std::vector<std::string> sampleEntries(const std::string& dir, int maxN) {
    std::vector<std::string> out;
    auto entries = listDirRaw(dir);
    for (const auto& d : entries) {
        if (d.name.empty() || d.name[0] == '.') continue;
        out.push_back(d.name);
        if ((int)out.size() >= maxN) break;
    }
    return out;
}

bool statOf(const std::string& p, sys::rawstat& out) {
    return sys::fstatat_raw(p.c_str(), &out, 0) == 0;
}

}  // namespace

void checkSandbox(ScanReport& r) {

    // ---------------------------------------------------------- C.FUSE_VIEW
    // 同一个文件（sdcard 根）通过两条路径访问：
    //   /storage/emulated/0   （FUSE 或 sdcardfs）
    //   /mnt/user/0/emulated/0（底层挂载）
    // st_dev 应一致；不一致说明有一层被替换成了其他文件系统。
    {
        sys::rawstat a{}, b{};
        // /sdcard 是指向同一目标的符号链接，fstatat 会 follow，
        // 因此两者必须看到完全相同的 (dev, ino)。
        const bool ok1 = statOf(RSs("/storage/emulated/0"), a);
        const bool ok2 = statOf(RSs("/sdcard"), b);

        if (!ok1 || !ok2) {
            r.add(makeUnknown(RSs("C.FUSE_VIEW"), Category::MOUNT, Severity::LOW,
                              RSs("storage 视图不可 stat")));
        } else if (a.dev == b.dev && a.ino == b.ino) {
            Evidence e = makePass(RSs("C.FUSE_VIEW"), Category::MOUNT,
                                  RSs("storage 两个视图指向同一对象"));
            e.addVector(RSs("dev"), hex64(a.dev));
            e.addVector(RSs("ino"), std::to_string(a.ino));
            r.add(std::move(e));
        } else if (a.dev != b.dev) {
            Evidence e = makeHit(RSs("C.FUSE_VIEW"), Category::MOUNT,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("storage 两个视图分属不同设备"));
            e.addVector(RSs("dev_a"), hex64(a.dev));
            e.addVector(RSs("dev_b"), hex64(b.dev));
            e.detail = RSs("有自定义挂载覆盖了 /storage 视图");
            e.fp_note = RSs("Android 10+/11+ 从 sdcardfs 切到 FUSE 时 "
                            "dev 编号会不同，属正常差异");
            r.add(std::move(e));
        } else {
            Evidence e = makeUnknown(RSs("C.FUSE_VIEW"), Category::MOUNT,
                                     Severity::LOW,
                                     RSs("storage 视图 inode 不同"));
            e.addVector(RSs("ino_a"), std::to_string(a.ino));
            e.addVector(RSs("ino_b"), std::to_string(b.ino));
            e.detail = RSs("同一挂载点下 inode 不同，可能是一层是符号链接");
            r.add(std::move(e));
        }
    }

    // ----------------------------------------------------------- A.SENSORS
    // /sys/class/sensors 的 readdir 对 untrusted_app 常被拒，
    // 改成直接读具体节点的属性文件（不需要枚举）。
    {
        const auto temp = trim(readFile(RSs("/sys/class/thermal/thermal_zone0/temp"), 4096));
        const auto cap = trim(readFile(RSs("/sys/class/power_supply/battery/capacity"), 4096));

        if (temp.empty() && cap.empty()) {
            // v2.22.66：同 A.BATTERY_TEMP，原生层沉默，交给
            // HardwareDetector 的 BatteryManager 通道（同一 id）。
        } else {
            Evidence e = makePass(RSs("A.SENSORS"), Category::ARTIFACT,
                                  RSs("硬件节点可读（真机特征）"));
            if (!temp.empty()) e.addVector(RSs("thermal_zone0"), temp);
            if (!cap.empty()) e.addVector(RSs("battery_capacity"), cap);
            r.add(std::move(e));
        }
    }

    // ------------------------------------------------------ A.POWER_SUPPLY
    {
        const auto status = trim(readFile(RSs("/sys/class/power_supply/battery/status"), 4096));
        const auto volt = trim(readFile(RSs("/sys/class/power_supply/battery/voltage_now"), 4096));

        if (status.empty() && volt.empty()) {
            // v2.22.66：同 A.BATTERY_TEMP，原生层沉默，交给
            // HardwareDetector 的 BatteryManager 通道（同一 id）。
        } else {
            Evidence e = makePass(RSs("A.POWER_SUPPLY"), Category::ARTIFACT,
                                  RSs("电池节点存在"));
            if (!status.empty()) e.addVector(RSs("status"), status);
            if (!volt.empty()) e.addVector(RSs("voltage_now"), volt);
            r.add(std::move(e));
        }
    }
}

}  // namespace rs