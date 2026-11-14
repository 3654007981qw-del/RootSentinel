// check_hw_ext.cpp — 硬件指纹扩展（M9）
//
// 真机的电池、温度、GPU 节点是「活的」——数值会变、节点齐全。
// 云手机 / 模拟器往往缺失或返回常量。
#include "rs_checks.h"

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

int countDir(const std::string& dir) {
    auto es = listDirRaw(dir);
    int n = 0;
    for (const auto& d : es) {
        if (d.name.empty() || d.name[0] == '.') continue;
        ++n;
    }
    return n;
}

}  // namespace

void checkHwExt(ScanReport& r) {

    // ------------------------------------------------------- A.BATTERY_TEMP
    // 单位是 0.1°C。真机电池温度在 150~450（15~45°C）之间。
    {
        const auto raw = trim(readFile(RSs("/sys/class/power_supply/battery/temp"), 4096));
        if (raw.empty()) {
            r.add(makeUnknown(RSs("A.BATTERY_TEMP"), Category::ARTIFACT,
                              Severity::LOW, RSs("电池温度不可读")));
        } else {
            const long v = std::atol(raw.c_str());
            const double celsius = (double)v / 10.0;

            char buf[32];
            std::snprintf(buf, sizeof(buf), "%.1f", celsius);

            if (v < 50 || v > 600) {
                Evidence e = makeHit(RSs("A.BATTERY_TEMP"), Category::ARTIFACT,
                                     Severity::MEDIUM, Confidence::MEDIUM,
                                     RSs("电池温度异常"));
                e.addVector(RSs("raw"), raw);
                e.addVector(RSs("celsius"), buf);
                e.detail = RSs("真机电池温度在 15~45°C；超出范围可能是模拟值");
                r.add(std::move(e));
            } else {
                Evidence e = makePass(RSs("A.BATTERY_TEMP"), Category::ARTIFACT,
                                      RSs("电池温度正常"));
                e.addVector(RSs("celsius"), buf);
                r.add(std::move(e));
            }
        }
    }

    // ------------------------------------------------------ A.THERMAL_ZONES
    {
        const int n = countDir(RSs("/sys/class/thermal"));
        if (n == 0) {
            r.add(makeUnknown(RSs("A.THERMAL_ZONES"), Category::ARTIFACT,
                              Severity::LOW, RSs("thermal 目录不可枚举")));
        } else if (n < 3) {
            Evidence e = makeHit(RSs("A.THERMAL_ZONES"), Category::ARTIFACT,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("温度传感器节点异常少"));
            e.addVector(RSs("count"), std::to_string(n));
            e.detail = RSs("真机通常有 10+ 个 thermal zone");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("A.THERMAL_ZONES"), Category::ARTIFACT,
                                  RSs("温度传感器节点正常"));
            e.addVector(RSs("count"), std::to_string(n));
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------------- A.GPU
    // 高通走 kgsl，Mali 走 mali0，其他走 drm。
    // 三个都没有 → 很可能是无 GPU 的环境（云手机）。
    {
        std::vector<std::string> found;

        if (accessState(RSs("/sys/class/kgsl/kgsl-3d0"), 0) != 2) {
            found.push_back(RSs("kgsl-3d0 (Adreno)"));
        }
        if (accessState(RSs("/sys/class/misc/mali0"), 0) != 2) {
            found.push_back(RSs("mali0 (Mali)"));
        }
        if (accessState(RSs("/sys/class/drm/card0"), 0) != 2) {
            found.push_back(RSs("drm/card0"));
        }

        if (found.empty()) {
            Evidence e = makeHit(RSs("A.GPU"), Category::ARTIFACT,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("未找到 GPU 设备节点"));
            e.addVector(RSs("checked"), "kgsl / mali0 / drm");
            e.detail = RSs("真机必有 GPU 驱动节点；缺失是无 GPU 环境的特征");
            e.fp_note = RSs("极少数老设备 GPU 节点在其他路径");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("A.GPU"), Category::ARTIFACT,
                                  RSs("GPU 设备节点存在"));
            for (const auto& s : found) e.addVector(RSs("node"), s);
            r.add(std::move(e));
        }
    }

    // --------------------------------------------------------- A.BOOT_REASON
    // ro.boot.bootreason 记录上次启动原因。
    // 值为 "kernel_panic" / "watchdog" 等说明设备曾异常重启 ——
    // LKM 型 root 出问题时经常留下这个。
    {
        const auto reason = getProp(RSs("ro.boot.bootreason"));
        if (reason.empty()) {
            r.add(makeUnknown(RSs("A.BOOT_REASON"), Category::ARTIFACT,
                              Severity::LOW, RSs("bootreason 属性缺失")));
        } else if (contains(reason, RSs("panic")) ||
                   contains(reason, RSs("watchdog")) ||
                   contains(reason, RSs("oops"))) {
            Evidence e = makeHit(RSs("A.BOOT_REASON"), Category::ARTIFACT,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("上次启动原因是内核异常"));
            e.addVector(RSs("bootreason"), reason);
            e.detail = RSs("内核 panic / watchdog / oops 后重启；"
                           "LKM 型 root 不稳定时常出现");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("A.BOOT_REASON"), Category::ARTIFACT,
                                  RSs("启动原因正常"));
            e.addVector(RSs("bootreason"), reason);
            r.add(std::move(e));
        }
    }
}

}  // namespace rs