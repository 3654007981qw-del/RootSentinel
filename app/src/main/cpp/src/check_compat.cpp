// check_compat.cpp — 刷机痕迹、崩溃日志、运行时能力矩阵（M8）
//
// 这一层的出发点：检测器要跨设备、跨 Android 版本工作，
// 就必须能区分「没检测到」和「这个环境根本测不了」。
//
// 所以除了 4 项真实检测，还额外输出一条 X.ENV_MATRIX，
// 把本机的关键能力（SDK / ABI / statx / apex / debug_ramdisk）
// 全部列出来 —— 换一台设备跑，一眼就能看出哪些项是因为环境缺失才 UNKNOWN。
#include "rs_checks.h"

#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

std::string abiName() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

int countLines(const std::string& path, size_t maxBytes, size_t maxLines) {
    const auto data = readFile(path, maxBytes);
    if (data.empty()) return -1;
    int n = 0;
    for (const char c : data) {
        if (c == '\n') ++n;
        if ((size_t)n >= maxLines) break;
    }
    return n;
}

}  // namespace

void checkCompat(ScanReport& r) {

    // ------------------------------------------------------ A.RECOVERY_TRACE
    // 刷过机 / 进过 recovery / 侧载过 OTA 的设备会留下这些目录。
    // 它们本身不等于 root，但是「设备被动过」的强提示，
    // 配合其他证据能让结论更准。
    {
        std::vector<std::string> found;
        bool undecidable = false;

        // /cache 与 /data 对 untrusted_app 本身就是「可 stat 不可进入」，
        // 其下子路径返回 EACCES 无法区分「存在」和「父目录不可进」。
        // 所以先看父目录能不能进，不能进就整项记 UNKNOWN。
        const int cacheState = accessState(RSs("/cache"), 4);
        const int dataState  = accessState(RSs("/data"), 4);

        if (cacheState == 1 || dataState == 1) {
            undecidable = true;
        } else {
            if (cacheState == 0 && accessState(RSs("/cache/recovery"), 0) != 2) {
                found.push_back(RSs("/cache/recovery"));
            }
            if (dataState == 0) {
                if (accessState(RSs("/data/ota"), 0) != 2) {
                    found.push_back(RSs("/data/ota"));
                }
                if (accessState(RSs("/data/ota_package"), 0) != 2) {
                    found.push_back(RSs("/data/ota_package"));
                }
            }
        }

        if (undecidable) {
            Evidence e = makeUnknown(RSs("A.RECOVERY_TRACE"), Category::ARTIFACT,
                                     Severity::LOW,
                                     RSs("/cache 与 /data 不可进入，无法判断"));
            e.addVector(RSs("cache_state"), cacheState == 1 ? "EACCES" : "OK");
            e.addVector(RSs("data_state"), dataState == 1 ? "EACCES" : "OK");
            r.add(std::move(e));
        } else if (found.empty()) {
            r.add(makePass(RSs("A.RECOVERY_TRACE"), Category::ARTIFACT,
                           RSs("无刷机 / recovery 痕迹")));
        } else {
            Evidence e = makeHit(RSs("A.RECOVERY_TRACE"), Category::ARTIFACT,
                                 Severity::LOW, Confidence::MEDIUM,
                                 RSs("存在刷机 / OTA 痕迹"));
            for (const auto& f : found) e.addVector(RSs("path"), f);
            e.detail = RSs("设备曾进入 recovery 或侧载过 OTA；"
                           "本身不是 root，但说明系统被动过");
            e.fp_note = RSs("正常 OTA 升级也会创建 /data/ota_package，"
                            "需结合时间戳判断");
            r.add(std::move(e));
        }
    }

    // ------------------------------------------------------------- F.PSTORE
    // /sys/fs/pstore 保存内核 oops / panic 的持久化日志。
    // root 工具（尤其 LKM 型）出问题时经常在这里留下调用栈，
    // 里面会直接出现模块名。
    {
        auto entries = listDirRaw(RSs("/sys/fs/pstore"));
        std::vector<std::string> names;
        for (const auto& d : entries) {
            if (d.name.empty() || d.name[0] == '.') continue;
            names.push_back(d.name);
        }

        if (names.empty()) {
            r.add(makePass(RSs("F.PSTORE"), Category::KERNEL,
                           RSs("无内核崩溃日志")));
        } else {
            Evidence e = makeHit(RSs("F.PSTORE"), Category::KERNEL,
                                 Severity::MEDIUM, Confidence::MEDIUM,
                                 RSs("存在内核崩溃日志"));
            for (const auto& n : names) e.addVector(RSs("entry"), n);
            e.detail = RSs("内核发生过 panic / oops；"
                           "LKM 型 root 常在这里留下模块名");
            r.add(std::move(e));
        }
    }

    // -------------------------------------------------------- D.MAPS_COUNT
    // 注入会在 maps 里增加大量匿名段。
    // 但阈值要按 Android 版本调：Android 14+ 因 APEX 拆分 + 大量厂商 so，
    // 真机 App 的 maps 条目数普遍能到 2000~3500。
    // 所以阈值放到 4000，且置信度只给 LOW —— 这一项只做提示，不做定罪。
    {
        const int n = countLines(RSs("/proc/self/maps"), 8u << 20, 40000);
        if (n < 0) {
            r.add(makeUnknown(RSs("D.MAPS_COUNT"), Category::MEMORY,
                              Severity::LOW, RSs("/proc/self/maps 不可读")));
        } else if (n > 4000) {
            Evidence e = makeHit(RSs("D.MAPS_COUNT"), Category::MEMORY,
                                 Severity::LOW, Confidence::LOW,
                                 RSs("maps 条目数偏多"));
            e.addVector(RSs("count"), std::to_string(n));
            e.detail = RSs("Android 14+ 真机通常在 2000~3500 条；"
                           "超过 4000 值得看一眼");
            e.fp_note = RSs("大型游戏 / 加固壳 / 多 dex App 也可能到 5000+，"
                            "必须结合 D.TRUE_ANON / D.MEMFD 一起判断");
            r.add(std::move(e));
        } else {
            Evidence e = makePass(RSs("D.MAPS_COUNT"), Category::MEMORY,
                                  RSs("maps 条目数正常"));
            e.addVector(RSs("count"), std::to_string(n));
            r.add(std::move(e));
        }
    }

    // --------------------------------------------------------- X.ENV_MATRIX
    // 这不是检测，是「自证」：
    // 换一台设备 / 换一个 Android 版本跑的时候，
    // 这张表能立刻说明哪些检查是因为环境缺失才 UNKNOWN。
    {
        Evidence e = makePass(RSs("X.ENV_MATRIX"), Category::CROSSCHECK,
                              RSs("运行时能力矩阵"));

        e.addVector(RSs("abi"), abiName());
        e.addVector(RSs("sdk"), getProp(RSs("ro.build.version.sdk")));
        e.addVector(RSs("release"), getProp(RSs("ro.build.version.release")));
        e.addVector(RSs("model"), getProp(RSs("ro.product.model")));

        // statx 是否真的可用（而不是走了 fstatat 回退）
        {
            sys::statx_buf sx{};
            bool fb = false;
            const bool ok = sys::statx_("/system", &sx, &fb, 0) == 0;
            e.addVector(RSs("statx"),
                        !ok ? RSs("unavailable")
                            : (fb ? RSs("fallback") : RSs("native")));
        }

        // v2.22.45（审查 A P2-17）：旧写法用 R_OK 可读性代指「存在」，
        // 目录存在但不可读会被报成 0（缺失）。改为三态：
        // 1=存在且可读，exists=存在但不可读，0=不存在。
        auto dirState = [](const std::string& p) -> std::string {
            if (accessState(p, 4) == 0) return "1";
            return fileExistsRaw(p) ? "exists" : "0";
        };
        e.addVector(RSs("apex"), dirState(RSs("/apex")));
        e.addVector(RSs("debug_ramdisk"), dirState(RSs("/debug_ramdisk")));
        e.addVector(RSs("system_ext"), dirState(RSs("/system_ext")));
        e.addVector(RSs("pstore"), dirState(RSs("/sys/fs/pstore")));
        e.addVector(RSs("proc_tainted_readable"),
                    readFile(RSs("/proc/sys/kernel/tainted"), 4096).empty() ? "0" : "1");

        e.detail = RSs("用于跨设备对照：某项 UNKNOWN 时，"
                       "先看这张表里对应能力是否为 0");
        r.add(std::move(e));
    }
}

}  // namespace rs