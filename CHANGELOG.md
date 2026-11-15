# Changelog

## 2.22.46

新功能（作者要求）：**诊断摘要上传**，配套一套完整的同意与关闭机制。

- **首次使用声明弹窗**：第一次打开 App 先弹声明（不可点外部/返回键绕过，必须读完点「我已阅读，继续使用」），继续使用即视为同意；确认状态持久化，之后不再弹。未确认声明时一律不上传。
- **设置开关**：「设置 → 隐私与数据 → 上传诊断摘要」，默认开启（与声明的同意模型一致），随时可关，关闭立即停传。
- **字段最小化**（与声明文案严格一致）：只传 App 版本、设备品牌/型号/厂商、Android SDK、评分汇总与逐项的编号/类别/严重度/置信度/判定。**不传**设备指纹（连哈希也不传）、任何设备标识、文件路径、证据标题与详情、已安装应用列表、报告全文——detail/vectors 因此整体排除在载荷之外。
- **失败语义**：发后即忘，无重试队列、不落盘缓存；无网/超时/非 2xx 静默丢弃，不影响扫描与界面。
- **接收端地址留空待命**：`Telemetry.UPLOAD_ENDPOINT` 当前为空字符串，未配置时 upload 直接返回、实际不发送任何数据；作者部署接收端后填入 HTTPS 地址即生效。
- **INTERNET 权限加回**：v2.22.43 曾移除本权限，本次为上述功能加回，仅 Telemetry 使用。


## 2.22.45

基于对 2.22.44 的第五轮独立审查——本轮有两份（审查 A：P0×6 + P1×8 + P2×6；审查 B：P2×8 + P3×3），共 31 条，经逐条回源码核查：24 条属实、7 条部分属实、0 条不属实（审查 B 附带的两条正面核查——.so 已 strip/对齐、CMake 45 个源文件与目录一致——复核属实）。全部按核查结论修复：

**P0 一组：原厂/模拟器环境误报（审查 A）**
- A1 E.ART_SYMBOL：`art::Runtime::instance_` 是数据符号（在 libart 的 .data/.bss），旧实现用只收可执行段的 isAddressInModule 判定，正常设备也报 CRITICAL；改用已有的 isAddressInModuleAnyPerm。
- A2 D.FD：fd 表扫描含 "memfd" 即 HIGH HIT，未做 ART 过滤——ART 的 JIT/图形栈本身持有 memfd fd。与 maps 路径 D.MEMFD 同口径：先过 isArtOwnedMemfd/isArtOwnedPath，只对非 ART memfd 与 /data/adb 报告，证据封顶 3 条。
- A3 B.PROP_KEYWORD：ro.kernel.qemu 是模拟器标记而非 root 证据，从 root 关键字列表移除（模拟器语境由 check_emulator 单独检查）。
- A4 I.SECCOMP_FILTERS：Seccomp=2 但 Seccomp_filters 字段缺失（老内核无此字段）旧实现直接 HIT；改判 UNKNOWN，filters=0 维持 HIT。
- A5 X.FRIDA_PORT：/proc/net/tcp* 是网络命名空间级视图，旧实现任一监听命中 Frida 端口即 CRITICAL。现解析 socket inode 并与 /proc/self/fd 对照：确属本进程才 CRITICAL；仅命名空间存在降 MEDIUM + heuristic。
- A6 statx 回退 dev 编码：回退把 st_dev 高/低 32 位直填 major/minor，不是 dev_t 编码；fstatat_raw 直连回退又返回未打包的原始 st_dev。统一 packDev() 解码三处共用，无 statx 老内核上 C.META_MISMATCH 不再必误报。

**协议与解析严格化（审查 B）**
- P2-01 ARM32 fstat ABI：Bionic 的 struct stat 在所有架构上都是 STAT64 布局，ARM32 的 NR_fstat/NR_fstatat 旧值 108/327 写的是旧式结构、元数据全错位；改为 fstat64(197)/fstatat64(268)。
- P2-03 NativeReportParser：severity/confidence/verdict 未知值不再静默降级（INFO/LOW/UNKNOWN），任一不可识别即置 protocolError 且该证据判定强制 UNKNOWN。
- P2-06 DER 读取器：readRaw 声明长度超过剩余数据直接失败（旧实现 minOf 静默截断）；parseKeyDescription 增加外层 SEQUENCE 完整消费校验、内层截断整体失败；parseAuthList 截断时整表作废。

**交叉验证接通（审查 B P2-04）**
- addCrossCheck 自引入起全仓无生产者，crosses 恒空、评分层 CROSSCHECK_BONUS 是死路径。现登记三个真实生产者：X.CHILD_PROC（父子进程三点观测）、C.MOUNT_MULTI（mountinfo↔mounts 集合）、C.META_MISMATCH（fstat↔statx）。只登记结构化观测、不经 addCrossCheck 重复产证。

**判定口径与噪声（审查 A P1 / 审查 B P2-02、P2-05）**
- F.OVER_HIDING：EACCES 与 ENOENT 分开计数，denied≥2 才 HIGH，恰 1 个降 LOW + heuristic。
- D.TASK_COUNT：差 ≤1 按瞬时差异 PASS；差 ≥2 重采样一次，两次都 ≥2 才 HIT。
- E.INLINE_HOOK：仅 bionic alias（open/stat）命中时降 LOW + heuristic，混有非 alias 才维持 HIGH。
- check_art_method 合法归属删掉裸子串 "art"（"smart" 等误放行），保留扩展名与 dalvik 口径。
- D.ANON_EXEC 删除：与 D.TRUE_ANON 同一事实集两套口径，统一由 D.TRUE_ANON 判定。
- A.DIR_ENUM_MISMATCH：新增三态 fileStateRaw，只有 ENOENT 计「枚举到但查不到」，EACCES 等记「不可验」并产 UNKNOWN，不再冒充不存在。
- C.VERITY：判定重构——有带属性也有可观测缺失才 HIT（剥离实证）；全有且无不可读才 PASS；部分不可读、或全部缺失（ROM 可能未启用 fs-verity）改 UNKNOWN，unreadable 正式参与结论。
- C.STATFS 全探测失败补 UNKNOWN；C.META_MISMATCH 补可比计数：0 可比 UNKNOWN、全一致 PASS。
- SELinux shell 域匹配串 ":u:r:shell:s0" 永远匹配不到（真实上下文无前导冒号），改 ":shell:"。
- A.MODULES_DIR：与父目录 /data/adb 口径互证——父 ENOENT 而子 EACCES 的矛盾观测记 UNKNOWN。
- B.UNAME_MULTI：两条同 ID PASS 合并为一条，双来源向量并入（评分器去重不再丢向量）。

**健壮性与维护（P2/P3）**
- check_bpf 的 bpf_attr 改对齐 uint32_t 数组（原 char[128]+reinterpret_cast 有对齐/别名 UB）；check_env auxv 改逐项 memcpy 解析；check_cross_process 的只跑一次标记改 std::atomic；check_kernel_deep 模块名改词边界匹配（"ksu"/"milk" 子串误伤消除）；X.ENV_MATRIX 目录能力改三态（存在且可读/存在不可读/不存在）。
- 手动导出补 Toast 结果反馈（此前返回值被丢弃，成功失败无提示）。
- 补 THIRD_PARTY_LICENSES.md 与 LICENSES/Apache-2.0.txt（vendored 的 Kyant Backdrop 源码为 Apache-2.0，此前仓库只有 MIT）；LICENSE 署名随 GitHub 改名更新为 AURORA-0711；README 版本号同步（此前停在 2.22.42）；删 Report.kt 未用变量与 Der 未用方法。
- 新增单元测试（NativeReportParser 协议严格性、DER 截断拒绝、评分去重）与 GitHub Actions 构建工作流——此前仓库无 test/androidTest 与 CI（审查 B P2-08）；测试在 CI 上运行，本仓库的手工构建管线不跑 Gradle。


## 2.22.44

基于对 2.22.43 的第四份独立静态审查（4 条 P1 + 14 条 P2 + P3，经逐条回源码核查：17 条属实、3 条部分属实、0 条不属实；其中 P1-01 经核查比审查写的更严重，§9 附带的「CMake 源清单重复条目」经复核不成立——syscall_asm.cpp 在源清单里只有一条，另一处是 set_source_files_properties 属性设置）全部修复：

**P1-01 Key Attestation ASN.1 解析按官方结构重写**
- 旧映射整体错位：[704] 是 rootOfTrust 的 EXPLICIT 包装而非 deviceLocked；[709]/[710] 是 attestationApplicationId / attestationIdBrand 而非启动状态/哈希；osVersion 实际在 [705]；且所有 EXPLICIT 字段从未剥内层 TLV。后果：deviceLocked 对一切带 rootOfTrust 的设备恒为 true——真实解锁的设备会被下游以 VERIFIED 置信度报成「已锁定」，deviceLocked=false 的 HIT 分支永不可达。
- 现在：[704] 解包 SEQUENCE 后读 [0..3] 子字段（deviceLocked / verifiedBootState / verifiedBootHash 各归其位），osVersion 改 [705]，补 [706] osPatchLevel，全部 EXPLICIT 字段先剥内层 TLV 再取值。

**P1-02 C.VERITY 常量与掩码**
- STATX_ATTR_VERITY 误写成 DAX 的 0x00200000，改回 UAPI 的 0x00100000；判定前先查 stx_attributes_mask，两属性都不可观测的文件按不可读计，不再把「内核不报告」当「属性缺失」凑 HIT。

**P1-03 跨进程白名单重建**
- 旧白名单 54 个 ID 只有 25 个能由 native 产出（改名漂移、Java 侧项、不存在的 ID），60% 门槛要 32 项，complete 永假、强差异分支从未触发。白名单按 native 实际 ID 注册表重建为 39 项（A.KSU_NODE→F.KSU_NODE、G.BPF→G.BPF_PINNED 等改名对齐），门槛随之可达；文件头注明维护规则与对照导出命令。

**P1-04 AAPT2 路径统一**
- 本地 Maven 仓库以 settings.gradle.kts 的 /root/localMaven 为唯一真源，setup-aapt2.sh 默认目录与之对齐；删掉无人引用的 SDK extras/m2repository 副本；aapt2shim.c 的真 aapt2/qemu 路径改为占位符，由脚本按当前 SDK 配置生成。

**P2 一组：结论可信度**
- P2-01 applicabilityOf 删掉「不存在 / 未提供 / 没有该 / 缺少」裸缺失词——分不清设备不支持与数据缺口，F.KERNEL_EOL、G.ATTEST_CHAIN 的数据缺口型 UNKNOWN 不再被移出覆盖率分母。
- P2-02 crosses 的 consistent 缺失不再默认 true（按不一致 + 协议问题处理）；证据 id 为空判协议错误并跳过该条。
- P2-03 F.AM_PROCS 进程列表为空改 UNKNOWN（原 PASS + HIGH），与同函数不可用路径对齐。
- P2-04 C.META_MISMATCH 换真独立视角：新增 sys::fstat_open_raw（openat + NR_fstat，三 ABI 补 NR_fstat），不再用内部先调 statx 的 fstatat_raw 自比；C.SAME_INODE 比较补上 st_dev；删 check_mount 未使用的 struct Probe。
- P2-05 C.OVERLAY 分档：/product、/apex、/system_ext 从 HIGH 降 MEDIUM + heuristic；C.DATA_SUBMOUNT 对 /data/misc 厂商区降 LOW + heuristic。A.SU_PATH 经核查 2.22.43 已基本修好，未动。

**P2 二组：应用与发布加固**
- P2-06 autoscan / navtest / snap 三个调试 intent 加入口加 BuildConfig.DEBUG 门槛，release 恒不触发。
- P2-07 逐项日志降级：Kotlin 逐项 Log.i → Log.d（R8 release 按现有 proguard 规则剥离）；native RS_LOG 由 INFO 降 DEBUG。扫描级汇总一行保留。
- P2-08 两条 JSON 导出路径（自动 / 手动）移 Dispatchers.IO。
- P2-09 MediaStore 导出检查 update() 返回值：失败删占位记录并返回 null，不再留 IS_PENDING=1 幽灵条目还报成功。
- P2-10 删除三个无消费者设置（themeMode / animations / haptics）及设置页「交互」卡片——只存不用、误导用户。
- P2-11 Attestation 收敛单一实现：删除旧 AttestationDetector 与「Java · 证明链」阶段（其链根判定停在 Subject 含 Google 的弱口径）；证明统一走 SafetyNetDetector → KeyAttestation，每轮扫描只生成一把证明密钥。旧实现独有的 G.ATTESTATION / G.CHAIN / G.ATTESTATION_EXT / G.ROOT_ANCHOR 四项随之移除，信息已被 G.ATTEST_CHAIN 的解析结果覆盖。
- P2-12 BUILD-GRADLE.md 的 out/ 路径改为 jniLibs 直写口径；删 build.gradle.kts 两处死的 externalNativeBuild 配置，注明基线注入的真实路径是 native.sh。
- P2-13 两处 keytool 调用改 -storepass:env 传密码（native.sh 与 build.gradle.kts 的 signerSha256），密码不再出现在进程 argv 里。
- P2-14 release 构建失败关闭：任务图含 Release 任务且无正式签名凭据时直接报错，不再回退 debug 签名。
- P3-01 native.sh 的 strip 改失败关闭：找不到 llvm-strip 或 strip 失败即中止（临时放行 RS_ALLOW_UNSTRIPPED=1）。

**延期（不变）**
- 评分适用性的结构化原因字段（UNSUPPORTED / DATA_MISSING 枚举）仍待做；本版先用关键词收敛止住误判。
- 自动化测试与 CI 仍缺位；ASN.1 解析已按官方结构重写，DER 测试向量回归建议在有 Gradle 的环境补上。

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
