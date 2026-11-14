// check_art_deep.cpp — ART / 符号层深度检测（M3）
//
// 核心思路：
//   dlsym(RTLD_DEFAULT, "open") 返回的必须是 libc 里的真实地址。
//   如果 PLT/GOT 被改写、或者 libc 被 inline hook 成跳板，
//   dlsym 拿到的地址就会落到别的模块里 —— 这是最难隐藏的 hook 证据，
//   因为 hook 作者通常只处理「调用路径」，不会想到有人验证「符号地址归属」。
#include "rs_checks.h"

#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <vector>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {
bool isAddressInModule(void* addr, const char* moduleHint);
bool isAddressInModuleAnyPerm(void* addr, const char* moduleHint);

namespace {

// 返回 true = 地址在预期模块内（正常）；false = 被重定向
bool symbolInModule(const char* sym, const char* module, std::string* observed) {
    void* p = dlsym(RTLD_DEFAULT, sym);
    if (!p) {
        *observed = "dlsym=null";
        return true;   // 无法判断，按正常处理，避免误报
    }
    if (isAddressInModule(p, module)) return true;

    char buf[96];
    std::snprintf(buf, sizeof(buf), "addr=%p", p);
    *observed = buf;
    return false;
}

}  // namespace

void checkArtDeep(ScanReport& r) {
    // ---------------------------------------------------------- 1. libc 符号归属
    static const std::vector<std::pair<std::string, std::string>> probes = {
        {RSs("open"),      RSs("libc.so")},
        {RSs("openat"),    RSs("libc.so")},
        {RSs("read"),      RSs("libc.so")},
        {RSs("write"),     RSs("libc.so")},
        {RSs("fopen"),     RSs("libc.so")},
        {RSs("fread"),     RSs("libc.so")},
        {RSs("strcmp"),    RSs("libc.so")},
        {RSs("malloc"),    RSs("libc.so")},
        {RSs("getpid"),    RSs("libc.so")},
        {RSs("dlsym"),     RSs("libdl.so")},
    };

    int hooked = 0;
    size_t checked = 0;
    for (const auto& pr : probes) {
        std::string observed;
        if (symbolInModule(pr.first.c_str(), pr.second.c_str(), &observed)) {
            ++checked;
            continue;
        }
        ++hooked;
        Evidence e = makeHit(RSs("E.SYMBOL_HOOK"), Category::HOOK,
                             Severity::CRITICAL, Confidence::HIGH,
                             RSs("libc 符号被重定向: ") + pr.first);
        e.addVector(RSs("symbol"), pr.first);
        e.addVector(RSs("expected"), pr.second);
        e.addVector(RSs("observed"), observed);
        e.detail = RSs("dlsym 返回的地址不在预期模块内 → PLT/GOT 或 inline hook");
        e.fp_note = RSs("极少数 ROM 会把 libc 符号转发到 libc++.so；"
                        "若同族符号批量命中，则更像转发而非 hook");
        r.add(std::move(e));
    }
    if (hooked == 0) {
        Evidence e = makePass(RSs("E.SYMBOL_HOOK"), Category::HOOK,
                              RSs("10 个 libc 符号归属正常"));
        e.addVector(RSs("checked"), std::to_string(checked));
        r.add(std::move(e));
    }

    // ---------------------------------------------------------- 2. libart 符号归属
    // art::Runtime::instance_ 这类符号被 hook 是 Zygisk 的常见落点
    {
        static const std::vector<std::string> artSyms = {
            RSs("_ZN3art7Runtime9instance_E"),
        };
        for (const auto& s : artSyms) {
            void* p = dlsym(RTLD_DEFAULT, s.c_str());
            if (!p) continue;   // 符号被 strip，正常
            Evidence e = makePass(RSs("E.ART_SYMBOL"), Category::HOOK,
                                  RSs("libart 符号可解析: ") + s);
            e.addVector(RSs("addr"), hex64((uint64_t)(uintptr_t)p));
            // v2.22.45（审查 A P0-1）：instance_ 是数据符号，位于
            // libart 的 .data/.bss，不在可执行段；exec-only 的
            // isAddressInModule 在正常设备上也必然落空 → 误报
            // CRITICAL。改用不限权限的版本。
            if (!isAddressInModuleAnyPerm(p, "libart.so")) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::CRITICAL;
                e.confidence = Confidence::HIGH;
                e.title = RSs("libart 符号被重定向: ") + s;
            }
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- 3. JIT code cache
    // 异常巨大的可执行匿名段，可能是注入的代码缓存
    {
        auto lines = readLines(RSs("/proc/self/maps"), 20000);
        uint64_t totalRwx = 0;
        int rwxSegs = 0;
        for (const auto& line : lines) {
            unsigned long long start = 0, end = 0;
            char perms[8] = {0};
            if (std::sscanf(line.c_str(), "%llx-%llx %7s", &start, &end, perms) < 3) {
                continue;
            }
            // 同时可写可执行 —— 现代 Android 严格 W^X，正常不会有
            if (perms[1] == 'w' && perms[2] == 'x') {
                ++rwxSegs;
                totalRwx += (end - start);
            }
        }
        if (rwxSegs > 0) {
            Evidence e = makeHit(RSs("E.RWX_SEGMENT"), Category::MEMORY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("存在同时可写可执行的内存段（W^X 违规）"));
            e.addVector(RSs("segments"), std::to_string(rwxSegs));
            e.addVector(RSs("total_bytes"), std::to_string(totalRwx));
            e.detail = RSs("注入器常用 mprotect(PROT_WRITE|PROT_EXEC) 写入代码后忘记收回权限");
            e.fp_note = RSs("旧内核/部分 JIT 实现可能保留 RWX 段；"
                            "结合 smaps 的 [anon:jit] 标记判断");
            r.add(std::move(e));
        } else {
            r.add(makePass(RSs("E.RWX_SEGMENT"), Category::MEMORY,
                           RSs("无 W^X 违规段")));
        }
    }

    // ---------------------------------------------------------- 4. dladdr 反查
    // dladdr 拿到的符号名与模块路径应当自洽
    {
        void* p = dlsym(RTLD_DEFAULT, "open");
        if (p) {
            Dl_info info{};
            if (dladdr(p, &info) != 0 && info.dli_fname) {
                Evidence e = makePass(RSs("E.DLADDR"), Category::HOOK,
                                      RSs("dladdr 反查成功"));
                e.addVector(RSs("fname"), info.dli_fname);
                if (info.dli_sname) e.addVector(RSs("sname"), info.dli_sname);
                if (!contains(info.dli_fname, RSs("libc.so"))) {
                    e.verdict = Verdict::HIT;
                    e.severity = Severity::HIGH;
                    e.confidence = Confidence::HIGH;
                    e.title = RSs("open 的 dladdr 归属不是 libc.so");
                }
                r.add(std::move(e));
            }
        }
    }
}

}  // namespace rs