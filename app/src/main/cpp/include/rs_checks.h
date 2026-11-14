// rs_checks.h — 所有检测模块入口声明
#ifndef RS_CHECKS_H
#define RS_CHECKS_H

#include <jni.h>

#include <string>

#include "rs_types.h"

namespace rs {

// 环境画像：先跑一次，供后续降权使用
DeviceProfile buildProfile();

// A 经典痕迹
void checkArtifacts(ScanReport& r);

// B 属性与启动状态（含 uname 多源、vbmeta digest）
void checkProps(ScanReport& r);

// C 挂载与命名空间（含三视角、元数据互验、statfs）
void checkMount(ScanReport& r);

// F 内核标识多源
void checkKernel(ScanReport& r);

// F SELinux
void checkSelinux(ScanReport& r);

// D 内存与注入（含 dl_iterate_phdr/maps、mincore、安全扫描）
void checkMemory(ScanReport& r);

// E Hook 框架 + JNIEnv 校验
void checkHooks(ScanReport& r);

// 库完整性
void checkIntegrity(ScanReport& r);

// 时序侧信道（仅软信号）
void checkTiming(ScanReport& r);

// ---- M2 新增 ----
// eBPF 层探测
void checkBpf(ScanReport& r);
// 反调试与进程来源
void checkAntiDebug(ScanReport& r);
// 父子进程交叉验证
void checkCrossProcess(ScanReport& r);
// 文件系统元数据（overlay upperdir / xattr / inode）
void checkFsMeta(ScanReport& r);

// ---- M3 新增 ----
// ART / libc 符号归属（dlsym 反查 hook）
void checkArtDeep(ScanReport& r);
// DEX / OAT / VDEX 加载来源
void checkDex(ScanReport& r);
// 启动链完整性（/proc/1）
void checkInitChain(ScanReport& r);
// 环境变量 / auxv / personality
void checkEnv(ScanReport& r);
// 库代码段与磁盘 ELF 逐字节对比
void checkTextHash(ScanReport& r);

// ---- M5 新增 ----
// 进程 / 线程层（task 枚举、线程名、anon fd、线程 ns）
void checkThreads(ScanReport& r);
// 文件系统物理层（st_dev、inode 交叉、verity 属性、APEX）
void checkFsDeep(ScanReport& r);
// 内核设备 / 模块 / 调试接口
void checkKernelDeep(ScanReport& r);
// 网络 / 端口 / socket
void checkNet(ScanReport& r);
// 运行环境真实性（模拟器、属性交叉、boot_id）
void checkEmulator(ScanReport& r);
// syscall 层矛盾检测
void checkSyscallAnomaly(ScanReport& r);

// ---- M6 新增 ----
// 主流 root 方案专项指纹（Magisk / KernelSU / APatch / Shamiko / SUSFS）
void checkMagiskFamily(ScanReport& r);
// 匿名可执行映射与代码来源
void checkAnonExec(ScanReport& r);
// /proc 自洽性与时间基线
void checkProcAnomaly(ScanReport& r);

// ---- M7 新增 ----
// 进程凭据与沙箱完整性（CapEff / NoNewPrivs / UID / SELinux 域 / setuid）
void checkCred(ScanReport& r);
// 内核 taint 与安全 sysctl
void checkKernelTaint(ScanReport& r);
// ArtMethod 归属与 OAT 路径（需要 JNIEnv）
void checkArtMethod(JNIEnv* env, ScanReport& r);
// 存储视图与硬件指纹
void checkSandbox(ScanReport& r);

// ---- M8 新增 ----
// 刷机痕迹、内核崩溃日志、maps 统计、运行时能力矩阵
void checkCompat(ScanReport& r);

// ---- M9 新增 ----
// 文件时间戳 / 物理块 / 挂载传播
void checkFsTime(ScanReport& r);
// 内核安全子系统扩展（LSM / lockdown / BPF / kprobe / SELinux / sysctl）
void checkKernelExt(ScanReport& r);
// /proc 深度扩展（信号 / pagemap / numa_maps / fd / vdso / 库顺序）
void checkProcExt(ScanReport& r);
// 硬件指纹扩展（电池温度 / thermal / GPU / bootreason）
void checkHwExt(ScanReport& r);
// 证书库 / seccomp / 自身 so / jiffies
void checkSelfExt(ScanReport& r);
// libc 函数入口的内联 hook 识别
void checkPltHooks(ScanReport& r);

// ---------------------------------------------------------------- v2.22.37 新增
//
// 对应 2026-11-10 周报的三条检测缺口。
// 三个都归「系统完整性」维度，不计入 Root 判定。

// SELinux 域声明 vs 实际访问行为 的交叉验证。
// KernelSU v3.3.1 的 SELinux Hide 会改写 attr/current 与 selinuxfs 状态页，
// 但改不了内核 avc 的实际决策 —— 所以用行为验证能穿透伪装。
void checkSelinuxXcheck(ScanReport& r);

// 安全补丁级别滞后月数。归属 INTEGRITY，最高 MEDIUM。
void checkSecurityPatch(ScanReport& r);

// 内核分支是否已超出 upstream LTS 维护窗口。归属 INTEGRITY，最高 LOW。
void checkKernelEol(ScanReport& r);

// 聚合：方案画像
void analyze(ScanReport& r);

// 序列化（native → Java）
std::string serializeReport(const ScanReport& r);

}  // namespace rs

#endif  // RS_CHECKS_H