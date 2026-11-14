// check_art_method.cpp — ArtMethod 归属与 OAT 路径（M7）
//
// 这是最后一块拼图。
//
// 已有的覆盖：
//   I.TEXT_HASH   → libc / libart 的机器码没被改
//   E.SYMBOL_HOOK → dlsym 返回的地址正确
//   E.DEX_ELEMENTS→ Java 层没多出 dex
//
// 还剩下的盲区：
//   有人不改代码段、不加 dex，只把某个 ArtMethod 结构体里的
//   entry_point_from_quick_compiled_code_ 换成自己的函数指针。
//   这时调用该 Java 方法，执行的是替换后的代码。
//
// 检测方式（不改结构体布局，只验证归属）：
//   jmethodID 在 ART 里就是 ArtMethod*。
//   拿一个已知方法（Object.hashCode）的 jmethodID，
//   在 /proc/self/maps 里查它属于哪个映射段。
//   正常必须落在 boot.art / boot.oat / .vdex / dalvik 相关映射内。
//   落在他处 → ArtMethod 被搬到别的地方或指向被改写的区域。
#include "rs_checks.h"

#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

std::string mappingOf(const uintptr_t addr) {
    const auto lines = readLines(RSs("/proc/self/maps"), 40000);
    for (const auto& line : lines) {
        unsigned long long a = 0, b = 0;
        if (std::sscanf(line.c_str(), "%llx-%llx", &a, &b) != 2) continue;
        if (addr >= a && addr < b) return line;
    }
    return {};
}

std::string pathOf(const std::string& mapsLine) {
    // start-end perms offset dev inode [path]
    int fields = 0;
    size_t pos = 0;
    std::string last;
    while (pos < mapsLine.size() && fields < 5) {
        while (pos < mapsLine.size() && mapsLine[pos] == ' ') ++pos;
        const size_t e = mapsLine.find(' ', pos);
        if (e == std::string::npos) { pos = mapsLine.size(); break; }
        pos = e + 1;
        ++fields;
    }
    while (pos < mapsLine.size() && mapsLine[pos] == ' ') ++pos;
    if (pos < mapsLine.size()) last = trim(mapsLine.substr(pos));
    return last;
}

}  // namespace

void checkArtMethod(JNIEnv* env, ScanReport& r) {
    // -------------------------------------------------------- E.ARTMETHOD
    if (env == nullptr) {
        r.add(makeUnknown(RSs("E.ARTMETHOD"), Category::HOOK, Severity::LOW,
                          RSs("JNIEnv 不可用")));
        return;
    }

    jclass objCls = env->FindClass("java/lang/Object");
    if (objCls == nullptr) {
        env->ExceptionClear();
        r.add(makeUnknown(RSs("E.ARTMETHOD"), Category::HOOK, Severity::LOW,
                          RSs("FindClass(java/lang/Object) 失败")));
        return;
    }

    jmethodID mid = env->GetMethodID(objCls, "hashCode", "()I");
    if (mid == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(objCls);
        r.add(makeUnknown(RSs("E.ARTMETHOD"), Category::HOOK, Severity::LOW,
                          RSs("GetMethodID(hashCode) 失败")));
        return;
    }

    // jmethodID 在 ART 里就是 ArtMethod*，直接把这个值当地址用
    const uintptr_t addr = reinterpret_cast<uintptr_t>(mid);
    const auto line = mappingOf(addr);
    const auto path = line.empty() ? std::string() : pathOf(line);

    if (line.empty()) {
        env->DeleteLocalRef(objCls);
        r.add(makeUnknown(RSs("E.ARTMETHOD"), Category::HOOK, Severity::LOW,
                          RSs("ArtMethod 地址不在任何映射内")));
        return;
    }

    // 合法归属：boot image（.art / .oat）、vdex、以及 ART 自己的命名匿名区
    const bool legit =
        contains(path, RSs("boot.art")) ||
        contains(path, RSs(".art")) ||
        contains(path, RSs(".oat")) ||
        contains(path, RSs(".vdex")) ||
        contains(path, RSs(".odex")) ||
        contains(path, RSs("dalvik")) ||
        contains(path, RSs("art"));

    // 抽取权限位用于展示
    std::string perms;
    {
        const auto sp = line.find(' ');
        if (sp != std::string::npos) {
            const auto sp2 = line.find(' ', sp + 1);
            if (sp2 != std::string::npos) {
                perms = line.substr(sp + 1, sp2 - sp - 1);
            }
        }
    }

    if (legit) {
        Evidence e = makePass(RSs("E.ARTMETHOD"), Category::HOOK,
                              RSs("ArtMethod 归属正常"));
        e.addVector(RSs("addr"), hex64(addr));
        e.addVector(RSs("perms"), perms);
        e.addVector(RSs("path"), path.substr(0, 96));
        r.add(std::move(e));
    } else {
        Evidence e = makeHit(RSs("E.ARTMETHOD"), Category::HOOK,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("ArtMethod 位于异常映射"));
        e.addVector(RSs("addr"), hex64(addr));
        e.addVector(RSs("perms"), perms);
        e.addVector(RSs("path"),
                    path.empty() ? RSs("(无路径)") : path.substr(0, 96));
        e.detail = RSs("ArtMethod 结构体必须位于 boot image / oat / vdex 内；"
                       "出现在其他区域说明方法表被搬移或伪造");
        e.fp_note = RSs("部分 ROM 把 boot image 内联进 /system，"
                        "此时路径可能不含 boot 字样，需看 perms 是否为 r--p");
        r.add(std::move(e));
    }

    env->DeleteLocalRef(objCls);

    // ---------------------------------------------------------- E.OAT_PATH
    // 本 App 自己的 oat/vdex 必须在 /data/app/... 下。
    // 出现在 /data/local/tmp 等位置 → APK 被搬到非规范位置后加载。
    {
        const auto lines = readLines(RSs("/proc/self/maps"), 40000);
        std::vector<std::string> odd;
        int total = 0;

        for (const auto& l : lines) {
            if (!contains(l, RSs(".odex")) && !contains(l, RSs(".vdex")) &&
                !contains(l, RSs(".oat"))) {
                continue;
            }
            ++total;
            const auto p = pathOf(l);
            if (p.empty()) continue;

            const bool normal =
                startsWith(p, RSs("/data/app/")) ||
                startsWith(p, RSs("/apex/")) ||
                startsWith(p, RSs("/system/")) ||
                startsWith(p, RSs("/system_ext/")) ||
                startsWith(p, RSs("/product/")) ||
                startsWith(p, RSs("/vendor/")) ||
                startsWith(p, RSs("/odm/")) ||
                startsWith(p, RSs("/data/dalvik-cache/")) ||
                startsWith(p, RSs("/memfd:"));

            if (!normal && odd.size() < 5) odd.push_back(p);
        }

        if (total == 0) {
            r.add(makeUnknown(RSs("E.OAT_PATH"), Category::HOOK, Severity::LOW,
                              RSs("无 oat/vdex 映射")));
        } else if (odd.empty()) {
            Evidence e = makePass(RSs("E.OAT_PATH"), Category::HOOK,
                                  RSs("oat/vdex 路径全部规范"));
            e.addVector(RSs("total"), std::to_string(total));
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("E.OAT_PATH"), Category::HOOK,
                                 Severity::HIGH, Confidence::MEDIUM,
                                 RSs("存在非规范路径的 oat/vdex"));
            e.addVector(RSs("total"), std::to_string(total));
            for (const auto& p : odd) e.addVector(RSs("path"), p);
            e.detail = RSs("代码缓存被放在非规范目录，"
                           "常见于手动 dex2oat 或从临时目录加载");
            r.add(std::move(e));
        }
    }
}

}  // namespace rs