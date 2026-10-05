package com.droiddeck.launcher.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * `network.txt`: what the session was given to work with, for the reports that say "the client
 * thinks WiFi is off" or "Firefox cannot resolve anything".
 *
 * The runtime's NetworkManager bridge reports Android's active link. Include whether the phone
 * had a validated link and which DNS servers the app wrote into the runtime's `resolv.conf`.
 *
 * **No network names.** The SSID is identifying and is deliberately not collected; the transport
 * type, the addresses' families and the DNS servers are what a diagnosis needs.
 */
object NetworkReport {
    private const val TAG = "NetworkReport"

    fun write(context: Context, target: File) {
        try {
            // Addresses as their kind ("<public IPv6>"), never the numbers: a public address is the
            // user's, and this file goes into shared session logs. The session's made-up hardware
            // address is stable per device (seeded from ANDROID_ID), so it is masked too.
            val text = LogRedactor.describeAddresses(build(context))
                .replace(Regex("(?m)^(\\s*mac )\\S+"), "$1<masked>")
            target.writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "无法写入 $target", e)
        }
    }

    private fun build(context: Context): String = buildString {
        append("会话启动时的网络状况\n")
        append("=============================\n")
        append("SSID 不会被记录，地址只显示类别而不显示具体数值。\n\n")
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        if (cm == null || network == null) {
            append("活动网络            无 — 手机报告没有连接\n")
        } else {
            val caps = cm.getNetworkCapabilities(network)
            val transport = when {
                caps == null -> "未知"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "其它"
            }
            append("传输类型            ").append(transport).append('\n')
            append("已验证              ")
                .append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: "未知")
                .append("   （链路正常却是 false，通常是强制门户）\n")
            append("计费网络            ")
                .append(caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } ?: "未知")
                .append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps != null) {
                append("链路速度            下行 ").append(caps.linkDownstreamBandwidthKbps)
                    .append(" kbps / 上行 ").append(caps.linkUpstreamBandwidthKbps).append(" kbps\n")
            }
            val link = cm.getLinkProperties(network)
            append("接口                ").append(link?.interfaceName ?: "未知").append('\n')
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append("MTU                 ").append(link?.mtu ?: 0).append('\n')
            }
            val v4 = link?.linkAddresses.orEmpty().count { it.address is Inet4Address }
            val v6 = link?.linkAddresses.orEmpty().count { it.address is Inet6Address }
            append("地址                ").append(v4).append(" 个 IPv4，").append(v6).append(" 个 IPv6")
                .append(if (v4 == 0) "   （没有 IPv4 — Steam 的内容服务器需要它）" else "").append('\n')
            append("系统提供的 DNS      ")
                .append(link?.dnsServers.orEmpty().joinToString(", ") { it.hostAddress ?: "?" }.ifEmpty { "无" })
                .append('\n')
            append("搜索域              ").append(link?.domains ?: "无").append('\n')
        }

        append("\n运行时拿到的配置\n")
        append("--------------------------\n")
        // The app writes this file from the live link at every change; it is what everything inside
        // the session resolves with, so a mismatch with the system's list above is the bug itself.
        val resolv = File(LinuxRuntime.rootDir(context), "etc/resolv.conf")
        if (resolv.isFile) {
            append("etc/resolv.conf:\n")
            FileUtils.readString(resolv)?.lines()?.forEach { append("    ").append(it).append('\n') }
        } else {
            append("etc/resolv.conf 尚不存在 — 会话链路发布时才会写入。\n")
        }
        val netdev = File(LinuxRuntime.rootDir(context), "etc/droiddeck-net")
        if (netdev.isFile) {
            append("\netc/droiddeck-net（会话视角下的链路）：\n")
            FileUtils.readString(netdev)?.lines()?.forEach { append("    ").append(it).append('\n') }
        }
        append("\nproot 不创建网络命名空间，会话直接使用手机的\n")
        append("连接：IPv4 与 IPv6 都直接通行，没有任何转发。\n")
        append("Steam 通过运行时的 NetworkManager D-Bus 桥读取 Android 的传输类型、\n")
        append("连接与计费状态；Wi-Fi 和移动网络由 Android 设置管理。\n")
    }
}
