// check_cred.cpp — 进程凭据与沙箱完整性（M7）
//
// Android 安全模型的基石是「每个 App 一个 UID + 一组 capability」。
// 这一层直接核对内核暴露给本进程的凭据：
//
//   CapEff        必须是 0 —— App 不应该持有任何 capability
//   NoNewPrivs    必须是 1 —— 禁止 execve 时提权
//   Uid 四元组    必须完全相同 —— real/effective/saved/fs
//   SELinux 域    必须是 untrusted_app —— 被改写说明域切换过
//   setuid        真实 uid 必须等于有效 uid
//
// 这些都是内核直接给出的，用户态 hook 改不了（除非也 hook /proc/self/status）。
#include "rs_checks.h"

#include <string>
#include <unistd.h>
#include <vector>

#include "rs_str.h"
#include "rs_util.h"

namespace rs {

namespace {

std::string statusField(const std::vector<std::string>& lines,
                        const std::string& key) {
    const auto prefix = key + RSs(":");
    for (const auto& l : lines) {
        if (startsWith(l, prefix)) return trim(l.substr(prefix.size()));
    }
    return {};
}

bool allZeroHex(const std::string& s) {
    if (s.empty()) return false;
    for (const char c : s) {
        if (c != '0') return false;
    }
    return true;
}

}  // namespace

void checkCred(ScanReport& r) {
    const auto status = readFile(RSs("/proc/self/status"), 64 * 1024);
    if (status.empty()) {
        r.add(makeUnknown(RSs("I.CAP_EFF"), Category::INTEGRITY, Severity::LOW,
                          RSs("/proc/self/status 不可读")));
        return;
    }
    const auto lines = split(status, '\n');

    // ---------------------------------------------------------- I.CAP_EFF
    // App 进程 CapEff 必须是 0000000000000000。
    // 任何非零位都意味着持有内核 capability（CAP_SYS_ADMIN 等），
    // 这在正常沙箱里不可能出现。
    {
        const auto capEff = statusField(lines, RSs("CapEff"));
        if (capEff.empty()) {
            r.add(makeUnknown(RSs("I.CAP_EFF"), Category::INTEGRITY, Severity::LOW,
                              RSs("CapEff 字段缺失")));
        } else if (allZeroHex(capEff)) {
            Evidence e = makePass(RSs("I.CAP_EFF"), Category::INTEGRITY,
                                  RSs("CapEff 为全 0（正常沙箱）"));
            e.addVector(RSs("CapEff"), capEff);
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("I.CAP_EFF"), Category::INTEGRITY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("App 进程持有 capability"));
            e.addVector(RSs("CapEff"), capEff);

            const auto capPrm = statusField(lines, RSs("CapPrm"));
            const auto capBnd = statusField(lines, RSs("CapBnd"));
            if (!capPrm.empty()) e.addVector(RSs("CapPrm"), capPrm);
            if (!capBnd.empty()) e.addVector(RSs("CapBnd"), capBnd);

            e.detail = RSs("正常 App 的 CapEff 恒为 0；非 0 说明进程被提权");
            r.add(std::move(e));
        }
    }

    // ------------------------------------------------------ I.NO_NEW_PRIVS
    // 实测：Android 的 zygote 在 fork App 时**不**设置 no_new_privs，
    // 普通 App 进程该值为 0 属正常（root shell 自己也是 0）。
    // 只有在需要执行 setuid 程序时才会按需开启。
    // 所以本项只记录状态，不做 HIT 判定。
    {
        const auto v = statusField(lines, RSs("NoNewPrivs"));
        if (v.empty()) {
            r.add(makeUnknown(RSs("I.NO_NEW_PRIVS"), Category::INTEGRITY,
                              Severity::LOW, RSs("NoNewPrivs 字段缺失")));
        } else {
            Evidence e = makePass(RSs("I.NO_NEW_PRIVS"), Category::INTEGRITY,
                                  RSs("NoNewPrivs 状态已记录"));
            e.addVector(RSs("NoNewPrivs"), v);
            if (v == RSs("1")) {
                e.detail = RSs("已启用 no_new_privs（比原厂更严）");
            } else {
                e.detail = RSs("未启用 —— 这是 Android 的默认行为，不是异常");
            }
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- I.UID_MAP
    // /proc/self/status 的 Uid 行有 4 个值：real / effective / saved / fs
    // 四个必须完全相同，否则意味着经历了 setuid。
    {
        const auto uid = statusField(lines, RSs("Uid"));
        if (uid.empty()) {
            r.add(makeUnknown(RSs("I.UID_MAP"), Category::INTEGRITY, Severity::LOW,
                              RSs("Uid 字段缺失")));
        } else {
            const auto parts = split(uid, '\t');
            std::vector<std::string> f;
            for (const auto& p : parts) {
                const auto t = trim(p);
                if (!t.empty()) f.push_back(t);
            }

            bool same = f.size() >= 4;
            if (same) {
                for (size_t i = 1; i < 4; ++i) {
                    if (f[i] != f[0]) same = false;
                }
            }

            if (f.size() < 4) {
                Evidence e = makeUnknown(RSs("I.UID_MAP"), Category::INTEGRITY,
                                         Severity::LOW,
                                         RSs("Uid 字段格式异常"));
                e.addVector(RSs("raw"), uid);
                r.add(std::move(e));
            } else if (same) {
                Evidence e = makePass(RSs("I.UID_MAP"), Category::INTEGRITY,
                                      RSs("四个 UID 完全一致"));
                e.addVector(RSs("uid"), f[0]);
                r.add(std::move(e));
            } else {
                Evidence e = makeHit(RSs("I.UID_MAP"), Category::INTEGRITY,
                                     Severity::CRITICAL, Confidence::HIGH,
                                     RSs("UID 四元组不一致"));
                e.addVector(RSs("raw"), uid);
                e.detail = RSs("real / effective / saved 不同 → 发生过 setuid");
                r.add(std::move(e));
            }
        }
    }

    // ------------------------------------------------------- I.SE_CONTEXT
    {
        const auto ctx = trim(readFile(RSs("/proc/self/attr/current"), 4096));
        if (ctx.empty()) {
            r.add(makeUnknown(RSs("I.SE_CONTEXT"), Category::INTEGRITY,
                              Severity::LOW,
                              RSs("/proc/self/attr/current 不可读")));
        } else if (contains(ctx, RSs("untrusted_app"))) {
            Evidence e = makePass(RSs("I.SE_CONTEXT"), Category::INTEGRITY,
                                  RSs("SELinux 域为 untrusted_app"));
            e.addVector(RSs("context"), ctx);
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("I.SE_CONTEXT"), Category::INTEGRITY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("SELinux 域不是 untrusted_app"));
            e.addVector(RSs("context"), ctx);
            e.detail = RSs("普通 App 必须运行在 untrusted_app 域；"
                           "其他域意味着被提权或域切换");
            e.fp_note = RSs("系统 App / 预装 App 使用 platform_app 等域，"
                            "若本 App 是系统签名需人工判断");
            r.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- I.SETUID
    {
        const auto ruid = (long)getuid();
        const auto euid = (long)geteuid();

        if (ruid == euid) {
            Evidence e = makePass(RSs("I.SETUID"), Category::INTEGRITY,
                                  RSs("真实 UID 与有效 UID 一致"));
            e.addVector(RSs("uid"), std::to_string(ruid));
            r.add(std::move(e));
        } else {
            Evidence e = makeHit(RSs("I.SETUID"), Category::INTEGRITY,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("真实 UID 与有效 UID 不一致"));
            e.addVector(RSs("ruid"), std::to_string(ruid));
            e.addVector(RSs("euid"), std::to_string(euid));
            e.detail = RSs("发生 setuid 提权");
            r.add(std::move(e));
        }
    }
}

}  // namespace rs