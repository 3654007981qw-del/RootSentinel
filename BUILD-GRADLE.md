# BUILD（Gradle / Compose / Miuix）— v2.5.0

从 v2.5.0 起，构建体系已从**手工链**（aapt2 + kotlinc + d8 + apksigner）
切换为 **Gradle + AGP + Jetpack Compose + Miuix**。

## 一、为什么要换

手工链能满足「View + 自绘」的 UI，但 **Compose 的 `@Composable` 必须经过
Compose 编译器插件**才能生成正确的 `$composer` 参数，纯 `kotlinc` 做不到。
想用真正的 **Miuix**（HyperOS 风格 Compose 组件库）就必须上 Gradle。

## 二、环境

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 8.11.1 |
| AGP | 8.9.1 |
| Kotlin | 2.2.20（含 `org.jetbrains.kotlin.plugin.compose`） |
| Compose BOM | 2024.12.01（实际解析到 Compose 1.10.x） |
| Miuix | `top.yukonga.miuix.kmp:miuix:0.8.8` |
| Android SDK | compileSdk 36 / build-tools 35.0.0 |
| NDK | 27.2.12479018（只用于编 `.so`，见第五节） |

## 三、arm64 主机上的 aapt2（关键坑）

Google 只发 x86_64 的 aapt2，在 arm64 上会遇到三个连环问题：

1. 系统自带的 `aapt2 2.19` **读不了 android-36 的 resources.arsc**（新稀疏格式）
2. build-tools 35 的 aapt2 是 **x86_64 + 动态链接**，要 `qemu-x86_64-static -L /usr/x86_64-linux-gnu` 才能跑
3. AGP 会校验「aapt2 是真正的可执行文件」→ **shell wrapper 会被拒绝**
   （报 `Custom AAPT2 location does not point to an AAPT2 executable`）

解决方案（本仓库已实现）：

```sh
sh setup-aapt2.sh
```

它做两件事：

- 编译 `aapt2shim.c` 成 **arm64 ELF**：`version` 子命令直接应答（骗过 AGP 校验），
  其余命令 `exec` 到 `qemu-x86_64-static … build-tools/35.0.0/aapt2`
- 把该程序打进**本地 Maven 仓库**，坐标与 AGP 期望的完全一致：
  `com.android.tools.build:aapt2:8.9.1-12782657:linux`

`settings.gradle.kts` 里把 `/root/localMaven` 放在 `google()` 之前，
Gradle 就会优先用这个本地 artifact。

## 四、构建

```sh
# 1. 一次性准备（arm64 主机才需要）
sh setup-aapt2.sh

# 2. 签名密码（不要提交）
echo 'RS_KS_PASS=<你的密码>' >> gradle.properties

# 3. 编译
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（已签名）
```

首次构建会下载 AGP / Kotlin / Compose / Miuix（约 400 MB），耗时较长。
本机（15 GB 内存的手机 proot）实测：首次约 2 分钟，增量约 1 分钟。

内存紧张时可以在 `gradle.properties` 里调小：

```properties
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m
org.gradle.workers.max=2
```

## 五、原生库（librootsentinel.so）

AGP 不使用 `externalNativeBuild`，因为 NDK 自带的 clang 是 x86_64、
在本机跑不起来。原生库仍由 `native.sh` 用**系统 clang + NDK sysroot**交叉编译：

```sh
bash native.sh                    # 三个 ABI → out/<abi>/librootsentinel.so
cp out/arm64-v8a/librootsentinel.so app/src/main/jniLibs/arm64-v8a/
# armeabi-v7a / x86_64 同理
./gradlew :app:assembleRelease
```

改了 C++ 之后：`native.sh` → 复制 `.so` → 重新 assemble。

## 六、目录

```
.
├── settings.gradle.kts        仓库顺序（localMaven 优先）
├── build.gradle.kts           AGP / Kotlin / Compose 插件版本
├── gradle.properties          内存、AndroidX、RS_KS_PASS
├── setup-aapt2.sh             arm64 的 aapt2 适配
├── aapt2shim.c                arm64 转发程序源码
├── native.sh                  C++ 增量编译（三 ABI）
├── VERSION
├── app/
│   ├── build.gradle.kts       签名、ABI、依赖（Compose + Miuix）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/               44 个 .cpp + 6 个头（native.sh 用）
│       ├── java/com/rootsentinel/
│       │   ├── MainActivity.kt    Compose 入口 + 导航 + 扫描状态
│       │   ├── PagesCore.kt       首页 / 扫描 / 报告 / 详情
│       │   ├── PagesMore.kt       历史 / 设备 / 设置 / 关于
│       │   ├── Version.kt
│       │   ├── engine/  model/  util/  data/   （检测逻辑，与 v2.4.1 相同）
│       └── res/
└── app/src/main/jniLibs/<abi>/librootsentinel.so
```

## 七、与旧手工链的关系

- 旧的手工链脚本（`build.sh` / `bump_version.sh`）在 v2.5.0 中**已停用**，
  版本文档保留在 `README-v2.4.1.md`
- `native.sh` 仍在用（编 `.so`），它的 Makefile 逻辑未变
- 检测引擎、评分模型、JNI 协议、`ProbeService` 跨进程探针**全部保留**，
  只是 UI 层从 View 换成了 Compose + Miuix