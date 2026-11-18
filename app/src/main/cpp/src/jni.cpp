// jni.cpp — JNI 入口（带实时进度回调）
//
// 只有一个方法：nativeScan(callback) → JSON 字符串
//   callback.onModule(name)  每个检测模块开始时调用
//   callback.onItem(...)     每产生一条证据时调用（含 PASS）
// 这样 UI 可以实时显示「正在检测什么」「已经检到第几项」「刚发现了什么」。
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <algorithm>
#include <chrono>
#include <functional>
#include <mutex>
#include <random>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>

#include "rs_checks.h"
#include "rs_str.h"
#include "rs_util.h"

namespace rs {
bool isAddressInModule(void* addr, const char* moduleHint);
bool isAddressInModuleAnyPerm(void* addr, const char* moduleHint);
}

// ---------------------------------------------------------------- 回调状态
//
// 之前是 4 个裸全局变量：
//   static jobject g_cb = nullptr; ...
// 一旦两个线程同时进 nativeScan（或一个线程正在回调、另一个线程
// 走到结尾执行 DeleteGlobalRef），就会发生：
//   · 回调对象被释放后仍被使用 → use-after-free → SIGSEGV
//   · g_midItem 被清零后 CallVoidMethod 传 nullptr → 崩溃
//   · 两次 scan 的回调互相覆盖 → 事件串到别的会话
//
// 现在：
//   · 用 mutex 串行化整个 nativeScan（native 扫描本来就是重活，
//     并发跑也没有收益，反而会互相拖慢）
//   · 回调句柄在持有锁的范围内读写
//   · 用一个原子标志快速短路，避免无锁读时踩到已释放对象
static JavaVM* g_vm = nullptr;

namespace {
struct CallbackState {
    jobject   cb        = nullptr;
    jmethodID midItem   = nullptr;
    jmethodID midModule = nullptr;
};

std::mutex      g_cbMutex;                 // 保护 g_cbState
CallbackState   g_cbState;                 // 受 g_cbMutex 保护
std::atomic<bool> g_cbActive{false};       // 快速判空，避免频繁加锁
std::mutex      g_scanMutex;               // 整个 nativeScan 串行化
}  // namespace

namespace {

JNIEnv* envForCurrentThread(bool* attached) {
    *attached = false;
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        *attached = true;
    }
    return env;
}

/**
 * 检查并清除挂起的 Java 异常。
 *
 * 之前完全没检查：一旦 Java 侧 onItem / onModule 抛异常（比如
 * ConcurrentModificationException、NPE），异常会挂在 JNIEnv 上，
 * 后续所有 JNI 调用都变得不可靠 —— 包括 DeleteLocalRef 和
 * 结尾的 NewStringUTF，最终可能返回 null 或直接 abort。
 *
 * 这里统一清理，并把异常摘要打到 logcat，方便定位。
 */
bool clearJavaException(JNIEnv* env, const char* where) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionDescribe();
    env->ExceptionClear();
    __android_log_print(ANDROID_LOG_WARN, "RootSentinel",
                        "JNI callback threw in %s (已清除)", where);
    return true;
}

// 每产生一条证据 → 推给 Java
//
// ---------------------------------------------------------------- 生命周期
// 之前这里存在 use-after-free 窗口：
//   锁内把 g_cbState.cb 拷到局部变量 → 释放锁 → 用局部变量调 CallVoidMethod。
// 如果另一线程在这两步之间走到 nativeScan 结尾执行 DeleteGlobalRef(cb)，
// 对象引用计数归零被回收，局部变量就成了野指针，CallVoidMethod 直接崩。
//
// 现在改成：**在锁内用 NewLocalRef 建立一份本地引用**。
// 本地引用会让对象在本次调用期间保持存活，即使全局引用已被删除。
// 用法：
//   锁内  localCb = NewLocalRef(g_cbState.cb)   ← 引用计数 +1
//   锁外  CallVoidMethod(localCb, ...)          ← 安全
//   收尾  DeleteLocalRef(localCb)               ← 引用计数 -1
void cbItem(const rs::Evidence& e, void*) {
    // 快速短路：没有活动回调时直接返回，不加锁
    if (!g_cbActive.load(std::memory_order_acquire)) return;

    bool at = false;
    JNIEnv* env = envForCurrentThread(&at);
    if (!env) return;

    // 锁内建立本地引用（+1），锁外使用
    jobject   localCb = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_cbMutex);
        if (g_cbState.cb != nullptr && g_cbState.midItem != nullptr) {
            localCb = env->NewLocalRef(g_cbState.cb);
            mid = g_cbState.midItem;
        }
    }
    if (localCb == nullptr || mid == nullptr) {
        if (at) g_vm->DetachCurrentThread();
        return;
    }

    jstring jId    = env->NewStringUTF(e.id.c_str());
    jstring jTitle = env->NewStringUTF(e.title.c_str());
    jstring jSev   = env->NewStringUTF(rs::severityName(e.severity));
    jstring jVer   = env->NewStringUTF(rs::verdictName(e.verdict));
    jstring jCat   = env->NewStringUTF(rs::categoryCode(e.category));

    env->CallVoidMethod(localCb, mid, jId, jTitle, jSev, jVer, jCat);
    // ★ Java 回调抛异常必须立刻清掉，否则后续 JNI 全部失效
    clearJavaException(env, "onItem");

    env->DeleteLocalRef(jId);
    env->DeleteLocalRef(jTitle);
    env->DeleteLocalRef(jSev);
    env->DeleteLocalRef(jVer);
    env->DeleteLocalRef(jCat);
    env->DeleteLocalRef(localCb);   // ★ 释放锁内建立的本地引用

    if (at) g_vm->DetachCurrentThread();
}

// 每个模块开始 → 推给 Java
// 生命周期处理与 cbItem 相同：锁内 NewLocalRef，锁外使用，收尾 DeleteLocalRef
void notifyModule(const char* name) {
    if (!g_cbActive.load(std::memory_order_acquire)) return;

    bool at = false;
    JNIEnv* env = envForCurrentThread(&at);
    if (!env) return;

    jobject   localCb = nullptr;
    jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_cbMutex);
        if (g_cbState.cb != nullptr && g_cbState.midModule != nullptr) {
            localCb = env->NewLocalRef(g_cbState.cb);
            mid = g_cbState.midModule;
        }
    }
    if (localCb == nullptr || mid == nullptr) {
        if (at) g_vm->DetachCurrentThread();
        return;
    }

    jstring s = env->NewStringUTF(name);
    env->CallVoidMethod(localCb, mid, s);
    clearJavaException(env, "onModule");
    env->DeleteLocalRef(s);
    env->DeleteLocalRef(localCb);   // ★

    if (at) g_vm->DetachCurrentThread();
}

// 二.5 校验：JNIEnv 函数表指针是否落在 libart 的映射内
//
// 注意：JNIEnv* 指向 JNIEnvExt，其首字段是 JNINativeInterface* ——
// 这是一个**函数表结构体**，位于 libart.so 的 .data.rel.ro（只读段），
// 不是可执行段。所以必须用 isAddressInModuleAnyPerm，
// 用只查 r-xp 的版本会永远判失败（2.2.1 及之前的 bug）。
std::string verifyJniEnv(JNIEnv* env) {
    if (!env) return "ENV_NULL";
    void* fnTable = *(void**)env;
    if (!fnTable) return "TABLE_NULL";

    // 先按 libart 查（正常情况）
    if (rs::isAddressInModuleAnyPerm(fnTable, "libart.so")) return "OK";

    // 少数 ROM 把 JNI 表放在 libnativehelper 或主可执行文件里
    if (rs::isAddressInModuleAnyPerm(fnTable, "libnativehelper.so")) return "OK";
    if (rs::isAddressInModuleAnyPerm(fnTable, "app_process")) return "OK";

    return "OUTSIDE_LIBART";
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rootsentinel_NativeBridge_nativeScan(JNIEnv* env, jobject /*thiz*/,
                                              jobject callback) {
    using namespace rs;

    // ---------------------------------------------------------- 串行化
    // 两次并发 nativeScan 会互相覆盖回调句柄，且 native 扫描本身
    // 就是 CPU 密集的，并发没有收益。这里直接串行。
    std::unique_lock<std::mutex> scanLock(g_scanMutex, std::defer_lock);
    if (!scanLock.try_lock()) {
        // 已有扫描在跑：返回一个明确的错误 JSON，而不是让两个扫描互相破坏
        return env->NewStringUTF(
            "{\"error\":\"native 扫描正在进行中，已拒绝并发调用\"}");
    }

    // ---------------------------------------------------------- 注册回调
    {
        std::lock_guard<std::mutex> lk(g_cbMutex);
        if (g_cbState.cb) {
            env->DeleteGlobalRef(g_cbState.cb);
            g_cbState.cb = nullptr;
        }
        g_cbState.midItem = nullptr;
        g_cbState.midModule = nullptr;
        g_cbActive.store(false, std::memory_order_release);

        if (callback) {
            g_cbState.cb = env->NewGlobalRef(callback);
            jclass cls = env->GetObjectClass(callback);
            g_cbState.midItem = env->GetMethodID(
                cls, "onItem",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                "Ljava/lang/String;Ljava/lang/String;)V");
            g_cbState.midModule = env->GetMethodID(cls, "onModule", "(Ljava/lang/String;)V");
            env->DeleteLocalRef(cls);
            // 方法找不到时会挂异常，清掉并降级为「无回调」而不是崩
            if (clearJavaException(env, "GetMethodID")) {
                g_cbState.midItem = nullptr;
                g_cbState.midModule = nullptr;
            }
            g_cbActive.store(
                g_cbState.cb != nullptr &&
                (g_cbState.midItem != nullptr || g_cbState.midModule != nullptr),
                std::memory_order_release);
        }
    }

    ScanReport report;
    report.profile = buildProfile();
    report.onItem  = cbItem;
    report.user    = nullptr;

    // ---------------------------------------------------------- 逐模块执行
    // v2.22.82（联合审查 N3）：模块执行顺序每轮随机。旧版 Java 阶段
    // 已 shuffled、Native 阶段却写死顺序，固定顺序本身就是可被
    // 针对性研究的特征。已核实各 check 模块之间没有结果依赖
    // （只有 analyze 读全量 items），故除 analyze 固定收尾外其余
    // 全部打乱；种子写入 report.shuffleSeed 并由 analyze 附到
    // Z.SCHEME 的 vector 里，需要时可按种子复现顺序。定位说明：
    // 随机化只抬高针对性绕过的成本，不代替数据源独立性。
    std::vector<std::pair<const char*, std::function<void(ScanReport&)>>> steps;
    steps.reserve(41);
    auto add = [&steps](const char* label, std::function<void(ScanReport&)> fn) {
        steps.emplace_back(label, std::move(fn));
    };
    add("A · 经典痕迹", [](ScanReport& r) { checkArtifacts(r); });
    add("A · 运行环境真实性", [](ScanReport& r) { checkEmulator(r); });
    add("A · root 方案指纹", [](ScanReport& r) { checkMagiskFamily(r); });
    add("A · 硬件指纹", [](ScanReport& r) { checkHwExt(r); });
    add("B · 属性与启动状态", [](ScanReport& r) { checkProps(r); });
    add("B · 安全补丁级别", [](ScanReport& r) { checkSecurityPatch(r); });
    add("C · 挂载与命名空间", [](ScanReport& r) { checkMount(r); });
    add("C · 文件系统元数据", [](ScanReport& r) { checkFsMeta(r); });
    add("C · 文件系统物理层", [](ScanReport& r) { checkFsDeep(r); });
    add("C · 存储视图与硬件", [](ScanReport& r) { checkSandbox(r); });
    add("C · 文件时间与物理块", [](ScanReport& r) { checkFsTime(r); });
    add("D · 内存与注入", [](ScanReport& r) { checkMemory(r); });
    add("D · 线程与匿名 fd", [](ScanReport& r) { checkThreads(r); });
    add("D · 匿名可执行映射", [](ScanReport& r) { checkAnonExec(r); });
    add("D · proc 深度", [](ScanReport& r) { checkProcExt(r); });
    add("D · DEX / OAT 加载", [](ScanReport& r) { checkDex(r); });
    add("E · Hook 框架", [](ScanReport& r) { checkHooks(r); });
    add("E · ART 与符号归属", [](ScanReport& r) { checkArtDeep(r); });
    add("E · ArtMethod 归属", [env](ScanReport& r) { checkArtMethod(env, r); });
    add("E · 内联 hook 形态", [](ScanReport& r) { checkPltHooks(r); });
    add("E · 反调试与进程来源", [](ScanReport& r) { checkAntiDebug(r); });
    add("F · SELinux", [](ScanReport& r) { checkSelinux(r); });
    add("F · SELinux 行为交叉验证", [](ScanReport& r) { checkSelinuxXcheck(r); });
    add("F · 内核标识", [](ScanReport& r) { checkKernel(r); });
    add("F · 启动链", [](ScanReport& r) { checkInitChain(r); });
    add("F · 内核设备与模块", [](ScanReport& r) { checkKernelDeep(r); });
    add("F · 内核 taint", [](ScanReport& r) { checkKernelTaint(r); });
    add("F · 内核维护状态", [](ScanReport& r) { checkKernelEol(r); });
    add("F · 内核安全子系统", [](ScanReport& r) { checkKernelExt(r); });
    add("G · eBPF 与硬件证明", [](ScanReport& r) { checkBpf(r); });
    add("I · 库完整性", [](ScanReport& r) { checkIntegrity(r); });
    add("I · 代码段比对", [](ScanReport& r) { checkTextHash(r); });
    add("I · 进程凭据", [](ScanReport& r) { checkCred(r); });
    add("I · 证书与 seccomp", [](ScanReport& r) { checkSelfExt(r); });
    add("I · 环境与 auxv", [](ScanReport& r) { checkEnv(r); });
    add("X · 网络与端口", [](ScanReport& r) { checkNet(r); });
    add("X · 交叉验证", [](ScanReport& r) { checkCrossProcess(r); });
    add("X · syscall 矛盾", [](ScanReport& r) { checkSyscallAnomaly(r); });
    add("X · proc 自洽性", [](ScanReport& r) { checkProcAnomaly(r); });
    add("X · 刷机痕迹与能力矩阵", [](ScanReport& r) { checkCompat(r); });
    add("", [](ScanReport& r) { checkTiming(r); });  // 原本就无进度标签

    const unsigned seed = (unsigned)(
        std::chrono::steady_clock::now().time_since_epoch().count()
        ^ (unsigned)getpid() * 2654435761u);
    report.shuffleSeed = seed;
    std::mt19937 rng(seed);
    std::shuffle(steps.begin(), steps.end(), rng);
    for (auto& step : steps) {
        if (step.first != nullptr && step.first[0] != '\0') notifyModule(step.first);
        step.second(report);
    }

    notifyModule("Z · 汇总与方案画像");
    analyze(report);

    // ---------------------------------------------------------- JNIEnv 校验
    {
        const std::string r = verifyJniEnv(env);
        if (r == "OK") {
            report.add(makePass(RSs("E.JNIENV_TABLE"), Category::HOOK,
                                RSs("JNIEnv 函数表位于 libart 内")));
        } else {
            Evidence e = makeHit(RSs("E.JNIENV_TABLE"), Category::HOOK,
                                 Severity::CRITICAL, Confidence::HIGH,
                                 RSs("JNIEnv 函数表不在 libart 内 ← JNI 被劫持"));
            e.addVector(RSs("state"), r);
            report.add(std::move(e));
        }
    }

    // ---------------------------------------------------------- 清理回调
    report.onItem = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_cbMutex);
        g_cbActive.store(false, std::memory_order_release);
        if (g_cbState.cb) {
            env->DeleteGlobalRef(g_cbState.cb);
            g_cbState.cb = nullptr;
        }
        g_cbState.midItem = nullptr;
        g_cbState.midModule = nullptr;
    }

    const std::string json = serializeReport(report);
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rootsentinel_NativeBridge_sigCheck(JNIEnv* env, jobject /*thiz*/, jstring hexSha256) {
    // ---------------------------------------------------------------- 签名基线比对
    // Java 侧算出签名证书的 SHA-256（hex），这里与编译进 .so 的期望值比对。
    // 基线用 RSs() 做编译期混淆，字符串表里查不到明文；
    // 想绕过就必须同时改 .so，而不是改 dex 里的一行常量。
    //
    // 返回值：1 = 匹配　0 = 不匹配　-1 = 无法判断
    if (hexSha256 == nullptr) return -1;

    const char* s = env->GetStringUTFChars(hexSha256, nullptr);
    if (s == nullptr) return -1;
    std::string got(s);
    env->ReleaseStringUTFChars(hexSha256, s);

    if (got.size() != 64) return -1;

    // ---------------------------------------------------------------- 签名基线
    //
    // 基线**不再写死**，而是由 Gradle 在构建期读取实际签名证书后
    // 通过 -DRS_SIGNER_SHA256=... 注入。
    //
    // 为什么必须这样做：
    //   v2.22.38 及之前这里硬编码了 rs.jks 的指纹，但
    //   build.gradle.kts 允许任意证书构建、缺凭据时还会回退 debug 签名。
    //   结果就是「用别的证书正常构建出来的 APK」也被判成重打包 ——
    //   这是构建配置与检测逻辑的直接冲突，属于误报。
    //
    // 现在：
    //   · Release 注入 release 证书指纹
    //   · Debug   注入 debug 证书指纹
    //   · 拿不到证书（未配置签名）时不定义该宏 → 返回 -1（未知）
    //     调用方据此报 UNKNOWN，而不是 HIT
#ifndef RS_SIGNER_SHA256
    // 没有注入基线：无法判定，不能报「不符」
    (void)got;
    return -1;
#else
    static const std::string expected = RSs(RS_SIGNER_SHA256);
    if (expected.size() != 64) return -1;   // 注入值异常，按未知处理
    // v2.22.41：大小写归一化后再比较。
    // 历史教训：Java 侧传来的 got 是小写 hex（IntegrityChecker 用 %02x
    // 格式化），而 Gradle 注入的基线是大写 hex（keytool 输出转大写），
    // 直接 == 永远不相等 —— 正常构建也会被误判为重打包（返回 0）。
    auto hexEq = [](const std::string& a, const std::string& b) {
        if (a.size() != b.size()) return false;
        for (size_t i = 0; i < a.size(); ++i) {
            char x = a[i], y = b[i];
            if (x >= 'A' && x <= 'F') x = char(x - 'A' + 'a');
            if (y >= 'A' && y <= 'F') y = char(y - 'A' + 'a');
            if (x != y) return false;
        }
        return true;
    };
    return hexEq(got, expected) ? 1 : 0;
#endif
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}