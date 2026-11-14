// check_env.cpp — 环境变量 / auxv / personality（M3）
//
// 这三项在常规检测里很少被看，但注入框架经常在这留下痕迹：
//   · LD_PRELOAD / LD_LIBRARY_PATH  —— Android 不常用，出现即可疑
//   · auxv 的 AT_SECURE             —— 反映 setuid / 安全执行状态
//   · personality 的 READ_IMPLIES_EXEC —— 会让所有可读页变成可执行
#include "rs_checks.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

namespace {
// auxv 类型
constexpr unsigned long AT_NULL   = 0;
constexpr unsigned long AT_SECURE = 23;
constexpr unsigned long AT_EXECFN = 31;

// personality 标志
constexpr unsigned long ADDR_NO_RANDOMIZE = 0x0040000;
constexpr unsigned long READ_IMPLIES_EXEC = 0x0400000;
}  // namespace

void checkEnv(ScanReport& r) {
    // ---------------------------------------------------------- 环境变量
    {
        const std::string raw = readFile(RSs("/proc/self/environ"), 64 * 1024);
        if (raw.empty()) {
            // Android App 由 zygote fork 而来，environ 本来就是空的。
            // 读不到不代表异常，因此记 PASS 并注明。
            Evidence e = makePass(RSs("I.ENVIRON"), Category::INTEGRITY,
                                  RSs("环境变量为空（Android App 正常行为）"));
            e.addVector(RSs("size"), "0");
            e.fp_note = RSs("zygote 不向 App 传递环境变量；"
                            "若进程由 adb shell 启动则会非空");
            r.add(std::move(e));
        } else {
            // 按 \0 切分
            std::vector<std::string> vars;
            std::string cur;
            for (char c : raw) {
                if (c == '\0') {
                    if (!cur.empty()) vars.push_back(cur);
                    cur.clear();
                } else {
                    cur.push_back(c);
                }
            }
            if (!cur.empty()) vars.push_back(cur);

            static const std::vector<std::string> badKeys = {
                RSs("LD_PRELOAD"), RSs("LD_LIBRARY_PATH"),
                RSs("LD_AUDIT"),   RSs("MAGISK"), RSs("KSU"),
                RSs("FRIDA"),      RSs("XPOSED"),
            };

            int bad = 0;
            for (const auto& v : vars) {
                for (const auto& k : badKeys) {
                    if (startsWith(v, k)) {
                        ++bad;
                        Evidence e = makeHit(RSs("I.ENV_INJECT"), Category::INTEGRITY,
                                             Severity::HIGH, Confidence::HIGH,
                                             RSs("环境变量可疑: ") + v);
                        e.addVector(RSs("var"), v);
                        e.fp_note = RSs("开发机上通过 adb shell 启动会带上 LD_LIBRARY_PATH");
                        r.add(std::move(e));
                        break;
                    }
                }
            }
            if (bad == 0) {
                Evidence e = makePass(RSs("I.ENV_INJECT"), Category::INTEGRITY,
                                      RSs("环境变量无可疑项"));
                e.addVector(RSs("count"), std::to_string(vars.size()));
                r.add(std::move(e));
            }
        }
    }

    // ---------------------------------------------------------- auxv
    {
        const std::string aux = readFile(RSs("/proc/self/auxv"), 4096);
        if (aux.empty()) {
            r.add(makeUnknown(RSs("I.AUXV"), Category::INTEGRITY, Severity::LOW,
                              RSs("/proc/self/auxv 不可读")));
        } else {
            unsigned long secure = 0;
            std::string execfn;
            const size_t n = aux.size() / (sizeof(unsigned long) * 2);
            for (size_t i = 0; i < n; ++i) {
                // v2.22.45（审查 A P2-16）：逐项 memcpy，避免把 string
                // 缓冲 reinterpret_cast 成 unsigned long* 的别名/对齐
                // 隐患（实机缓冲是堆对齐的，属健壮性收口）。
                unsigned long pair[2];
                std::memcpy(pair, aux.data() + i * sizeof(pair),
                            sizeof(pair));
                unsigned long type = pair[0];
                unsigned long val  = pair[1];
                if (type == AT_NULL) break;
                if (type == AT_SECURE) secure = val;
                if (type == AT_EXECFN && val != 0) {
                    const char* s = reinterpret_cast<const char*>(val);
                    // 只取前 64 字节，避免越界
                    execfn.assign(s, strnlen(s, 64));
                }
            }
            Evidence e = makePass(RSs("I.AUXV"), Category::INTEGRITY,
                                  RSs("auxv 已解析"));
            e.addVector(RSs("AT_SECURE"), std::to_string(secure));
            e.addVector(RSs("entries"), std::to_string(n));
            if (secure != 0) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::LOW;
                e.confidence = Confidence::LOW;
                e.heuristic = true;
                e.title = RSs("AT_SECURE != 0（进程处于安全执行模式）");
                e.fp_note = RSs("setuid 程序正常会有该标志；普通 App 通常为 0");
            }
            r.add(std::move(e));

            if (!execfn.empty()) {
                Evidence x = makePass(RSs("I.EXECFN"), Category::INTEGRITY,
                                      RSs("AT_EXECFN 已读取"));
                x.addVector(RSs("execfn"), execfn);
                if (!contains(execfn, RSs("app_process"))) {
                    x.verdict = Verdict::HIT;
                    x.severity = Severity::MEDIUM;
                    x.confidence = Confidence::MEDIUM;
                    x.title = RSs("AT_EXECFN 不是 app_process");
                }
                r.add(std::move(x));
            }
        }
    }

    // ---------------------------------------------------------- personality
    // personality(0xffffffff) 是「查询」语义，返回当前值
    {
        long per = sys::raw(sys::NR_personality, 0xffffffffL, 0, 0, 0, 0, 0);
        // x86_64 是 135；其它架构查表失败时返回负值，按 UNKNOWN 处理
        if (per >= 0) {
            Evidence e = makePass(RSs("I.PERSONALITY"), Category::INTEGRITY,
                                  RSs("personality 已读取"));
            e.addVector(RSs("value"), hex64((uint64_t)per));
            if ((uint64_t)per & READ_IMPLIES_EXEC) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::HIGH;
                e.confidence = Confidence::MEDIUM;
                e.title = RSs("personality 含 READ_IMPLIES_EXEC（所有可读页可执行）");
                e.detail = RSs("注入器常设置该标志以便执行任意映射页");
                e.fp_note = RSs("旧 32 位二进制或特定兼容模式会正常带上该位");
            }
            if ((uint64_t)per & ADDR_NO_RANDOMIZE) {
                e.addVector(RSs("ADDR_NO_RANDOMIZE"), RSs("set"));
            }
            r.add(std::move(e));
        } else {
            r.add(makeUnknown(RSs("I.PERSONALITY"), Category::INTEGRITY,
                              Severity::LOW, RSs("personality() 不可用")));
        }
    }
}

}  // namespace rs