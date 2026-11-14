# Changelog

## 2.22.43

基于对 2.22.42 的第三份独立静态审查（5 条 P1 + 8 条 P2 + 风险表，经逐条复核：11 条属实，P1-04 / P2-08 各有一处结论需修正，§五时间检测"仅 30 次样本"的说法有误——实际为 64 轮取中位数）修复：

**P1-01 H 类证据维度污染**
- `dimensionOf()` 新增 HIDING 分支：H 类（隐藏与欺骗痕迹）归 HOOK（观测完整性）维度，不再掉进默认的 ROOT。analyze.cpp 由交叉验证矛盾启发式生成的 H.HIDING_SIGNAL 从此只进 hookScore，不再直接污染 rootScore / 凑 ROOTED_HIDING 的分数部分（定级的 hasHiding 门槛仍按类别判定，不变）。

**P1-02 证据去重顺序依赖**
- 去重 rank 改为全序：状态 > severity 权重 > confidence（VERIFIED> HIGH > MEDIUM > LOW）> 非启发式优先。之前同 ID 同严重度时保留先到的一条，而阶段顺序是 shuffled 的——同一设备两次扫描可算出不同分数。现在同 ID 永远选同一条。

**P1-03 取消按钮不真正取消**
- MainActivity 现在把 `isCancelled` 真正接进引擎（新增 `ScanState.cancelRequested`）；取消只置标志，`scanning` 保持 true 直到引擎返回，不再出现"旧扫描在后台继续跑、UI 却允许开新一轮"。引擎收尾再补查一次取消：在最后一个阶段之后才取消的，也会落 Z.CANCELLED 并强制 INCOMPLETE。

**P1-04 构建文档顺序与死配置**
- README 改为先 export 签名凭据、再跑 native.sh（顺序反了 .so 没有签名基线）。native.sh 缺失基线由"只警告"改为默认直接报错退出（本地调试可用 RS_ALLOW_NO_SIGNER=1 放行）。审查所说"Gradle 没有 externalNativeBuild 配置"不准确：build.gradle.kts 里有两处，但因无顶层 cmake path 属死配置，实际打包走 jniLibs 预置库——文档口径已按此澄清。已交付的 APK 走手工管线、基线经核对在 .so 内，不受影响。

**P1-05 凭据卫生**
- 根目录 gradle.properties 中由旧文档引导写入的 RS_KS_PASS 行已删除，文件改为不入库（.gitignore 已覆盖），新增 gradle.properties.example 模板；BUILD-GRADLE.md 改为只教环境变量 / 仓库外 ~/.gradle 两种方式。若被删的那行曾是真实 keystore 密码，请按"已公开"处理并轮换——git 历史里仍有该文件的旧版本，本版无法改写历史。

**P2 修正**
- P2-01 适用性判定不再把 fpNote（误报说明）拼进关键词匹配，避免真实证据被误判 NOT_APPLICABLE 缩小覆盖率分母。
- P2-02 覆盖率折扣同比例作用于 hard/soft/total，保持 rootScore == hardScore + softScore；整体置信度真正计入可读率（coverage < 0.6 封顶 LOW）。
- P2-03 X.TCP_LISTEN 标题改"本机网络命名空间存在本地监听端口"，与自己的 fp_note 口径一致（/proc/net 是命名空间级视图）。
- P2-04 C.MOUNT_MULTI 补反方向比对：mounts 有而 mountinfo 没有的挂载点同样判不一致。
- P2-05 G.STRONGBOX 真正调用 setIsStrongBoxBacked(true) 生成 StrongBox 密钥；StrongBox 临时不可用时退回 TEE 并在标题/向量中如实标注，不再把"KeyStore 可用"冒充 StrongBox 通过。
- P2-06 JSON 导出：MediaStore 写入失败会删除 IS_PENDING 占位记录，不再留 0 字节幽灵条目；API 26–28 写公共目录失败时退回应用专属外部目录。
- P2-07 JSON 导出补齐：nativeVersion、证据 state 与 dimension、四维分数（root/hook/integrity/environment）、integrityBreach、crosses 明细——拿到导出文件可完整复算评分。
- P2-08 仓库 jniLibs 的 .so 改为 strip 后再入库（native.sh 产物在打包时由管线 strip，此前仓库里的预置库未 strip；已交付 APK 内的 .so 一直是 strip 过的，不受影响）。

**P3 落地项**
- 移除未使用的 INTERNET 权限（全仓无外网调用，仅有 127.0.0.1 回环探测与网卡枚举，均不需要该权限）。
- JSON 导出的设备段不再含完整 Build 指纹，改为 fingerprintSha256 摘要（同构建可比对，不泄露确切构建串）。
- 审查提及的设置项无消费者、Attestation 双实现统一、测试缺失等问题已确认属实，属结构性后续项，本版未动（Attestation 统一 Provider 沿用 2.22.42 的延期结论）。

验证：Native 三 ABI 全量编译通过；Kotlin 全量编译 0 错误；交付 APK 通过全量 dex 类审计（管线既定出厂门槛）。


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
