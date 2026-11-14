// check_init_chain.cpp — 启动链完整性（M3）
//
// init 是 Android 第一个用户态进程（pid 1）。
// 它的 exe、cmdline、SELinux 域都是不可变的：
//   /proc/1/exe       → /system/bin/init
//   /proc/1/cmdline   → init
//   attr/current      → u:r:init:s0
// 任何一项对不上，说明启动链被动过（极少见，但一旦发生就是最高级别证据）。
#include "rs_checks.h"

#include <cerrno>
#include <cstdio>
#include <cstring>

#include "rs_str.h"
#include "rs_syscall.h"
#include "rs_util.h"

namespace rs {

void checkInitChain(ScanReport& r) {
    // ---------------------------------------------------------- /proc/1/exe
    {
        const std::string exe = readLink(RSs("/proc/1/exe"));
        if (exe.empty()) {
            r.add(makeUnknown(RSs("F.INIT_EXE"), Category::KERNEL, Severity::LOW,
                              RSs("/proc/1/exe 不可读（App 视角常见）")));
        } else {
            Evidence e = makePass(RSs("F.INIT_EXE"), Category::KERNEL,
                                  RSs("init 的 exe 已解析"));
            e.addVector(RSs("exe"), exe);
            if (exe != RSs("/system/bin/init") && exe != RSs("/init")) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::CRITICAL;
                e.confidence = Confidence::HIGH;
                e.title = RSs("pid 1 的 exe 不是 init ← 启动链被替换");
            }
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- /proc/1/cmdline
    {
        const std::string cmd = readFile(RSs("/proc/1/cmdline"));
        if (cmd.empty()) {
            r.add(makeUnknown(RSs("F.INIT_CMD"), Category::KERNEL, Severity::LOW,
                              RSs("/proc/1/cmdline 不可读")));
        } else {
            Evidence e = makePass(RSs("F.INIT_CMD"), Category::KERNEL,
                                  RSs("init 的 cmdline 已读取"));
            e.addVector(RSs("cmdline"), trim(cmd));
            if (!contains(cmd, RSs("init"))) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::HIGH;
                e.confidence = Confidence::MEDIUM;
                e.title = RSs("pid 1 的 cmdline 不含 init");
            }
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- init 的 SELinux 域
    {
        const std::string ctx = trim(readFile(RSs("/proc/1/attr/current")));
        if (ctx.empty()) {
            r.add(makeUnknown(RSs("F.INIT_DOMAIN"), Category::KERNEL, Severity::LOW,
                              RSs("/proc/1/attr/current 不可读")));
        } else {
            Evidence e = makePass(RSs("F.INIT_DOMAIN"), Category::KERNEL,
                                  RSs("init 的 SELinux 域已读取"));
            e.addVector(RSs("domain"), ctx);
            if (!contains(ctx, RSs("u:r:init"))) {
                e.verdict = Verdict::HIT;
                e.severity = Severity::CRITICAL;
                e.confidence = Confidence::HIGH;
                e.title = RSs("pid 1 不在 u:r:init 域 ← 策略被改");
            }
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- /system/bin/init 存在性
    {
        // /system/bin/init 的 SELinux 标签是 init_exec。
        // 实测：untrusted_app 对它 statx(getattr) 会失败，但 faccessat 仍可判断。
        // 因此以 faccessat 的三态结果为主判据，避免把「被拒」误判成「不存在」。
        const int st = accessState(RSs("/system/bin/init"), 0);
        if (st == 0) {
            Evidence e = makePass(RSs("F.INIT_BIN"), Category::KERNEL,
                                  RSs("/system/bin/init 可访问"));
            e.addVector(RSs("state"), RSs("OK"));
            r.add(std::move(e));
        } else if (st == 1) {
            Evidence e = makeUnknown(RSs("F.INIT_BIN"), Category::KERNEL,
                                     Severity::LOW,
                                     RSs("/system/bin/init 被拒绝访问（init_exec 标签）"));
            e.addVector(RSs("state"), RSs("EACCES"));
            e.fp_note = RSs("untrusted_app 通常没有 init_exec 的 getattr 权限，"
                            "属正常现象，不能据此判缺失");
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("F.INIT_BIN"), Category::KERNEL,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("/system/bin/init 不存在（ENOENT）"));
            e.detail = RSs("原厂设备该路径必然存在；缺失说明系统分区被替换");
            r.add(std::move(e));
        }
    }
}

}  // namespace rs