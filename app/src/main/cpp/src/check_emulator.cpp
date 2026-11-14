// check_emulator.cpp — 运行环境真实性（M5）
//
// 精装检测器（Ruru / Holmes）都会先判断「这是不是一台真机」，
// 因为模拟器上所有 root 痕迹都无意义，且模拟器本身是攻击面。
#include "rs_checks.h"

#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

const std::vector<std::string>& emuMarkers() {
    static const std::vector<std::string> v = {
        RSs("goldfish"), RSs("ranchu"), RSs("qemu"), RSs("vbox"),
        RSs("microvm"), RSs("android_x86"), RSs("cuttlefish"),
        RSs("genymotion"), RSs("bluestacks"),
    };
    return v;
}

}  // namespace

void checkEmulator(ScanReport& r) {
    // ---------------------------------------------------------- A.EMULATOR
    {
        const auto cpu = toLower(readFile(RSs("/proc/cpuinfo"), 256 * 1024));
        std::vector<std::string> hits;

        for (const auto& k : emuMarkers()) {
            if (contains(cpu, k)) hits.push_back(RSs("cpuinfo:") + k);
        }

        // 关键属性
        static const std::vector<std::string> props = {
            RSs("ro.hardware"), RSs("ro.product.device"),
            RSs("ro.product.name"), RSs("ro.boot.hardware"),
            RSs("ro.kernel.qemu"),
        };
        std::vector<std::string> propLines;
        for (const auto& p : props) {
            const auto v = toLower(getProp(p));
            if (v.empty()) continue;
            propLines.push_back(p + RSs("=") + v);
            for (const auto& k : emuMarkers()) {
                if (contains(v, k)) {
                    hits.push_back(p + RSs(":") + k);
                    break;
                }
            }
        }

        if (!hits.empty()) {
            Evidence e = makeHit(RSs("A.EMULATOR"), Category::ARTIFACT,
                                 Severity::INFO, Confidence::HIGH,
                                 RSs("运行在模拟器环境"));
            for (const auto& h : hits) e.addVector(RSs("marker"), h);
            e.detail = RSs("模拟器上所有 root 痕迹的参考价值都会变化");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("A.EMULATOR"), Category::ARTIFACT,
                                  RSs("未发现模拟器特征"));
            for (const auto& p : propLines) e.addVector(RSs("prop"), p);
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- A.PROP_CROSS
    // boot 属性自洽性检查。
    // 这几个属性彼此存在强约束，单独改其中一个必然自相矛盾：
    //   flash.locked=1  → verifiedbootstate 只能是 green / yellow
    //   flash.locked=0  → verifiedbootstate 不能是 green
    //   veritymode=enforcing 且 state=red → 矛盾
    // 相比「getprop vs 文件」，这种方式不依赖任何不可读文件。
    {
        const auto state  = getProp(RSs("ro.boot.verifiedbootstate"));
        const auto locked = getProp(RSs("ro.boot.flash.locked"));
        const auto verity = getProp(RSs("ro.boot.veritymode"));

        if (state.empty()) {
            r.add(makeUnknown(RSs("A.PROP_CROSS"), Category::PROPERTY,
                              Severity::LOW,
                              RSs("ro.boot.verifiedbootstate 缺失")));
        } else {
            std::vector<std::string> lines;
            static const std::vector<std::string> valid = {
                RSs("green"), RSs("yellow"), RSs("orange"), RSs("red"),
            };

            if (!containsAny(toLower(state), valid)) {
                lines.push_back(RSs("state=") + state + RSs("（非法值）"));
            }
            if (locked == RSs("1") && state != RSs("green") &&
                state != RSs("yellow")) {
                lines.push_back(RSs("locked=1 但 state=") + state);
            }
            if (locked == RSs("0") && state == RSs("green")) {
                lines.push_back(RSs("locked=0 但 state=green"));
            }
            if (verity == RSs("enforcing") && state == RSs("red")) {
                lines.push_back(RSs("verity=enforcing 但 state=red"));
            }

            if (lines.empty()) {
                Evidence e = makePass(RSs("A.PROP_CROSS"), Category::PROPERTY,
                                      RSs("boot 属性彼此自洽"));
                e.addVector(RSs("state"), state);
                e.addVector(RSs("locked"), locked.empty() ? "?" : locked);
                e.addVector(RSs("verity"), verity.empty() ? "?" : verity);
                r.add(std::move(e));
            } else {
                Evidence e = makeHit(RSs("A.PROP_CROSS"), Category::PROPERTY,
                                     Severity::HIGH, Confidence::HIGH,
                                     RSs("boot 属性自相矛盾"));
                for (const auto& s : lines) e.addVector(RSs("conflict"), s);
                e.addVector(RSs("state"), state);
                e.detail = RSs("有人单独改写了其中一个属性"
                               "（resetprop / 隐藏方案），但没改全");
                e.fp_note = RSs("个别工程机可能只设置部分属性，"
                                "此时缺失项不参与判断");
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- A.BOOT_ID
    {
        const auto id = trim(readFile(RSs("/proc/sys/kernel/random/boot_id"), 4096));
        if (id.empty()) {
            r.add(makeUnknown(RSs("A.BOOT_ID"), Category::ARTIFACT, Severity::LOW,
                              RSs("boot_id 不可读")));
        } else if (id.size() < 32) {
            Evidence e = makeHit(RSs("A.BOOT_ID"), Category::ARTIFACT,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("boot_id 长度异常"));
            e.addVector(RSs("value"), id);
            e.detail = RSs("标准 UUID 形式为 36 字符；被伪造时常被截断");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("A.BOOT_ID"), Category::ARTIFACT,
                                  RSs("boot_id 格式正常"));
            e.addVector(RSs("len"), std::to_string(id.size()));
            r.add(std::move(e));
        }
    }
}

}  // namespace rs