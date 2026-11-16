package com.rootsentinel.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rootsentinel.model.Category
import com.rootsentinel.model.Confidence
import com.rootsentinel.model.Evidence
import com.rootsentinel.model.Severity
import com.rootsentinel.model.Verdict
import java.net.NetworkInterface

/**
 * 网络层（M9）— 3 项
 *
 * 抓包 / 中间人必须先改流量路径：
 *   全局 HTTP 代理、VPN 接管、多出来的 tun/tap 网卡。
 */
class NetworkDetector(private val context: Context) {

    fun collect(): List<Evidence> = listOf(proxy(), vpn(), netInterfaces())

    // ------------------------------------------------------------- I.PROXY
    private fun proxy(): Evidence = try {
        val host = System.getProperty("http.proxyHost")
        val port = System.getProperty("http.proxyPort")
        if (host.isNullOrEmpty()) {
            ev("I.PROXY", Severity.INFO, Verdict.PASS, "无全局 HTTP 代理", Confidence.HIGH)
        } else {
            ev("I.PROXY", Severity.MEDIUM, Verdict.HIT, "设置了全局 HTTP 代理",
                Confidence.HIGH, vec = listOf("host=$host", "port=${port ?: "?"}"),
                detail = "所有 HTTP 流量会经过该代理",
                fp = "企业环境 / 开发调试属常见")
        }
    } catch (t: Throwable) {
        ev("I.PROXY", Severity.LOW, Verdict.UNKNOWN, "代理属性不可读")
    }

    // --------------------------------------------------------------- I.VPN
    private fun vpn(): Evidence = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ev("I.VPN", Severity.LOW, Verdict.UNKNOWN, "ConnectivityManager 不可用")
        val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return ev("I.VPN", Severity.HIGH, Verdict.HIT, "当前流量走 VPN",
                Confidence.HIGH,
                detail = "VPN 可以完全接管流量，是抓包的前置条件",
                fp = "用户可能正常使用 VPN")
        }
        // v2.22.66：旧实现只看默认网络，取不到就 UNKNOWN。补上
        // 全网络扫描——VPN 可能挂着但不是默认网络；全部网络都
        // 枚举不到时才保留 UNKNOWN。
        var swept = 0
        var vpnElsewhere = false
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            swept++
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpnElsewhere = true
                break
            }
        }
        when {
            vpnElsewhere -> ev("I.VPN", Severity.MEDIUM, Verdict.HIT, "存在 VPN 网络（非默认网络）",
                Confidence.MEDIUM,
                detail = "VPN 已建立但不是默认网络，流量可能部分经过它",
                fp = "用户可能正常使用 VPN")
            activeCaps != null || swept > 0 ->
                ev("I.VPN", Severity.INFO, Verdict.PASS, "当前流量不走 VPN", Confidence.HIGH)
            else -> ev("I.VPN", Severity.LOW, Verdict.UNKNOWN, "无活动网络")
        }
    } catch (t: Throwable) {
        ev("I.VPN", Severity.LOW, Verdict.UNKNOWN, "VPN 状态不可读")
    }

    // ------------------------------------------------------ I.NET_INTERFACES
    private fun netInterfaces(): Evidence = try {
        val list = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val names = list.mapNotNull { it.name }
        val tun = names.filter { it.startsWith("tun") || it.startsWith("tap") }

        if (tun.isNotEmpty()) {
            ev("I.NET_INTERFACES", Severity.MEDIUM, Verdict.HIT, "存在 tun/tap 网卡",
                Confidence.MEDIUM, vec = tun.take(4).map { "iface=$it" },
                detail = "tun/tap 是 VPN 的底层接口")
        } else {
            ev("I.NET_INTERFACES", Severity.INFO, Verdict.PASS, "无 tun/tap 网卡",
                Confidence.HIGH, vec = names.take(5).map { "iface=$it" })
        }
    } catch (t: Throwable) {
        ev("I.NET_INTERFACES", Severity.LOW, Verdict.UNKNOWN, "网卡列表不可读")
    }

    private fun ev(
        id: String, sev: Severity, v: Verdict, title: String,
        conf: Confidence = Confidence.MEDIUM,
        vec: List<String> = emptyList(), detail: String = "", fp: String = "",
    ) = Evidence(
        id = id, category = Category.INTEGRITY, severity = sev, confidence = conf,
        verdict = v, title = title, vectors = vec, detail = detail, fpNote = fp,
    )
}