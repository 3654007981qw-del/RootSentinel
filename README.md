# RootSentinel

Android Root 环境检测器 —— Kotlin / Jetpack Compose UI + C++ NDK 检测引擎。

在自有设备上被动检测 Root 痕迹、Hook/注入、系统完整性破坏与运行环境异常，输出四维评分与逐项证据。**检测全程只读**：不执行 `su`、不提权、不修改系统。唯一的网络行为是**诊断摘要上报**（v2.22.46 起）：首次启动会弹出声明、经你同意后，每次扫描把一份字段最小化的摘要（版本、机型、评分与逐项结论；不含设备指纹、标识、路径与包名清单）经加密发送到项目接收端；设置里可随时关闭。另有应用内更新检查（只读公开的 Release 信息）。

当前版本：**2.22.63**（见 [CHANGELOG](CHANGELOG.md)）

## 设计原则

- **测不出来 ≠ 没问题。** 证据是三态判定（PASS / HIT / UNKNOWN）叠加独立的执行状态（SKIPPED / ERROR / TIMEOUT）。读不到、跑不了、被取消，一律按 UNKNOWN / INCOMPLETE 记录，永远不会被翻译成「干净」。
- **一个事实只算一次。** 同一检测项被多个模块观察到时按 ID 去重，只计最严重的一条；交叉验证矛盾计入 HOOK（观测完整性）维度，不冒充 Root 证据。
- **维度隔离。** 评分分为 ROOT / HOOK / INTEGRITY / ENVIRONMENT 四个独立维度：解锁 Bootloader 是完整性问题，不等于已 Root；跨进程观测不一致是 Hook 风险，也不等于 Root。
- **硬件证明要验真。** Key Attestation 逐级验签、比对 challenge、要求 TEE/StrongBox 级别、链根锚定 Google 证明根；启动状态字段只采信 `teeEnforced`，软件层自报不算数。

## 检测面

| 前缀 | 方向 | 示例 |
|---|---|---|
| A | Root 方案痕迹 | Magisk / KernelSU / APatch 文件与节点、管理器包 |
| B | 系统属性与镜像 | verified boot 属性、构建指纹、安全补丁等级 |
| C | 挂载一致性 | mountinfo ↔ mounts 双向核对、statfs、Overlay |
| D | 内存注入 | memfd、匿名可执行页、线程名、注入库残留 |
| E | Hook 框架 | Zygisk / LSPosed / Frida / inline hook / PLT hook |
| F | 内核与 SELinux | enforce / policy、kprobe / ftrace / BPF、KSU 节点、内核 EOL |
| G | 证明与交叉验证 | Key Attestation 链、跨进程探针对比（`:probe` 进程二次扫描） |
| H | 隐藏痕迹 | errno 三视角矛盾、syscall 异常、视图不一致 |
| I | 自身完整性 | APK 签名自校验（构建期注入证书基线）、签名链 |
| Z | 元状态 | native 失败 / 阶段异常 / 扫描取消（强制 INCOMPLETE） |

规模：45 个 Native C++ 源文件（41 个检测函数）、13 个 Kotlin 检测阶段，真机单次扫描约 230 项证据。

## 构建

环境：JDK 17、Android SDK（platform 36）、NDK 27、CMake 3.22+。

```bash
# 0) 先导出签名凭据（只走环境变量，绝不写进任何文件 —— 包括
#    gradle.properties，它是被 git 跟踪的，写进去等于公开密码）
export RS_KS_PATH=/path/to/rs.jks RS_KS_PASS=*** RS_KS_ALIAS=*** RS_KEY_PASS=***

# 1) 构建 native 库（双路线：有 NDK 时走 CMake，ARM64 主机走系统 clang 直编）
#    签名基线在此步注入：环境变量 RS_SIGNER_SHA256，
#    或由上面的 RS_KS_* 指向的 keystore 派生。顺序不能反 ——
#    先跑 native.sh 再 export 的话，.so 里没有基线，
#    I.APK_SIGNATURE 将为 UNKNOWN；缺失基线时 native.sh 直接报错退出
#    （仅本地调试可加 RS_ALLOW_NO_SIGNER=1 放行）。
./native.sh

# 2) 打包
./gradlew assembleRelease
```

- minSdk 26 / targetSdk 36；ABI：arm64-v8a、armeabi-v7a、x86_64（64 位满足 16 KB 页对齐）。
- Release 开启 R8（含 JNI 按名回调的 keep 规则）。
- Native 不由 Gradle 构建（`app/build.gradle.kts` 没有 `externalNativeBuild` 配置）：修改 C++ 后必须先运行根目录的 `native.sh` 重新编译三个 ABI 并更新 `app/src/main/jniLibs`，Gradle 只负责打包现成的 .so。只跑 Gradle 就改 C++ 不会生效（详见 BUILD-GRADLE.md）。

## 仓库结构

```
app/src/main/cpp/     Native 检测引擎（src/ 检测模块，include/ 头文件）
app/src/main/java/com/rootsentinel/
  engine/             扫描调度、Kotlin 检测器、跨进程探针
  model/              证据模型与四维评分器
  util/               Key Attestation 解析、报告解析
  ui/ 及根包           Compose 界面（液态玻璃风格）
native.sh             Native 双路线构建脚本
BUILD-GRADLE.md       Gradle 构建说明
```

## 说明

本项目是个人安全研究工具，用于检测**自己设备**的 Root 与篡改状态。检测结论是启发式证据的综合评分，不构成对任何设备安全性的保证或认证。

## License

[MIT](LICENSE)
