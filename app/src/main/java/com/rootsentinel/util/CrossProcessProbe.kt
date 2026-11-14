package com.rootsentinel.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.rootsentinel.engine.ProbeService
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 跨进程探针客户端（M10）。
 *
 * 流程：
 *   1. bind 到 :probe 进程的 ProbeService
 *   2. 通过 Messenger 让它跑一次 native 扫描
 *   3. 把它的 JSON 与主进程的 JSON 逐项对比
 *   4. 同一检测项两边 verdict 不一致 → 有进程级 hook
 *
 * 会超时（默认 12s），拿不到就返回 error，不会卡住 UI。
 */
object CrossProcessProbe {

    data class Result(
        val json: String?,
        val pid: Int,
        val error: String?,
    )

    fun probe(context: Context, timeoutMs: Long = 12000): Result {
        var json: String? = null
        var pid = -1
        var error: String? = null
        val latch = CountDownLatch(1)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    error = "binder == null"
                    latch.countDown()
                    return
                }

                val req = Message.obtain(null, ProbeService.MSG_PROBE)
                val reply = Messenger(Handler(Looper.getMainLooper()) { m ->
                    if (m.what == ProbeService.MSG_RESULT) {
                        json = m.data.getString(ProbeService.KEY_JSON)
                        pid = m.data.getInt(ProbeService.KEY_PID)
                        latch.countDown()
                    }
                    true
                })
                req.replyTo = reply

                runCatching { Messenger(binder).send(req) }.onFailure {
                    error = it.message
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                error = "service disconnected"
                latch.countDown()
            }
        }

        try {
            val intent = Intent(context, ProbeService::class.java)
            val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!ok) {
                error = "bindService 返回 false"
            } else {
                // ★ 必须检查 await 的返回值。
                //   latch.await(timeout) 超时返回 false 而不是抛异常，
                //   之前完全不看返回值 —— 超时后 json 仍是 null，
                //   上层只能看到「探针不可用」，无法区分
                //   「进程起不来」和「进程起来了但没在时限内返回」。
                val done = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                if (!done && json == null && error == null) {
                    error = "探针超时：${timeoutMs}ms 内未返回结果"
                }
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
        } finally {
            runCatching { context.unbindService(conn) }
        }

        return Result(json, pid, error)
    }

    /**
     * 对比两份 JSON 的判定，返回差异列表。
     *
     * v2.4.0 修复两点：
     *  1. 之前只比较「两侧都有」的 ID，缺失项不参与结论，
     *     上层只看 mismatched.isEmpty() 就宣布「完全一致」。
     *     现在 missing 会体现在 complete=false 上，调用方必须降级处理。
     *  2. 之前用正则扫 JSON，且同 ID 多条证据时后者覆盖前者
     *     （可能把 HIT 覆盖成 PASS）。现在用 JSONObject 解析，同 ID 取最严重结论。
     */
    data class Diff(
        val mismatched: List<String> = emptyList(),
        /** UNKNOWN ↔ PASS 这类弱差异，单独统计但不当成 hook 铁证 */
        val weak: List<String> = emptyList(),
        val onlyInMain: List<String> = emptyList(),
        val onlyInProbe: List<String> = emptyList(),
        val compared: Int = 0,
        /** 两侧检测项集合是否对齐。false 时不能宣称「结论一致」 */
        val complete: Boolean = true,
        /** 白名单里本应可比的项数 */
        val expectedCompared: Int = 0,
        /** 达到多少项才算覆盖充分 */
        val minCompared: Int = 0,
    )

    // ================================================================
    // 比较白名单（v2.22.40 从黑名单翻转）
    // ================================================================
    //
    // ---------------------------------------------------------------- 为什么翻转
    // v2.22.39 及之前用的是黑名单 NOISE_IDS：列出「哪些项会波动」然后排除。
    // 这有个根本问题 —— 黑名单是**猜**出来的：
    //   · 漏列一个波动项 → 产生假阳性（误报 hook）
    //   · 多列一个稳定项 → 丢失真信号（漏报 hook）
    // 而且新增检测项时很容易忘了往黑名单里加。
    //
    // 现在改成白名单：只比较**与进程自身运行时状态完全无关**的项。
    // 这类项在正常情况下两个进程必然给出**完全相同**的结论，
    // 因此任何差异都是强信号，信噪比远高于黑名单。
    //
    // 白名单里只放「读的是全局系统状态」的检测项：
    //   B 属性、F SELinux/内核、A 方案指纹、G 硬件证明
    // 明确排除：D 内存/线程/fd、X 时间、I 进程自身身份。
    private val DETERMINISTIC_IDS = setOf(
        // ---- A 经典痕迹：读的是文件系统与属性，与进程无关
        "A.SU_PATH", "A.SU_BINARY", "A.MAGISK_PATH", "A.MAGISK_MOUNT",
        "A.KSU_NODE", "A.KSU_PATH", "A.APATCH_PATH",
        "A.DEBUG_RAMDISK", "A.MODULES_DIR", "A.INIT_SCRIPT",
        "A.PROP_KEYWORD", "A.EMULATOR", "A.SENSOR_COUNT",

        // ---- B 属性与启动状态：全部来自 getprop，与进程无关
        "B.TEST_KEYS", "B.DEBUGGABLE", "B.BUILD_TYPE", "B.BUILD_TAGS",
        "B.VB_STATE", "B.FLASH_LOCKED", "B.VBMETA_DIGEST",
        "B.PROP_TAMPER", "B.UNAME_MISMATCH", "B.UNAME_MULTI",
        "B.SEC_PATCH", "B.PKG_VISIBLE",

        // ---- C 挂载与命名空间：读 /proc/self/mountinfo 之外的全局挂载表
        "C.MOUNT_OVERLAY", "C.MOUNT_MAGISK", "C.MOUNT_HIDE",
        "C.NS_DIVERGE", "C.FS_META", "C.FS_DEEP", "C.SANDBOX",

        // ---- F SELinux 与内核：全局状态
        "F.SELINUX_ENFORCE", "F.SELINUX_POLICY", "F.SELINUX_FILTERED",
        "F.OVER_HIDING", "F.SELINUX_XCHECK",
        "F.KERNEL_DIRTY", "F.KERNEL_VERSION", "F.KERNEL_TAINT",
        "F.KERNEL_MODULE", "F.INIT_CHAIN", "F.KERNEL_EOL",
        "F.KALLSYMS", "F.KPTR_RESTRICT",

        // ---- G 硬件证明：TEE 签名，与进程完全无关
        "G.ATTEST_CHAIN", "G.ATTESTATION_EXT", "G.CHAIN", "G.ROOT_ANCHOR",
        "G.STRONGBOX", "G.PERF_PARANOID", "G.BPF",

        // ---- H 隐藏痕迹：读的是全局文件系统状态
        "H.HIDING_SIGNAL",

        // ---- Z 方案画像：由上面各项推导，间接确定
        "Z.SCHEME",
    )

    fun compare(mainJson: String, probeJson: String): Diff {
        val a = parseVerdicts(mainJson)
        val b = parseVerdicts(probeJson)

        val mismatched = mutableListOf<String>()
        val weak = mutableListOf<String>()
        var compared = 0

        // 只比较白名单内的确定性项
        for ((id, va) in a) {
            if (id !in DETERMINISTIC_IDS) continue
            val vb = b[id] ?: continue
            compared++
            if (va == vb) continue

            // 确定性项在正常情况下必须完全相同。
            // 所以这里不再区分「强/弱」—— 任何差异都值得记录。
            // 但仍按方向分级：
            //   HIT↔PASS 反转  → 强信号（有进程被拦截）
            //   其它组合        → 弱信号（可能是一侧读不到）
            val strong = (va == "HIT" && vb == "PASS") ||
                (va == "PASS" && vb == "HIT")
            if (strong) {
                mismatched.add("$id: 主进程=$va 探针=$vb ← 强不一致")
            } else {
                weak.add("$id: 主进程=$va 探针=$vb")
            }
        }

        val onlyMain = a.keys.filter { it in DETERMINISTIC_IDS && it !in b.keys }
        val onlyProbe = b.keys.filter { it in DETERMINISTIC_IDS && it !in a.keys }

        // ---------------------------------------------------------------- 门槛
        //
        // v2.22.39 及之前：complete = (missing <= 3) && (compared >= 40)
        //
        // 问题：白名单里只有约 45 项，而且部分项在特定 ROM 上可能不产出。
        // 固定 40 太严，会让正常设备也判「覆盖不足」。
        //
        // 现在的口径：
        //   · 分母改为「白名单项数」而不是硬编码 40
        //   · 要求至少比到白名单的 60%
        //   · 缺项允许 5 项以内（白名单项少，3 项太紧）
        val expected = DETERMINISTIC_IDS.size
        val minCompared = (expected * 0.6).toInt()
        val missing = onlyMain.size + onlyProbe.size
        val complete = missing <= 5 && compared >= minCompared

        return Diff(
            mismatched = mismatched,
            weak = weak,
            onlyInMain = onlyMain,
            onlyInProbe = onlyProbe,
            compared = compared,
            complete = complete,
            expectedCompared = expected,
            minCompared = minCompared,
        )
    }

    private val RANK = mapOf("PASS" to 0, "UNKNOWN" to 1, "HIT" to 2)

    private fun worse(a: String?, b: String): String {
        if (a == null) return b
        return if ((RANK[b] ?: 1) >= (RANK[a] ?: 1)) b else a
    }

    /** 用正式 JSON 解析器读取 id → verdict；同 ID 多条时取最严重结论 */
    private fun parseVerdicts(json: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        if (json.isEmpty()) return out

        try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("items") ?: return regexFallback(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val v = o.optString("verdict")
                if (v.isEmpty()) continue
                out[id] = worse(out[id], v)
            }
            return out
        } catch (t: Throwable) {
            // JSON 损坏时退回正则，至少不完全丢数据
            return regexFallback(json)
        }
    }

    private fun regexFallback(json: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val re = Regex(
            "\"id\"\\s*:\\s*\"([^\"]+)\"[\\s\\S]{0,240}?\"verdict\"\\s*:\\s*\"([A-Z]+)\""
        )
        for (m in re.findAll(json)) {
            val id = m.groupValues[1]
            val v = m.groupValues[2]
            if (id.isNotEmpty()) out[id] = worse(out[id], v)
        }
        return out
    }
}