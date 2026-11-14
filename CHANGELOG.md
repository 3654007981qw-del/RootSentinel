# Changelog

## 2.22.42

基于对 2.22.41 的第二份独立静态审查（3 条 P1 + 4 条 P2，全部经逐条复核属实）修复：

**P1-01 Key Attestation 根信任锚正式钉扎**
- `TRUSTED_ROOT_CERT_SHA256` 填入两枚 Google 证明根指纹（构建方从官方端点 `android.googleapis.com/attestation/root` 下载证书 DER 后本机计算，2026-11-14）：RSA 根（f92009e853b6b045）与 2025 年启用的 EC 根「Key Attestation CA1」。空集不再放行——链根不在钉扎集合内即未锚定，`cryptoTrusted` 不成立；`rootIsGoogle` 降为信息字段。使用 OEM 自有证明根的设备暂不给 VERIFIED（已知取舍，后续以可维护的 OEM 根集合扩展）。
- `verifyChain` 补路径校验：每张证书有效期、签发者 BasicConstraints 必须为 CA、KeyUsage 若存在必须含 keyCertSign。
- 新增 `keymasterSecurityLevel` 解析（KeyDescription 第 4 字段），与 attestationSecurityLevel 交叉核对：后者声称 TEE 而前者为 Software 时硬件级不成立。

**P1-02 Native 发行一致性**
- 三个 ABI 的 `jniLibs` 预置库全部按本版源码重编（ELF 内版本标识为 2.22.42），不再是 2.22.40 的旧二进制。
- 版本强校验：DetectorEngine 比对 native 报告版本与应用版本，不一致（或 native 未报版本）落 `Z.VERSION` 并由评分器强制 INCOMPLETE；协议异常同理（`Z.PROTOCOL`）。

**P1-03 Magisk 家族 EACCES 推断收紧**
- 方案路径只有 EACCES、无任何可达路径时不再判 HIT：EACCES 同样可由中间目录权限、SELinux 遍历拒绝、厂商 LSM 在正常 ROM 上产生。改判 UNKNOWN；有路径真正可达时仍为 CRITICAL HIT。

**P2 修正**
- F.FTRACE：函数表可读改为能力记录——`current_tracer=nop` 且无已启用函数时 PASS；存在活动 tracer 或已启用函数才 HIT；活动状态不可读则 UNKNOWN。
- F.BPF_PROGS：`/sys/fs/bpf` 不存在（bpffs 未挂载）由 PASS 改 UNKNOWN；pinned 程序先分系统自带（netd / tethering / timeinstate 前缀）与未知来源，只有未知来源才 HIT。
- AttestationDetector：证书链为空提前返回时也清理 KeyStore 别名，不再残留条目。（两个 Detector 共享同一 TEE 信任根、并非独立来源的问题已确认；统一 Attestation Provider 属结构重构，列为后续项，本版未做。）
- NativeReportParser：未知类别不再默认落 ARTIFACT（ROOT 维度）——降级为 UNKNOWN 并落 CROSSCHECK，同时置 `protocolError` 使整次扫描 INCOMPLETE。

验证：Native 三 ABI 全量编译通过；Kotlin 全量编译 0 错误；仓库 jniLibs 与交付 APK 内的 .so 均逐个核对版本字符串为 2.22.42。

## 2.22.41

基于对 2.22.40 的第三方静态源码审查（7 条 P1 + 5 条 P2，全部经逐条复核属实）修复结论安全性问题：

**评分与结论安全**
- 取消扫描不再可能返回 CLEAN：定级条件纳入「已取消」；每个未执行阶段各落一条 SKIPPED（`Z.CANCELLED[.n]`），不再用一条代表全部。
- `Category.CROSSCHECK` 维度归位到 HOOK；交叉验证矛盾加分改计 hookScore。单条 `G.CROSS_PROC`（CRITICAL）不再能凑满 ROOTED 阈值。
- `Z.STAGE_FAIL` 改为每阶段独立 ID，失败阶段数不再被 ID 去重吞掉。

**读失败不再误判 PASS**（新增 util 层带 errno 的探测 `accessErrnoRaw`）
- `F.SELINUX_POLICY`：仅当实测 `enforce == "1"` 时不可读才 PASS，否则 UNKNOWN。
- `F.KSU_NODE`：节点 EACCES/EPERM 或状态不明时 UNKNOWN。
- `F.KPROBES` / `F.FTRACE`：不可读改 UNKNOWN，可读且为空才是 PASS。
- `F.BPF_PROGS`：目录不可枚举（EACCES/EPERM）时 UNKNOWN。

**签名基线注入链**（三处断裂一并修复）
- CMakeLists 真正消费 `-DRS_SIGNER_SHA256` 并转发为带引号的编译定义（此前宏从未被定义，`I.APK_SIGNATURE` 恒 UNKNOWN）。
- `native.sh` 双路线（clang 直编 / CMake）均注入基线：环境变量，或从 `RS_KS_PATH` 的 keystore 用 keytool 派生；缺失时明确警告。
- `jni.cpp` 的 sigCheck 比较改大小写不敏感（Java 侧小写 hex 与构建侧大写基线此前永远不相等）。

**Key Attestation**
- `parseKeyDescription` 分离 softwareEnforced / teeEnforced：TEE/StrongBox 时启动状态三件套只认 teeEnforced。
- `cryptoTrusted` 纳入链根锚定（rootIsGoogle，此前只计算不使用）；预留根证书指纹钉扎集 `TRUSTED_ROOT_CERT_SHA256`。
- 定性分支改用 `Confidence.VERIFIED`（此前无任何 VERIFIED 生产者，评分器的 integrityBreach 永远触发不了）。
- `verifiedBootState == 0` 且 deviceLocked 缺失时改判 UNKNOWN，不再落入「未锁定」HIT 分支。

验证：Native arm64-v8a 全量编译通过（带签名基线定义）；Kotlin model/engine/util 编译 0 错误；`native.sh` 语法检查通过。jniLibs 预置 .so 需用 `native.sh` 按新版源码重建。

## 2.22.40

- 跨进程对比从噪声黑名单翻转为确定性白名单（54 项），覆盖门槛与差异判定顺序修正。
- JNI 回调本地引用 use-after-free 修复（`NewLocalRef`）。

> 2.22.40 及更早版本为快速迭代期，变更细节以代码注释为准；本仓库的 git 历史自 2.22.41 起。
