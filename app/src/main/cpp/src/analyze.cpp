// analyze.cpp — 环境画像 + 方案推断 + 序列化
#include "rs_checks.h"

#include <algorithm>
#include <cstdlib>
#include <vector>

#include "rs_json.h"
#include "rs_str.h"
#include "rs_util.h"

namespace rs {

DeviceProfile buildProfile() {
    DeviceProfile p;

    p.sdk = std::atoi(getProp(RSs("ro.build.version.sdk")).c_str());
    p.fingerprint = getProp(RSs("ro.build.fingerprint"));
    p.build_type = getProp(RSs("ro.build.type"));
    p.build_tags = getProp(RSs("ro.build.tags"));
    p.product_brand = getProp(RSs("ro.product.brand"));
    p.product_model = getProp(RSs("ro.product.model"));
    p.product_manufacturer = getProp(RSs("ro.product.manufacturer"));
    p.soc_manufacturer = getProp(RSs("ro.soc.manufacturer"));

    p.is_debuggable = (getProp(RSs("ro.debuggable")) == "1");
    p.is_userdebug = (p.build_type == RSs("userdebug"));
    p.is_eng = (p.build_type == RSs("eng"));

    const std::string hw = toLower(getProp(RSs("ro.hardware")));
    const std::string qemu = getProp(RSs("ro.kernel.qemu"));
    const std::string model = toLower(p.product_model);
    p.is_emulator = (qemu == "1") ||
                    contains(hw, RSs("goldfish")) ||
                    contains(hw, RSs("ranchu")) ||
                    contains(hw, RSs("qemu")) ||
                    contains(model, RSs("sdk_gphone")) ||
                    contains(model, RSs("emulator")) ||
                    contains(model, RSs("aosp on"));

    // GSI 判定：只认真正只属于 GSI 的属性。
    // 注意：ro.system.build.type 在任何 Android 上都有值（user/userdebug），
    // 把它拼进来会让 is_gsi 恒为 true —— 这是 2.2.0 及之前的 bug。
    const std::string gsi = getProp(RSs("ro.gsi.version")) +
                            getProp(RSs("ro.build.gsi"));
    p.is_gsi = !gsi.empty() ||
               contains(toLower(p.fingerprint), RSs("gsi"));

    static const std::vector<std::string> vendorKeys = {
        RSs("ro.miui.ui.version.name"),
        RSs("ro.build.version.opporom"),
        RSs("ro.vivo.os.version"),
        RSs("ro.coloros.version"),
        RSs("ro.build.version.emui"),
        RSs("ro.magicui.version"),
        RSs("ro.oneplus.version"),
    };
    for (const auto& k : vendorKeys) {
        if (!getProp(k).empty()) { p.is_vendor_rom = true; break; }
    }

    return p;
}

namespace {

// 从所有证据里提取关键字，推断实际 root 方案
std::string inferScheme(const ScanReport& r, bool* outHiding) {
    bool magisk = false, ksu = false, apatch = false;
    bool lsposed = false, frida = false, hiding = false;

    for (const auto& e : r.items) {
        std::string blob = toLower(e.id + " " + e.title + " " + e.detail);
        for (const auto& v : e.vectors) blob += " " + toLower(v);

        if (e.verdict == Verdict::HIT) {
            if (contains(blob, RSs("magisk"))) magisk = true;
            if (contains(blob, RSs("kernelsu")) || contains(blob, RSs("ksu"))) ksu = true;
            if (contains(blob, RSs("apatch"))) apatch = true;
            if (contains(blob, RSs("lsposed")) || contains(blob, RSs("xposed"))) lsposed = true;
            if (contains(blob, RSs("frida"))) frida = true;
        }
        if (e.category == Category::HIDING && e.verdict == Verdict::HIT) hiding = true;
        if (contains(blob, RSs("过度隐藏"))) hiding = true;
        if (contains(blob, RSs("交叉验证矛盾"))) hiding = true;
    }

    *outHiding = hiding;

    std::vector<std::string> parts;
    if (magisk) parts.push_back(RSs("Magisk"));
    if (ksu) parts.push_back(RSs("KernelSU/APatch"));
    if (apatch && !ksu) parts.push_back(RSs("APatch"));
    if (lsposed) parts.push_back(RSs("LSPosed"));
    if (frida) parts.push_back(RSs("Frida"));

    if (parts.empty()) return RSs("未知");

    std::string s;
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i) s += RSs(" + ");
        s += parts[i];
    }
    if (hiding) s += RSs(" (+ 隐藏手段)");
    return s;
}

}  // namespace

void analyze(ScanReport& r) {
    bool hiding = false;
    const std::string scheme = inferScheme(r, &hiding);

    Evidence e = makePass(RSs("Z.SCHEME"), Category::CROSSCHECK,
                          RSs("方案画像: ") + scheme);
    e.detail = r.profile.is_emulator ? RSs("运行于模拟器") : RSs("运行于物理设备");
    e.addVector(RSs("hiding"), hiding ? RSs("yes") : RSs("no"));
    // v2.22.82 N3：记录本轮模块乱序种子，凭种子可复现执行顺序。
    if (r.shuffleSeed != 0) e.addVector(RSs("seed"), std::to_string(r.shuffleSeed));
    r.add(std::move(e));

    if (hiding) {
        Evidence h = makeHit(RSs("H.HIDING_SIGNAL"), Category::HIDING,
                             Severity::HIGH, Confidence::MEDIUM,
                             RSs("检测到隐藏行为特征"));
        h.detail = RSs("依据：交叉验证矛盾 / 过度隐藏 / 隐藏类关键字");
        h.fp_note = RSs("部分厂商 ROM 会收紧 SELinux 策略，"
                        "可能产生类似的「过度隐藏」信号");
        h.heuristic = true;
        r.add(std::move(h));
    }
}

std::string serializeReport(const ScanReport& r) {
    Json j;
    j.obj();

    j.key(RSs("schema")).str(RSs("rootsentinel/1"));
    // 版本由 CMake 在编译期注入（RS_VERSION），不再硬编码。
    // 之前这里写死 "2.4.1"，与 Java 侧 2.22.34 长期不一致，
    // 报告里的版本号没法用来定位问题。
    j.key(RSs("version")).str(RSs(RS_VERSION));

    // ---------------- profile
    j.key(RSs("profile")).obj();
    j.kv(RSs("sdk"), r.profile.sdk);
    j.kv(RSs("fingerprint"), r.profile.fingerprint);
    j.kv(RSs("buildType"), r.profile.build_type);
    j.kv(RSs("buildTags"), r.profile.build_tags);
    j.kv(RSs("brand"), r.profile.product_brand);
    j.kv(RSs("model"), r.profile.product_model);
    j.kv(RSs("manufacturer"), r.profile.product_manufacturer);
    j.kv(RSs("socManufacturer"), r.profile.soc_manufacturer);
    j.kv(RSs("isEmulator"), r.profile.is_emulator);
    j.kv(RSs("isGsi"), r.profile.is_gsi);
    j.kv(RSs("isUserdebug"), r.profile.is_userdebug);
    j.kv(RSs("isEng"), r.profile.is_eng);
    j.kv(RSs("isVendorRom"), r.profile.is_vendor_rom);
    j.kv(RSs("isDebuggable"), r.profile.is_debuggable);
    j.endObj();

    // ---------------- items
    j.key(RSs("items")).arr();
    for (const auto& e : r.items) {
        j.obj();
        j.kv(RSs("id"), e.id);
        j.kv(RSs("category"), categoryCode(e.category));
        j.kv(RSs("severity"), severityName(e.severity));
        j.kv(RSs("confidence"), confidenceName(e.confidence));
        j.kv(RSs("verdict"), verdictName(e.verdict));
        j.kv(RSs("heuristic"), e.heuristic);
        // v2.22.82 E1：来源组（additive 字段，老解析器忽略即可）
        if (!e.sourceGroup.empty()) j.kv(RSs("sg"), e.sourceGroup);
        j.kv(RSs("title"), e.title);
        if (!e.detail.empty()) j.kv(RSs("detail"), e.detail);

        if (!e.vectors.empty()) {
            j.key(RSs("vectors")).arr();
            for (const auto& v : e.vectors) j.str(v);
            j.endArr();
        }
        if (!e.fp_note.empty()) j.kv(RSs("fpNote"), e.fp_note);
        if (!e.verify_note.empty()) j.kv(RSs("verifyNote"), e.verify_note);
        j.endObj();
    }
    j.endArr();

    // ---------------- crosses
    j.key(RSs("crosses")).arr();
    for (const auto& c : r.crosses) {
        j.obj();
        j.kv(RSs("id"), c.id);
        j.kv(RSs("subject"), c.subject);
        j.kv(RSs("consistent"), c.consistent);
        j.key(RSs("observations")).arr();
        for (const auto& o : c.observations) {
            j.obj();
            j.kv(RSs("view"), o.first);
            j.kv(RSs("value"), o.second);
            j.endObj();
        }
        j.endArr();
        j.endObj();
    }
    j.endArr();

    j.endObj();
    return j.take();
}

}  // namespace rs