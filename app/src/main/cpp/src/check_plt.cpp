// check_plt.cpp — libc 函数入口的内联 hook 识别（M9）
//
// I.TEXT_HASH 比对的是「代码段前 32 字节与磁盘是否一致」。
// 但如果 hook 只改了函数内部的某条指令（不在前 32 字节内），它查不出来。
//
// 这一层换思路：直接看几个关键 libc 函数**入口**的机器码形态。
// 正常实现入口不会是「无条件跳转」——那是 PLT stub / trampoline 的特征。
//
// 注意：bionic 里有少数函数本身就是 alias（`open` 直接跳到 `openat`），
// 所以这一项只给 MEDIUM 置信度，必须结合 E.SYMBOL_HOOK / I.TEXT_HASH 判断。
#include "rs_checks.h"

#include <cstdint>
#include <dlfcn.h>
#include <string>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

bool looksLikeJump(const void* addr) {
    if (addr == nullptr) return false;

#if defined(__aarch64__)
    const uint32_t w = *reinterpret_cast<const uint32_t*>(addr);
    // b / bl（opcode 位 [31:26] = 000101 / 100101）
    if ((w & 0x7C000000u) == 0x14000000u) return true;
    // ldr x16, #8 ; br x16（典型 PLT stub 前 4 字节）
    if (w == 0x58000050u) return true;
    return false;
#elif defined(__arm__)
    const uint32_t w = *reinterpret_cast<const uint32_t*>(addr);
    // ARM 模式 b / bl
    if ((w & 0x0F000000u) == 0x0A000000u) return true;
    if ((w & 0x0F000000u) == 0x0B000000u) return true;
    // Thumb b.w（前 16 位 0xF000, 次 16 位 0xB800）
    const uint16_t h = *reinterpret_cast<const uint16_t*>(addr);
    if ((h & 0xF800u) == 0xF000u) return true;
    return false;
#elif defined(__x86_64__)
    const uint8_t* p = reinterpret_cast<const uint8_t*>(addr);
    if (p[0] == 0xE9 || p[0] == 0xEB) return true;          // jmp rel8/rel32
    if (p[0] == 0xFF && p[1] == 0x25) return true;          // jmp [rip+disp]
    return false;
#else
    (void)addr;
    return false;
#endif
}

}  // namespace

void checkPltHooks(ScanReport& r) {
    static const char* const targets[] = {
        "open", "openat", "read", "write", "close",
        "mmap", "munmap", "mprotect", "ptrace",
        "dlsym", "dlopen", "stat", "fstatat",
    };

    std::vector<std::string> hooked;
    std::vector<std::string> clean;

    for (const char* name : targets) {
        void* p = dlsym(RTLD_DEFAULT, name);
        if (p == nullptr) continue;
        if (looksLikeJump(p)) {
            hooked.push_back(name);
        } else {
            clean.push_back(name);
        }
    }

    if (hooked.empty() && clean.empty()) {
        r.add(makeUnknown(RSs("E.INLINE_HOOK"), Category::HOOK, Severity::LOW,
                          RSs("dlsym 全部失败")));
        return;
    }

    if (!hooked.empty()) {
        Evidence e = makeHit(RSs("E.INLINE_HOOK"), Category::HOOK,
                             Severity::HIGH, Confidence::MEDIUM,
                             RSs("libc 函数入口被改写为跳转"));
        for (const auto& s : hooked) e.addVector(RSs("jump"), s);
        for (const auto& s : clean) e.addVector(RSs("clean"), s);
        e.detail = RSs("函数入口以无条件跳转开头 → 可能是 PLT 劫持或 trampoline；"
                       "正常 bionic 实现以 stp/ldr 开头");
        e.fp_note = RSs("bionic 里 open→openat、stat→fstatat 这类 alias "
                        "本身就是一条跳转指令，会误判；"
                        "必须结合 E.SYMBOL_HOOK 与 I.TEXT_HASH 一起看");
        r.add(std::move(e));
    } else {
        Evidence e = makePass(RSs("E.INLINE_HOOK"), Category::HOOK,
                              RSs("libc 函数入口无跳转桩"));
        e.addVector(RSs("checked"), std::to_string(clean.size()));
        for (const auto& s : clean) e.addVector(RSs("clean"), s);
        r.add(std::move(e));
    }
}

}  // namespace rs