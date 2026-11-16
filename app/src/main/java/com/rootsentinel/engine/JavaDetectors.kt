package com.rootsentinel.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict

/**
 * Java 层检测。
 *
 * 为什么 native 明明更可靠还要做 Java 层？
 * 因为 PackageManager 走 Binder，SELinux 的文件规则完全管不到它。
 * 「native 说干净、Java 说看到管理器」这种矛盾本身就是最强的隐藏证据。
 *
 * v2 包第三节要求：同一包名必须在多个不同接口上都问一遍，
 * 结果与异常类型都必须一致。v2.22.73 起扩到 9 个 Binder 通道
 * + 1 个文件系统 stat 通道（非对称使用，见 checkPackages）。
 */
class JavaDetectors(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    // 已知 root 管理器 / 隐藏工具包名
    private val managerPackages = listOf(
        "com.topjohnwu.magisk",
        "io.github.huskydg.magisk",
        "me.weishu.kernelsu",
        "com.sukisu.ultra",
        "com.sukisu.nova",
        "io.milksu.manager",
        "me.inkdye.vivoksu",
        "com.rifsxd.ksunext",
        "com.sakisu.sakisu",
        "me.bmax.apatch",
        "org.lsposed.manager",
        "org.matrix.vector.manager",
        "de.robv.android.xposed.installer",
        "io.github.lsposed.manager",
        "moe.shizuku.privileged.api",
        "org.frknkrc44.hma_oss",
    )

    fun collect(): List<Evidence> {
        val out = mutableListOf<Evidence>()
        out += checkPackages()
        out += checkUidConsistency()
        out += checkDebugState()
        out += checkInstallSource()
        return out
    }

    // ---------------------------------------------------------------- 包管理多接口交叉
    private fun checkPackages(): List<Evidence> {
        val out = mutableListOf<Evidence>()

        for (pkg in managerPackages) {
            val observations = mutableListOf<Pair<String, String>>()

            // 接口 1: getPackageInfo
            val byInfo = runCatching {
                pm.getPackageInfo(pkg, 0)
            }.fold(
                onSuccess = { observations += "getPackageInfo" to "found"; true },
                onFailure = { e ->
                    observations += "getPackageInfo" to (e.javaClass.simpleName)
                    false
                },
            )

            // 接口 2: getApplicationInfo
            val byApp = runCatching {
                pm.getApplicationInfo(pkg, 0)
            }.fold(
                onSuccess = { observations += "getApplicationInfo" to "found"; true },
                onFailure = { e ->
                    observations += "getApplicationInfo" to (e.javaClass.simpleName)
                    false
                },
            )

            // 接口 3: getPackageUid
            var byUid = false
            runCatching { pm.getPackageUid(pkg, 0) }
                .onSuccess { uid ->
                    observations += "getPackageUid" to uid.toString()
                    byUid = uid > 0
                }
                .onFailure { e ->
                    observations += "getPackageUid" to e.javaClass.simpleName
                }

            // 接口 4: getInstalledPackages 全量遍历
            var inList = false
            runCatching {
                pm.getInstalledPackages(0)
            }.onSuccess { list ->
                inList = list.any { it.packageName == pkg }
                observations += "getInstalledPackages" to if (inList) "found" else "absent"
            }.onFailure { e ->
                observations += "getInstalledPackages" to e.javaClass.simpleName
            }

            // 接口 5: Intent 解析（很多隐藏模块只改 getPackageInfo，忘了改这里）
            val byIntent = runCatching {
                pm.getLaunchIntentForPackage(pkg)
            }.getOrNull() != null
            observations += "launchIntent" to if (byIntent) "resolved" else "null"

            // 接口 6: resolveActivity（MAIN 动作 + setPackage；比 launchIntent
            // 宽，不要求 LAUNCHER 类别，走 PMS 的意图解析表）
            val byActivity = runCatching {
                pm.resolveActivity(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)
            }.getOrNull() != null
            observations += "resolveActivity" to if (byActivity) "resolved" else "null"

            // 接口 7: queryIntentActivities（与 resolveActivity 是两个 PMS 方法，
            // 钩子常只补其中一个）
            val byQuery = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)
            }.getOrNull()?.isNotEmpty() == true
            observations += "queryActivities" to if (byQuery) "found" else "absent"

            // 接口 8: getResourcesForApplication（资源加载路径，
            // 与包信息查询是 PMS 里两条不同的代码路径）
            val byResources = runCatching {
                pm.getResourcesForApplication(pkg)
            }.fold(
                onSuccess = { observations += "resources" to "found"; true },
                onFailure = { e ->
                    observations += "resources" to (e.javaClass.simpleName)
                    false
                },
            )

            // 接口 9: getPackagesForUid 反查（先由接口 3 拿到 uid 再反查包名；
            // 正反两个方向的映射在 PMS 里分开维护，钩子容易只改一个方向）
            var byUidReverse = false
            if (byUid) {
                val uid = runCatching { pm.getPackageUid(pkg, 0) }.getOrNull()
                if (uid != null) {
                    byUidReverse = runCatching {
                        pm.getPackagesForUid(uid)?.contains(pkg) == true
                    }.getOrDefault(false)
                    observations += "uidReverse" to if (byUidReverse) "found" else "absent"
                }
            }

            // 通道 10: /data/data/<pkg> 的 stat（文件系统通道，非 Binder）。
            // 非对称使用：stat 到只作为「存在」的独立旁证 —— 某些机型连
            // 穿越 /data/data 都被挡，stat 不到不能反推「没装」，
            // 所以不计入 hits/一致性，只在 PM 全盲而它可见时单独立案。
            val byDataDir = runCatching {
                java.io.File("/data/data/$pkg").exists()
            }.getOrDefault(false)
            observations += "dataDirStat" to if (byDataDir) "found" else "absent"

            val hits = listOf(
                byInfo, byApp, byUid, inList, byIntent, byActivity, byQuery,
                byResources, byUidReverse,
            ).count { it }

            // PM 九个通道全盲、文件系统却看得见 → 同样是针对性过滤
            if (hits == 0 && byDataDir) {
                out += Evidence(
                    id = "X.PKG_INCONSISTENT",
                    category = Category.CROSSCHECK,
                    severity = Severity.CRITICAL,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.HIT,
                    title = "包管理接口全盲但数据目录存在: $pkg",
                    detail = "九个 Binder 通道均不可见，/data/data 数据目录却存在 → 存在针对性过滤（HMA/Shamiko 类）",
                    vectors = observations.map { "${it.first}=${it.second}" },
                    fpNote = "",
                )
                continue
            }

            if (hits == 0) continue

            // 不一致 = 高置信度隐藏证据（核心四通道必须同结论；
            // 其余通道只进观测向量，避免无 MAIN 活动的包误报矛盾）
            val consistent = (byInfo == byApp) && (byInfo == inList) && (byInfo == byResources)
            val e = if (consistent) {
                Evidence(
                    id = "B.PKG_VISIBLE",
                    category = Category.PROPERTY,
                    // 注意：包可见只能说明「装了管理器」，设备是否已 root 未知。
                    // 之前给 CRITICAL（40 分）会导致「仅安装管理器 = 直接判 Rooted」。
                    severity = Severity.HIGH,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.HIT,
                    title = "管理器包可见: $pkg",
                    detail = "核心接口一致返回结果（共 ${observations.size} 个通道观测）",
                    fpNote = "包可见只证明「装了这个 App」，"
                        + "不证明设备已 root —— 必须结合 A.SU_PATH / A.DATA_ADB 判断",
                    vectors = observations.map { "${it.first}=${it.second}" },
                )
            } else {
                Evidence(
                    id = "X.PKG_INCONSISTENT",
                    category = Category.CROSSCHECK,
                    severity = Severity.CRITICAL,
                    confidence = Confidence.HIGH,
                    verdict = Verdict.HIT,
                    title = "包管理接口结论矛盾: $pkg",
                    detail = "部分接口能看到、部分看不到 → 存在针对性过滤（HMA/Shamiko 类）",
                    vectors = observations.map { "${it.first}=${it.second}" },
                    fpNote = "",
                )
            }
            out += e
        }

        if (out.isEmpty()) {
            out += Evidence(
                id = "B.PKG_VISIBLE",
                category = Category.PROPERTY,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "${managerPackages.size} 个管理器包名在所有接口下均不可见",
            )
        }
        return out
    }

    // ---------------------------------------------------------------- UID 一致性
    private fun checkUidConsistency(): List<Evidence> {
        val myUid = Process.myUid()
        val dataDir = context.dataDir
        val ownerUid = runCatching {
            val st = android.system.Os.stat(dataDir.absolutePath)
            st.st_uid
        }.getOrNull()

        val out = mutableListOf<Evidence>()
        if (ownerUid != null && ownerUid != myUid) {
            out += Evidence(
                id = "X.UID_MISMATCH",
                category = Category.CROSSCHECK,
                severity = Severity.HIGH,
                confidence = Confidence.HIGH,
                verdict = Verdict.HIT,
                title = "自身数据目录 owner 与 Process.myUid() 不一致",
                detail = "myUid=$myUid ownerUid=$ownerUid",
                vectors = listOf("myUid=$myUid", "dataDirOwner=$ownerUid"),
                fpNote = "多开/工作资料/虚拟化环境同样会命中",
            )
        } else {
            out += Evidence(
                id = "C.UID_CONSISTENT",
                category = Category.PROPERTY,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "UID 与数据目录 owner 一致",
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 调试状态
    private fun checkDebugState(): List<Evidence> {
        val out = mutableListOf<Evidence>()
        val appInfo = context.applicationInfo

        if ((appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            out += Evidence(
                id = "I.SELF_DEBUGGABLE",
                category = Category.INTEGRITY,
                severity = Severity.HIGH,
                confidence = Confidence.HIGH,
                verdict = Verdict.HIT,
                title = "检测器自身是 debuggable 构建",
                detail = "debuggable 包可被任意进程 attach，结果不可信",
                fpNote = "",
            )
        } else {
            out += Evidence(
                id = "I.SELF_DEBUGGABLE",
                category = Category.INTEGRITY,
                severity = Severity.INFO,
                confidence = Confidence.HIGH,
                verdict = Verdict.PASS,
                title = "检测器自身不可调试",
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 安装来源
    private fun checkInstallSource(): List<Evidence> {
        val installer = runCatching {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        }.getOrNull()

        val out = mutableListOf<Evidence>()
        if (installer != null &&
            installer != "com.android.vending" &&
            installer != "com.google.android.packageinstaller" &&
            installer != "com.android.packageinstaller"
        ) {
            out += Evidence(
                id = "I.INSTALL_SOURCE",
                category = Category.INTEGRITY,
                severity = Severity.LOW,
                confidence = Confidence.MEDIUM,
                verdict = Verdict.HIT,
                title = "检测器由非官方渠道安装: $installer",
                detail = "侧载包可能被重打包",
                fpNote = "adb install / 本地安装均会命中",
            )
        }
        return out
    }
}