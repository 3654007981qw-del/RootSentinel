// rs_types.h — 全局数据结构与枚举
// 设计约束：
//  1) 每个结论必须是「三态」：PASS / HIT / UNKNOWN，SELinux 拒绝必须落 UNKNOWN
//  2) 每条证据带 severity（硬权重）与 confidence（可信度），并区分 hard / heuristic
//  3) 交叉验证不一致单独成一条 CROSSCHECK 证据，权重高于单点命中
#ifndef RS_TYPES_H
#define RS_TYPES_H

#include <cstdint>
#include <string>
#include <vector>

namespace rs {

// ---------------------------------------------------------------- 三态
enum class Verdict : int32_t {
    PASS    = 0,   // 明确判定「无此痕迹」
    HIT     = 1,   // 明确命中
    UNKNOWN = 2,   // 无法读取 / 结果不可用（SELinux 拒绝、接口缺失）
};

// ---------------------------------------------------------------- 严重度（硬权重）
enum class Severity : int32_t {
    INFO     = 0,
    LOW      = 1,
    MEDIUM   = 2,
    HIGH     = 3,
    CRITICAL = 4,
};

// ---------------------------------------------------------------- 置信度
// LOW      : 单一视角、易误报
// MEDIUM   : 单一视角但特征明确，或多视角弱一致
// HIGH     : 多视角一致
// VERIFIED : 密码学验证（Key Attestation 证书链）
enum class Confidence : int32_t {
    LOW      = 0,
    MEDIUM   = 1,
    HIGH     = 2,
    VERIFIED = 3,
};

// ---------------------------------------------------------------- 类别（对应需求 A~H）
enum class Category : int32_t {
    ARTIFACT    = 0,  // A 经典痕迹
    PROPERTY    = 1,  // B 属性与启动状态
    MOUNT       = 2,  // C 挂载与命名空间
    MEMORY      = 3,  // D 内存与注入
    HOOK        = 4,  // E Hook 框架
    SELINUX     = 5,  // F SELinux 与内核
    ATTESTATION = 6,  // G 硬件级证明
    HIDING      = 7,  // H 隐藏与欺骗痕迹
    INTEGRITY   = 8,  // 自保：自身完整性
    CROSSCHECK  = 9,  // 交叉验证矛盾（独立类别，权重最高）
    KERNEL      = 10, // F 内核标识（与 SELINUX 同属 F 类，单独细分）
};

// ---------------------------------------------------------------- 证据
struct Evidence {
    std::string             id;          // 稳定 ID，如 "A.SU_PATH"
    Category                category     = Category::ARTIFACT;
    Severity                severity     = Severity::INFO;
    Confidence              confidence   = Confidence::LOW;
    Verdict                 verdict      = Verdict::UNKNOWN;
    bool                    heuristic    = false;  // true = 软证据（启发式）
    std::string             title;                 // 一句话结论
    std::string             detail;                // 详细说明（含原始值）
    std::vector<std::string> vectors;              // 各视角观测："view=value"
    std::string             fp_note;               // 误报说明（何时会误判）
    std::string             verify_note;           // "需真机验证"之类

    // 便捷构造
    static Evidence make(std::string id_, Category cat, Severity sev,
                         Confidence conf, Verdict v, std::string title_,
                         std::string detail_ = {}) {
        Evidence e;
        e.id = std::move(id_);
        e.category = cat;
        e.severity = sev;
        e.confidence = conf;
        e.verdict = v;
        e.title = std::move(title_);
        e.detail = std::move(detail_);
        return e;
    }

    void addVector(const std::string& view, const std::string& value) {
        vectors.push_back(view + "=" + value);
    }
};

// ---------------------------------------------------------------- 交叉验证
// 同一事实的多视角观测；不一致 => 存在 hook / 隐藏
struct CrossCheck {
    std::string id;                                  // "X.MODULES_READABLE"
    std::string subject;                             // 被验证的事实
    std::vector<std::pair<std::string, std::string>> observations;  // 视角 -> 结果
    bool consistent = true;

    void observe(const std::string& view, const std::string& value) {
        observations.emplace_back(view, value);
    }
};

// ---------------------------------------------------------------- 环境画像（用于降权/白名单）
struct DeviceProfile {
    int32_t     sdk              = 0;
    std::string fingerprint;
    std::string build_type;        // user / userdebug / eng
    std::string build_tags;        // release-keys / test-keys
    std::string product_brand;
    std::string product_model;
    std::string product_manufacturer;
    bool        is_emulator      = false;  // qemu / goldfish / ranchuniverse 等
    bool        is_gsi           = false;  // ro.build.ab_update + gsi 标识
    bool        is_userdebug     = false;
    bool        is_eng           = false;
    bool        is_vendor_rom    = false;  // 厂商深度定制（MIUI/OriginOS/ColorOS/HyperOS…）
    bool        is_debuggable    = false;  // ro.debuggable=1
    bool        developer_options = false; // Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
    std::string soc_manufacturer;
    std::string build_flavor;
};

// ---------------------------------------------------------------- 汇总
struct ScanReport {
    std::vector<Evidence>  items;
    std::vector<CrossCheck> crosses;
    DeviceProfile          profile;

    // 进度回调：由 jni.cpp 注入，用于把每一条证据实时推给 Java
    void (*onItem)(const Evidence&, void*) = nullptr;
    void* user = nullptr;

    void add(Evidence e) {
        items.push_back(std::move(e));
        if (onItem) onItem(items.back(), user);
    }
    void addCross(CrossCheck c) { crosses.push_back(std::move(c)); }
};

// ---------------------------------------------------------------- 工具
inline const char* verdictName(Verdict v) {
    switch (v) {
        case Verdict::PASS:    return "PASS";
        case Verdict::HIT:     return "HIT";
        case Verdict::UNKNOWN: return "UNKNOWN";
    }
    return "UNKNOWN";
}

inline const char* severityName(Severity s) {
    switch (s) {
        case Severity::INFO:     return "INFO";
        case Severity::LOW:      return "LOW";
        case Severity::MEDIUM:   return "MEDIUM";
        case Severity::HIGH:     return "HIGH";
        case Severity::CRITICAL: return "CRITICAL";
    }
    return "INFO";
}

inline const char* confidenceName(Confidence c) {
    switch (c) {
        case Confidence::LOW:      return "LOW";
        case Confidence::MEDIUM:   return "MEDIUM";
        case Confidence::HIGH:     return "HIGH";
        case Confidence::VERIFIED: return "VERIFIED";
    }
    return "LOW";
}

inline const char* categoryCode(Category c) {
    switch (c) {
        case Category::ARTIFACT:    return "A";
        case Category::PROPERTY:    return "B";
        case Category::MOUNT:       return "C";
        case Category::MEMORY:      return "D";
        case Category::HOOK:        return "E";
        case Category::SELINUX:     return "F";
        case Category::KERNEL:      return "F";
        case Category::ATTESTATION: return "G";
        case Category::HIDING:      return "H";
        case Category::INTEGRITY:   return "I";
        case Category::CROSSCHECK:  return "X";
    }
    return "?";
}

}  // namespace rs

#endif  // RS_TYPES_H
