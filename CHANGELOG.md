# Changelog

## 2.22.55

更新下载提速（用户反馈国内下载慢）+ 下载弹窗显示实时速度。

- **测速选源**：旧版按固定顺序（GitHub 直连 → 镜像 → Gitee）逐个试，排在前面的源只要「慢但不断」就会一直拖着下。新版下载前先并行探测每个候选源的实际吞吐（从文件三分之一处取 256 KB 计时），按实测速度从快到慢重排再下载；探测失败的源不剔除，只沉底作兜底。国内网络下各源速度差异极大且时时不同，先测再下才选得准。
- **慢源淘汰**：下载中单条连接 5 秒内下不到 192 KB（约 38 KB/s）即断开换下一个源续下（分片模式按字节区间续，单流模式换源重下）；每个分片只在前几轮连接启用淘汰、最后一个源不淘汰，避免所有源都慢时来回横跳下不完。
- **分片 4 → 6**：慢速 CDN 上更多并行连接更容易跑满带宽。
- **速度显示**：下载弹窗新增实时速度（3 秒滑动窗口平均）与「已下载 / 总大小」，不再只有一个百分比。进度回调同步带上速度读数（SpeedMeter 纯逻辑，有单测）。
- 校验与安装逻辑不变：SHA-256 / 包名 / 签名三重校验照旧，镜像仍只影响速度。

## 2.22.54

遥测接收端迁移：阿里云函数计算 → Cloudflare Workers。

- **仅换上传地址**：诊断摘要的接收端从阿里云 FC 迁到 Cloudflare Workers（服务端为同一协议 v2 的移植，防重放与报告存储改用 D1 数据库，跨实例一致、强于原实例内存缓存）。协议、密钥、载荷字段、首次声明同意与设置开关全部不变，客户端只改 `UPLOAD_ENDPOINT` 一个常量。
- **迁移原因**：阿里云账号因日志服务（SLS）按量计费产生欠费，控制台拒绝部署新版服务端；新接收端在免费额度内运行（Workers 10 万次/日、D1 5 GB），无欠费停服概念。部署当日实测：合法信封 200 且报告落库、重放 401、篡改 401、明文 v1 401。
- 旧 FC 端点保持在线，服务已发布的 ≤2.22.53 版本；本版起新报告走新端点。

## 2.22.53

第八轮审计（v2.22.51 + 服务端 v5）全部修复：9 项中 8 项属实、1 项为已决重提，用户拍板全修。服务端同步升至 v6。

- **构建管线接 R8（P2-02）**：release 的 dex 阶段改走 R8（混淆 + 收缩 + 按规则剥离 Log.d/Log.v），不再只有 D8 转换；规则栈 = aapt2 清单 keep + 项目 proguard 规则 + 全部 AAR consumer rules + 管线附加规则。dex 由约 29.8 MB 降至约 4.2 MB。配套：E.JAVA_STACK 的自家包前缀改为运行时推导（写死包名会在混淆后把自己的调用栈误报成外部帧）；打包时 META-INF/services 改从 R8 产物收集（类名已改写，沿用 JAR 旧名会让 Dispatchers.Main 失效）。debug 包仍走 D8 不混淆。mapping 文件只留维护者本地，不公开。
- **管线失败关闭补齐**：kotlinc 退出码直接校验（P2-03，此前只数日志错误条数，编译器崩溃会被漏过）；AAR 资源编译失败立即中止（P2-06）；发布门新增签名证书指纹比对与三 ABI 存在性/ELF 机器类型校验（P2-04），package_apk 缺任一 ABI 的 .so 改为硬失败。
- **Native 健壮性（P2-05）**：getdents64 目录解析逐条验边界（头部长度、d_reclen 范围、名字 strnlen 截断）——解析的正是被 Hook 环境的不可信返回数据，此前只有 d_reclen==0 一道检查。
- **隐私声明对齐（P3-01）**：首次声明文案逐项列出实际上传的全部字段（制造商、四项分项评分、逐项类别/严重度/置信度/启发式标记、计数、耗时、时间戳等），此前只列了部分。
- **CI 补 Native（P3-02）**：新增独立 job 用固定版 NDK 真实编译三 ABI 并校验 ELF 机器类型与 LOAD 对齐，此前 CI 只用仓库预编译 jniLibs，C++ 改坏发现不了。
- **下载清理残留项（P3-03）**：单流下载的连接统一 try/finally 断开（.52 的多源+分片重写已消解其余部分）。
- **服务端 v6（P2-01）**：nonce 防重放改锁内原子「清过期→查重→容量→插入」，且先查重后容量、满载拒绝新 nonce 不再淘汰窗口内旧条目——v5 的无锁竞态与「淘汰最旧后旧 nonce 被重新接受」两个确定性缺陷均已本地复现并堵上，协议行为其余不变。

## 2.22.52

更新下载大改版：多源切换 + 分片续传（用户点单）。检查更新的结论逻辑不变。

- **多源自动切换**：下载候选按「GitHub 直连 → 公益镜像 ×3（gh-proxy.com / ghfast.top / ghproxy.net）→ Gitee」排列，某源失败或卡住自动换下一个。镜像只是中转、只能影响速度：安装前的 SHA-256 / 包名 / 签名三重校验照旧强制，内容被污染也装不上；走镜像的只有公开的 release 文件，不带任何凭据。GitHub 命中后会顺手取一次 Gitee 同版本地址作最后兜底；Gitee 命中时按本仓库固定发布规律反推 GitHub 直链，候选顺序一致。
- **分片并行 + 断点续传**：文件切 4 段并行下载，每段落盘保存；中断、取消、杀进程后重试都从已有进度续传，不再从零开始。某段在某个源失败时换源续下同一段（各源是同一文件，字节区间可跨源拼）。源不支持 Range 时自动退回单流下载，同样逐源切换。
- **浏览器兜底**：更新弹窗（发现新版与失败两种状态）新增「复制下载链接 / 浏览器打开」，App 内实在下不动时可以拿官方直链去浏览器或下载器里下。

## 2.22.51

第七轮增量审查的修复版（客户端 1 项 + 构建管线加固；服务端同步升 v5，不在本仓库内）：

- **调试器检测真值表**：SelfGuardDetector 的 I.DEBUGGER 改为「任一 API 明确报 true 即确定性命中」。2.22.50 为防异常冒充 PASS 把任一异常一律落 UNKNOWN，矫枉过正——一个 API 已确认有调试器、另一个抛异常时，确定性命中会被异常盖掉。现在只有双 false 才 PASS，无命中但有一侧读不到才 UNKNOWN。
- **手工构建管线加固**：build_apk.sh 启用 pipefail；kotlinc 编译前清空 kotlin-classes 输出、D8 前清空 dex-release，两者的退出状态改为直接校验（错误数非 0 或无产物即中止构建）；打包末尾内置发布门 stage_gate（badging 版本、签名、dex 无旧版版本串残留、64 位 .so 版本串）——此前这些检查靠人工执行，管线自身对编译失败没有防护。

## 2.22.50

第六轮全面审查的修复版（21 条结论逐条核查：12 条属实、5 条部分属实）。重点是一处真实漏检。

**检测与评分**

- **KernelSU 证据归类错误（本轮最重要）**：native 检出的 KernelSU 节点命中（F.KSU_NODE）此前被评分层归进「系统完整性」维度，只涨完整性分、不涨 Root 分——只暴露这一类证据的设备可能被评得过轻。已改归 Root 维度并加了回归测试。
- **交叉验证计数去重**：三个交叉检查生产者会同时产出证据本体与交叉记录，同一事实此前在命中数里被计两次、覆盖率分子分母也重复代表；现只计一次（交叉矛盾的额外权重作为设计保留）。
- **异常不再冒充通过**：调试器状态读取异常此前会被吞成「无调试器」的 PASS；现改为 UNKNOWN。系统服务检测里几处失败兜底共用写死的证据 ID（会顶替真正的 F.SERVICE_LIST 并被去重合并），现各归其位、读取异常与「列表为空」分开处理。

**更新检查**

- **检查失败不再冒充「已是最新」**：此前两个更新源都不可达时界面会显示已是最新版本。现结论三分（有新版 / 已是最新 / 检查失败），只有源真正答复过才敢说最新；顺手修了手动检查撞上自动检查时弹出空白弹窗的竞争。
- 下载增加 64 MB 体积硬帽（发布包约 16 MB），超限中止，后面仍有 SHA/包名/签名三重校验。

**服务端（同步上线 v4）**

- 严格载荷 Schema（字段类型、等级/置信度枚举、分数范围、逐项结构与条数上限），不合规直接拒收不落日志。
- 防重放 nonce 改用规范化缓存键，堵上大小写变体绕过；非数字时间戳返回 400 而非抛异常；Content-Type 改精确匹配；结构化入口校验请求方法；所有拒收路径统一记 RS_REJECT 原因。

**构建与文档**

- 版本生成补齐最后一块：`build.gradle.kts` 纳入管线自动生成，此前它停在 2.22.46，与 Version.kt/CMakeLists 脱节（发布包走手工管线不受影响，但 Gradle 构建会漂移）。
- 发布管线脚本随源码入仓（`tools/manual-build/`），版本生成流程可审计。
- README 修正：删除「不上传任何数据」的过时表述，如实说明诊断摘要上报（声明同意 + 加密 + 可关闭）与更新检查的网络行为。


## 2.22.49

修复 2.22.47/2.22.48 的版本常量漂移（本版无功能变化）。

**现象（2.22.47/2.22.48）**

- 已装最新版仍反复弹窗提示更新，点更新后下载完成却报「安装包版本不高于当前版本」。

**根因**

- 程序里有两处手写版本常量（Kotlin 的 `RootSentinelVersion` 与 native 的 `RS_VERSION`）在 2.22.47 起没跟着版本号走，一直停在 2.22.46。更新检查按这个假版本判断，永远以为有新版；而安装前的版本校验用的是系统记录的真实版本，于是把同版安装包正确地拒了——一个误报、一个拦截，合起来就是上面的现象。扫描与检测功能不受影响（两处常量彼此一致，版本交叉校验未被触发）；诊断摘要里上报的版本号同样受此影响标错为 2.22.46。

**修复**

- 两处常量改由构建管线按唯一的版本变量自动生成，生成失败构建立即中止，杜绝再次漂移；本版起 App 内显示、更新判断、native 自报与上报口径全部回到真实版本 2.22.49。
- 装了 2.22.48 的用户：本版可经 App 内更新正常升级（这次校验会通过），或直接覆盖安装。


## 2.22.48

传输加密与服务端防护：诊断摘要不再发明文，接收端只收验签通过的报告。

**上传加密（协议 v2）**

- **加密信封**：报告 JSON 先加密再发送——密钥由主密钥经 HMAC 派生（加密/签名两把子钥匙分离），HMAC-SHA256 密钥流 XOR 加密、每份报告随机 nonce、先加密后签名（EtM）。链路抓包只能看到一个信封（版本/时间戳/nonce/密文/签名），看不到任何字段内容。
- **防伪造防重放**：服务端验签通过才落日志；时间戳窗口 ±5 分钟、nonce 单次使用，重放或改一个字节都会被拒。
- **密钥存放**：App 内密钥以分片 XOR 掩码形式存放、运行时拼装，不以明文字符串出现在源码里；服务端密钥只放在函数环境变量中，不进代码。说明一句实话：客户端内嵌密钥对有耐心的逆向者不是绝对秘密，这层防护拦的是随手伪造、灌水与被动抓包；载荷本身仍是字段最小化、没有设备标识。
- **协议切换**：服务端即日起只收 v2 信封，**2.22.47 及更早版本的诊断上传会被拒收**（扫描、更新等其它功能不受影响）。

**服务端防护（接收函数 v3）**

- 密钥未配置时一律拒收（fail closed）；Content-Type 必须是 application/json；请求体上限 256 KB（维持）。
- 签名比较用常量时间算法；解密后仍再做一遍形状校验（纵深防御）。
- 拒收事件单独记 `RS_REJECT` + 原因 + 来源 IP，与正式报告 `RS_REPORT` 分开，灌水一眼可见。

**下发侧**

- 本 App 唯一的下发数据是更新检查与安装包：检查接口是公开数据（HTTPS），安装包在 2.22.47 已有三重校验（发布哈希/包名版本/签名证书），本版维持不变。


## 2.22.47

两件新功能：诊断摘要接收端正式上线；应用内更新。

**诊断摘要上传生效（承接 2.22.46）**

- **接收端已部署**：阿里云函数计算（华东2·上海）`rootsentinel-report`，HTTP 触发器仅 POST、匿名访问；`Telemetry.UPLOAD_ENDPOINT` 已填入正式地址。接收函数只做形状/大小校验（单份上限 256 KB）后整份写入函数日志，不连数据库、不存设备标识。
- **端到端实测通过**（2026-11-15）：合法载荷返回 200 `{"ok": true}`，形状不对/坏 JSON 返回 400 并注明原因；声明未确认或设置开关关闭时仍一律不发送，口径与 2.22.46 不变。

**应用内更新**

- **启动自动检查**：每次打开 App 自动查一次新版（声明弹窗未确认时不查），数据源为 GitHub Releases（主）/ Gitee 镜像（备）的 latest 接口，均为公开 API、无需令牌；查到新版才弹窗，没有新版不打扰。
- **更新弹窗**：显示「当前版 → 新版」版本号、更新来源、安装包大小与本次更新内容（发布说明全文，可滚动）。
- **设置页手动入口**：「设置 → 版本与更新」新增「检查更新」（显示当前版本，点了立即给反馈）与「GitHub 仓库」跳转（AURORA-0711/RootSentinel）。
- **下载与安装**：点「立即更新」后在弹窗内下载并显示进度，可取消；下载完成自动进入校验，校验通过自动调起系统安装确认（系统安装界面仍需用户点确认，本应用不做静默安装）；缺「安装未知应用」授权时引导去系统设置开启，开启后可直接续装、不用重新下载。
- **安装前强制校验**（任一不过不安装）：① 文件 SHA-256 与发布接口给出的 asset digest 一致（接口未给 digest 时跳过此项）；② 包名与本应用一致且版本号高于当前；③ APK 签名证书 SHA-256 与当前已安装应用完全一致，保证更新包出自同一把密钥。
- **新增权限**：`REQUEST_INSTALL_PACKAGES`（仅用于上述应用内安装）；新增 FileProvider 仅暴露 `cacheDir/updates` 一个目录给系统安装器。


## 2.22.46

新功能：**诊断摘要上传**，配套一套完整的同意与关闭机制。

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
