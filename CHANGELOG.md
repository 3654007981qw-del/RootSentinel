# Changelog

## 2.22.81 — 2026-11-17

**「切换到管理器」改为优先静默安装；GitHub CI 修复转绿。** 点「切换到管理器」时若管理器未装，RootSentinel 先尝试用 root 权限静默安装内置副本（无系统安装框、不用碰安装包，装完直接打开管理器）；没有 root 授权时才退回系统安装框。另：CI 自 v2.22.71 起因 Gradle 依赖清单未随 SukiSU 界面移植更新而持续失败，本版将 Gradle 依赖与手动管线对齐（补 core-splashscreen、protobuf、serialization/parcelize 插件、libsu、coil 等），main 已恢复绿色；发版仍走手动管线。


## 2.22.80 — 2026-11-17

**内置 vivo 版 KernelSU 管理器，设置里一键切换。** 本版起 RootSentinel 安装包内自带用户提供的 vivo 版 KernelSU 管理器（me.inkdye.vivoksu，vivo-196-g0760b7c5）。两种外壳的设置里都新增「切换到管理器」：管理器已装时直接打开；未装时从内置副本调起系统安装，装好再点一次即可打开。说明：管理器必须保持它自己的包名与 KernelSU 签名才能被内核认领，所以无法与检测器真正合并成同一个应用身份——它以独立应用落地，但随 RootSentinel 一起分发，不再需要单独找安装包。代价：安装包体积增加约 13.7 MB。


## 2.22.79 — 2026-11-17

**SukiSU（Ultra）外壳补上手动「检查更新」入口。** 此前 Ultra 外壳只有启动时自动检查（关于页的开关），没有手动检查：上游的手动入口是主页的更新卡片，那张卡在 RootSentinel 里不显示（它查的是 SukiSU 的版本）。现在关于页在「检查更新」开关下方多了一行手动检查，点按立即检查新版——有新版弹更新窗、已是最新或失败都给明确反馈，与 KernelSU 外壳设置里的手动检查走同一套更新器（GitHub 发行版、下载后三重校验不变）。


## 2.22.78 — 2026-11-17

**SukiSU 外壳主页底部卡换成 RootSentinel 自己的。** 「支持开发」不再指向 SukiSU 的捐赠页（Material 模式下甚至指向 KernelSU 作者的 Patreon），文案改为 RootSentinel 免费开源、去 GitHub 点 Star 或反馈问题，点击打开 RootSentinel 的 GitHub 仓库；「了解 SukiSU」改为「了解 RootSentinel」，同样指向项目主页（使用说明、更新日志、检测原理）。KernelSU 外壳主页没有这张卡，不受影响。

**关于页也换成 RootSentinel 自己的。** 「检查 CI 版本更新」开关删除（RS 没有 CI 构建更新通道，检查更新只跟 GitHub 发行版）；链接改为「在 GitHub 查看源码」（RootSentinel 仓库）与「在 Gitee 查看镜像」，不再指向 SukiSU 的仓库与 Telegram 频道；「主要贡献者」头像墙与 SUKISU 名字字标删除，换成 RS 的「开发与维护」致谢卡（界面移植自 KernelSU 与 SukiSU-Nova，致谢保留）。「检查更新」开关保留——它真实门控着 RS 自己的启动时更新检查。


## 2.22.77 — 2026-11-17

**SukiSU 外壳再清三处上游残留（真机截图反馈）。** ① 名字：外壳里的应用名由「SukiSU Nova」改为「RootSentinel」（主页标题、关于页、启动屏等所有引用该名称处一致）。② 主页状态卡：去掉红色的「点击安装」提示行，大字不再显示「未安装」，改为 RootSentinel 自己的检测状态——未扫时显示「待检测」，扫描中显示「检测中」，扫完显示评级词，下方小字是进度或结论摘要。③ 主页橙色横幅（「未检测到 SukiSU 内核…预览模式」）已移除。④ 设置里的「发送日志」换成「导出检测报告」：不再导出上游的内核日志包，改为导出 RootSentinel 最近一次的检测报告 JSON（与报表页的导出是同一动作）。KernelSU 外壳与检测引擎无变化。


## 2.22.76 — 2026-11-17

**SukiSU 外壳主页补上扫描入口，并清掉其余没改过来的地方。** SukiSU 的主页是内核管理器的主页（状态卡是「安装内核」），没有检测入口，切到这个外壳后无处开始扫描——只能绕到报表页点重新检测。本版在 SukiSU 主页的状态卡下方加了一张 RootSentinel 自己的「开始检测」卡（Miuix / Material 两种模式各一张，照既有的皮肤切换卡接缝方式接入）：点它直接开始扫描并进入扫描页，扫完自动回到外壳；卡片副标题实时显示扫描进度，扫完后显示最近一次的结论摘要。同时：主页状态卡的「安装 / 越狱」按钮和整卡点击跳转内核安装流程的入口已去掉（RootSentinel 不带内核守护程序，那条路走到底必然失败）；SukiSU 设置里补了一张 RootSentinel 隐私卡，「上传诊断摘要」「上传崩溃日志」两个开关在这个外壳里也能改了；扫描页、详情页等子页和声明 / 更新弹窗在 SukiSU 模式下改为跟随 SukiSU 自己的界面模式，不再跟 KSU 外壳的模式。KernelSU 外壳与检测引擎无变化。


## 2.22.75 — 2026-11-17

**SukiSU 外壳内的报表 / 历史两页换成 SukiSU 原生样式。** .74 之前这两页仍是照 KernelSU 外壳写的版本（用 KSU 外壳的模糊与组件、模式跟 KSU 外壳的设置走），放进 SukiSU 外壳里与主页、设置页不是一套。本版按 SukiSU-Nova 自己页面的写法重写这两页：Miuix 模式走 AppScaffold + SukiSU 自家的模糊顶栏与背景感知卡片（AppCard），Material 模式走 SukiSU 自家的 ExpressiveScaffold 与分段列表；明暗模式改读 SukiSU 自己的界面模式（与它的主页、设置页一致），标题走自定义文字系统。数据、文案、导出 / 重检 / 清空 / 点开详情等动作与原页完全一致；KernelSU 外壳内的报表 / 历史页不变。检测引擎无变化。


## 2.22.74 — 2026-11-16

**修复切换/进入 SukiSU 外壳闪退（根因版）。** .73 的自救机制送回了第一份真实堆栈：`NoClassDefFoundError: androidx.lifecycle.MutableLiveData` —— SukiSU 外壳记录当前页签时走 SavedStateHandle，它内部要创建一个 MutableLiveData，而打包依赖里一直缺 lifecycle-livedata-core 这个库（.71 时的 dex 审计曾把它列在缺失清单里，当时误判为无害引用）。本版补入 lifecycle-livedata-core 2.11.0 并加入依赖抓取种子，dex 审计复核其余缺失项均为可选探针类、无代码路径。原 .71/.72 的待发日志在 .73 首次启动时被新崩溃覆盖，未能回收，但本次堆栈与当时的崩溃路径一致（同为进入外壳时触发）。


## 2.22.73 — 2026-11-16

**崩溃自救（断循环）。** .72 真机反馈仍是打开即闪退：外壳设置已存盘，崩溃发生在首帧组合，进程当场死亡，.72 提前到 Activity 的上传线程也来不及把日志发完。本版把崩溃捕获与补传再提前到 Application 启动的第一行（任何界面出现之前）；同时加一次性自救：检测到「上次崩过且当前外壳是 SukiSU」时，本次启动强制用 KernelSU 外壳打开（设置本身不动），让日志在不崩溃的一次启动里送达，循环就此断开，之后恢复原外壳。

**包探测通道补全。** 管理器包名（Magisk / KernelSU / SukiSU / APatch 等）的可见性探测由 5 个接口扩到 9 个 Binder 通道 + 1 个文件系统 stat 通道：新增 resolveActivity 与 queryIntentActivities 两条意图解析路径、getResourcesForApplication 资源加载路径、getPackagesForUid 的 UID 反查，以及 /data/data/<pkg> 数据目录存在性旁证。核心一致性判定加入资源通道；出现「九个 Binder 通道全盲、数据目录却存在」时单独判为接口矛盾（针对性过滤证据）。包名表新增 com.sukisu.nova（SukiSU 改名后的现行包名）。


## 2.22.72 — 2026-11-16

**修复切换到 SukiSU Ultra 风格时闪退（加固版）。** .71 真机反馈：一切换到 SukiSU 外壳就闪退，且因为崩溃发生在应用首帧组合阶段，崩溃日志的补传（原本挂在界面组合后的效应里）永远等不到执行，服务器收不到这次堆栈。本版先做三处加固，把这类问题彻底兜住：

- **崩溃补传提前到 Activity.onCreate**（后台线程，按声明与开关状态放行）：无论界面能不能组合起来，上次崩溃的堆栈都会先传走，不再被崩溃循环饿死；
- **SukiSU 主页 ViewModel 的数据组装加兜底**：组装过程要走 libsu shell、SELinux 读法等接缝通道，任何一处抛错现在都变成主页的错误状态（可重试），不再掀翻整个应用；
- **SukiSU 设置与外壳状态读取加兜底**：设置刷新（几十个接缝支撑的读取）与外壳状态同步读取（在 ViewModel 构造期同步执行）抛错时分别降级为无操作与默认状态，不再闪退。

.71 那次的确切堆栈会在装上本版后首次启动时自动补传到服务器，定位后如有残余问题会在下一版根治。检测引擎、遥测与更新校验逻辑无变化。

## 2.22.71 — 2026-11-16

**SukiSU-Nova 界面原样移植（可切换的第二套完整 UI）。** .70 的 SukiSU Ultra 风格只是主页换皮；本版按 .60 移植 KernelSU 界面的同一规格，把 SukiSU-Nova（原 SukiSU-Ultra）管理器的**整个 UI 层原样搬入**：主题系统、主页（含沉浸式布局与自定义背景）、设置全套（含主题商店、配色、自定义文字、应用图标、背景裁剪、关于、工具、SuSFS、扩展、内置 Zygisk 等子页）、底部栏与液态玻璃组件、导航框架，共 300+ 个上游文件逐字 vendored，数据层以接缝对接 RootSentinel 自身：

- 入口：设置里原有的界面风格切换选「SukiSU Ultra 风格」后，整个应用外壳换成 SukiSU 的（底栏四页：主页 / 报告 / 历史 / 设置——报告与历史是 RootSentinel 自有页，底栏目的地按 .60 的做法做了标注式适配）；
- SukiSU 主页的数据由接缝 ViewModel 喂 RootSentinel 的真实状态（版本、内核、系统信息、SELinux 状态走分层通道读法），更新卡不出现——应用更新仍走 RootSentinel 自己的校验更新器；
- SukiSU 设置页底部追加一张 RootSentinel 的「界面皮肤」卡，可随时切回 KernelSU 风格；两套 UI 各自的设置互不干扰（SukiSU 设置存它自己的存储，RootSentinel 设置保持原样）；
- root 功能页（超级用户、模块、刷写等）在 RootSentinel 里没有对应能力，按 .60 的处理原则保留界面、由接缝数据层呈现未就绪状态，不伪造功能；
- 依赖栈新增 Coil、libsu、protobuf（payload 解析）等 SukiSU 同款依赖；手工流水线相应升级：Kotlin 编译目标 JVM 21、启用 parcelize 与 serialization 编译器插件、Kotlin/Java 混合编译（含 AIDL 生成的 zako 接口与 protoc 生成的 update_engine 类）。

检测引擎、遥测与更新校验逻辑无变化。

## 2.22.70 — 2026-11-16

**新增 SukiSU Ultra 界面风格（可切换）。** 设置中新增「SukiSU Ultra 风格」开关（在隐私与数据卡上方的新卡片里，Miuix / Material 两种模式下都有）。SukiSU 管理器（现已更名 SukiSU-Nova）的标志性主页是沉浸式布局，本版按其实现方式移植：

- 主页变为沉浸式：整页背景图铺满，状态卡与设备信息卡改为半透明（表面色 88% 不透明度，与 SukiSU 本体一致），顶栏透明，内容在背景之上滚动；
- 内置一张默认背景图（深蓝紫极光渐变，1152×2048）；
- 「主页背景图」可从系统照片选择器自选图片（复制进应用私有目录并降采样，不长期持有外部授权），也可一键恢复默认；
- 与原有 Miuix / Material 渲染模式正交：两种模式下都可开启，关闭即回到 KernelSU 经典主页。

实现说明：SukiSU 本体用 Coil 加载背景图，RootSentinel 的依赖栈没有 Coil，背景层用 BitmapFactory 直读（静态图无区别）；布局开关模型对应 SukiSU 设置里的 classic / immersive 主页布局。

## 2.22.69 — 2026-11-16

**更新弹窗闪退的真因修复。** .66 上传的崩溃日志（服务器 crashes id 4）证明 .66 的 heightIn 修复没有根治：更新弹窗里渲染发行说明的 MarkdownContent 组件**自带一层 verticalScroll**，嵌套在弹窗的滚动列中时会被以无限高测量，抛 IllegalStateException——外层列加 heightIn 治不了，因为滚动容器给子项的约束永远是无限高。本版改为在弹窗里直接使用无滚动包装的 GithubMarkdown（WebView 不是 Compose 滚动容器，可安全嵌套）。此前 .64 的模板缺失、.66 的高度约束都是同一弹窗的叠加问题，本次为根因。

**首页 SELinux 状态行改走分层通道。** 首页那一行此前在 MainActivity 里直读 enforce 文件（全应用第三处原始读法），在 selinuxfs 被厂商关闭的机型上恒显示未知，与 .67 起检测已判定通过的结论对不上。本版改走与检测相同的分层通道（文件 → 状态页 → 金丝雀推断），首页显示规范词，推断标记保留在设备页与检测详情中。

## 2.22.68 — 2026-11-16

**设备信息页的 SELinux 显示改走分层通道。** .67 的金丝雀推断已经让检测项 F.SELINUX_ENFORCE 在 vivo 上判定通过（真机报告未知数 36 → 34），但「设备信息」页那一行是独立的原始读法——直读 `/sys/fs/selinux/enforce` 文件，在 selinuxfs 被厂商关闭的机型上永远失败、恒显示 Unknown，与检测结论对不上。本版把该行改为与原生检测相同的通道顺序：enforce 文件 → status 状态页 → 金丝雀推断（stat 核对权限位后 access 被拒 ⇒ Enforcing，标注「（推断）」）。检测引擎本身无变化。

## 2.22.67 — 2026-11-16

**SELinux 状态：金丝雀推断（仿 KernelSU 的无 root 读法）。** .66 实测确认部分厂商（如 vivo）把整个 selinuxfs 对应用域全关：enforce 文件、status 状态页（read 与 mmap 都试过）、booleans、对象类目录全部拒绝，F.SELINUX_ENFORCE 只能报未知。KernelSU 管理器的 SELinuxChecker 给了思路：它跑系统 `getenforce` 命令，无 root 时若被 Permission denied 就判定 Enforcing——Permissive 模式下 SELinux 的拒绝只记日志、不拦截，能真正拦住操作就说明强制执行在生效。本版把这个推断引进原生层并把前提做严谨：先用 stat 核对节点权限位，确认「按 DAC 本应用可读、且属主不是自己」之后，访问仍被 EACCES/EPERM 拦下，才推断 Enforcing（结论带 via=canary 标记与推断说明）；DAC 本身不给读的机型不参与推断，继续报未知，不硬猜。F.SELINUX_POLICY 随 enforce 确认同步受益。

**I.AUXV 口径校准**：AT_SECURE 在 Android 上恒为 1 是常态（app_process 执行时 SELinux 域切换触发 secureexec，zygote 孵化的每个 App 都继承），旧口径把它记为低置信命中属于口径错误——.66 通道打通后该命中在真机报告里出现，本版修正为只记录向量、不再翻转结论。

## 2.22.66 — 2026-11-16

**「未知」专项：给读不到的检查项加更底层的通道。** 上一版真机报告 226 项里有 45 项落「无法判定」，逐项审计后确认其中一批不是权限死墙、只是通道选错了，本版逐项换通道（判定口径不变，不把未知硬说成正常）：

- **电池三项**（A.BATTERY_TEMP / A.POWER_SUPPLY / A.SENSORS）：sysfs 被 SELinux 拒时改走 BatteryManager 粘性广播（温度/状态/电压/电量，框架正式通道，无需权限）；原生层读不到时改为沉默，避免旧的「未知」压过框架层的结论。
- **I.AUXV**：`/proc/self/auxv` 读不到时改用 `getauxval()` 直接读进程内的 auxv（内核在 exec 时放入，无文件可拒）。
- **X.LOADAVG / X.CLOCK_SKEW**：`/proc/loadavg`、`/proc/uptime` 被拒时改用 `sysinfo()` 系统调用取同一批内核数据，来源记入证据向量。
- **G.BPF_SYSCALL**：补齐 errno 分类——内核非特权 BPF 门返回的 EACCES 与 EPERM 同义（拒绝即正常加固），此前未归类而落未知。
- **I.USER_CA**：改用 AndroidCAStore 权威枚举（用户 CA 别名以 `user:` 开头），取代原来的猜目录读法。
- **I.VPN**：补全网络扫描，VPN 挂着但不是默认网络时也能得出结论。
- **F.SE_BOOLS**：修复一个恒未知 bug——booleans 在 selinuxfs 里是目录，旧实现用读文件的方式打开它，必然失败；改用枚举。
- **F.SE_CLASSES / F.SYS_MODULE**：目录枚举被拦时改为逐个探测已知名字（标准对象类 / 已知 root 模块名），探测的确定答复（存在/不存在）才作数，含糊的拒绝仍保留未知。
- **F.LSM**：securityfs 被墙时用「自身安全上下文 + selinuxfs 状态页」推断 SELinux 是否激活（只能证在、不能证不在）。
- **A.BOOT_REASON**：主属性为空时依次问同义属性与 `/proc/bootconfig`。
- **D.TASK_NS / D.UNIX_ABSTRA**：分别改走 `/proc/self/ns/pid` 直读与 `/proc/self/net/unix` 规范路径，绕开被拦的枚举/路径。

**修复「检测到更新还是闪退」**（崩溃自动上传首次立功：真机堆栈直达服务器定位）：与 .64 修的模板缺失无关，是新的布局崩溃——Miuix 风格下 WindowDialog 内部自带一层滚动容器，更新弹窗（与声明弹窗）里又套了一层 verticalScroll，内层滚动被量到无限高度约束直接抛 IllegalStateException。四个弹窗的滚动列全部加上高度上限（heightIn 360dp），嵌套滚动合法化。

**SELinux 状态通道再纠正**：.65 给 F.SELINUX_ENFORCE 加的 `/sys/fs/selinux/status` 备用通道在真机上仍报未知——该状态页是为 mmap 设计的接口（libselinux 就是 open + mmap 读它），部分内核对 read() 不返回内容，.65 的普通读法拿不到数据。本版改走 mmap 原法，普通读保留为兜底；若厂商策略连 open 都拒（整棵 selinuxfs 被墙），则继续如实报未知。

**明确保留「未知」的项**：A.SU_PATH、A.MAGISK_TRACE、F.KSU_NODE、A.RECOVERY_TRACE、G.ATTEST_CHAIN 是「证据本身有歧义/信任门未过」的设计判定，不用弱通道硬改结论；其余内核节点（/proc/1、kallsyms、/proc/sys/kernel 等）是真权限墙，无特权下没有第二来源，继续如实报未知。

## 2.22.65 — 2026-11-16

**修「SELinux 状态检测不到」**（用户反馈）：F.SELINUX_ENFORCE 原来只读 `/sys/fs/selinux/enforce` 一个节点，部分厂商机型上该节点权限 0600、应用读不到就落「无法判定」。新增备用通道 `/sys/fs/selinux/status`（内核给用户态的状态页，libselinux 同款读法，通常世界可读），从状态页解析 Enforcing 标志；两个通道都读不到才落无法判定。F.SELINUX_POLICY 的判定同步受益（Enforcing 确认来源更广）。

**修「重新检测」没用**（用户反馈）：报告页的「重新检测」此前只切回首页、并不开扫。现在点击会回首页并立即开始新一轮扫描。

**换新图标**：深曜石藏青底 + 青紫渐变盾形雷达环 + 哨兵星核，全套自适应图标与各密度图已更新。

## 2.22.64 — 2026-11-16

**修复「检测到新版本就闪退」**（真机日志定位）：更新弹窗渲染发行说明时，markdown 组件要从应用资源里打开 `webview/template.html` 模板——v2.22.60 照抄 KernelSU 界面时只搬了代码、漏带了这批模板资源文件，文件不存在直接抛异常闪退。已从 KernelSU 原仓库补齐全部 11 个 webview 资源文件，构建管线也学会了打包应用自身的 assets（此前只收依赖库的）。

**新增崩溃日志自动上传**（用户要求）：应用崩溃时先把崩溃堆栈（异常类型、消息、完整堆栈、发生线程）加版本与机型存到本地，下次启动时经与诊断摘要相同的加密通道上传到服务器；只传本应用自己的堆栈，不抓系统日志。可在「设置 → 隐私与数据 → 上传崩溃日志」单独关闭（默认开），首次声明文案已同步说明。服务器端已同步升级并实测通过。

**构建工具链对齐 KernelSU**：Gradle 升至 9.7.1、AGP 升至 9.4.1，compileSdk 改用新写法（release 37 + minorApiLevel）——承接 .63 的 CI 修复，GitHub CI 的 Gradle 构建自 .59 变红的问题至此从根上对齐。发布包仍由手工管线产出，不受影响。

## 2.22.63 — 2026-11-16

**让预测性返回真正生效。** 此前实现只有 NavigationBackHandler 在手势结束时关页面，手势过程中页面纹丝不动，开了开关看起来也「没用」。现在手势进度实时驱动子页面容器：从屏幕边缘滑动时页面跟随手势横移并轻微缩放，松手取消时平滑弹回，完成时关闭页面（左右边缘方向自动区分）。

- 附带修复 Gradle/CI 构建：Kotlin 升至 2.4.20 后旧的 `kotlinOptions { jvmTarget = "17" }` 写法已被移除，CI 自 .59 起每次失败；改用 compilerOptions DSL。手工管线不经 Gradle，已发布安装包不受此问题影响。

## 2.22.62 — 2026-11-15

**修复 v2.22.61 两处闪退**（真机日志定位）：

1. **点「开始检测」闪退** — `IllegalArgumentException: Key "D.MEMFD_SOFT" was already used`。检测引擎会对同一检查项产出两条结果（如 D.MEMFD_SOFT），而报告/扫描实时列表用检查项 id 作 LazyColumn 的 key，id 重复即崩。五处列表 key 全部改为「序号 + id」组合（itemsIndexed），引擎产出重复项时列表也不再崩。
2. **切换界面风格闪退** — `NoClassDefFoundError: androidx/compose/material3/ripple/RippleKt`。material3 1.5.0-alpha28 把 ripple 拆成了独立构件（material3-ripple / material-ripple），只写在 Gradle 模块元数据里，手工管线的 POM 依赖抓取漏了这两个包，运行时 material3 自己的涟漪节点找不到类。已补齐两个构件（deps2 135 → 137），并对新 dex 做了一次「引用类 vs 定义类」全量审计，确认没有同类缺失。

## 2.22.61 — 2026-11-15

**修复 v2.22.60 打开即闪退。** 崩溃日志定位：`BootstrapMethodError` 出在 miuix 的 `PagerSwipeNode` / `TiltFeedback` 内部——手工管线用的 R8 8.6.2-dev（build-tools 自带）在重命名 kotlinx.coroutines 接口后，没能正确改写 miuix 预编译类里的 invokedynamic lambda 引导方法，第一帧手势节点启动即崩。

- 构建管线改用独立 R8 **8.13.6**（与 KernelSU 的 AGP 9 同代），并补齐 AGP 会自动应用的 **JAR 内嵌 consumer rules**（META-INF/proguard/*.pro，此前只收了 AAR 的 proguard.txt，kotlinx-coroutines 等库的规则被漏掉）。
- 应用代码与 v2.22.60 完全一致，仅版本号与构建管线变化；单测 42/42。

## 2.22.60 — 2026-11-15

界面按用户要求改为**直接照抄 KernelSU 管理器（tiann/KernelSU）的 UI 代码**，不再是仿写：主题系统、浮动液态玻璃底栏、页面骨架、设置页、调色板页、对话框框架、Markdown 渲染等均逐文件移植自 KernelSU 源码（保留其原包名 me.weishu.kernelsu）。随之整套依赖栈对齐 KernelSU：Kotlin 2.4.20、Compose BOM 2026.09.00（Compose 1.12.1）、material3 1.5.0-alpha28、miuix 0.9.4、material-kolor 5.0.1、okhttp 5.5.0、commonmark 0.30.0。检测引擎、评分、遥测、更新逻辑未动；单测 42/42。

- **底栏液态玻璃**：KernelSU 的 FloatingBottomBar（Backdrop 模糊 + lens 折射 + 内阴影）原样启用，设置里可开关浮动底栏与其模糊（默认开，按用户要求）。
- **照抄范围**：Theme/MiuixTheme/MaterialTheme、Colors、FloatingBottomBar + liquid 组件、BottomBar/SideRail、WarningCard、Dialog 框架、SegmentedList、调色板页、设置页（含外观/玻璃/手势等全部条目）、页面手势与动画工具。
- **接缝（仅数据源不同，界面代码不动）**：首页/报告/历史/扫描/详情/设备/关于页沿用 KernelSU 的 Scaffold/卡片骨架，内容换成 RootSentinel 的扫描结果与设备信息；设置页末尾追加 RootSentinel 自己的隐私卡（上传诊断摘要/自动导出/保留历史）。KernelSU 的内核专属功能（超级用户、模块、su 设置等）在 RootSentinel 无对应能力，入口按 KernelSU 自己的 KsuIsValid 机制不显示，不做假开关。
- **设置项全部真实生效**：主题模式（7 档 ColorMode）、键色/取色风格、页面缩放、预测性返回、滑动关闭、Pager 手势拦截、导航徽标、模块描述行数等均接 RootSentinel 的设置存储。
- **扫描进度如实显示**：引擎没有固定模块总数，进度只显示当前模块名 + 已检项数，不编造百分比。
- Release APK 10,089,827 B（dex 9.3 MB；体积增长来自完整的 KernelSU 界面栈）。

## 2.22.59 — 2026-11-15

界面按 KernelSU 管理器（tiann/KernelSU）全面重构：风格与页面逻辑照它，检测引擎、评分、遥测、更新逻辑未动。

- **双模式**：新增 UiMode（Miuix / Material），结构与 KernelSU 的 LocalUiMode 同构。设置 → 外观可切换：Miuix 模式为类 MIUI 卡片 + 浮动底栏；Material 模式为 Material 3 主题（动态取色）+ 标准导航栏。默认 Miuix。
- **深色模式三档**：跟随系统 / 浅色 / 深色，真实生效（v2.22.44 删掉的只存不用设置，这次连功能一起回来）。
- **首页照 KernelSU**：更新卡（有新版才出现）→ 警告卡（结论不完整时）→ 整卡语义色状态卡（大图标 + 状态词 + 分数摘要）→ 设备信息卡（图标信息行）→ 检测按钮。
- **报告页**：结论改为整卡语义色；维度得分补齐「挂钩与注入」一行（四维齐全）；完整性、风险分布、高风险项目均为分组卡片，点行进详情。
- **扫描页**：进度卡（模块名 + 阶段 + 进度条 + 已检测/命中）+ 实时发现列表，版式对齐 KernelSU 的刷入页。
- **设置页**：分组顺序照 KernelSU——外观 → 隐私与数据 → 版本与更新 → 更多；开关行/单选行/导航行统一组件。
- **导航逻辑**：主标签（首页/报告/历史/设置）底栏切换；详情/设备/关于为子页（顶栏返回 + 预测性返回手势）；扫描页无底栏。
- 删除液态玻璃全套（GlassEffect / LiquidGlassNav / LiquidGlassControls / GlassSwitch / Beauty / RsCard）：玻璃顶栏、玻璃底栏、仪表盘 Hero 全部退场，声明与更新弹窗的按钮改用新组件层。
- 实现说明：KernelSU 本体每页写 Miuix/Material 两份文件共享 UiState；本项目用一层模式感知组件（KsuUi.kt）收敛两种外观，页面只写一份、结构逐项对应。页面语义色经 RsPalette 注入，不再直读某个主题的 colorScheme。

## 2.22.58 — 2026-11-15

第九轮联合审计（客户端 + Cloudflare 服务端）修复版：12 项全部修复。

客户端：

- **分片下载严格核对 Content-Range**：收到 206 后逐项核对返回区间——起点必须与请求一致、终点不得越界、总长自洽，不合即弃用该响应换源重试。此前只取总长，镜像返回错区间会把错字节写入分片（最终靠哈希/签名兜底拦下，但下载白费）。
- **安装前校验 versionName**：下载的 APK 版本名必须与发行标签一致，与既有的 SHA-256、包名、versionCode、签名四项校验并列，元数据与安装包错位时拒绝安装。
- **Native JSON 的 UTF-8 安全处理**：序列化时严格校验多字节序列，非法字节（文件名、系统属性等外部数据可能携带）替换为 U+FFFD，避免非法 UTF-8 进入 JNI 字符串转换导致失败甚至进程终止。
- **硬件证明缺失字段降级**：Key Attestation 的 keymaster 安全级别字段缺失（或为异常值）时不再按硬件级处理——两个安全级别字段都必须明确为 TEE/StrongBox 才算硬件级。此前缺失默认放行。
- **评分去重平局稳定化**：同 ID 证据在评分权重完全相同时改用内容字典序裁决，两次扫描保留同一条、报告可复现（不影响分数）。
- **子进程交叉检查不再被首次失败永久关掉**：pipe/fork 等瞬时失败只影响当次扫描并给出 UNKNOWN，下次扫描重试；成功结果缓存并在后续扫描重放。

服务端（遥测接收端 v7-cf，已先行部署）：

- 报告落库失败返回 503，不再假成功（此前 D1 写失败仍回 200，报告静默丢失且监控失真）。
- 请求体先查 Content-Length 再流式限长读取，超限立即中止，不再完整缓冲后才拒绝。
- nonce 存储异常与重放分开：只有主键冲突算重放（401），数据库故障返回 503。
- 部署脚本失败关闭：子域配置失败不再以成功退出；路径改脚本相对路径；补本地自动化回归测试（含故障注入）。

文档：README 当前版本与 Native 构建说明已按实际更正。

## 2.22.57 — 2026-11-15

下载器健壮性修复：实测 .55 在国内网络下载 .56 时长时间卡 0%、取消按钮无反应。

- 取消即时生效：取消标志改 @Volatile（旧版普通 var 不保证跨线程可见，取消可能永远传不到下载线程）；新增在飞连接登记表，点取消立即断开全部连接，阻塞中的 connect/read 当场抛错返回，不再等 10~15 秒超时；测速阶段同样可取消。
- 黑洞源隔离：同一源在一次下载中连接级失败（连不上/超时/断连）累计 2 次即拉黑，不再让每个分片都去它身上白等；分片打开连接的超时从 10/15 秒收紧到 8 秒。
- 无进度看门狗：所有分片合计 25 秒零字节进账 → 自动中止并报错提示重试，不再无限期挂在 0%（网络只是慢但有字节在走时不会误杀）。
- 下载开始先回一次进度：弹窗立刻显示总大小，测速阶段不再是长时间 0% 加总量空白。

## 2.22.56

修复国内不挂梯子无法更新：检查竞速 + Gitee 发行版补齐真资产。

- **根因（发行侧）**：Gitee 镜像同步只同步代码与标签，从不同步 GitHub 的发行版附件——Gitee 上一直没有任何发行版，App 里的 Gitee 备用通道查到的永远是 404，等于不存在。国内用户不挂梯子时 GitHub 又经常连不上，于是检查失败、更新无门。本版起每个版本同步在 Gitee 建发行版并附 release APK（.55 一并补建），Gitee 通道第一次真正可用。
- **检查改双源竞速**：旧版串行先问 GitHub，国内网络下 GitHub 经常黑洞式挂起（不报错也不返回），要干等它超时完才轮到 Gitee。新版两源并行问，先答先用；Gitee 先答且 GitHub 2.5 秒内没跟上就直接按 Gitee 下结论。结论三态（有新版/已最新/检查失败）语义不变。
- **Gitee 命中时其下载地址排候选第一**：能答复检查就说明这条国内路径此刻是通的；配合 .55 的测速排序，国内下载会优先走 Gitee。展示与复制用的官方地址仍是 GitHub 直链。
- 校验与安装逻辑不变。

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
