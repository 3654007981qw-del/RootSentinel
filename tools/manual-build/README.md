# 手工发布管线（维护者构建脚本）

发布 APK 不走 Gradle，而是这套手工管线（aapt2 / kotlinc / CMake /
zipalign / apksigner 分阶段执行；dex 阶段 release 走 R8 混淆收缩、
debug 走 D8 不混淆——v2.22.53 起，第八轮审查 P2-02）。本目录是
维护者机器上实际使用的脚本快照，自 v2.22.50 起随源码入仓——
第六轮审查指出此前管线只存在于维护者本地，源码包无法证明版本
生成流程已交付。

R8 规则栈：aapt2 按清单生成的组件 keep（构建时产出）+ 项目
`app/proguard-rules.pro` + 全部 AAR consumer rules + 本目录的
`r8-extra.pro`。R8 的 mapping 文件只保留在维护者本地，不随
发布公开（公开等于自废混淆）。

- `build_apk.sh` —— 主管线。`VNAME`/`VCODE` 是**版本单一事实源**：
  prep 阶段据此生成 `Version.kt`、`CMakeLists.txt` 的 `RS_VERSION`
  与 `app/build.gradle.kts` 的 versionName/versionCode，生成失败
  立即中止构建。脚本内的路径（源码目录、依赖目录、SDK/NDK 位置）
  是维护者机器的布局，换机器需按实际调整。
- `package_apk.py` —— APK 组装（把 dex 与三 ABI .so 塞进 linked APK）。
- `merge_manifest.py` / `gen_r.py` —— 清单合并与 R 类生成辅助。

注意：依赖的 AAR/JAR 集合（约 67 个）与签名密钥不在仓库内，
因此本管线在任意机器上开箱即用地复现发布构建仍需要维护者的
依赖目录与密钥；入仓的目的在于让版本生成与构建流程可审计。
Gradle 构建（`app/build.gradle.kts`）使用仓库内的预编译
`jniLibs` .so，可独立完成 assembleDebug 与单元测试。
