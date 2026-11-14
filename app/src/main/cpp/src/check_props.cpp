// check_props.cpp — B 属性与启动状态
// 覆盖 v2 增强包【一.5】内核版本多源对比、【一.6】vbmeta digest
#include "rs_checks.h"

#include <sys/utsname.h>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

// 从多个分区的 build.prop 里取原始值
std::string propFromFiles(const std::string& key) {
    static const std::vector<std::string> files = {
        RSs("/system/build.prop"),
        RSs("/vendor/build.prop"),
        RSs("/product/build.prop"),
        RSs("/system_ext/build.prop"),
        RSs("/odm/etc/build.prop"),
        RSs("/prop.default"),
        RSs("/system/etc/prop.default"),
    };

    for (const auto& f : files) {
        std::string data = readFile(f, 256 * 1024);
        if (data.empty()) continue;

        size_t pos = 0;
        while (pos < data.size()) {
            size_t eol = data.find('\n', pos);
            if (eol == std::string::npos) eol = data.size();
            std::string line = trim(data.substr(pos, eol - pos));
            if (!line.empty() && line[0] != '#') {
                size_t eq = line.find('=');
                if (eq != std::string::npos) {
                    if (trim(line.substr(0, eq)) == key) {
                        return trim(line.substr(eq + 1));
                    }
                }
            }
            pos = eol + 1;
        }
    }
    return {};
}

}  // namespace

void checkProps(ScanReport& r) {
    // ---------------------------------------------------------- 属性篡改
    // resetprop 只改内存属性树，不改 build.prop 文件
    static const std::vector<std::string> keys = {
        RSs("ro.build.tags"),         RSs("ro.build.type"),
        RSs("ro.debuggable"),         RSs("ro.secure"),
        RSs("ro.boot.verifiedbootstate"), RSs("ro.boot.flash.locked"),
        RSs("ro.boot.veritymode"),    RSs("ro.build.fingerprint"),
        RSs("ro.product.brand"),      RSs("ro.product.model"),
    };

    int mismatches = 0;
    for (const auto& k : keys) {
        const std::string live = getProp(k);
        const std::string file = propFromFiles(k);
        if (live.empty() || file.empty()) continue;
        if (live == file) continue;

        ++mismatches;
        Evidence e = makeHit(RSs("B.PROP_TAMPER"), Category::PROPERTY,
                             Severity::HIGH, Confidence::HIGH,
                             RSs("属性被篡改: ") + k);
        e.addVector(RSs("getprop"), live);
        e.addVector(RSs("build.prop"), file);
        e.fp_note = RSs("厂商 OTA 后 build.prop 与运行时属性短暂不一致；"
                        "重启后应消失");
        r.add(std::move(e));
    }
    if (mismatches == 0) {
        r.add(makePass(RSs("B.PROP_TAMPER"), Category::PROPERTY,
                       RSs("关键属性 getprop 与 build.prop 全部一致")));
    }

    // ---------------------------------------------------------- 危险值
    const std::string tags = getProp(RSs("ro.build.tags"));
    if (tags == RSs("test-keys")) {
        r.add(makeHit(RSs("B.TEST_KEYS"), Category::PROPERTY,
                      Severity::MEDIUM, Confidence::MEDIUM,
                      RSs("ro.build.tags = test-keys（自编译签名）")));
    }

    const std::string debuggable = getProp(RSs("ro.debuggable"));
    if (debuggable == "1") {
        r.add(makeHit(RSs("B.DEBUGGABLE"), Category::PROPERTY,
                      Severity::HIGH, Confidence::HIGH,
                      RSs("ro.debuggable = 1（可调试构建）")));
    }

    const std::string btype = getProp(RSs("ro.build.type"));
    if (btype == RSs("userdebug") || btype == RSs("eng")) {
        r.add(makeHit(RSs("B.BUILD_TYPE"), Category::PROPERTY,
                      Severity::MEDIUM, Confidence::MEDIUM,
                      RSs("ro.build.type 非 user: ") + btype));
    }

    // ---------------------------------------------------------- bootloader
    const std::string vbstate = getProp(RSs("ro.boot.verifiedbootstate"));
    if (vbstate == RSs("green")) {
        r.add(makePass(RSs("B.VB_STATE"), Category::PROPERTY,
                       RSs("verifiedbootstate = green")));
    } else if (!vbstate.empty()) {
        Evidence e = makeHit(RSs("B.VB_STATE"), Category::PROPERTY,
                             Severity::MEDIUM, Confidence::MEDIUM,
                             RSs("verifiedbootstate 非 green: ") + vbstate);
        e.fp_note = RSs("部分厂商在 boot 早期改写该属性");
        r.add(std::move(e));
    }

    const std::string locked = getProp(RSs("ro.boot.flash.locked"));
    if (locked == "0") {
        r.add(makeHit(RSs("B.FLASH_LOCKED"), Category::PROPERTY,
                      Severity::MEDIUM, Confidence::HIGH,
                      RSs("ro.boot.flash.locked = 0（bootloader 已解锁）")));
    }

    // ---------------------------------------------------------- 一.6 vbmeta digest
    const std::string vbDigest = getProp(RSs("ro.boot.vbmeta.digest"));
    if (vbDigest.empty()) {
        r.add(makeUnknown(RSs("B.VBMETA_DIGEST"), Category::PROPERTY,
                          Severity::LOW,
                          RSs("ro.boot.vbmeta.digest 不可读（记录为 UNKNOWN）")));
    } else {
        Evidence e = makePass(RSs("B.VBMETA_DIGEST"), Category::PROPERTY,
                              RSs("vbmeta digest 可读"));
        e.addVector(RSs("digest"), vbDigest);
        e.verify_note = RSs("M2 阶段与 Key Attestation 的 verifiedBootHash 比对");
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 全量关键字
    const std::string all = readFile(RSs("/system/build.prop"), 512 * 1024) +
                            getProp(RSs("ro.boot.vbmeta.device_state"));
    (void)all;

    // 属性全量扫描（getprop 输出需要 Java 侧配合，这里只查关键几个）
    static const std::vector<std::string> suspects = {
        // v2.22.45（审查 A P0-3）：ro.kernel.qemu 是模拟器标记，不是
        // root 证据，已由 check_emulator 在模拟器语境单独检查，从本
        // 列表移除，避免在模拟器上污染 Root 结论。
        RSs("ro.boot.ksu"),
        RSs("ro.kernelsu.version"), RSs("ro.magisk.version"),
    };
    for (const auto& s : suspects) {
        std::string v = getProp(s);
        if (!v.empty()) {
            Evidence e = makeHit(RSs("B.PROP_KEYWORD"), Category::PROPERTY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("属性暴露 root 组件: ") + s);
            e.addVector(s, v);
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- 一.5 uname 多源
    // v2.22.45（审查 A P1-14 附带收口）：旧实现同 ID B.UNAME_MULTI
    // 产出两条 PASS，评分器去重时只能留一条、另一条的向量被丢弃。
    // 现在合并为一条证据，两个来源的观测都进 vectors。
    struct utsname u{};
    std::string unameRelease;
    Evidence multi = makePass(RSs("B.UNAME_MULTI"), Category::PROPERTY,
                              RSs("uname 多源可读"));
    bool multiAny = false;
    if (uname(&u) == 0) {
        unameRelease = u.release;
        multi.addVector(RSs("release"), u.release);
        multi.addVector(RSs("version"), u.version);
        multi.addVector(RSs("machine"), u.machine);
        multiAny = true;
    }

    const std::string osrel = readFile(RSs("/proc/sys/kernel/osrelease"));
    if (!osrel.empty()) {
        multi.addVector(RSs("osrelease"), trim(osrel));
        multiAny = true;

        if (!unameRelease.empty() && trim(osrel) != unameRelease) {
            Evidence c = makeHit(RSs("B.UNAME_MISMATCH"), Category::CROSSCHECK,
                                 Severity::HIGH, Confidence::HIGH,
                                 RSs("uname(2) 与 osrelease 不一致"));
            c.addVector(RSs("uname"), unameRelease);
            c.addVector(RSs("osrelease"), trim(osrel));
            c.detail = RSs("有 hook 在改写其中一个来源");
            r.add(std::move(c));
        }
    }
    if (multiAny) r.add(std::move(multi));
}

}  // namespace rs
