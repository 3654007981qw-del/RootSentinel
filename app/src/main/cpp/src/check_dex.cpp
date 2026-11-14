// check_dex.cpp — DEX / OAT 加载检测（M3）
//
// Xposed / LSPosed / 部分注入框架会把额外的 dex 塞进进程。
// 正常 App 只会加载自己 APK 里的 classes.dex（可能还有 split APK），
// 以及 /data/dalvik-cache 或 /data/app/.../oat 下的 vdex/oat。
// 出现其它路径的 dex/odex/vdex/oat，基本可判定为动态注入。
#include "rs_checks.h"

#include <cstdio>
#include <cstring>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {

bool isNormalDexPath(const std::string& p) {
    static const std::vector<std::string> ok = {
        RSs("/data/app/"),
        RSs("/data/dalvik-cache/"),
        RSs("/data/user/"),
        RSs("/apex/"),
        RSs("/system/"),
        RSs("/product/"),
        RSs("/system_ext/"),
        RSs("/vendor/"),
        RSs("/odm/"),
    };
    for (const auto& k : ok) {
        if (startsWith(p, k)) return true;
    }
    return false;
}

}  // namespace

void checkDex(ScanReport& r) {
    auto lines = readLines(RSs("/proc/self/maps"), 20000);

    std::vector<std::string> allDex;
    int suspicious = 0;

    for (const auto& line : lines) {
        // 路径是最后一个空白之后的部分
        size_t sp = line.find_last_of(' ');
        if (sp == std::string::npos) continue;
        std::string path = trim(line.substr(sp + 1));
        if (path.empty() || path[0] != '/') continue;

        if (!contains(path, RSs(".dex")) &&
            !contains(path, RSs(".odex")) &&
            !contains(path, RSs(".vdex")) &&
            !contains(path, RSs(".oat"))) {
            continue;
        }

        // 去重
        bool dup = false;
        for (const auto& d : allDex) {
            if (d == path) { dup = true; break; }
        }
        if (dup) continue;
        allDex.push_back(path);

        if (!isNormalDexPath(path)) {
            ++suspicious;
            Evidence e = makeHit(RSs("D.EXTRA_DEX"), Category::MEMORY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("加载了非应用目录的 DEX: ") + path);
            e.addVector(RSs("path"), path);
            e.detail = RSs("正常 App 不会从该路径加载 dex/oat —— 典型动态注入");
            r.add(std::move(e));
        }
    }

    if (suspicious == 0) {
        Evidence e = makePass(RSs("D.EXTRA_DEX"), Category::MEMORY,
                              RSs("所有 DEX/OAT 均来自应用或系统目录"));
        e.addVector(RSs("count"), std::to_string(allDex.size()));
        for (size_t i = 0; i < allDex.size() && i < 4; ++i) {
            e.addVector(RSs("path"), allDex[i]);
        }
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- dex 数量异常
    // 单 APK 正常是 1 个 base.dex（+ 可能的 split），超过 6 个要留意
    if (allDex.size() > 6) {
        Evidence e = makeHit(RSs("D.DEX_COUNT"), Category::MEMORY,
                             Severity::MEDIUM, Confidence::MEDIUM,
                             RSs("加载的 DEX/OAT 数量异常"));
        e.addVector(RSs("count"), std::to_string(allDex.size()));
        e.fp_note = RSs("多 dex 应用（multidex）或 Play 分卷会正常超过 6 个");
        r.add(std::move(e));
    }
}

}  // namespace rs