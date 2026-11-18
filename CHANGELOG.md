# Changelog

更新说明从 2.22.82 起中英双语；2.22.81 及更早的条目已一并改写。
Release notes are bilingual (Chinese / English) from 2.22.82 on; older entries have been rewritten to match.

## 2.22.83 — 2026-11-18

**切换界面外壳时有过渡动画了。**
**Switching UI shells is animated now.**

- 之前在 KernelSU 风格和 SukiSU Ultra 风格之间切换是硬切，画面直接跳变。现在切换走一段过渡：新外壳 260ms 淡入，带一点横向漂移和轻微缩放，旧外壳 180ms 淡出，两个方向镜像对称。动画只包在外壳根节点上，检测引擎、设置存储、两个外壳各自保存的页面状态都没动，切换前后停留的位置和以前一致。
  Switching between the KernelSU style and the SukiSU Ultra style used to be a hard cut. It now runs a transition: the incoming shell fades in over 260 ms with a slight horizontal drift and scale, the outgoing shell fades out over 180 ms, mirrored in both directions. The animation only wraps the shell root — the detection engine, settings storage, and each shell's own saved page state are untouched, and you land exactly where you were.
- 证据影子评分扩了一个同源组：F.SELINUX_ENFORCE、F.SELINUX_POLICY、F.SELINUX_FILTERED 三项的证据都读自 selinuxfs 这棵树，内核级隐藏（如 KernelSU 的 selinux_hide）伪造这棵树时可以同时喂饱三路。此前 .82 的影子评分只分了 /data/adb 与 /debug_ramdisk 两组；本版把这三项归入 `selinuxfs` 组，影子口径里同组折算，不再当三份独立证据。正式评分和评级口径不变。
  Shadow evidence scoring gained a same-source group: F.SELINUX_ENFORCE, F.SELINUX_POLICY, and F.SELINUX_FILTERED all read their evidence from the selinuxfs tree, and a kernel-level hider forging that tree (KernelSU's selinux_hide, for one) can feed all three at once. The .82 shadow scoring only grouped /data/adb and /debug_ramdisk; these three now form a `selinuxfs` group and are discounted as one source in the shadow score instead of counting as three independent witnesses. The official score and grade are unchanged.

## 2.22.82 — 2026-11-18

**联合审查修复版：服务端防重放与落库原子性、原生层 errno 口径、跨进程探针加固、证据影子评分。**
**Joint-review fix release: server replay protection and write atomicity, native errno discipline, a hardened cross-process probe, and shadow evidence scoring.**

- 服务端升 v9-cf（已先行部署并实测）：nonce 的过期时间改绑信封时间戳——旧版按服务器当前时间存 nonce，客户端用接近未来 5 分钟的时间戳发信封时，nonce 先过期、信封还合法，同一份报告能重放第二次（已在线复现后堵上）；nonce 消费与报告/崩溃落库合并成一次原子提交，落库失败返回 503 且 nonce 未被消费，客户端可以原样重试，不再「服务器说失败、重试又撞重放」；补 nonces(exp) 索引与保留期自动清理（报告 90 天、崩溃 30 天）；按来源 IP 限流（60 次/分钟、300 次/天，超限 429，IP 只认 Cloudflare 边缘给的地址，不再采信可伪造的 X-Forwarded-For）；报告一致性校验（条目计数必须与结果列表长度一致）与路由白名单（只认 / 与 /crash，其余 POST 路径 404）；服务端日志只打摘要，不再整包输出载荷。
  Server upgraded to v9-cf (deployed and verified ahead of this release): nonce expiry is now bound to the envelope timestamp — the old code stored nonces against server time, so an envelope stamped near the future edge of the 5-minute window outlived its nonce and could be replayed a second time (reproduced live, then closed). Nonce consumption and the report/crash write are one atomic commit now; a failed write returns 503 with the nonce unconsumed, so the client can retry the same envelope instead of "server said failure, retry hits replay protection". Added the nonces(exp) index and automatic retention cleanup (reports 90 days, crashes 30 days); per-IP rate limiting (60/min, 300/day, 429 over the limit; the IP comes only from Cloudflare's edge header, not the spoofable X-Forwarded-For); report consistency validation (the item count must equal the results length) and a route whitelist (only / and /crash; other POST paths 404); server logs print summaries only, never full payloads.
- 服务端校验的一处实测纠正：初版一致性校验还拒收结果列表里的重复 ID，上线后抽查服务器里存的真机报告发现每份都带 6–7 个重复 ID——引擎对一个检查项产出多条结果是正常形态，去重是客户端评分环节的事。重复 ID 拒收已撤销并补了回归测试；计数一致性经真机报告核对恒成立，保留。
  One server check corrected by live evidence: the first cut of the consistency validation also rejected duplicate IDs in the results list. Inspecting genuine device reports stored on the server showed every one carries 6–7 duplicate IDs — the engine emitting several results per check item is normal, and dedup is the client scorer's job. The duplicate-ID rejection was reverted with a regression test; the count check, verified against real reports, stays.
- 原生层 errno 口径修正：accessState 的「其他错误」返回值此前被四处调用点当成「路径不存在」——Magisk 家族方案扫描、A.DATA_ADB、A.MODULES_DIR、F.OVER_HIDING 都因此可能把 EIO/ENOTDIR 这类错误说成「不存在」并判通过。本版全部改走带原始 errno 的探测：只有真 ENOENT 才算不存在，其他错误如实落「无法判定」。
  Native errno discipline: the "other error" return of accessState was being read as "path does not exist" at four call sites — the Magisk-family scheme scan, A.DATA_ADB, A.MODULES_DIR, and F.OVER_HIDING could all call an EIO/ENOTDIR-style error "missing" and pass. All four now go through the raw-errno probe: only a true ENOENT counts as missing; anything else honestly lands on "cannot determine".
- 跨进程探针加固：子进程管道读取加 2 秒超时（超时杀子进程并落「无法判定」，不再无限等），子进程退出状态现在会检查（异常退出不采信），成功结果的缓存加 60 秒有效期（不再一次成功管一辈子）；该项的通过与命中都标注为启发式——fork 出的子进程会继承父进程的注入状态，它本来就只是旁证，主通道是独立进程里的 Java 探针。
  Cross-process probe hardened: pipe reads from the child get a 2-second timeout (on timeout the child is killed and the item lands on "cannot determine" instead of waiting forever), the child's exit status is checked now (abnormal exits are not trusted), and the success cache has a 60-second TTL instead of lasting forever. The item's passes and hits are both marked heuristic — a forked child inherits the parent's injected state, so it was only ever corroboration; the primary channel is the Java probe in its own process.
- 原生检测模块执行顺序每轮随机：41 个检测步骤的执行顺序在每次扫描时打乱（种子记入报告备查），固定顺序下可预测的时序特征随之消失；汇总分析仍固定在最后。
  Native check order is shuffled per scan: the 41 check steps run in a randomized order each scan (the seed is recorded in the report), removing the predictable timing signature of a fixed order. The summary analysis still runs last.
- 崩溃上传跨进程防重：崩溃文件待传时会被主进程和 :probe 进程各传一次的风险堵上了——上传前用原子改名「认领」待传文件，认领成功的进程才传；认领残留超过 2 分钟自动回收，失败时释放回待传状态。崩溃消息在上传前做脱敏：去掉链接、绝对路径与邮箱样式子串，超长截断，异常类名与堆栈帧保留。
  Crash uploads deduplicated across processes: the pending crash file could previously be uploaded once by the main process and once by the :probe process. The uploader now claims the file by atomic rename before sending — only the process that wins the claim uploads; claims stale beyond 2 minutes are recovered, and a failed upload releases the file back to pending. Crash messages are sanitized before upload: links, absolute paths, and email-like substrings are stripped and overlong text truncated, while exception classes and stack frames are kept.
- 证据来源分组与影子评分（不影响正式结论）：给两个已知「同源多项」的证据组打了来源标记——/data/adb 组（A.DATA_ADB、A.MODULES_DIR）与 /debug_ramdisk 组（A.DEBUG_RAMDISK 及 Magisk/KSU/APatch/Shamiko 痕迹项）。评分器并行算一份影子分：同组命中只按「最高一项 + 其余的四分之一」计，避免一个底层事实被放大成多份独立证据。正式分数与定级完全不变；影子分与正式分有出入时，报告里会多一行注记，导出的 JSON 也带影子分数字段。先跑一个版本看真实数据，再决定要不要转正。
  Evidence source groups and shadow scoring (official verdicts untouched): two known "many items, one source" groups got source tags — the /data/adb group (A.DATA_ADB, A.MODULES_DIR) and the /debug_ramdisk group (A.DEBUG_RAMDISK plus the Magisk/KSU/APatch/Shamiko trace items). The scorer computes a parallel shadow score where a group's hits count as "the largest one + a quarter of the rest", so one underlying fact is not amplified into several independent pieces of evidence. The official score and grade are completely unchanged; when the shadow score diverges, the report gains a note line, and exported JSON carries the shadow fields too. It runs in shadow for one release so real data can judge whether it deserves promotion.
- 客户端共享密钥的结论写进了部署文档：内嵌共享密钥是已知并接受的设计边界（拦随手伪造、灌水与被动抓包，拦不住有耐心的逆向者），不引入设备注册体系，风险靠服务端的限流与一致性校验压。更新说明从本版起中英双语，2.22.81 及更早的历史条目已一并改写。
  The shared-key conclusion is now written into the deployment doc: the embedded shared key is a known, accepted design limit (it stops casual forgery, spam, and passive capture — not a patient reverse engineer), no device-registration system will be built, and the risk is held down by server-side rate limiting and consistency checks. Release notes are bilingual from this release on, and all entries back to 2.22.40 have been rewritten to match.
- 测试：Kotlin 单测 46/46、服务端测试 49/49 全绿。
  Tests: Kotlin unit tests 46/46, server tests 49/49, all green.

## 2.22.81 — 2026-11-17

**「切换到管理器」先走静默安装；CI 修绿了。**
**"Switch to manager" tries a silent install first; CI is green again.**

- 点「切换到管理器」时，如果管理器还没装，RootSentinel 先用 root 权限静默安装内置的那份副本，装完直接打开，没有系统安装框，也不用去碰安装包。没有 root 授权时才退回原来的系统安装框。
  When the manager is not installed yet, tapping "Switch to manager" now first installs the bundled copy silently with root — no system installer prompt, no APK file to handle — and opens it right away. Without a root grant it falls back to the system installer, as before.
- CI 从 2.22.71 起一直是红的：Gradle 的依赖清单没跟着 SukiSU 界面移植一起更新。这次把 Gradle 依赖和手工管线对齐（补上 core-splashscreen、protobuf、serialization / parcelize 插件、libsu、coil 等），main 恢复绿色。发布仍然走手工管线。
  CI had been red since 2.22.71 because the Gradle dependency list never got updated for the SukiSU UI port. It is now aligned with the manual pipeline (core-splashscreen, protobuf, the serialization/parcelize plugins, libsu, coil, and friends), and main is green again. Releases still come from the manual pipeline.

## 2.22.80 — 2026-11-17

**安装包里带了一份 vivo 版 KernelSU 管理器，设置里一键切换。**
**A vivo KernelSU manager build now ships inside the app; switch to it from Settings.**

- 本版起，RootSentinel 的安装包自带用户提供的 vivo 版 KernelSU 管理器（me.inkdye.vivoksu，vivo-196-g0760b7c5）。两种外壳的设置里都加了「切换到管理器」：已安装就直接打开；没装就从内置副本调起系统安装，装好后再点一次打开。
  Starting with this release, RootSentinel bundles the user-supplied vivo KernelSU manager build (me.inkdye.vivoksu, vivo-196-g0760b7c5). Both shells gained a "Switch to manager" entry in Settings: if the manager is installed it just opens; if not, the bundled copy is handed to the system installer, and one more tap opens it once installed.
- 说明一句：管理器必须保持自己的包名和 KernelSU 的签名才能被内核认领，没法和检测器合成同一个应用身份。它以独立应用落地，只是跟着 RootSentinel 一起分发，不用再单独找安装包。代价是安装包大约多了 13.7 MB。
  One honest note: the manager has to keep its own package name and the KernelSU signature or the kernel will not claim it, so it cannot literally merge into the detector's app identity. It lands as its own app, just distributed inside RootSentinel — no more hunting for a separate APK. The cost is about 13.7 MB of extra APK size.

## 2.22.79 — 2026-11-17

**SukiSU（Ultra）外壳补上手动「检查更新」。**
**The SukiSU (Ultra) shell finally gets a manual "Check for updates".**

- 之前 Ultra 外壳只有启动自动检查，没有手动入口。上游的手动入口是主页更新卡片，但那张卡在 RootSentinel 里不显示（它查的是 SukiSU 的版本）。现在关于页在「检查更新」开关下面多了一行手动检查，点一下立刻查：有新版弹更新窗，已是最新或检查失败都会给明确反馈。走的还是同一套更新器（GitHub 发行版 + 下载后三重校验），和 KernelSU 外壳里的手动检查一致。
  The Ultra shell only had the launch-time automatic check and no manual entry point. Upstream's manual entry is the home-screen update card, which never renders in RootSentinel (it checks SukiSU's version, not ours). The About page now has a manual check row under the "Check for updates" switch: tap it and it checks immediately — update dialog if there is a new version, a clear message if you are current or the check fails. It uses the same updater as the KernelSU shell (GitHub releases, triple verification after download).

## 2.22.78 — 2026-11-17

**SukiSU 外壳的主页底部卡和关于页换成 RootSentinel 自己的。**
**The SukiSU shell's home footer card and About page are RootSentinel's own now.**

- 「支持开发」不再跳 SukiSU 的捐赠页（Material 模式下甚至跳的是 KernelSU 作者的 Patreon）。文案改成 RootSentinel 免费开源、欢迎去 GitHub 点 Star 或反馈问题，点击打开 RootSentinel 的 GitHub 仓库；「了解 SukiSU」改成「了解 RootSentinel」，同样指向项目主页。KernelSU 外壳主页没有这张卡，不受影响。
  "Support development" no longer opens SukiSU's donation page (in Material mode it even pointed at the KernelSU author's Patreon). It now says RootSentinel is free and open source and links to RootSentinel's GitHub repo; "About SukiSU" became "About RootSentinel" and points at the project home too. The KernelSU shell never had this card and is unaffected.
- 关于页：删掉「检查 CI 版本更新」开关（RootSentinel 没有 CI 构建更新通道，检查更新只跟 GitHub 发行版）；链接改成「在 GitHub 查看源码」和「在 Gitee 查看镜像」，不再指向 SukiSU 的仓库和 Telegram；「主要贡献者」头像墙和 SUKISU 字标删掉，换成 RootSentinel 的「开发与维护」致谢卡（界面移植自 KernelSU 与 SukiSU-Nova，致谢保留）。「检查更新」开关保留——它真实管着 RootSentinel 自己的启动检查。
  About page: the "Check for CI updates" switch is gone (RootSentinel has no CI update channel; update checks only follow GitHub releases). Links now go to the RootSentinel repo on GitHub and its Gitee mirror instead of SukiSU's repo and Telegram. The contributor avatar wall and the SUKISU wordmark were replaced with RootSentinel's own credits card (the UI is ported from KernelSU and SukiSU-Nova, and the credit says so). The plain "Check for updates" switch stays — it really does gate RootSentinel's own launch-time check.

## 2.22.77 — 2026-11-17

**SukiSU 外壳按真机截图反馈再清三处上游残留。**
**Three more upstream leftovers cleaned from the SukiSU shell, per on-device screenshots.**

- 名字：外壳里的应用名从「SukiSU Nova」改成「RootSentinel」，主页标题、关于页、启动屏等所有用到这个名字的地方一致。
  Name: the app name inside the shell changed from "SukiSU Nova" to "RootSentinel" everywhere it appears — home title, About page, splash screen.
- 主页状态卡：去掉红色的「点击安装」提示行，大字不再显示「未安装」，改成 RootSentinel 自己的检测状态——没扫时是「待检测」，扫描中是「检测中」，扫完显示评级词，下方小字是进度或结论摘要。
  Home status card: the red "tap to install" line is gone, and the big text no longer says "Not installed". It shows RootSentinel's own scan state instead — "Not scanned yet", "Scanning", or the grade after a scan, with progress or a short verdict underneath.
- 主页那条橙色横幅（「未检测到 SukiSU 内核…预览模式」）移除了。
  The orange banner on the home page ("No SukiSU kernel detected… preview mode") was removed.
- 设置里的「发送日志」换成「导出检测报告」：不再导出上游的内核日志包，改成导出 RootSentinel 最近一次的检测报告 JSON，和报表页的导出是同一个动作。
  "Send logs" in Settings became "Export scan report": it no longer exports the upstream kernel log bundle, it exports RootSentinel's latest scan report JSON — the same action as the export on the Report page.

## 2.22.76 — 2026-11-17

**SukiSU 外壳主页补上扫描入口，顺带清掉其余没改过来的地方。**
**The SukiSU shell home page gets a scan entry, plus the remaining unconverted bits.**

- SukiSU 的主页是内核管理器的主页（状态卡是「安装内核」），压根没有检测入口，切到这个外壳就没地方开始扫描，只能绕去报表页点重新检测。本版在状态卡下方加了一张 RootSentinel 自己的「开始检测」卡（Miuix / Material 各一张，照既有的皮肤切换卡接缝接进去）：点它直接开扫并进扫描页，扫完自动回外壳；副标题实时显示进度，扫完显示最近一次的结论摘要。
  SukiSU's home page is a kernel manager's home page (its status card is about installing a kernel) and had no scan entry at all — in this shell the only way to scan was detouring through the Report page. There is now a RootSentinel "Start scan" card under the status card (one per Miuix / Material mode, wired through the same seam as the existing skin-switch card): tap it to scan and jump to the scan page, and it returns to the shell when done. The subtitle shows live progress, then the latest verdict summary.
- 主页状态卡的「安装 / 越狱」按钮和整卡点击跳内核安装流程的入口去掉（RootSentinel 不带内核守护程序，那条路走到底必然失败）。
  The status card's "Install / Jailbreak" button and the whole-card tap into the kernel install flow are gone (RootSentinel ships no kernel daemon; that path could only ever fail).
- SukiSU 设置里补了一张 RootSentinel 隐私卡，「上传诊断摘要」「上传崩溃日志」两个开关在这个外壳里也能改了。
  SukiSU Settings gained a RootSentinel privacy card, so the "Upload diagnostic summary" and "Upload crash logs" toggles can be changed in this shell too.
- 扫描页、详情页等子页和声明 / 更新弹窗在 SukiSU 模式下跟随 SukiSU 自己的界面模式，不再跟 KernelSU 外壳的模式走。KernelSU 外壳和检测引擎没动。
  Sub-pages (scan, detail) and the declaration / update dialogs now follow the SukiSU shell's own UI mode in SukiSU mode instead of the KernelSU shell's. The KernelSU shell and the detection engine are untouched.

## 2.22.75 — 2026-11-17

**SukiSU 外壳里的报表页和历史页换成 SukiSU 原生样式。**
**Report and History pages inside the SukiSU shell restyled the SukiSU way.**

- .74 之前，这两页还是照 KernelSU 外壳写的版本（用 KSU 外壳的模糊和组件、明暗跟 KSU 外壳的设置走），放进 SukiSU 外壳里和主页、设置页不是一套。本版按 SukiSU-Nova 自己页面的写法重写：Miuix 模式走 AppScaffold + SukiSU 自家的模糊顶栏和背景感知卡片（AppCard），Material 模式走 SukiSU 自家的 ExpressiveScaffold 和分段列表；明暗模式改读 SukiSU 自己的界面模式（和它的主页、设置页一致），标题走自定义文字系统。数据、文案、导出 / 重检 / 清空 / 点开详情这些动作和原来完全一致；KernelSU 外壳里的两页不变。检测引擎没动。
  Before .74 these two pages were still written for the KernelSU shell (its blur, its components, its dark-mode setting), which clashed with the SukiSU shell's home and settings pages. They are rewritten the way SukiSU-Nova writes its own pages: AppScaffold with SukiSU's blurred top bar and background-aware AppCards in Miuix mode, SukiSU's ExpressiveScaffold and segmented lists in Material mode. Dark mode now reads SukiSU's own UI mode (consistent with its home and settings), and titles use the custom text system. Data, wording, and actions (export / rescan / clear / open detail) are exactly as before; the KernelSU shell's pages are unchanged, and the detection engine is untouched.

## 2.22.74 — 2026-11-16

**修好切换 / 进入 SukiSU 外壳闪退（这次是根因）。**
**Fixed the SukiSU shell launch crash — the actual root cause this time.**

- .73 的自救机制把第一份真实堆栈送回来了：`NoClassDefFoundError: androidx.lifecycle.MutableLiveData`。SukiSU 外壳记页签时走 SavedStateHandle，它内部要建一个 MutableLiveData，而打包依赖里一直缺 lifecycle-livedata-core（.71 的 dex 审计把它列在缺失清单里，当时误判成无害引用）。本版补入 lifecycle-livedata-core 2.11.0 并加进依赖抓取种子；dex 审计复核其余缺失项都是可选探针类，没有代码路径会踩到。
  The .73 self-rescue delivered the first real stack trace: `NoClassDefFoundError: androidx.lifecycle.MutableLiveData`. The SukiSU shell records its current tab through SavedStateHandle, which internally creates a MutableLiveData — and lifecycle-livedata-core was never in the packaged dependencies. (The .71 dex audit had listed it as missing and I misjudged it as a harmless reference.) This release adds lifecycle-livedata-core 2.11.0 plus a dependency-fetch seed for it, and a dex audit re-check confirmed the remaining missing classes are all optional probes with no code path reaching them.

## 2.22.73 — 2026-11-16

**崩溃自救：先把死循环断掉。**
**Crash self-rescue: break the loop first.**

- .72 真机反馈还是打开就闪退：外壳设置已存盘，崩溃发生在首帧组合，进程当场死亡，.72 提前到 Activity 的上传线程也来不及把日志发完。本版把崩溃捕获和补传再提前到 Application 启动的第一行（任何界面出现之前）；另外加了一次性自救：检测到「上次崩过且当前外壳是 SukiSU」时，本次启动强制用 KernelSU 外壳打开（设置本身不动），让日志能在不崩溃的一次启动里送达，之后恢复原外壳。
  .72 still crashed on open on the device: the shell setting was already saved, the crash hit during first-frame composition, the process died on the spot, and even .72's earlier Activity-level upload thread never got to send the log. Crash capture and re-upload now happen on the very first line of Application startup, before any UI. There is also a one-time rescue: if the last run crashed while the SukiSU shell was active, this launch forces the KernelSU shell (the setting itself is not changed) so the log can get out on a launch that does not crash; after that, your chosen shell is back.
- 包探测通道补全：管理器包名（Magisk / KernelSU / SukiSU / APatch 等）的可见性探测从 5 个接口扩到 9 个 Binder 通道 + 1 个文件系统 stat 通道。新增 resolveActivity、queryIntentActivities 两条意图解析路径、getResourcesForApplication 资源加载路径、getPackagesForUid 的 UID 反查，还有 /data/data/<pkg> 数据目录存在性旁证。核心一致性判定加入资源通道；出现「九个 Binder 通道全盲、数据目录却存在」时单独判为接口矛盾（针对性过滤证据）。包名表新增 com.sukisu.nova（SukiSU 改名后的现行包名）。
  Package probing completed: visibility probes for manager packages (Magisk / KernelSU / SukiSU / APatch, …) grew from 5 interfaces to 9 Binder channels plus 1 filesystem stat channel — resolveActivity and queryIntentActivities intent resolution, the getResourcesForApplication resource path, getPackagesForUid reverse lookup, and /data/data/<pkg> existence as side evidence. The core consistency check now includes the resource channel, and "all nine Binder channels blind but the data directory exists" is judged as an interface contradiction (targeted filtering evidence) on its own. com.sukisu.nova (SukiSU's current package name after its rename) was added to the package table.

## 2.22.72 — 2026-11-16

**修切换到 SukiSU Ultra 风格闪退（加固版）。**
**Crash hardening for the SukiSU Ultra style switch.**

- .71 真机反馈：一切换到 SukiSU 外壳就闪退。崩溃发生在应用首帧组合阶段，原本挂在界面组合之后的崩溃日志补传永远等不到执行，服务器收不到堆栈。本版先做三处加固，把这类问题兜住：
  On the device, .71 crashed the moment you switched to the SukiSU shell. The crash happened during first-frame composition, so the crash-log upload that used to hang off a post-composition effect never ran and the server never saw the stack. This release hardens three places to close that hole:
- 崩溃补传提前到 Activity.onCreate（后台线程，按声明与开关状态放行）：界面能不能组合起来，上次崩溃的堆栈都会先传走，不再被崩溃循环饿死。
  Crash re-upload moved into Activity.onCreate (background thread, still gated by the declaration and the toggle): the previous crash's stack goes out first, whether or not the UI manages to compose, and can no longer be starved by a crash loop.
- SukiSU 主页 ViewModel 的数据组装加兜底：组装要走 libsu shell、SELinux 读法等接缝通道，任何一处抛错现在变成主页的错误状态（可重试），不再掀翻整个应用。
  The SukiSU home ViewModel's data assembly got a fallback: it goes through seam channels (libsu shell, SELinux reads, …), and any throw now becomes a retryable error state on the home page instead of taking the whole app down.
- SukiSU 设置与外壳状态读取加兜底：设置刷新和外壳状态同步读取抛错时，分别降级为无操作与默认状态，不再闪退。
  SukiSU settings refresh and shell-state reads got fallbacks too: a throw degrades to a no-op or the default state instead of crashing.
- .71 那次的确切堆栈会在装上本版后首次启动时自动补传到服务器。检测引擎、遥测与更新校验逻辑没动。
  .71's exact stack trace will auto-upload on first launch after installing this version. Detection engine, telemetry, and update verification are unchanged.

## 2.22.71 — 2026-11-16

**SukiSU-Nova 界面原样移植（可切换的第二套完整 UI）。**
**The SukiSU-Nova UI, ported verbatim — a second full shell you can switch to.**

- .70 的 SukiSU Ultra 风格只是主页换皮。本版按 .60 移植 KernelSU 界面的同一规格，把 SukiSU-Nova（原 SukiSU-Ultra）管理器的整个 UI 层原样搬入：主题系统、主页（沉浸式布局与自定义背景）、设置全套（主题商店、配色、自定义文字、应用图标、背景裁剪、关于、工具、SuSFS、扩展、内置 Zygisk 等子页）、底部栏与液态玻璃组件、导航框架，共 300+ 个上游文件逐字 vendored，数据层用接缝对接 RootSentinel 自身。
  .70's SukiSU Ultra style was only a home-page reskin. This release ports the whole SukiSU-Nova (formerly SukiSU-Ultra) manager UI layer verbatim, to the same standard as the .60 KernelSU port: theme system, home page (immersive layout, custom backgrounds), the full settings tree (theme store, colors, custom text, app icon, background cropping, About, tools, SuSFS, extensions, built-in Zygisk, …), bottom bar and liquid-glass components, and the navigation framework — 300+ upstream files vendored as-is, with the data layer seamed onto RootSentinel itself.
- 入口：设置里的界面风格切换选「SukiSU Ultra 风格」后，整个应用外壳换成 SukiSU 的（底栏四页：主页 / 报告 / 历史 / 设置；报告与历史是 RootSentinel 自有页，底栏目的地按 .60 的做法做了标注式适配）。
  Entry point: pick "SukiSU Ultra style" in the existing shell-style switch and the whole app shell becomes SukiSU's (four bottom tabs: Home / Report / History / Settings; Report and History are RootSentinel's own pages, with the tab destinations adapted the annotated way .60 established).
- SukiSU 主页的数据由接缝 ViewModel 喂 RootSentinel 的真实状态（版本、内核、系统信息、SELinux 走分层通道读法）；更新卡不出现——应用更新仍走 RootSentinel 自己的校验更新器。
  The SukiSU home page is fed RootSentinel's real state by a seam ViewModel (version, kernel, system info, SELinux via the layered channel reads). No update card appears — app updates still go through RootSentinel's own verifying updater.
- SukiSU 设置页底部追加一张 RootSentinel 的「界面皮肤」卡，随时切回 KernelSU 风格；两套 UI 的设置互不干扰。
  A RootSentinel "shell skin" card sits at the bottom of SukiSU Settings so you can switch back to the KernelSU style any time; the two UIs' settings do not interfere with each other.
- root 功能页（超级用户、模块、刷写等）在 RootSentinel 里没有对应能力，按 .60 的原则保留界面、由接缝数据层呈现未就绪状态，不伪造功能。
  Root feature pages (superuser, modules, flashing, …) have no RootSentinel counterpart; following the .60 rule, the UI stays and the seam data layer presents a not-ready state — no fake functionality.
- 依赖栈新增 Coil、libsu、protobuf（payload 解析）等 SukiSU 同款依赖；手工管线相应升级：Kotlin 编译目标 JVM 21、启用 parcelize 与 serialization 编译器插件、Kotlin/Java 混合编译（含 AIDL 生成的 zako 接口与 protoc 生成的 update_engine 类）。检测引擎、遥测与更新校验逻辑没动。
  The dependency stack gained SukiSU's own picks (Coil, libsu, protobuf for payload parsing, …). The manual pipeline was upgraded to match: Kotlin target JVM 21, parcelize and serialization compiler plugins on, mixed Kotlin/Java compilation (including the AIDL-generated zako interfaces and protoc-generated update_engine classes). Detection engine, telemetry, and update verification are unchanged.

## 2.22.70 — 2026-11-16

**新增 SukiSU Ultra 界面风格（可切换）。**
**New switchable SukiSU Ultra home style.**

- 设置里新增「SukiSU Ultra 风格」开关（在隐私与数据卡上方的新卡片里，Miuix / Material 两种模式都有）。SukiSU 管理器（现已更名 SukiSU-Nova）的标志性主页是沉浸式布局，本版按它的实现方式移植：
  Settings gained a "SukiSU Ultra style" switch (in a new card above Privacy & data, in both Miuix and Material modes). The SukiSU manager (now renamed SukiSU-Nova) is known for its immersive home layout; this release ports that approach:
- 主页变沉浸式：整页背景图铺满，状态卡与设备信息卡变半透明（表面色 88% 不透明度，与 SukiSU 本体一致），顶栏透明，内容在背景之上滚动。
  The home page goes immersive: a full-page background image, translucent status and device-info cards (surface color at 88% opacity, same as SukiSU itself), a transparent top bar, and content scrolling over the background.
- 内置一张默认背景图（深蓝紫极光渐变，1152×2048）。
  A default background ships in the app (deep blue-purple aurora gradient, 1152×2048).
- 「主页背景图」可以从系统照片选择器自选图片（复制进应用私有目录并降采样，不长期持有外部授权），也能一键恢复默认。
  "Home background" can be picked from the system photo picker (copied into the app's private storage and downsampled; no long-lived external permission is held), or reset to the default in one tap.
- 与原有 Miuix / Material 渲染模式正交：两种模式都能开，关掉就回到 KernelSU 经典主页。实现说明：SukiSU 本体用 Coil 加载背景图，RootSentinel 的依赖栈没有 Coil，背景层用 BitmapFactory 直读（静态图无区别）；布局开关对应 SukiSU 设置里的 classic / immersive 主页布局。
  It is orthogonal to the existing Miuix / Material rendering modes: it works in both, and turning it off returns to the classic KernelSU home. Implementation note: SukiSU loads the background with Coil; RootSentinel's stack has no Coil, so the background layer reads via BitmapFactory directly (no difference for a still image). The layout switch maps to SukiSU's classic / immersive home layout setting.

## 2.22.69 — 2026-11-16

**更新弹窗闪退找到真因了。**
**The update dialog crash — actual root cause found.**

- .66 上传的崩溃日志（服务器 crashes id 4）证明 .66 的 heightIn 修复没根治：更新弹窗里渲染发行说明的 MarkdownContent 组件自带一层 verticalScroll，嵌套在弹窗的滚动列里时会被以无限高测量，抛 IllegalStateException——外层列加 heightIn 治不了，因为滚动容器给子项的约束永远是无限高。本版改成在弹窗里直接用无滚动包装的 GithubMarkdown（WebView 不是 Compose 滚动容器，可以安全嵌套）。此前 .64 的模板缺失、.66 的高度约束都是同一个弹窗的叠加问题，这次是根因。
  The crash log uploaded by .66 (server crashes id 4) proved the .66 heightIn fix had not cured it: the MarkdownContent component that renders release notes in the update dialog carries its own verticalScroll, and nested inside the dialog's scrolling column it gets measured with infinite height and throws IllegalStateException. A heightIn on the outer column cannot help — a scroll container always gives its children infinite height constraints. The dialog now uses GithubMarkdown directly, without the scrolling wrapper (a WebView is not a Compose scroll container, so it nests safely). The .64 missing template and the .66 height constraint were stacked problems in the same dialog; this one is the root cause.
- 首页 SELinux 状态行改走分层通道：首页那行此前在 MainActivity 里直读 enforce 文件（全应用第三处原始读法），在 selinuxfs 被厂商关闭的机型上恒显示未知，和 .67 起检测已判定通过的结论对不上。本版改走与检测相同的分层通道（文件 → 状态页 → 金丝雀推断），首页显示规范词，推断标记保留在设备页与检测详情里。
  The home page's SELinux row now uses the layered channel: it used to read the enforce file directly in MainActivity (the third raw read site in the app) and showed "unknown" forever on devices whose vendor walls off selinuxfs, contradicting the PASS the detector has produced since .67. It now goes through the same layered channel as detection (file → status page → canary inference); the home row shows the standard term, and the inference markers stay on the Device page and in scan details.

## 2.22.68 — 2026-11-16

**设备信息页的 SELinux 显示改走分层通道。**
**The Device page's SELinux row now uses the layered channel.**

- .67 的金丝雀推断已让检测项 F.SELINUX_ENFORCE 在 vivo 上判定通过（真机报告未知数 36 → 34），但「设备信息」页那行是独立的原始读法——直读 /sys/fs/selinux/enforce，在 selinuxfs 被厂商关闭的机型上永远失败、恒显示 Unknown，和检测结论对不上。本版把该行改成与原生检测相同的通道顺序：enforce 文件 → status 状态页 → 金丝雀推断（stat 核对权限位后 access 被拒 ⇒ Enforcing，标注「（推断）」）。检测引擎本身没动。
  .67's canary inference already made F.SELINUX_ENFORCE pass on vivo (unknowns on the real-device report went 36 → 34), but the Device page's row was an independent raw read — straight at /sys/fs/selinux/enforce — which fails forever on devices whose vendor closes selinuxfs, showing Unknown while detection says otherwise. That row now follows the same channel order as the native detector: enforce file → status page → canary inference (stat verifies the permission bits, then an access denial means Enforcing, labeled "(inferred)"). The detection engine itself is unchanged.

## 2.22.67 — 2026-11-16

**SELinux 状态：金丝雀推断（学 KernelSU 的无 root 读法）。**
**SELinux state: canary inference, borrowed from KernelSU's rootless read.**

- .66 实测确认部分厂商（如 vivo）把整个 selinuxfs 对应用域全关：enforce 文件、status 状态页（read 和 mmap 都试过）、booleans、对象类目录全部拒绝，F.SELINUX_ENFORCE 只能报未知。KernelSU 管理器的 SELinuxChecker 给了思路：它跑系统 getenforce 命令，无 root 时被 Permission denied 就判定 Enforcing——Permissive 模式下 SELinux 的拒绝只记日志、不拦截，能真正拦住操作就说明强制执行在生效。本版把这个推断引进原生层并把前提做严谨：先用 stat 核对节点权限位，确认「按 DAC 本应用可读、属主不是自己」之后，访问仍被 EACCES/EPERM 拦下，才推断 Enforcing（结论带 via=canary 标记与推断说明）；DAC 本身不给读的机型不参与推断，继续报未知，不硬猜。F.SELINUX_POLICY 随 enforce 确认同步受益。
  .66 confirmed on a real device that some vendors (vivo, for one) wall off the whole selinuxfs from the app domain: the enforce file, the status page (both read and mmap), booleans, and the class directory are all denied, so F.SELINUX_ENFORCE could only report unknown. KernelSU's manager SELinuxChecker suggested the way out: it runs the system getenforce command, and a Permission denied without root means Enforcing — in Permissive mode SELinux only logs denials instead of enforcing them, so a real block means enforcement is live. This release brings that inference into the native layer with strict preconditions: stat first verifies the node's permission bits ("readable by this app per DAC, and not owned by us"), and only then does an EACCES/EPERM denial count as Enforcing (the verdict carries a via=canary marker and an explanation). Devices where DAC itself denies the read do not take part in the inference and keep reporting unknown — no guessing. F.SELINUX_POLICY benefits from the confirmed enforce state too.
- I.AUXV 口径校准：AT_SECURE 在 Android 上恒为 1 是常态（app_process 执行时 SELinux 域切换触发 secureexec，zygote 孵化的每个 App 都继承），旧口径把它记成低置信命中属于口径错误——.66 通道打通后该命中在真机报告里出现，本版修正为只记录向量、不再翻转结论。
  I.AUXV recalibrated: AT_SECURE being 1 is simply normal on Android (the SELinux domain transition when app_process executes triggers secureexec, and every zygote-forked app inherits it). The old rule counted it as a low-confidence hit, which was a calibration error — once .66 opened the channel, that hit showed up in real-device reports. It now records the vector only and no longer flips the verdict.

## 2.22.66 — 2026-11-16

**「未知」专项：给读不到的检查项换更底层的通道。**
**The "unknowns" pass: lower-level channels for checks that could not read.**

- 上一版真机报告 226 项里有 45 项落「无法判定」。逐项审计后确认，其中一批不是权限死墙，只是通道选错了。本版逐项换通道（判定口径不变，不把未知硬说成正常）：
  The previous real-device report had 45 of 226 items stuck at "cannot determine". An item-by-item audit showed a batch of them were not permission dead-ends at all — just the wrong channel. This release re-channels them one by one (verdict rules unchanged; unknowns are not dressed up as passes):
- 电池三项（A.BATTERY_TEMP / A.POWER_SUPPLY / A.SENSORS）：sysfs 被 SELinux 拒时改走 BatteryManager 粘性广播（温度 / 状态 / 电压 / 电量，框架正式通道，无需权限）；原生层读不到时改为沉默，避免旧的「未知」压过框架层的结论。
  Battery trio (A.BATTERY_TEMP / A.POWER_SUPPLY / A.SENSORS): when sysfs is denied by SELinux, read the BatteryManager sticky broadcast instead (temperature / status / voltage / level — an official framework channel, no permission needed). When the native layer cannot read, it now stays silent instead of letting a stale "unknown" override the framework layer's verdict.
- I.AUXV：/proc/self/auxv 读不到时改用 getauxval() 直读进程内的 auxv（内核在 exec 时放入，没有文件可拒）。
  I.AUXV: when /proc/self/auxv is unreadable, use getauxval() to read the in-process auxv directly (the kernel places it at exec; there is no file to deny).
- X.LOADAVG / X.CLOCK_SKEW：/proc/loadavg、/proc/uptime 被拒时改用 sysinfo() 系统调用取同一批内核数据，来源记入证据向量。
  X.LOADAVG / X.CLOCK_SKEW: when /proc/loadavg and /proc/uptime are denied, take the same kernel data via the sysinfo() syscall, with the source recorded in the evidence vectors.
- G.BPF_SYSCALL：补齐 errno 分类——内核非特权 BPF 门返回的 EACCES 与 EPERM 同义（拒绝即正常加固），此前未归类而落未知。
  G.BPF_SYSCALL: errno classification completed — the kernel's unprivileged-BPF gate returns EACCES with the same meaning as EPERM (denied = properly hardened); it was previously unclassified and fell to unknown.
- I.USER_CA：改用 AndroidCAStore 权威枚举（用户 CA 别名以 user: 开头），取代原来的猜目录读法。
  I.USER_CA: now enumerates the AndroidCAStore authoritatively (user CA aliases start with "user:") instead of guessing directory paths.
- I.VPN：补全网络扫描，VPN 挂着但不是默认网络时也能得出结论。
  I.VPN: network scan completed, so a VPN that is up but not the default network still yields a verdict.
- F.SE_BOOLS：修一个恒未知 bug——booleans 在 selinuxfs 里是目录，旧实现用读文件的方式打开它，必然失败；改用枚举。
  F.SE_BOOLS: fixed an always-unknown bug — booleans is a directory in selinuxfs, and the old code opened it as a file, which could never work. It enumerates now.
- F.SE_CLASSES / F.SYS_MODULE：目录枚举被拦时改为逐个探测已知名字（标准对象类 / 已知 root 模块名）；探测的确定答复（存在 / 不存在）才作数，含糊的拒绝仍保留未知。
  F.SE_CLASSES / F.SYS_MODULE: when directory enumeration is blocked, probe known names one by one (standard object classes / known root module names). Only definite answers (exists / does not exist) count; ambiguous denials stay unknown.
- F.LSM：securityfs 被墙时，用「自身安全上下文 + selinuxfs 状态页」推断 SELinux 是否激活（只能证在、不能证不在）。
  F.LSM: when securityfs is walled off, infer SELinux activation from "own security context + the selinuxfs status page" (can prove presence, never absence).
- A.BOOT_REASON：主属性为空时，依次问同义属性与 /proc/bootconfig。
  A.BOOT_REASON: when the main property is empty, fall through to synonymous properties and then /proc/bootconfig.
- D.TASK_NS / D.UNIX_ABSTRA：分别改走 /proc/self/ns/pid 直读与 /proc/self/net/unix 规范路径，绕开被拦的枚举 / 路径。
  D.TASK_NS / D.UNIX_ABSTRA: read /proc/self/ns/pid directly and use the canonical /proc/self/net/unix path, bypassing the blocked enumeration and path.
- 修「检测到更新还是闪退」（崩溃自动上传首次立功，真机堆栈直达服务器定位）：与 .64 修的模板缺失无关，是新的布局崩溃——Miuix 风格下 WindowDialog 内部自带一层滚动容器，更新弹窗（与声明弹窗）里又套了一层 verticalScroll，内层滚动被量到无限高度约束直接抛 IllegalStateException。四个弹窗的滚动列全部加上高度上限（heightIn 360dp），嵌套滚动合法化。
  Fixed "still crashes when an update is found" (the crash auto-upload earned its keep on its first outing — the real stack reached the server and pinpointed it): unrelated to the .64 template issue, this was a new layout crash. In Miuix style, WindowDialog already contains a scrolling container, and the update dialog (and the declaration dialog) nested another verticalScroll inside it; the inner scroller got measured with infinite height constraints and threw IllegalStateException. All four dialogs' scrolling columns got a height cap (heightIn 360dp), making the nested scrolling legal.
- SELinux 状态通道再纠正：.65 给 F.SELINUX_ENFORCE 加的 /sys/fs/selinux/status 备用通道在真机上仍报未知——该状态页是为 mmap 设计的接口（libselinux 就是 open + mmap 读它），部分内核对 read() 不返回内容，.65 的普通读法拿不到数据。本版改走 mmap 原法，普通读保留为兜底；若厂商策略连 open 都拒（整棵 selinuxfs 被墙），则继续如实报未知。
  SELinux channel corrected again: the /sys/fs/selinux/status fallback .65 added for F.SELINUX_ENFORCE still reported unknown on the device — that status page is an mmap-designed interface (libselinux opens and mmaps it), some kernels return nothing to read(), and .65's plain read got no data. It now uses the original mmap method with plain read as fallback; if the vendor policy denies even open (the whole selinuxfs walled), it keeps honestly reporting unknown.
- 明确保留「未知」的项：A.SU_PATH、A.MAGISK_TRACE、F.KSU_NODE、A.RECOVERY_TRACE、G.ATTEST_CHAIN 是「证据本身有歧义 / 信任门未过」的设计判定，不用弱通道硬改结论；其余内核节点（/proc/1、kallsyms、/proc/sys/kernel 等）是真权限墙，无特权下没有第二来源，继续如实报未知。
  Deliberately kept "unknown": A.SU_PATH, A.MAGISK_TRACE, F.KSU_NODE, A.RECOVERY_TRACE, and G.ATTEST_CHAIN are design verdicts ("the evidence itself is ambiguous / the trust gate has not passed") and will not be forced through weak channels. The remaining kernel nodes (/proc/1, kallsyms, /proc/sys/kernel, …) are genuine permission walls with no second source without privilege, and keep honestly reporting unknown.

## 2.22.65 — 2026-11-16

**修「SELinux 状态检测不到」，顺带修「重新检测」没用，换新图标。**
**Fixed "SELinux state not detected", a dead "Rescan", and a new icon.**

- F.SELINUX_ENFORCE 原来只读 /sys/fs/selinux/enforce 一个节点，部分厂商机型上该节点权限 0600、应用读不到就落「无法判定」。新增备用通道 /sys/fs/selinux/status（内核给用户态的状态页，libselinux 同款读法，通常世界可读），从状态页解析 Enforcing 标志；两个通道都读不到才落无法判定。F.SELINUX_POLICY 的判定同步受益。
  F.SELINUX_ENFORCE used to read only /sys/fs/selinux/enforce; on some vendor devices that node is mode 0600, the app cannot read it, and the item fell to "cannot determine". A fallback channel was added: /sys/fs/selinux/status (the kernel's user-space status page, read the libselinux way, usually world-readable), parsing the Enforcing flag from it. Only when both channels fail does the item report undetermined now, and F.SELINUX_POLICY benefits from the wider confirmation too.
- 报告页的「重新检测」此前只切回首页、并不开扫。现在点击会回首页并立即开始新一轮扫描。
  The Report page's "Rescan" used to just switch back to the home tab without scanning. It now returns home and immediately starts a new scan.
- 换新图标：深曜石藏青底 + 青紫渐变盾形雷达环 + 哨兵星核，全套自适应图标与各密度图已更新。
  New icon: deep obsidian-teal base, a cyan-to-violet gradient shield/radar ring, and a sentinel star core. The full adaptive icon set and all density assets were updated.

## 2.22.64 — 2026-11-16

**修「检测到新版本就闪退」；新增崩溃日志自动上传；构建工具链对齐 KernelSU。**
**Fixed the "crashes when an update is found" bug; crash logs auto-upload now; build toolchain aligned with KernelSU.**

- 更新弹窗渲染发行说明时，markdown 组件要从应用资源里打开 webview/template.html 模板——2.22.60 照抄 KernelSU 界面时只搬了代码、漏带了这批模板资源，文件不存在直接抛异常闪退（真机日志定位）。已从 KernelSU 原仓库补齐全部 11 个 webview 资源文件，构建管线也学会了打包应用自身的 assets（此前只收依赖库的）。
  When the update dialog renders release notes, the markdown component opens a webview/template.html template from app resources — the 2.22.60 KernelSU UI port brought the code but not those template assets, and the missing file threw and crashed (found via an on-device log). All 11 webview asset files are now restored from the upstream KernelSU repo, and the build pipeline learned to package the app's own assets (it previously only collected library assets).
- 新增崩溃日志自动上传（用户要求）：应用崩溃时先把崩溃堆栈（异常类型、消息、完整堆栈、发生线程）加版本与机型存到本地，下次启动时经与诊断摘要相同的加密通道上传到服务器；只传本应用自己的堆栈，不抓系统日志。可在「设置 → 隐私与数据 → 上传崩溃日志」单独关闭（默认开），首次声明文案已同步说明。服务器端已同步升级并实测通过。
  Crash logs auto-upload now (user request): on a crash, the stack (exception type, message, full trace, thread) plus app version and device model are stored locally, then uploaded at next launch through the same encrypted channel as diagnostic summaries. Only this app's own stack is sent — no system log scraping. It can be turned off separately under Settings → Privacy & data → Upload crash logs (on by default), the first-launch declaration text covers it, and the server side was upgraded and verified live.
- 构建工具链对齐 KernelSU：Gradle 升至 9.7.1、AGP 升至 9.4.1，compileSdk 改用新写法（release 37 + minorApiLevel）——承接 .63 的 CI 修复，GitHub CI 的 Gradle 构建自 .59 变红的问题至此从根上对齐。发布包仍由手工管线产出，不受影响。
  Build toolchain aligned with KernelSU: Gradle 9.7.1, AGP 9.4.1, and compileSdk in the new form (release 37 + minorApiLevel). Together with the .63 fix, this addresses the root of the GitHub CI Gradle build being red since .59. Release packages still come from the manual pipeline and are unaffected.

## 2.22.63 — 2026-11-16

**让预测性返回真正生效。**
**Predictive back that actually moves.**

- 此前实现只有 NavigationBackHandler 在手势结束时关页面，手势过程中页面纹丝不动，开了开关看起来也「没用」。现在手势进度实时驱动子页面容器：从屏幕边缘滑动时页面跟随手势横移并轻微缩放，松手取消时平滑弹回，完成时关闭页面（左右边缘方向自动区分）。
  The previous implementation only closed the page when the gesture ended (NavigationBackHandler); during the gesture the page did not move at all, so the switch looked broken. Gesture progress now drives the child-page container in real time: swiping from the screen edge moves the page with the gesture with a slight scale, releasing mid-gesture springs it back smoothly, and completing the gesture closes the page (left/right edge direction detected automatically).
- 附带修复 Gradle/CI 构建：Kotlin 升至 2.4.20 后，旧的 kotlinOptions { jvmTarget = "17" } 写法已被移除，CI 自 .59 起每次失败；改用 compilerOptions DSL。手工管线不经 Gradle，已发布安装包不受此问题影响。
  Also fixed the Gradle/CI build: after the Kotlin 2.4.20 upgrade the old kotlinOptions { jvmTarget = "17" } syntax no longer exists, and CI had failed on every run since .59; it now uses the compilerOptions DSL. The manual pipeline does not go through Gradle, so published packages were never affected.

## 2.22.62 — 2026-11-15

**修 2.22.61 的两处闪退（真机日志定位）。**
**Fixed two crashes from 2.22.61 (found via on-device logs).**

- 点「开始检测」闪退 — IllegalArgumentException: Key "D.MEMFD_SOFT" was already used。检测引擎会对同一检查项产出两条结果，而报告 / 扫描实时列表用检查项 id 作 LazyColumn 的 key，id 重复即崩。五处列表 key 全部改成「序号 + id」组合（itemsIndexed），引擎产出重复项时列表也不再崩。
  Crash on tapping "Start scan" — IllegalArgumentException: Key "D.MEMFD_SOFT" was already used. The engine can emit two results for the same check item, and the report / live-scan lists used the item id as the LazyColumn key, so a duplicate id crashed. All five list keys now use an "index + id" combination (itemsIndexed); duplicate items from the engine can no longer crash the lists.
- 切换界面风格闪退 — NoClassDefFoundError: androidx/compose/material3/ripple/RippleKt。material3 1.5.0-alpha28 把 ripple 拆成了独立构件（material3-ripple / material-ripple），只写在 Gradle 模块元数据里，手工管线的 POM 依赖抓取漏了这两个包。已补齐两个构件（deps2 135 → 137），并对新 dex 做了一次「引用类 vs 定义类」全量审计，确认没有同类缺失。
  Crash when switching UI style — NoClassDefFoundError: androidx/compose/material3/ripple/RippleKt. material3 1.5.0-alpha28 split ripple into separate artifacts (material3-ripple / material-ripple) declared only in Gradle module metadata, and the manual pipeline's POM-based dependency fetch missed both. Both artifacts are now included (deps2 135 → 137), and a full "referenced vs defined classes" audit of the new dex confirmed no other gap of the same kind.

## 2.22.61 — 2026-11-15

**修 2.22.60 打开即闪退。**
**Fixed 2.22.60 crashing on open.**

- 崩溃日志定位：BootstrapMethodError 出在 miuix 的 PagerSwipeNode / TiltFeedback 内部——手工管线用的 R8 8.6.2-dev（build-tools 自带）在重命名 kotlinx.coroutines 接口后，没能正确改写 miuix 预编译类里的 invokedynamic lambda 引导方法，第一帧手势节点启动即崩。
  The crash log pointed at a BootstrapMethodError inside miuix's PagerSwipeNode / TiltFeedback: the pipeline's R8 8.6.2-dev (bundled with build-tools), after renaming kotlinx.coroutines interfaces, failed to rewrite the invokedynamic lambda bootstrap methods in miuix's precompiled classes, so the first frame's gesture node crashed on startup.
- 构建管线改用独立 R8 8.13.6（与 KernelSU 的 AGP 9 同代），并补齐 AGP 会自动应用的 JAR 内嵌 consumer rules（META-INF/proguard/*.pro，此前只收了 AAR 的 proguard.txt，kotlinx-coroutines 等库的规则被漏掉）。
  The pipeline now uses standalone R8 8.13.6 (same generation as KernelSU's AGP 9) and also harvests the JAR-embedded consumer rules AGP applies automatically (META-INF/proguard/*.pro — previously only AAR proguard.txt files were collected, so rules from libraries like kotlinx-coroutines were missed).
- 应用代码与 2.22.60 完全一致，仅版本号与构建管线变化；单测 42/42。
  App code is identical to 2.22.60; only the version number and the build pipeline changed. Unit tests 42/42.

## 2.22.60 — 2026-11-15

**界面改为直接照抄 KernelSU 管理器（tiann/KernelSU）的 UI 代码。**
**The UI is now a verbatim port of the KernelSU manager (tiann/KernelSU), not an imitation.**

- 按用户要求，主题系统、浮动液态玻璃底栏、页面骨架、设置页、调色板页、对话框框架、Markdown 渲染等均逐文件移植自 KernelSU 源码（保留其原包名 me.weishu.kernelsu）。整套依赖栈随之对齐 KernelSU：Kotlin 2.4.20、Compose BOM 2026.09.00（Compose 1.12.1）、material3 1.5.0-alpha28、miuix 0.9.4、material-kolor 5.0.1、okhttp 5.5.0、commonmark 0.30.0。检测引擎、评分、遥测、更新逻辑未动；单测 42/42。
  Per the user's request, the theme system, floating liquid-glass bottom bar, page scaffolds, settings page, palette page, dialog framework, and Markdown rendering are all ported file-by-file from the KernelSU source (keeping its original package name me.weishu.kernelsu). The whole dependency stack was aligned with KernelSU to match: Kotlin 2.4.20, Compose BOM 2026.09.00 (Compose 1.12.1), material3 1.5.0-alpha28, miuix 0.9.4, material-kolor 5.0.1, okhttp 5.5.0, commonmark 0.30.0. Detection engine, scoring, telemetry, and update logic untouched; unit tests 42/42.
- 底栏液态玻璃：KernelSU 的 FloatingBottomBar（Backdrop 模糊 + lens 折射 + 内阴影）原样启用，设置里可开关浮动底栏与其模糊（默认开，按用户要求）。
  Bottom-bar liquid glass: KernelSU's FloatingBottomBar (Backdrop blur + lens refraction + inner shadow) is enabled as-is; Settings can toggle the floating bottom bar and its blur (on by default, per the user's request).
- 接缝（仅数据源不同，界面代码不动）：首页 / 报告 / 历史 / 扫描 / 详情 / 设备 / 关于页沿用 KernelSU 的 Scaffold / 卡片骨架，内容换成 RootSentinel 的扫描结果与设备信息；设置页末尾追加 RootSentinel 自己的隐私卡（上传诊断摘要 / 自动导出 / 保留历史）。KernelSU 的内核专属功能（超级用户、模块、su 设置等）在 RootSentinel 无对应能力，入口按 KernelSU 自己的 KsuIsValid 机制不显示，不做假开关。
  Seams (only the data source differs; UI code untouched): Home / Report / History / Scan / Detail / Device / About keep KernelSU's Scaffold/card skeletons with RootSentinel's scan results and device info as content; a RootSentinel privacy card (upload diagnostic summary / auto-export / keep history) is appended at the end of Settings. KernelSU's kernel-only features (superuser, modules, su settings, …) have no RootSentinel counterpart; their entries stay hidden via KernelSU's own KsuIsValid mechanism — no fake switches.
- 设置项全部真实生效：主题模式（7 档 ColorMode）、键色 / 取色风格、页面缩放、预测性返回、滑动关闭、Pager 手势拦截、导航徽标、模块描述行数等均接 RootSentinel 的设置存储。扫描进度如实显示：引擎没有固定模块总数，进度只显示当前模块名 + 已检项数，不编造百分比。Release APK 10,089,827 B（dex 9.3 MB；体积增长来自完整的 KernelSU 界面栈）。
  Every setting genuinely works: theme mode (7 ColorModes), key color / color-picking style, page scale, predictive back, swipe-to-close, pager gesture interception, navigation badges, module description lines, and more are all wired to RootSentinel's settings store. Scan progress is honest: the engine has no fixed module total, so progress shows the current module name plus items checked — no invented percentages. Release APK 10,089,827 B (dex 9.3 MB; the growth is the full KernelSU UI stack).

## 2.22.59 — 2026-11-15

**界面按 KernelSU 管理器全面重构（风格与页面逻辑照它，引擎不动）。**
**UI rebuilt after the KernelSU manager (its style and page logic; engine untouched).**

- 双模式：新增 UiMode（Miuix / Material），结构与 KernelSU 的 LocalUiMode 同构。设置 → 外观可切换：Miuix 模式为类 MIUI 卡片 + 浮动底栏；Material 模式为 Material 3 主题（动态取色）+ 标准导航栏。默认 Miuix。
  Dual mode: a new UiMode (Miuix / Material), structured like KernelSU's LocalUiMode. Switchable under Settings → Appearance: Miuix mode is MIUI-like cards plus a floating bottom bar; Material mode is a Material 3 theme (dynamic color) with a standard navigation bar. Miuix is the default.
- 深色模式三档：跟随系统 / 浅色 / 深色，真实生效（2.22.44 删掉的只存不用设置，这次连功能一起回来）。
  Dark mode with three options — follow system / light / dark — and it actually works (the stored-but-unused setting deleted in 2.22.44 returns together with the feature this time).
- 首页照 KernelSU：更新卡（有新版才出现）→ 警告卡（结论不完整时）→ 整卡语义色状态卡（大图标 + 状态词 + 分数摘要）→ 设备信息卡（图标信息行）→ 检测按钮。
  Home page after KernelSU: update card (only when a new version exists) → warning card (when the verdict is incomplete) → a full-card semantic-color status card (big icon + status word + score summary) → device info card (icon info rows) → scan button.
- 报告页：结论改为整卡语义色；维度得分补齐「挂钩与注入」一行（四维齐全）；完整性、风险分布、高风险项目均为分组卡片，点行进详情。
  Report page: the verdict is a full semantic-color card; dimension scores gained the missing "Hooks & injection" row (all four dimensions present); integrity, risk distribution, and high-risk items are grouped cards, tap a row for details.
- 扫描页：进度卡（模块名 + 阶段 + 进度条 + 已检测 / 命中）+ 实时发现列表，版式对齐 KernelSU 的刷入页。
  Scan page: a progress card (module name + phase + progress bar + checked / hits) plus a live findings list, laid out like KernelSU's flash page.
- 设置页：分组顺序照 KernelSU——外观 → 隐私与数据 → 版本与更新 → 更多；开关行 / 单选行 / 导航行统一组件。导航逻辑：主标签（首页 / 报告 / 历史 / 设置）底栏切换；详情 / 设备 / 关于为子页（顶栏返回 + 预测性返回手势）；扫描页无底栏。
  Settings page: grouping order follows KernelSU — Appearance → Privacy & data → Version & updates → More; switch rows / radio rows / navigation rows share unified components. Navigation: main tabs (Home / Report / History / Settings) switch in the bottom bar; Detail / Device / About are child pages (top-bar back + predictive back gesture); the scan page has no bottom bar.
- 删除液态玻璃全套（GlassEffect / LiquidGlassNav / LiquidGlassControls / GlassSwitch / Beauty / RsCard）：玻璃顶栏、玻璃底栏、仪表盘 Hero 全部退场，声明与更新弹窗的按钮改用新组件层。实现说明：KernelSU 本体每页写 Miuix/Material 两份文件共享 UiState；本项目用一层模式感知组件（KsuUi.kt）收敛两种外观，页面只写一份、结构逐项对应。页面语义色经 RsPalette 注入，不再直读某个主题的 colorScheme。
  The whole liquid-glass set is deleted (GlassEffect / LiquidGlassNav / LiquidGlassControls / GlassSwitch / Beauty / RsCard): glass top bar, glass bottom bar, and the dashboard hero all retire; the declaration and update dialogs' buttons moved to the new component layer. Implementation note: KernelSU writes each page twice (Miuix and Material files sharing a UiState); this project converges both looks into one mode-aware component layer (KsuUi.kt), so each page is written once with a structure that maps item-by-item. Page semantic colors are injected via RsPalette instead of reading a theme's colorScheme directly.

## 2.22.58 — 2026-11-15

**第九轮联合审计（客户端 + Cloudflare 服务端）修复版：12 项全部修复。**
**Round-9 joint audit fixes (client + Cloudflare server): all 12 items fixed.**

- 分片下载严格核对 Content-Range：收到 206 后逐项核对返回区间——起点必须与请求一致、终点不得越界、总长自洽，不合即弃用该响应换源重试。此前只取总长，镜像返回错区间会把错字节写入分片（最终靠哈希 / 签名兜底拦下，但下载白费）。
  Chunked downloads now strictly verify Content-Range: on a 206, the returned range is checked item by item — start must match the request, end must not overrun, total must be consistent — otherwise the response is discarded and another source is tried. Previously only the total was read, so a mirror returning a wrong range wrote wrong bytes into a chunk (the hash/signature checks caught it in the end, but the download was wasted).
- 安装前校验 versionName：下载的 APK 版本名必须与发行标签一致，与既有的 SHA-256、包名、versionCode、签名四项校验并列，元数据与安装包错位时拒绝安装。
  versionName is verified before install: the downloaded APK's version name must match the release tag, alongside the existing SHA-256, package name, versionCode, and signature checks. Metadata/package mismatches are refused.
- Native JSON 的 UTF-8 安全处理：序列化时严格校验多字节序列，非法字节（文件名、系统属性等外部数据可能携带）替换为 U+FFFD，避免非法 UTF-8 进入 JNI 字符串转换导致失败甚至进程终止。
  Native JSON is now UTF-8-safe: serialization strictly validates multibyte sequences and replaces invalid bytes (which external data like file names and system properties can carry) with U+FFFD, so invalid UTF-8 can no longer fail — or kill the process in — JNI string conversion.
- 硬件证明缺失字段降级：Key Attestation 的 keymaster 安全级别字段缺失（或为异常值）时不再按硬件级处理——两个安全级别字段都必须明确为 TEE/StrongBox 才算硬件级。此前缺失默认放行。
  Hardware attestation downgrades on missing fields: if the keymaster security level is missing (or abnormal), it is no longer treated as hardware-level — both security-level fields must explicitly say TEE/StrongBox. Missing used to default to pass.
- 评分去重平局稳定化：同 ID 证据在评分权重完全相同时改用内容字典序裁决，两次扫描保留同一条、报告可复现（不影响分数）。
  Scoring dedup ties are stable now: when same-ID evidence ties exactly on scoring weight, content lexicographic order decides, so two scans keep the same entry and reports are reproducible (scores unaffected).
- 子进程交叉检查不再被首次失败永久关掉：pipe/fork 等瞬时失败只影响当次扫描并给出 UNKNOWN，下次扫描重试；成功结果缓存并在后续扫描重放。
  The child-process cross-check is no longer permanently disabled by a first failure: transient pipe/fork failures affect only that scan (with an UNKNOWN), the next scan retries, and successful results are cached and replayed on later scans.
- 服务端（遥测接收端 v7-cf，已先行部署）：报告落库失败返回 503，不再假成功；请求体先查 Content-Length 再流式限长读取，超限立即中止；nonce 存储异常与重放分开——只有主键冲突算重放（401），数据库故障返回 503；部署脚本失败关闭，补本地自动化回归测试（含故障注入）。
  Server (telemetry receiver v7-cf, deployed ahead): a failed report write now returns 503 instead of fake success; request bodies are checked by Content-Length first, then read with a streaming cap that aborts on overflow; nonce storage errors are separated from replays — only a primary-key conflict counts as replay (401), database failures return 503; the deploy script is fail-closed and gained a local automated regression suite (including fault injection).
- 文档：README 当前版本与 Native 构建说明已按实际更正。
  Docs: the README's current version and native build instructions were corrected to match reality.

## 2.22.57 — 2026-11-15

**下载器健壮性修复：实测 .55 在国内网络下载 .56 时长时间卡 0%、取消按钮无反应。**
**Downloader robustness: .55 was seen stuck at 0% for ages downloading .56 on a domestic network, with Cancel not responding.**

- 取消即时生效：取消标志改 @Volatile（旧版普通 var 不保证跨线程可见，取消可能永远传不到下载线程）；新增在飞连接登记表，点取消立即断开全部连接，阻塞中的 connect/read 当场抛错返回，不再等 10~15 秒超时；测速阶段同样可取消。
  Cancellation is immediate now: the cancel flag became @Volatile (a plain var does not guarantee cross-thread visibility, so a cancel might never reach the download thread), and an in-flight connection registry was added — tapping Cancel disconnects everything at once, blocked connect/read calls throw and return on the spot instead of waiting out a 10–15 s timeout. The speed-probe phase is cancellable too.
- 黑洞源隔离：同一源在一次下载中连接级失败（连不上/超时/断连）累计 2 次即拉黑，不再让每个分片都去它身上白等；分片打开连接的超时从 10/15 秒收紧到 8 秒。
  Black-hole source isolation: a source that fails at connection level (unreachable / timeout / dropped) twice in one download is blacklisted, so every chunk no longer wastes its wait on it. Chunk connection timeouts tightened from 10/15 s to 8 s.
- 无进度看门狗：所有分片合计 25 秒零字节进账 → 自动中止并报错提示重试，不再无限期挂在 0%（网络只是慢但有字节在走时不会误杀）。
  No-progress watchdog: if all chunks together gain zero bytes for 25 seconds, the download aborts with an error suggesting a retry instead of hanging at 0% forever (a merely slow network with bytes flowing is not killed).
- 下载开始先回一次进度：弹窗立刻显示总大小，测速阶段不再是长时间 0% 加总量空白。
  An initial progress callback fires at download start: the dialog shows the total size immediately instead of sitting at 0% with a blank total through the probe phase.

## 2.22.56 — 2026-11-15

**修复国内不挂梯子无法更新：检查竞速 + Gitee 发行版补齐真资产。**
**Fixed updating from China without a proxy: raced update checks + real Gitee releases.**

- 根因（发行侧）：Gitee 镜像同步只同步代码与标签，从不同步 GitHub 的发行版附件——Gitee 上一直没有任何发行版，App 里的 Gitee 备用通道查到的永远是 404，等于不存在。国内用户不挂梯子时 GitHub 又经常连不上，于是检查失败、更新无门。本版起每个版本同步在 Gitee 建发行版并附 release APK（.55 一并补建），Gitee 通道第一次真正可用。
  Root cause (release side): Gitee's mirror sync copies code and tags only, never GitHub release assets — Gitee had no releases at all, so the app's Gitee fallback channel always got a 404, as if it did not exist. With GitHub often unreachable from China without a proxy, checks failed and there was no way to update. From this release on, every version also gets a Gitee release with the release APK attached (.55 backfilled), and the Gitee channel works for the first time.
- 检查改双源竞速：旧版串行先问 GitHub，国内网络下 GitHub 经常黑洞式挂起（不报错也不返回），要干等它超时完才轮到 Gitee。新版两源并行问，先答先用；Gitee 先答且 GitHub 2.5 秒内没跟上就直接按 Gitee 下结论。结论三态（有新版/已最新/检查失败）语义不变。
  Checks now race both sources: the old code asked GitHub first, serially, and on domestic networks GitHub often hangs black-hole style (no error, no reply), so Gitee only got its turn after a full timeout. Both sources are now asked in parallel and the first answer wins; if Gitee answers and GitHub has not followed within 2.5 s, the verdict is taken from Gitee. The three-way verdict (update available / up to date / check failed) is unchanged.
- Gitee 命中时其下载地址排候选第一：能答复检查就说明这条国内路径此刻是通的；配合 .55 的测速排序，国内下载会优先走 Gitee。展示与复制用的官方地址仍是 GitHub 直链。
  When Gitee answers, its download URL ranks first among candidates: answering the check proves that domestic path is open right now, and combined with .55's speed ranking, domestic downloads prefer Gitee. The official address shown and copied is still the GitHub direct link.
- 校验与安装逻辑不变。
  Verification and install logic unchanged.

## 2.22.55 — 2026-11-15

**更新下载提速（用户反馈国内下载慢）+ 下载弹窗显示实时速度。**
**Faster update downloads (user report: slow in China) + live speed in the download dialog.**

- 测速选源：旧版按固定顺序（GitHub 直连 → 镜像 → Gitee）逐个试，排在前面的源只要「慢但不断」就会一直拖着下。新版下载前先并行探测每个候选源的实际吞吐（从文件三分之一处取 256 KB 计时），按实测速度从快到慢重排再下载；探测失败的源不剔除，只沉底作兜底。国内网络下各源速度差异极大且时时不同，先测再下才选得准。
  Speed-tested source selection: the old code tried sources in a fixed order (GitHub direct → mirrors → Gitee), and an early source that was "slow but alive" would drag the whole download. Now every candidate's real throughput is probed in parallel before downloading (256 KB timed from one-third into the file), and sources are reordered fastest-first; a failed probe does not remove a source, it just sinks to the bottom as a fallback. On domestic networks the sources differ wildly and change constantly — measure first, then download.
- 慢源淘汰：下载中单条连接 5 秒内下不到 192 KB（约 38 KB/s）即断开换下一个源续下（分片模式按字节区间续，单流模式换源重下）；每个分片只在前几轮连接启用淘汰、最后一个源不淘汰，避免所有源都慢时来回横跳下不完。
  Slow-source eviction: a connection that moves less than 192 KB in 5 seconds (~38 KB/s) is dropped and the next source continues (chunked mode resumes by byte range; single-stream restarts from the new source). Eviction only applies to a chunk's first few connections, and the last source is never evicted, so an all-slow lineup cannot bounce forever without finishing.
- 分片 4 → 6：慢速 CDN 上更多并行连接更容易跑满带宽。
  Chunks 4 → 6: more parallel connections fill the pipe better on slow CDNs.
- 速度显示：下载弹窗新增实时速度（3 秒滑动窗口平均）与「已下载 / 总大小」，不再只有一个百分比。进度回调同步带上速度读数（SpeedMeter 纯逻辑，有单测）。
  Speed display: the download dialog shows live speed (3-second sliding-window average) and "downloaded / total" instead of a bare percentage. Progress callbacks carry the speed reading too (SpeedMeter is pure logic, unit-tested).
- 校验与安装逻辑不变：SHA-256 / 包名 / 签名三重校验照旧，镜像仍只影响速度。
  Verification and install unchanged: SHA-256 / package / signature triple-check as before; mirrors still only affect speed.

## 2.22.54 — 2026-11-15

**遥测接收端迁移：阿里云函数计算 → Cloudflare Workers。**
**Telemetry receiver migrated: Alibaba Cloud Function Compute → Cloudflare Workers.**

- 仅换上传地址：诊断摘要的接收端从阿里云 FC 迁到 Cloudflare Workers（服务端为同一协议 v2 的移植，防重放与报告存储改用 D1 数据库，跨实例一致、强于原实例内存缓存）。协议、密钥、载荷字段、首次声明同意与设置开关全部不变，客户端只改 UPLOAD_ENDPOINT 一个常量。
  Only the upload address changed: the diagnostic-summary receiver moved from Alibaba FC to Cloudflare Workers (the server is a port of the same protocol v2; replay protection and report storage now use D1, consistent across instances — stronger than the old per-instance memory cache). Protocol, key, payload fields, first-launch consent, and settings toggles are all unchanged; the client changed exactly one constant, UPLOAD_ENDPOINT.
- 迁移原因：阿里云账号因日志服务（SLS）按量计费产生欠费，控制台拒绝部署新版服务端；新接收端在免费额度内运行（Workers 10 万次/日、D1 5 GB），无欠费停服概念。部署当日实测：合法信封 200 且报告落库、重放 401、篡改 401、明文 v1 401。
  Why: the Alibaba account ran up pay-as-you-go arrears on its log service (SLS) and the console refused new server deployments. The new receiver runs inside free quotas (Workers 100k requests/day, D1 5 GB) with no arrears-shutdown concept. Verified on deploy day: valid envelope 200 and stored, replay 401, tampered 401, plaintext v1 401.
- 旧 FC 端点保持在线，服务已发布的 ≤2.22.53 版本；本版起新报告走新端点。
  The old FC endpoint stays online for already-released versions ≤ 2.22.53; from this release, new reports go to the new endpoint.

## 2.22.53 — 2026-11-15

**第八轮审计（v2.22.51 + 服务端 v5）全部修复：9 项中 8 项属实、1 项为已决重提，用户拍板全修。服务端同步升至 v6。**
**Round-8 audit fixes (v2.22.51 + server v5): of 9 findings, 8 were real and 1 was a re-raise of a settled item; the user called for fixing all of them. Server bumped to v6 alongside.**

- 构建管线接 R8：release 的 dex 阶段改走 R8（混淆 + 收缩 + 按规则剥离 Log.d/Log.v），不再只有 D8 转换；规则栈 = aapt2 清单 keep + 项目 proguard 规则 + 全部 AAR consumer rules + 管线附加规则。dex 由约 29.8 MB 降至约 4.2 MB。配套：E.JAVA_STACK 的自家包前缀改为运行时推导（写死包名会在混淆后把自己的调用栈误报成外部帧）；打包时 META-INF/services 改从 R8 产物收集（类名已改写，沿用 JAR 旧名会让 Dispatchers.Main 失效）。debug 包仍走 D8 不混淆。mapping 文件只留维护者本地，不公开。
  R8 in the build pipeline: the release dex stage now runs R8 (obfuscation + shrinking + rule-based Log.d/Log.v stripping) instead of a bare D8 conversion. Rule stack = aapt2 manifest keeps + project proguard rules + all AAR consumer rules + pipeline extras. Dex shrank from ~29.8 MB to ~4.2 MB. Supporting changes: E.JAVA_STACK's own-package prefix is derived at runtime (a hard-coded package name would, after obfuscation, misreport our own stack frames as external); META-INF/services is now collected from the R8 output (class names are rewritten — reusing the JAR's old names would break Dispatchers.Main). Debug builds still use D8, unobfuscated. Mapping files stay on the maintainer's machine, never published.
- 管线失败关闭补齐：kotlinc 退出码直接校验（此前只数日志错误条数，编译器崩溃会被漏过）；AAR 资源编译失败立即中止；发布门新增签名证书指纹比对与三 ABI 存在性/ELF 机器类型校验，package_apk 缺任一 ABI 的 .so 改为硬失败。
  Pipeline fail-closed gaps closed: kotlinc's exit code is checked directly (previously only log error lines were counted, so a compiler crash could slip through); an AAR resource compile failure aborts immediately; the release gate gained signer-certificate fingerprint comparison and three-ABI presence / ELF machine-type checks, and package_apk now hard-fails if any ABI's .so is missing.
- Native 健壮性：getdents64 目录解析逐条验边界（头部长度、d_reclen 范围、名字 strnlen 截断）——解析的正是被 Hook 环境的不可信返回数据，此前只有 d_reclen==0 一道检查。
  Native robustness: getdents64 directory parsing now validates bounds entry by entry (header length, d_reclen range, name truncation via strnlen) — this parser consumes untrusted data returned inside a possibly hooked environment, and previously had only a d_reclen==0 check.
- 隐私声明对齐：首次声明文案逐项列出实际上传的全部字段（制造商、四项分项评分、逐项类别/严重度/置信度/启发式标记、计数、耗时、时间戳等），此前只列了部分。
  Privacy declaration aligned: the first-launch declaration now lists every field actually uploaded (manufacturer, the four dimension scores, per-item category/severity/confidence/heuristic flags, counts, elapsed time, timestamps, …); it previously listed only some of them.
- CI 补 Native：新增独立 job 用固定版 NDK 真实编译三 ABI 并校验 ELF 机器类型与 LOAD 对齐，此前 CI 只用仓库预编译 jniLibs，C++ 改坏发现不了。
  CI gained a native job: a pinned NDK really compiles all three ABIs and checks ELF machine types and LOAD alignment. CI previously only used the repo's prebuilt jniLibs, so broken C++ would go unnoticed.
- 下载清理残留项：单流下载的连接统一 try/finally 断开（.52 的多源+分片重写已消解其余部分）。
  Download cleanup leftover: single-stream download connections are uniformly disconnected in try/finally (the .52 multi-source + chunked rewrite had already dissolved the rest).
- 服务端 v6：nonce 防重放改锁内原子「清过期→查重→容量→插入」，且先查重后容量、满载拒绝新 nonce 不再淘汰窗口内旧条目——v5 的无锁竞态与「淘汰最旧后旧 nonce 被重新接受」两个确定性缺陷均已本地复现并堵上，协议行为其余不变。
  Server v6: nonce replay protection became an atomic under-lock "purge expired → check duplicate → capacity → insert", with the duplicate check before capacity and a full store refusing new nonces instead of evicting in-window entries. Both of v5's deterministic defects — the lock-free race and "an evicted old nonce gets accepted again" — were reproduced locally and closed. Protocol behavior otherwise unchanged.

## 2.22.52 — 2026-11-15

**更新下载大改版：多源切换 + 分片续传（用户点单）。检查更新的结论逻辑不变。**
**Update downloads overhauled: multi-source failover + chunked resume (user-requested). The update-check verdict logic is unchanged.**

- 多源自动切换：下载候选按「GitHub 直连 → 公益镜像 ×3（gh-proxy.com / ghfast.top / ghproxy.net）→ Gitee」排列，某源失败或卡住自动换下一个。镜像只是中转、只能影响速度：安装前的 SHA-256 / 包名 / 签名三重校验照旧强制，内容被污染也装不上；走镜像的只有公开的 release 文件，不带任何凭据。GitHub 命中后会顺手取一次 Gitee 同版本地址作最后兜底；Gitee 命中时按本仓库固定发布规律反推 GitHub 直链，候选顺序一致。
  Automatic multi-source failover: candidates are ordered "GitHub direct → three public mirrors (gh-proxy.com / ghfast.top / ghproxy.net) → Gitee", and a failed or stuck source hands over to the next automatically. Mirrors are mere relays and can only affect speed: the pre-install SHA-256 / package / signature triple-check stays mandatory, so poisoned content cannot install; only public release files ever go through a mirror, with no credentials. When GitHub answers, the same version's Gitee URL is fetched as a final fallback; when Gitee answers, the GitHub direct link is derived from this repo's fixed release pattern, keeping candidate order consistent.
- 分片并行 + 断点续传：文件切 4 段并行下载，每段落盘保存；中断、取消、杀进程后重试都从已有进度续传，不再从零开始。某段在某个源失败时换源续下同一段（各源是同一文件，字节区间可跨源拼）。源不支持 Range 时自动退回单流下载，同样逐源切换。
  Chunked parallel download with resume: the file is split into 4 chunks downloaded in parallel, each persisted to disk; interrupts, cancels, and process kills all resume from existing progress instead of starting over. A chunk that fails on one source continues the same chunk from another (all sources serve the same file, so byte ranges splice across sources). Sources without Range support fall back to single-stream download, with the same per-source failover.
- 浏览器兜底：更新弹窗（发现新版与失败两种状态）新增「复制下载链接 / 浏览器打开」，App 内实在下不动时可以拿官方直链去浏览器或下载器里下。
  Browser fallback: the update dialog (both the "new version" and "failed" states) gained "Copy download link / Open in browser", so when the in-app download truly cannot move, the official direct link can be taken to a browser or download manager.

## 2.22.51 — 2026-11-15

**第七轮增量审查的修复版（客户端 1 项 + 构建管线加固；服务端同步升 v5，不在本仓库内）。**
**Round-7 incremental review fixes (1 client item + build pipeline hardening; server bumped to v5 alongside, not in this repo).**

- 调试器检测真值表：SelfGuardDetector 的 I.DEBUGGER 改为「任一 API 明确报 true 即确定性命中」。.50 为防异常冒充 PASS 把任一异常一律落 UNKNOWN，矫枉过正——一个 API 已确认有调试器、另一个抛异常时，确定性命中会被异常盖掉。现在只有双 false 才 PASS，无命中但有一侧读不到才 UNKNOWN。
  Debugger detection truth table: SelfGuardDetector's I.DEBUGGER is now "any API explicitly reporting true is a definite hit". .50 had overcorrected — to stop exceptions masquerading as PASS it sent any exception to UNKNOWN, so when one API confirmed a debugger and another threw, the definite hit got buried. Now only a double false is a PASS; no hit but one side unreadable is UNKNOWN.
- 手工构建管线加固：build_apk.sh 启用 pipefail；kotlinc 编译前清空 kotlin-classes 输出、D8 前清空 dex-release，两者的退出状态改为直接校验（错误数非 0 或无产物即中止构建）；打包末尾内置发布门 stage_gate（badging 版本、签名、dex 无旧版版本串残留、64 位 .so 版本串）——此前这些检查靠人工执行，管线自身对编译失败没有防护。
  Manual pipeline hardening: build_apk.sh enables pipefail; kotlin-classes output is wiped before kotlinc and dex-release before D8, with both stages' exit states checked directly (nonzero errors or no output aborts the build); a release gate stage_gate is built into the end of packaging (badging version, signature, no stale version strings in dex, 64-bit .so version string). These checks used to be run by hand, and the pipeline itself had no protection against compile failures.

## 2.22.50 — 2026-11-15

**第六轮全面审查的修复版（21 条结论逐条核查：12 条属实、5 条部分属实）。重点是一处真实漏检。**
**Round-6 full review fixes (21 conclusions checked one by one: 12 real, 5 partially real). The headline is one genuine missed detection.**

- KernelSU 证据归类错误（本轮最重要）：native 检出的 KernelSU 节点命中（F.KSU_NODE）此前被评分层归进「系统完整性」维度，只涨完整性分、不涨 Root 分——只暴露这一类证据的设备可能被评得过轻。已改归 Root 维度并加了回归测试。
  KernelSU evidence filed under the wrong dimension (this round's most important fix): the native KernelSU node hit (F.KSU_NODE) was scored into the "system integrity" dimension, raising only the integrity score and never the Root score — a device exposing only this class of evidence could be graded too lightly. It now belongs to the Root dimension, with a regression test.
- 交叉验证计数去重：三个交叉检查生产者会同时产出证据本体与交叉记录，同一事实此前在命中数里被计两次、覆盖率分子分母也重复代表；现只计一次（交叉矛盾的额外权重作为设计保留）。
  Cross-check counting deduplicated: three cross-check producers emitted both the evidence itself and a cross record, so the same fact was counted twice in hit counts and double-represented in coverage; it now counts once (the extra weight for cross contradictions stays, by design).
- 异常不再冒充通过：调试器状态读取异常此前会被吞成「无调试器」的 PASS；现改为 UNKNOWN。系统服务检测里几处失败兜底共用写死的证据 ID（会顶替真正的 F.SERVICE_LIST 并被去重合并），现各归其位、读取异常与「列表为空」分开处理。
  Exceptions no longer masquerade as passes: a debugger-state read exception used to be swallowed into a "no debugger" PASS; it is UNKNOWN now. Several failure fallbacks in the system-service check shared one hard-coded evidence ID (which could displace the real F.SERVICE_LIST and get merged by dedup); each now has its own place, with read exceptions and "empty list" handled separately.
- 检查失败不再冒充「已是最新」：此前两个更新源都不可达时界面会显示已是最新版本。现结论三分（有新版 / 已是最新 / 检查失败），只有源真正答复过才敢说最新；顺手修了手动检查撞上自动检查时弹出空白弹窗的竞争。
  A failed check no longer poses as "up to date": when both update sources were unreachable, the UI used to show "already latest". The verdict is now three-way (update available / up to date / check failed), and "latest" is only claimed when a source actually answered. Also fixed the race where a manual check colliding with the automatic check popped an empty dialog.
- 下载增加 64 MB 体积硬帽（发布包约 16 MB），超限中止，后面仍有 SHA/包名/签名三重校验。
  Downloads got a 64 MB hard size cap (release packages are ~16 MB); exceeding it aborts, and the SHA / package / signature triple-check still stands behind it.
- 服务端（同步上线 v4）：严格载荷 Schema（字段类型、等级/置信度枚举、分数范围、逐项结构与条数上限），不合规直接拒收不落日志；防重放 nonce 改用规范化缓存键，堵上大小写变体绕过；非数字时间戳返回 400 而非抛异常；Content-Type 改精确匹配；结构化入口校验请求方法；所有拒收路径统一记 RS_REJECT 原因。
  Server (v4, deployed alongside): a strict payload schema (field types, grade/confidence enums, score ranges, per-item structure and count caps) — nonconforming payloads are refused without logging; replay nonces use a normalized cache key, closing the case-variant bypass; non-numeric timestamps return 400 instead of throwing; Content-Type matching is exact; the structured entry validates the request method; all rejection paths log a unified RS_REJECT reason.
- 版本生成补齐最后一块：build.gradle.kts 纳入管线自动生成，此前它停在 2.22.46，与 Version.kt/CMakeLists 脱节（发布包走手工管线不受影响，但 Gradle 构建会漂移）。
  Version generation completed: build.gradle.kts is now generated by the pipeline too — it had been stuck at 2.22.46, out of step with Version.kt/CMakeLists (release packages come from the manual pipeline and were unaffected, but Gradle builds would drift).
- 发布管线脚本随源码入仓（tools/manual-build/），版本生成流程可审计。
  The release pipeline scripts are in the repo with the source (tools/manual-build/), so the version-generation flow is auditable.
- README 修正：删除「不上传任何数据」的过时表述，如实说明诊断摘要上报（声明同意 + 加密 + 可关闭）与更新检查的网络行为。
  README corrected: the outdated "uploads no data" claim is gone, replaced by an honest description of diagnostic-summary upload (declaration consent + encryption + can be turned off) and the update check's network behavior.

## 2.22.49 — 2026-11-15

**修复 2.22.47/2.22.48 的版本常量漂移（本版无功能变化）。**
**Fixed the version-constant drift in 2.22.47/2.22.48 (no functional changes in this release).**

- 现象：已装最新版仍反复弹窗提示更新，点更新后下载完成却报「安装包版本不高于当前版本」。根因：程序里两处手写版本常量（Kotlin 的 RootSentinelVersion 与 native 的 RS_VERSION）自 2.22.47 起没跟着版本号走，一直停在 2.22.46。更新检查按这个假版本判断，永远以为有新版；安装前校验用系统记录的真实版本，于是把同版安装包正确地拒了——一个误报、一个拦截，合起来就是上面的现象。扫描与检测不受影响（两处常量彼此一致，版本交叉校验未被触发）；诊断摘要里上报的版本号同样被标错为 2.22.46。
  Symptom: with the latest version installed, the app kept popping update prompts, and after downloading, the install was refused with "package version not higher than current". Root cause: two hand-written version constants (Kotlin's RootSentinelVersion and native's RS_VERSION) stopped following the version number at 2.22.47 and stayed at 2.22.46. The update check judged by the fake version and always believed a newer one existed; the pre-install check used the system's real recorded version and correctly refused the same-version package — one false alarm plus one correct block adds up to the symptom above. Scanning and detection were unaffected (the two constants agreed with each other, so the version cross-check never fired); the version reported in diagnostic summaries was mislabeled 2.22.46 as well.
- 修复：两处常量改由构建管线按唯一的版本变量自动生成，生成失败构建立即中止，杜绝再次漂移；本版起 App 内显示、更新判断、native 自报与上报口径全部回到真实版本 2.22.49。装了 2.22.48 的用户可经 App 内更新正常升级（这次校验会通过），或直接覆盖安装。
  Fix: both constants are now generated by the build pipeline from the single version variable, and a generation failure aborts the build — no more drift. From this release, the in-app display, update decisions, native self-report, and upload labels all use the real version, 2.22.49. Users on 2.22.48 can upgrade through the in-app update (the check passes this time) or install over the top directly.

## 2.22.48 — 2026-11-15

**传输加密与服务端防护：诊断摘要不再发明文，接收端只收验签通过的报告。**
**Transport encryption and server hardening: diagnostic summaries no longer travel in plaintext, and the receiver only accepts signature-verified reports.**

- 加密信封（协议 v2）：报告 JSON 先加密再发送——密钥由主密钥经 HMAC 派生（加密/签名两把子钥匙分离），HMAC-SHA256 密钥流 XOR 加密、每份报告随机 nonce、先加密后签名（EtM）。链路抓包只能看到一个信封（版本/时间戳/nonce/密文/签名），看不到任何字段内容。
  Encrypted envelope (protocol v2): the report JSON is encrypted before sending — keys are HMAC-derived from the master key (separate encryption and signing subkeys), encryption is an HMAC-SHA256 keystream XOR with a random per-report nonce, encrypt-then-MAC. A packet capture sees only an envelope (version / timestamp / nonce / ciphertext / signature), never any field content.
- 防伪造防重放：服务端验签通过才落日志；时间戳窗口 ±5 分钟、nonce 单次使用，重放或改一个字节都会被拒。
  Anti-forgery and anti-replay: the server logs only after signature verification; a ±5-minute timestamp window and single-use nonces mean a replay — or a single changed byte — is refused.
- 密钥存放：App 内密钥以分片 XOR 掩码形式存放、运行时拼装，不以明文字符串出现在源码里；服务端密钥只放在函数环境变量中，不进代码。说明一句实话：客户端内嵌密钥对有耐心的逆向者不是绝对秘密，这层防护拦的是随手伪造、灌水与被动抓包；载荷本身仍是字段最小化、没有设备标识。
  Key storage: the in-app key is stored as XOR-masked fragments and assembled at runtime, never as a plaintext string in the source; the server key lives only in the function's environment variables, not in code. Honestly stated: an embedded client key is not an absolute secret against a patient reverse engineer — this layer stops casual forgery, spam, and passive capture. The payload itself remains field-minimized with no device identifiers.
- 协议切换：服务端即日起只收 v2 信封，2.22.47 及更早版本的诊断上传会被拒收（扫描、更新等其它功能不受影响）。
  Protocol switch: the server accepts v2 envelopes only from now on; diagnostic uploads from 2.22.47 and earlier will be refused (scanning, updates, and everything else are unaffected).
- 服务端防护（接收函数 v3）：密钥未配置时一律拒收（fail closed）；Content-Type 必须是 application/json；请求体上限 256 KB（维持）；签名比较用常量时间算法；解密后仍再做一遍形状校验（纵深防御）；拒收事件单独记 RS_REJECT + 原因 + 来源 IP，与正式报告 RS_REPORT 分开，灌水一眼可见。
  Server hardening (receiver v3): no key configured means everything is refused (fail closed); Content-Type must be application/json; the 256 KB body cap stays; signature comparison is constant-time; decrypted payloads get a second shape check (defense in depth); rejections are logged separately as RS_REJECT with reason and source IP, apart from real RS_REPORT entries, so spam is visible at a glance.
- 下发侧：本 App 唯一的下发数据是更新检查与安装包——检查接口是公开数据（HTTPS），安装包在 2.22.47 已有三重校验（发布哈希/包名版本/签名证书），本版维持不变。
  Download side: the app's only inbound data is the update check and the install package — the check API is public data over HTTPS, and packages have had the triple check since 2.22.47 (release hash / package+version / signing certificate). Unchanged in this release.

## 2.22.47 — 2026-11-15

**两件新功能：诊断摘要接收端正式上线；应用内更新。**
**Two new features: the diagnostic-summary receiver goes live, and in-app updates.**

- 接收端已部署：阿里云函数计算（华东2·上海）rootsentinel-report，HTTP 触发器仅 POST、匿名访问；Telemetry.UPLOAD_ENDPOINT 已填入正式地址。接收函数只做形状/大小校验（单份上限 256 KB）后整份写入函数日志，不连数据库、不存设备标识。端到端实测通过（2026-11-15）：合法载荷返回 200，形状不对/坏 JSON 返回 400 并注明原因；声明未确认或设置开关关闭时仍一律不发送，口径与 2.22.46 不变。
  The receiver is deployed: Alibaba Cloud Function Compute (East China 2, Shanghai), rootsentinel-report, HTTP trigger POST-only with anonymous access; Telemetry.UPLOAD_ENDPOINT now holds the real address. The function does shape/size validation only (256 KB per report) and writes the whole payload to the function log — no database, no device identifiers stored. End-to-end verified (2026-11-15): a valid payload returns 200; bad shape or broken JSON returns 400 with a reason. Nothing is sent while the declaration is unconfirmed or the settings toggle is off — same rules as 2.22.46.
- 应用内更新：每次打开 App 自动查一次新版（声明弹窗未确认时不查），数据源为 GitHub Releases（主）/ Gitee 镜像（备）的 latest 接口，均为公开 API、无需令牌；查到新版才弹窗，没有新版不打扰。更新弹窗显示「当前版 → 新版」版本号、更新来源、安装包大小与本次更新内容（发布说明全文，可滚动）。
  In-app updates: the app checks once per launch (skipped until the declaration is confirmed), against the GitHub Releases (primary) / Gitee mirror (fallback) latest endpoints — public APIs, no token. A dialog appears only when a new version exists. The dialog shows "current → new" versions, the update source, package size, and this release's notes in full (scrollable).
- 设置页手动入口：「设置 → 版本与更新」新增「检查更新」（显示当前版本，点了立即给反馈）与「GitHub 仓库」跳转（AURORA-0711/RootSentinel）。
  Manual entry in Settings: "Settings → Version & updates" gained "Check for updates" (shows the current version, gives immediate feedback) and a "GitHub repository" jump (AURORA-0711/RootSentinel).
- 下载与安装：点「立即更新」后在弹窗内下载并显示进度，可取消；下载完成自动进入校验，校验通过自动调起系统安装确认（系统安装界面仍需用户点确认，本应用不做静默安装）；缺「安装未知应用」授权时引导去系统设置开启，开启后可直接续装、不用重新下载。
  Download and install: "Update now" downloads inside the dialog with progress, cancellable; on completion, verification runs automatically and success hands over to the system install confirmation (the user still confirms there — this app does no silent installs). If the "install unknown apps" permission is missing, the user is guided to enable it in system settings, after which the install resumes without re-downloading.
- 安装前强制校验（任一不过不安装）：① 文件 SHA-256 与发布接口给出的 asset digest 一致（接口未给 digest 时跳过此项）；② 包名与本应用一致且版本号高于当前；③ APK 签名证书 SHA-256 与当前已安装应用完全一致，保证更新包出自同一把密钥。
  Mandatory pre-install verification (any failure blocks the install): (1) the file's SHA-256 matches the asset digest from the release API (skipped when the API provides no digest); (2) the package name matches this app and the version is higher than current; (3) the APK signing certificate SHA-256 is identical to the installed app's, guaranteeing the update comes from the same key.
- 新增权限 REQUEST_INSTALL_PACKAGES（仅用于上述应用内安装）；新增 FileProvider 仅暴露 cacheDir/updates 一个目录给系统安装器。
  New permission REQUEST_INSTALL_PACKAGES (used only for the in-app install above); a new FileProvider exposes exactly one directory, cacheDir/updates, to the system installer.

## 2.22.46 — 2026-11-15

**新功能：诊断摘要上传，配套一套完整的同意与关闭机制。**
**New feature: diagnostic summary upload, with a complete consent and opt-out mechanism.**

- 首次使用声明弹窗：第一次打开 App 先弹声明（不可点外部/返回键绕过，必须读完点「我已阅读，继续使用」），继续使用即视为同意；确认状态持久化，之后不再弹。未确认声明时一律不上传。
  First-use declaration dialog: the first launch shows a declaration that cannot be dismissed by tapping outside or pressing Back — you read it and tap "I have read it, continue", and continuing counts as consent. The confirmation persists and never shows again. Nothing uploads before it is confirmed.
- 设置开关：「设置 → 隐私与数据 → 上传诊断摘要」，默认开启（与声明的同意模型一致），随时可关，关闭立即停传。
  Settings toggle: "Settings → Privacy & data → Upload diagnostic summary", on by default (consistent with the declaration's consent model), can be turned off any time, and turning it off stops uploads immediately.
- 字段最小化（与声明文案严格一致）：只传 App 版本、设备品牌/型号/厂商、Android SDK、评分汇总与逐项的编号/类别/严重度/置信度/判定。不传设备指纹（连哈希也不传）、任何设备标识、文件路径、证据标题与详情、已安装应用列表、报告全文——detail/vectors 因此整体排除在载荷之外。
  Field minimization (matching the declaration text exactly): only the app version, device brand/model/manufacturer, Android SDK, the score summary, and per-item id/category/severity/confidence/verdict. Not sent: device fingerprints (not even hashed), any device identifier, file paths, evidence titles and details, installed-app lists, or the full report — detail/vectors are therefore excluded from the payload wholesale.
- 失败语义：发后即忘，无重试队列、不落盘缓存；无网/超时/非 2xx 静默丢弃，不影响扫描与界面。
  Failure semantics: fire-and-forget — no retry queue, no on-disk cache; no network, timeouts, and non-2xx responses are silently dropped and never affect scanning or the UI.
- 接收端地址留空待命：Telemetry.UPLOAD_ENDPOINT 当前为空字符串，未配置时 upload 直接返回、实际不发送任何数据；作者部署接收端后填入 HTTPS 地址即生效。
  Receiver address left blank on standby: Telemetry.UPLOAD_ENDPOINT is an empty string for now; unconfigured, upload returns immediately and sends nothing. Once the author deploys the receiver and fills in the HTTPS address, it takes effect.
- INTERNET 权限加回：v2.22.43 曾移除本权限，本次为上述功能加回，仅 Telemetry 使用。
  The INTERNET permission is back: v2.22.43 removed it; it returns for this feature and is used by Telemetry only.

## 2.22.45 — 2026-11-14

**基于对 2.22.44 的第五轮独立审查修复（两份审查共 31 条，逐条回源码核查：24 条属实、7 条部分属实、0 条不属实），全部按核查结论修复。**
**Round-5 independent review fixes, against 2.22.44 (two reviews, 31 findings total; each checked against the source: 24 real, 7 partially real, 0 wrong — including two positive verifications from review B, .so stripped/aligned and CMake's 45 sources matching the tree, both confirmed). All fixed per the verification conclusions.**

- 原厂/模拟器环境误报一组：E.ART_SYMBOL 旧实现用只收可执行段的判定去查 libart 的数据符号，正常设备也报 CRITICAL，改用含任意权限段的判定；D.FD 把含 memfd 的 fd 一律判 HIGH，未做 ART 过滤——ART 的 JIT/图形栈本身就持有 memfd，现与 D.MEMFD 同口径先过 ART 归属过滤，只对非 ART memfd 与 /data/adb 报告；ro.kernel.qemu 是模拟器标记而非 root 证据，从 root 关键字列表移除（模拟器由专门检查负责）；I.SECCOMP_FILTERS 在老内核缺 filters 字段时旧实现直接 HIT，改判 UNKNOWN（filters=0 维持 HIT）；X.FRIDA_PORT 旧实现见命名空间里任一 Frida 端口监听即 CRITICAL，现解析 socket inode 与本进程 fd 对照——确属本进程才 CRITICAL，仅命名空间存在降 MEDIUM + heuristic；statx 回退的 dev 编码三处统一 packDev() 解码，老内核上 C.META_MISMATCH 不再必误报。
  A batch of stock/emulator false positives: E.ART_SYMBOL used an executable-segments-only test on a libart data symbol, flagging CRITICAL on normal devices — it now uses the any-permission variant. D.FD judged any memfd fd HIGH with no ART filtering, but ART's own JIT/graphics stack holds memfds; it now applies the same ART-ownership filter as D.MEMFD and reports only non-ART memfds and /data/adb. ro.kernel.qemu is an emulator marker, not root evidence, and left the root keyword list (emulators have their own check). I.SECCOMP_FILTERS hit outright when old kernels lack the filters field; it is UNKNOWN now (filters=0 stays a HIT). X.FRIDA_PORT used to go CRITICAL on any Frida-port listener in the network namespace; it now resolves socket inodes against this process's fds — CRITICAL only when the socket is ours, namespace-only presence drops to MEDIUM + heuristic. The statx fallback's dev encoding is unified through packDev() in all three places, so C.META_MISMATCH no longer false-fires on old kernels.
- 协议与解析严格化：ARM32 的 fstat/fstatat 旧编号写的是旧式结构、元数据全错位，改为 fstat64/fstatat64（Bionic 在所有架构上都是 STAT64 布局）；NativeReportParser 对 severity/confidence/verdict 的未知值不再静默降级，任一不可识别即置 protocolError 且该证据强制 UNKNOWN；DER 读取器声明长度超过剩余数据直接失败（旧实现静默截断），KeyDescription 增加外层 SEQUENCE 完整消费校验，authList 截断时整表作废。
  Protocol and parsing strictness: ARM32 fstat/fstatat used legacy numbers with a legacy struct layout that misaligned all metadata — now fstat64/fstatat64 (Bionic uses the STAT64 layout on every architecture). NativeReportParser no longer silently downgrades unknown severity/confidence/verdict values; any unrecognized value sets protocolError and forces that evidence to UNKNOWN. The DER reader fails outright when a declared length exceeds the remaining data (it used to truncate silently), KeyDescription parsing validates full consumption of the outer SEQUENCE, and a truncated authList invalidates the whole list.
- 交叉验证接通：addCrossCheck 自引入起全仓无生产者，crosses 恒空、评分层的 CROSSCHECK_BONUS 是死路径。现登记三个真实生产者：X.CHILD_PROC（父子进程三点观测）、C.MOUNT_MULTI（mountinfo↔mounts 集合）、C.META_MISMATCH（fstat↔statx），只登记结构化观测、不重复产证。
  Cross-checks wired up: addCrossCheck had no producer anywhere since its introduction — crosses was always empty and the scoring layer's CROSSCHECK_BONUS was a dead path. Three real producers are now registered: X.CHILD_PROC (parent/child three-point observation), C.MOUNT_MULTI (mountinfo↔mounts sets), and C.META_MISMATCH (fstat↔statx) — registering structured observations only, without duplicating evidence.
- 判定口径与噪声：F.OVER_HIDING 把 EACCES 与 ENOENT 分开计数，denied≥2 才 HIGH，恰 1 个降 LOW + heuristic；D.TASK_COUNT 差 ≤1 按瞬时差异 PASS，差 ≥2 重采样一次、两次都 ≥2 才 HIT；E.INLINE_HOOK 仅 bionic alias 命中时降 LOW + heuristic；check_art_method 的合法归属删掉裸子串 "art"（"smart" 等误放行）；D.ANON_EXEC 删除（与 D.TRUE_ANON 同一事实集两套口径，统一由后者判定）；A.DIR_ENUM_MISMATCH 新增三态 fileStateRaw，只有 ENOENT 计「枚举到但查不到」，其余记「不可验」产 UNKNOWN；C.VERITY 判定重构——有带属性也有可观测缺失才 HIT，全有且无不可读才 PASS，部分不可读或全部缺失改 UNKNOWN；C.STATFS 全探测失败补 UNKNOWN，C.META_MISMATCH 补可比计数（0 可比 UNKNOWN、全一致 PASS）；SELinux shell 域匹配串修正（真实上下文无前导冒号）；A.MODULES_DIR 与父目录口径互证（父 ENOENT 而子 EACCES 的矛盾观测记 UNKNOWN）；B.UNAME_MULTI 两条同 ID PASS 合并为一条、双来源向量并入。
  Verdict rules and noise: F.OVER_HIDING counts EACCES and ENOENT separately — denied≥2 is HIGH, exactly 1 drops to LOW + heuristic. D.TASK_COUNT treats a difference ≤1 as a transient PASS; ≥2 resamples once and hits only if both samples are ≥2. E.INLINE_HOOK drops to LOW + heuristic when only bionic aliases match. check_art_method's legitimate-ownership test lost the bare substring "art" (which waved through "smart", etc.). D.ANON_EXEC deleted — same fact set as D.TRUE_ANON under two rule sets; the latter judges alone now. A.DIR_ENUM_MISMATCH gained a three-state fileStateRaw: only ENOENT counts as "enumerated but not found"; other errors are "unverifiable" and yield UNKNOWN. C.VERITY rebuilt: a HIT needs both attributed files and observably missing ones; all present with nothing unreadable is a PASS; partially unreadable or all missing is UNKNOWN. C.STATFS reports UNKNOWN when all probes fail; C.META_MISMATCH gained comparable-count rules (0 comparable → UNKNOWN, all agreeing → PASS). The SELinux shell-domain match string was fixed (real contexts have no leading colon). A.MODULES_DIR cross-checks against its parent directory (parent ENOENT but child EACCES is a contradictory observation → UNKNOWN). B.UNAME_MULTI's two same-ID PASSes merged into one with both source vectors kept.
- 健壮性与维护：check_bpf 的 bpf_attr 改对齐数组（原写法有对齐/别名 UB）；check_env auxv 改逐项 memcpy 解析；check_cross_process 的只跑一次标记改 std::atomic；check_kernel_deep 模块名改词边界匹配（"ksu"/"milk" 子串误伤消除）；X.ENV_MATRIX 目录能力改三态；手动导出补 Toast 结果反馈；补 THIRD_PARTY_LICENSES.md 与 Apache-2.0 许可证文本（vendored 的 Kyant Backdrop 为 Apache-2.0，此前仓库只有 MIT）；LICENSE 署名随改名更新为 AURORA-0711；README 版本号同步（此前停在 2.22.42）；删未用变量与方法；新增单元测试（协议严格性、DER 截断拒绝、评分去重）与 GitHub Actions 构建工作流——此前仓库无测试与 CI，测试在 CI 上运行，手工管线不跑 Gradle。
  Robustness and housekeeping: check_bpf's bpf_attr became a properly aligned array (the old form had alignment/aliasing UB); check_env parses auxv entry-by-entry with memcpy; check_cross_process's run-once flag is std::atomic now; check_kernel_deep matches module names on word boundaries (substring false hits like "ksu"/"milk" gone); X.ENV_MATRIX directory capability is three-state; manual export gives Toast feedback; THIRD_PARTY_LICENSES.md and the Apache-2.0 text were added (the vendored Kyant Backdrop is Apache-2.0; the repo previously carried only MIT); the LICENSE attribution follows the rename to AURORA-0711; the README version was synced (it had stalled at 2.22.42); unused variables and methods deleted; unit tests added (protocol strictness, DER truncation refusal, scoring dedup) plus a GitHub Actions build workflow — the repo previously had no tests and no CI; tests run on CI, and the manual pipeline does not run Gradle.

## 2.22.44 — 2026-11-14

**基于对 2.22.43 的第四份独立静态审查修复（4 条 P1 + 14 条 P2 + P3，逐条回源码核查：17 条属实、3 条部分属实、0 条不属实），全部修复。**
**Round-4 independent static review fixes, against 2.22.43 (4 P1 + 14 P2 + P3 findings; each checked against the source: 17 real, 3 partially real, 0 wrong). All fixed.**

- Key Attestation ASN.1 解析按官方结构重写（本轮最重）：旧映射整体错位——[704] 是 rootOfTrust 的 EXPLICIT 包装而非 deviceLocked，[709]/[710] 是 attestationApplicationId / attestationIdBrand 而非启动状态/哈希，osVersion 实际在 [705]，且所有 EXPLICIT 字段从未剥内层 TLV。后果很实在：deviceLocked 对一切带 rootOfTrust 的设备恒为 true——真实解锁的设备会被以 VERIFIED 置信度报成「已锁定」，deviceLocked=false 的 HIT 分支永不可达。现在 [704] 解包 SEQUENCE 后读 [0..3] 子字段（deviceLocked / verifiedBootState / verifiedBootHash 各归其位），osVersion 改 [705]，补 [706] osPatchLevel，全部 EXPLICIT 字段先剥内层 TLV 再取值。
  Key Attestation ASN.1 parsing rewritten to the official structure (this round's biggest item): the old mapping was shifted wholesale — [704] is the EXPLICIT wrapper of rootOfTrust, not deviceLocked; [709]/[710] are attestationApplicationId / attestationIdBrand, not boot state/hash; osVersion actually lives in [705]; and no EXPLICIT field ever had its inner TLV stripped. The consequence was concrete: deviceLocked read as true for every device carrying rootOfTrust, so a genuinely unlocked device would be reported "locked" at VERIFIED confidence, and the deviceLocked=false HIT branch was unreachable. Now [704] is unpacked as a SEQUENCE and its [0..3] subfields are read in place (deviceLocked / verifiedBootState / verifiedBootHash), osVersion moved to [705], [706] osPatchLevel added, and every EXPLICIT field has its inner TLV stripped before use.
- C.VERITY 常量与掩码：STATX_ATTR_VERITY 误写成 DAX 的 0x00200000，改回 UAPI 的 0x00100000；判定前先查 stx_attributes_mask，两属性都不可观测的文件按不可读计，不再把「内核不报告」当「属性缺失」凑 HIT。
  C.VERITY constant and mask: STATX_ATTR_VERITY had been written as DAX's 0x00200000; restored to the UAPI 0x00100000. The stx_attributes_mask is checked before judging, and files where neither attribute is observable count as unreadable — "the kernel doesn't report it" no longer poses as "attribute missing" to凑 a HIT.
- 跨进程白名单重建：旧白名单 54 个 ID 只有 25 个能由 native 产出（改名漂移、Java 侧项、不存在的 ID），60% 门槛要 32 项，complete 永假、强差异分支从未触发。白名单按 native 实际 ID 注册表重建为 39 项（改名逐项对齐），门槛随之可达；文件头注明维护规则与对照导出命令。
  Cross-process whitelist rebuilt: of the old whitelist's 54 IDs, only 25 could actually be produced by the native side (rename drift, Java-side items, nonexistent IDs); the 60% threshold needed 32, so "complete" was forever false and the strong-divergence branch never fired. The whitelist was rebuilt from the native side's actual ID registry — 39 entries, renames aligned one by one — making the threshold reachable; the file header records the maintenance rule and the comparison export command.
- AAPT2 路径统一：本地 Maven 仓库以 settings.gradle.kts 的 /root/localMaven 为唯一真源，setup-aapt2.sh 默认目录与之对齐；删掉无人引用的 SDK extras 副本；aapt2shim.c 的真 aapt2/qemu 路径改为占位符，由脚本按当前 SDK 配置生成。
  AAPT2 paths unified: the local Maven repo in settings.gradle.kts (/root/localMaven) is the single source of truth, and setup-aapt2.sh's default directory matches it; the unreferenced SDK extras copy was deleted; aapt2shim.c's real aapt2/qemu paths became placeholders the script generates from the current SDK config.
- 结论可信度一组：applicabilityOf 删掉「不存在 / 未提供 / 没有该 / 缺少」等裸缺失词——分不清设备不支持与数据缺口，数据缺口型 UNKNOWN 不再被移出覆盖率分母；crosses 的 consistent 缺失不再默认 true（按不一致 + 协议问题处理），证据 id 为空判协议错误并跳过；F.AM_PROCS 进程列表为空改 UNKNOWN（原 PASS + HIGH）；C.META_MISMATCH 换真独立视角——新增 openat + NR_fstat 的原始读法，不再用内部先调 statx 的 fstatat 自比，C.SAME_INODE 补 st_dev 比较；C.OVERLAY 分档（/product、/apex、/system_ext 从 HIGH 降 MEDIUM + heuristic），C.DATA_SUBMOUNT 对 /data/misc 厂商区降 LOW + heuristic。
  Verdict credibility batch: applicabilityOf lost the bare missing-words ("does not exist / not provided / lacks", …) — they cannot distinguish "device doesn't support it" from "data gap", and data-gap UNKNOWNs are no longer removed from the coverage denominator. A missing crosses.consistent no longer defaults to true (treated as inconsistent + protocol problem), and an empty evidence id is a protocol error and skipped. F.AM_PROCS with an empty process list is UNKNOWN now (was PASS + HIGH). C.META_MISMATCH got a genuinely independent viewpoint — a new raw openat + NR_fstat read instead of comparing against an fstatat that internally calls statx first — and C.SAME_INODE compares st_dev too. C.OVERLAY was re-tiered (/product, /apex, /system_ext from HIGH to MEDIUM + heuristic) and C.DATA_SUBMOUNT drops to LOW + heuristic for the /data/misc vendor area.
- 应用与发布加固：autoscan / navtest / snap 三个调试 intent 入口加 BuildConfig.DEBUG 门槛，release 恒不触发；逐项日志降级（Kotlin 逐项 Log.i → Log.d，R8 release 按规则剥离；native 日志由 INFO 降 DEBUG），扫描级汇总一行保留；两条 JSON 导出路径移 Dispatchers.IO；MediaStore 导出检查 update() 返回值，失败删占位记录，不再留幽灵条目还报成功；删除三个无消费者设置（themeMode / animations / haptics）及设置页「交互」卡片——只存不用、误导用户；Attestation 收敛单一实现：删除旧 AttestationDetector 与「Java · 证明链」阶段，证明统一走 SafetyNetDetector → KeyAttestation，每轮扫描只生成一把证明密钥（旧实现独有的四项随之移除，信息已被 G.ATTEST_CHAIN 的解析结果覆盖）；两处 keytool 调用改 -storepass:env 传密码，密码不再出现在进程 argv 里；release 构建失败关闭（任务图含 Release 且无正式签名凭据时直接报错，不再回退 debug 签名）；native.sh 的 strip 改失败关闭。
  App and release hardening: the autoscan / navtest / snap debug intent entries are gated on BuildConfig.DEBUG and can never fire in release; per-item logging was demoted (Kotlin per-item Log.i → Log.d, stripped in release by the R8 rules; native logging INFO → DEBUG), with the one-line scan summary kept; both JSON export paths moved to Dispatchers.IO; MediaStore export checks update()'s return value and deletes the placeholder on failure instead of leaving a ghost entry while reporting success; three consumer-less settings (themeMode / animations / haptics) and the Settings "Interaction" card were deleted — stored but never used, misleading users; attestation converged to a single implementation — the old AttestationDetector and the "Java · attestation chain" stage are gone, attestation runs uniformly through SafetyNetDetector → KeyAttestation with one key per scan (the old implementation's four unique items went with it; their information is covered by G.ATTEST_CHAIN's parsed result); both keytool calls pass the password via -storepass:env so it no longer appears in process argv; release builds are fail-closed (a Release task in the graph without real signing credentials errors out instead of falling back to the debug signature); native.sh's strip step is fail-closed too.
- 延期（不变）：评分适用性的结构化原因字段（UNSUPPORTED / DATA_MISSING 枚举）仍待做，本版先用关键词收敛止住误判；自动化测试与 CI 仍缺位，DER 测试向量回归建议在有 Gradle 的环境补上。另：审查 §9 附带的「CMake 源清单重复条目」经复核不成立——syscall_asm.cpp 在源清单里只有一条，另一处是属性设置。
  Deferred (unchanged): the structured applicability-reason field (UNSUPPORTED / DATA_MISSING enum) is still to do — this release stops the misjudgments with keyword narrowing meanwhile; automated tests and CI are still absent, and DER test-vector regression is recommended in an environment with Gradle. Also: the review's §9 side note about a "duplicate CMake source entry" did not hold up on re-check — syscall_asm.cpp appears once in the source list; the other mention is a source-file properties setting.

## 2.22.43 — 2026-11-14

**基于对 2.22.42 的第三份独立静态审查修复（5 条 P1 + 8 条 P2 + 风险表，逐条复核：11 条属实，P1-04 / P2-08 各有一处结论需修正）。**
**Round-3 independent static review fixes, against 2.22.42 (5 P1 + 8 P2 + a risk table; checked one by one: 11 real, with one conclusion each in P1-04 / P2-08 needing correction — and the §5 claim of "only 30 timing samples" was wrong, it is a median of 64 rounds).**

- H 类证据维度污染：dimensionOf() 新增 HIDING 分支，H 类（隐藏与欺骗痕迹）归 HOOK（观测完整性）维度，不再掉进默认的 ROOT。analyze.cpp 由交叉验证矛盾生成的 H.HIDING_SIGNAL 从此只进 hookScore，不再直接污染 rootScore（定级的 hasHiding 门槛仍按类别判定，不变）。
  H-class evidence polluted the wrong dimension: dimensionOf() gained a HIDING branch — H-class items (hiding and deception traces) belong to the HOOK (observation integrity) dimension instead of falling into the default ROOT. The H.HIDING_SIGNAL that analyze.cpp generates from cross-check contradictions now feeds only hookScore and no longer pollutes rootScore directly (the grade's hasHiding gate still judges by category, unchanged).
- 证据去重顺序依赖：去重 rank 改为全序——状态 > severity 权重 > confidence（VERIFIED > HIGH > MEDIUM > LOW）> 非启发式优先。之前同 ID 同严重度时保留先到的一条，而阶段顺序是 shuffled 的，同一设备两次扫描可算出不同分数；现在同 ID 永远选同一条。
  Dedup was order-dependent: the dedup rank is now a total order — state > severity weight > confidence (VERIFIED > HIGH > MEDIUM > LOW) > non-heuristic first. Previously, same ID + same severity kept whichever arrived first, and stage order is shuffled, so the same device could score differently across two scans; the same entry now always wins.
- 取消按钮不真正取消：MainActivity 现在把 isCancelled 真正接进引擎（新增 ScanState.cancelRequested）；取消只置标志，scanning 保持 true 直到引擎返回，不再出现「旧扫描在后台继续跑、UI 却允许开新一轮」。引擎收尾再补查一次取消：在最后一个阶段之后才取消的，也会落 Z.CANCELLED 并强制 INCOMPLETE。
  The Cancel button did not actually cancel: MainActivity now wires isCancelled into the engine for real (new ScanState.cancelRequested). Cancelling only sets the flag; scanning stays true until the engine returns, so "old scan still running in the background while the UI allows a new one" cannot happen. The engine re-checks cancellation at the end: a cancel arriving after the last stage still lands Z.CANCELLED and forces INCOMPLETE.
- 构建文档顺序与死配置：README 改为先 export 签名凭据、再跑 native.sh（顺序反了 .so 没有签名基线）；native.sh 缺失基线由「只警告」改为默认直接报错退出（本地调试可用 RS_ALLOW_NO_SIGNER=1 放行）。审查所说「Gradle 没有 externalNativeBuild 配置」不准确：build.gradle.kts 里有两处，但因无顶层 cmake path 属死配置，实际打包走 jniLibs 预置库——文档口径已按此澄清。已交付 APK 走手工管线、基线经核对在 .so 内，不受影响。
  Build docs order and dead config: the README now exports signing credentials before running native.sh (reversed, the .so gets no signature baseline); a missing baseline in native.sh changed from "warn only" to a hard error by default (RS_ALLOW_NO_SIGNER=1 allows local debugging). The review's "Gradle has no externalNativeBuild config" was inaccurate: build.gradle.kts has two, but with no top-level cmake path they are dead config and packaging actually uses the prebuilt jniLibs — the docs now say so. Delivered APKs come from the manual pipeline, and their baselines were verified inside the .so; unaffected.
- 凭据卫生：根目录 gradle.properties 中由旧文档引导写入的 RS_KS_PASS 行已删除，文件改为不入库（.gitignore 已覆盖），新增 gradle.properties.example 模板；BUILD-GRADLE.md 改为只教环境变量 / 仓库外 ~/.gradle 两种方式。若被删的那行曾是真实 keystore 密码，请按「已公开」处理并轮换——git 历史里仍有该文件的旧版本，本版无法改写历史。
  Credential hygiene: the RS_KS_PASS line that old docs had guided into the root gradle.properties is deleted, the file is no longer committed (.gitignore covers it), and a gradle.properties.example template was added; BUILD-GRADLE.md now teaches only environment variables or an out-of-repo ~/.gradle. If the deleted line ever held a real keystore password, treat it as public and rotate it — the file's old versions remain in git history, and this release cannot rewrite history.
- 其余修正：适用性判定不再把 fpNote（误报说明）拼进关键词匹配，避免真实证据被误判 NOT_APPLICABLE 缩小覆盖率分母；覆盖率折扣同比例作用于 hard/soft/total，保持 rootScore == hardScore + softScore，整体置信度真正计入可读率（coverage < 0.6 封顶 LOW）；X.TCP_LISTEN 标题改「本机网络命名空间存在本地监听端口」，与自己的 fp_note 口径一致；C.MOUNT_MULTI 补反方向比对（mounts 有而 mountinfo 没有的挂载点同样判不一致）；G.STRONGBOX 真正调用 setIsStrongBoxBacked(true) 生成 StrongBox 密钥，临时不可用时退回 TEE 并如实标注；JSON 导出补齐 nativeVersion、证据 state 与 dimension、四维分数、integrityBreach、crosses 明细——拿到导出文件可完整复算评分，导出失败不再留 0 字节幽灵条目，API 26–28 写公共目录失败时退回应用专属外部目录；仓库 jniLibs 的 .so 改为 strip 后再入库（已交付 APK 内的 .so 一直是 strip 过的，不受影响）。
  Remaining fixes: applicability no longer concatenates fpNote (the false-positive note) into keyword matching, so real evidence is not misjudged NOT_APPLICABLE and shrunk out of the coverage denominator; the coverage discount applies proportionally to hard/soft/total, keeping rootScore == hardScore + softScore, and overall confidence truly accounts for readability (coverage < 0.6 caps at LOW); X.TCP_LISTEN's title now reads "local listening port exists in this network namespace", matching its own fp_note (/proc/net is a namespace-level view); C.MOUNT_MULTI compares in the reverse direction too (a mount in mounts but not mountinfo is equally inconsistent); G.STRONGBOX actually calls setIsStrongBoxBacked(true) to generate a StrongBox key, falling back to TEE with honest labeling when temporarily unavailable; JSON export gained nativeVersion, evidence state and dimension, the four dimension scores, integrityBreach, and crosses detail — an exported file can now fully recompute the score — failed exports no longer leave 0-byte ghost entries, and on API 26–28 a failed public-directory write falls back to the app-specific external directory; the repo's jniLibs .so files are stripped before committing (the .so inside delivered APKs was always stripped; unaffected).
- 落地项：移除未使用的 INTERNET 权限（全仓无外网调用，仅有 127.0.0.1 回环探测与网卡枚举，均不需要该权限）；JSON 导出的设备段不再含完整 Build 指纹，改为 fingerprintSha256 摘要（同构建可比对，不泄露确切构建串）。审查提及的设置项无消费者、Attestation 双实现统一、测试缺失等问题已确认属实，属结构性后续项，本版未动。
  Also landed: the unused INTERNET permission removed (the codebase makes no external calls — only 127.0.0.1 loopback probes and interface enumeration, neither needs it); the JSON export's device section no longer contains the full Build fingerprint, replaced by a fingerprintSha256 digest (comparable across the same build without leaking the exact build string). The review's other points — consumer-less settings, dual attestation implementations, missing tests — were confirmed real but are structural follow-ups, untouched in this release.
- 验证：Native 三 ABI 全量编译通过；Kotlin 全量编译 0 错误；交付 APK 通过全量 dex 类审计（管线既定出厂门槛）。
  Verification: native compiles clean for all three ABIs; Kotlin compiles with 0 errors; the delivered APK passed the full dex class audit (the pipeline's standing release gate).

## 2.22.42 — 2026-11-14

**基于对 2.22.41 的第二份独立静态审查修复（3 条 P1 + 4 条 P2，全部经逐条复核属实）。**
**Round-2 independent static review fixes, against 2.22.41 (3 P1 + 4 P2, all confirmed real on item-by-item re-check).**

- Key Attestation 根信任锚正式钉扎：TRUSTED_ROOT_CERT_SHA256 填入两枚 Google 证明根指纹（构建方从官方端点下载证书 DER 后本机计算，2026-11-14）：RSA 根与 2025 年启用的 EC 根「Key Attestation CA1」。空集不再放行——链根不在钉扎集合内即未锚定，cryptoTrusted 不成立；rootIsGoogle 降为信息字段。verifyChain 补路径校验：每张证书有效期、签发者 BasicConstraints 必须为 CA、KeyUsage 若存在必须含 keyCertSign。新增 keymasterSecurityLevel 解析，与 attestationSecurityLevel 交叉核对：后者声称 TEE 而前者为 Software 时硬件级不成立。使用 OEM 自有证明根的设备暂不给 VERIFIED（已知取舍，后续以可维护的 OEM 根集合扩展）。
  The Key Attestation root trust anchor is pinned for real: TRUSTED_ROOT_CERT_SHA256 now holds two Google attestation root fingerprints (computed locally from certificate DER downloaded from the official endpoint, 2026-11-14) — the RSA root and the EC root "Key Attestation CA1" enabled in 2025. An empty set no longer passes: a chain root outside the pinned set is unanchored and cryptoTrusted does not hold; rootIsGoogle is demoted to an informational field. verifyChain gained path validation: each certificate's validity period, the issuer's BasicConstraints must be CA, and KeyUsage, when present, must include keyCertSign. A new keymasterSecurityLevel parse cross-checks attestationSecurityLevel: if the latter claims TEE while the former says Software, hardware-level does not hold. Devices using an OEM's own attestation root do not get VERIFIED for now (a known trade-off, to be extended later with a maintainable OEM root set).
- Native 发行一致性：三个 ABI 的 jniLibs 预置库全部按本版源码重编（ELF 内版本标识为 2.22.42），不再是 2.22.40 的旧二进制。版本强校验：DetectorEngine 比对 native 报告版本与应用版本，不一致（或 native 未报版本）落 Z.VERSION 并由评分器强制 INCOMPLETE；协议异常同理（Z.PROTOCOL）。
  Native release consistency: all three ABIs' prebuilt jniLibs were recompiled from this version's source (the ELF version marker reads 2.22.42), no longer the stale 2.22.40 binaries. Version enforcement: DetectorEngine compares the native report's version against the app version; a mismatch (or no native version) lands Z.VERSION and the scorer forces INCOMPLETE; protocol anomalies likewise (Z.PROTOCOL).
- Magisk 家族 EACCES 推断收紧：方案路径只有 EACCES、无任何可达路径时不再判 HIT——EACCES 同样可由中间目录权限、SELinux 遍历拒绝、厂商 LSM 在正常 ROM 上产生。改判 UNKNOWN；有路径真正可达时仍为 CRITICAL HIT。
  Magisk-family EACCES inference tightened: when scheme paths return only EACCES with no reachable path, it no longer judges HIT — EACCES can equally come from intermediate directory permissions, SELinux traversal denials, or a vendor LSM on a stock ROM. It is UNKNOWN now; a genuinely reachable path is still a CRITICAL HIT.
- 其余修正：F.FTRACE 函数表可读改为能力记录——current_tracer=nop 且无已启用函数时 PASS，存在活动 tracer 或已启用函数才 HIT，活动状态不可读则 UNKNOWN；F.BPF_PROGS 在 /sys/fs/bpf 不存在（bpffs 未挂载）时由 PASS 改 UNKNOWN，pinned 程序先分系统自带与未知来源、只有未知来源才 HIT；AttestationDetector 在证书链为空提前返回时也清理 KeyStore 别名，不再残留条目；NativeReportParser 对未知类别不再默认落 ARTIFACT（ROOT 维度）——降级为 UNKNOWN 并落 CROSSCHECK，同时置 protocolError 使整次扫描 INCOMPLETE。
  Remaining fixes: F.FTRACE treats a readable function table as a capability record — current_tracer=nop with no enabled functions is a PASS, an active tracer or enabled functions are a HIT, and unreadable active state is UNKNOWN. F.BPF_PROGS is UNKNOWN (was PASS) when /sys/fs/bpf does not exist (bpffs not mounted), and pinned programs are split into system-bundled vs unknown origin, with only unknown origins hitting. AttestationDetector cleans up its KeyStore alias even on the empty-chain early return, leaving no residue. NativeReportParser no longer defaults unknown categories to ARTIFACT (the ROOT dimension) — they degrade to UNKNOWN under CROSSCHECK, with protocolError set so the whole scan is INCOMPLETE.
- 验证：Native 三 ABI 全量编译通过；Kotlin 全量编译 0 错误；仓库 jniLibs 与交付 APK 内的 .so 均逐个核对版本字符串为 2.22.42。
  Verification: native compiles clean for all three ABIs; Kotlin compiles with 0 errors; the repo jniLibs and the delivered APK's .so files were each checked to carry the 2.22.42 version string.

## 2.22.41 — 2026-11-14

**基于对 2.22.40 的第三方静态源码审查修复（7 条 P1 + 5 条 P2，全部经逐条复核属实）：修结论安全性问题。**
**Third-party static source review fixes, against 2.22.40 (7 P1 + 5 P2, all confirmed real on item-by-item re-check): verdict-safety problems.**

- 评分与结论安全：取消扫描不再可能返回 CLEAN——定级条件纳入「已取消」；每个未执行阶段各落一条 SKIPPED（Z.CANCELLED[.n]），不再用一条代表全部。Category.CROSSCHECK 维度归位到 HOOK，交叉验证矛盾加分改计 hookScore，单条 G.CROSS_PROC（CRITICAL）不再能凑满 ROOTED 阈值。Z.STAGE_FAIL 改为每阶段独立 ID，失败阶段数不再被 ID 去重吞掉。
  Scoring and verdict safety: a cancelled scan can no longer return CLEAN — "cancelled" is part of the grading conditions; every unexecuted stage lands its own SKIPPED entry (Z.CANCELLED[.n]) instead of one entry standing for all. Category.CROSSCHECK moved to its proper HOOK dimension, cross-check contradiction points now count into hookScore, and a single G.CROSS_PROC (CRITICAL) can no longer fill the ROOTED threshold by itself. Z.STAGE_FAIL is a per-stage ID now, so the number of failed stages is not swallowed by ID dedup.
- 读失败不再误判 PASS（新增 util 层带 errno 的探测 accessErrnoRaw）：F.SELINUX_POLICY 仅当实测 enforce == "1" 时不可读才 PASS，否则 UNKNOWN；F.KSU_NODE 在节点 EACCES/EPERM 或状态不明时 UNKNOWN；F.KPROBES / F.FTRACE 不可读改 UNKNOWN，可读且为空才是 PASS；F.BPF_PROGS 目录不可枚举（EACCES/EPERM）时 UNKNOWN。
  Read failures no longer pose as PASS (new errno-carrying probe accessErrnoRaw in the util layer): F.SELINUX_POLICY passes on unreadable only when enforce was actually measured as "1", otherwise UNKNOWN; F.KSU_NODE is UNKNOWN on EACCES/EPERM or unclear node state; F.KPROBES / F.FTRACE are UNKNOWN when unreadable and PASS only when readable and empty; F.BPF_PROGS is UNKNOWN when the directory cannot be enumerated (EACCES/EPERM).
- 签名基线注入链（三处断裂一并修复）：CMakeLists 真正消费 -DRS_SIGNER_SHA256 并转发为带引号的编译定义（此前宏从未被定义，I.APK_SIGNATURE 恒 UNKNOWN）；native.sh 双路线（clang 直编 / CMake）均注入基线——环境变量，或从 RS_KS_PATH 的 keystore 用 keytool 派生，缺失时明确警告；jni.cpp 的 sigCheck 比较改大小写不敏感（Java 侧小写 hex 与构建侧大写基线此前永远不相等）。
  The signature-baseline injection chain (three breaks fixed together): CMakeLists actually consumes -DRS_SIGNER_SHA256 and forwards it as a quoted compile definition (the macro was never defined before, so I.APK_SIGNATURE was permanently UNKNOWN); native.sh injects the baseline on both routes (direct clang / CMake) — from an environment variable, or derived via keytool from the keystore at RS_KS_PATH, with a clear warning when missing; jni.cpp's sigCheck comparison is case-insensitive now (the Java side's lowercase hex and the build side's uppercase baseline could never have been equal).
- Key Attestation：parseKeyDescription 分离 softwareEnforced / teeEnforced，TEE/StrongBox 时启动状态三件套只认 teeEnforced；cryptoTrusted 纳入链根锚定（rootIsGoogle，此前只计算不使用），预留根证书指纹钉扎集 TRUSTED_ROOT_CERT_SHA256；定性分支改用 Confidence.VERIFIED（此前无任何 VERIFIED 生产者，评分器的 integrityBreach 永远触发不了）；verifiedBootState == 0 且 deviceLocked 缺失时改判 UNKNOWN，不再落入「未锁定」HIT 分支。
  Key Attestation: parseKeyDescription separates softwareEnforced / teeEnforced, and under TEE/StrongBox the boot-state trio is read from teeEnforced only; cryptoTrusted now includes chain-root anchoring (rootIsGoogle — previously computed but never used), with a reserved root-certificate fingerprint pin set TRUSTED_ROOT_CERT_SHA256; the qualitative branch uses Confidence.VERIFIED (there was no VERIFIED producer before, so the scorer's integrityBreach could never trigger); verifiedBootState == 0 with deviceLocked missing is UNKNOWN now instead of falling into the "unlocked" HIT branch.
- 验证：Native arm64-v8a 全量编译通过（带签名基线定义）；Kotlin model/engine/util 编译 0 错误；native.sh 语法检查通过。jniLibs 预置 .so 需用 native.sh 按新版源码重建。
  Verification: native arm64-v8a compiles clean (with the signature baseline defined); Kotlin model/engine/util compile with 0 errors; native.sh passes a syntax check. The prebuilt jniLibs .so files must be rebuilt from the new source with native.sh.

## 2.22.40 — 2026-11-14

- 跨进程对比从噪声黑名单翻转为确定性白名单（54 项），覆盖门槛与差异判定顺序修正。
  Cross-process comparison flipped from a noise blacklist to a deterministic whitelist (54 items); coverage thresholds and divergence-judgment order corrected.
- JNI 回调本地引用 use-after-free 修复（NewLocalRef）。
  Fixed a JNI callback local-reference use-after-free (NewLocalRef).

> 2.22.40 及更早版本为快速迭代期，变更细节以代码注释为准；本仓库的 git 历史自 2.22.41 起。
> Versions 2.22.40 and earlier were a rapid-iteration period; for their change details, see the code comments. This repository's git history starts at 2.22.41.
