package com.droiddeck.launcher.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WirelessAdbPairingService : Service() {
    sealed interface Stage {
        data object Idle : Stage
        data object Waiting : Stage
        data class CodeNeeded(val error: String? = null) : Stage
        data class Working(val step: String) : Stage
        data object Done : Stage
        data class Failed(val error: String) : Stage
    }

    private val main = Handler(Looper.getMainLooper())
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var pairingHost: String? = null
    private var pairingPort: Int? = null
    @Volatile private var working = false
    private val timeout = Runnable { finish(Stage.Failed("等待配对弹窗超时，请在 DroidDeck 中重试。")) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)
                    ?.filter(Char::isDigit)?.toString().orEmpty()
                onCode(code)
            }
            ACTION_CANCEL -> finish(Stage.Idle)
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        pairingHost = null
        pairingPort = null
        working = false
        publish(Stage.Waiting)
        startForeground(NOTIFICATION_ID, notification(Stage.Waiting))
        startDiscovery()
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun startDiscovery() {
        stopDiscovery()
        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains(PAIRING_TYPE) != true) return
                runCatching { manager.resolveService(serviceInfo, resolveListener()) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { finish(Stage.Failed("无法搜索配对弹窗（$errorCode），请检查 Wi-Fi 是否开启。")) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { manager.discoverServices("$PAIRING_TYPE.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { nsd = manager; discovery = listener }
            .onFailure { finish(Stage.Failed("无法搜索配对弹窗：${it.localizedMessage}")) }
    }

    private fun resolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val address = serviceInfo.host ?: return
            if (!WirelessAdbFix.isLocalAddress(address)) return
            main.post {
                if (working || current.value == Stage.Done) return@post
                pairingHost = address.hostAddress
                pairingPort = serviceInfo.port
                if (current.value !is Stage.CodeNeeded) show(Stage.CodeNeeded())
            }
        }
    }

    private fun onCode(code: String) {
        val host = pairingHost
        val port = pairingPort
        if (working) return
        if (host == null || port == null) {
            show(Stage.Waiting)
            return
        }
        if (code.length != 6) {
            show(Stage.CodeNeeded("请输入完整的 6 位配对码"))
            return
        }
        working = true
        show(Stage.Working("正在配对…"))
        Thread({
            val paired = runCatching { kotlinx.coroutines.runBlocking { WirelessAdbFix.pair(this@WirelessAdbPairingService, WirelessAdbFix.LOOPBACK, port, code) } }
            if (paired.isFailure) {
                main.post {
                    working = false
                    pairingPort = null
                    show(Stage.CodeNeeded("配对码无效，请重新打开配对弹窗并输入新的配对码。"))
                }
                return@Thread
            }
            main.post {
                stopDiscovery()
                show(Stage.Working("配对成功，正在连接…"))
            }
            val result = runCatching {
                val connectPort = WirelessAdbFix.localConnectPort(this)
                    ?: error("已配对，但未找到无线调试端口。请保持无线调试开启后重试。")
                main.post { show(Stage.Working("正在应用设置…")) }
                WirelessAdbFix.setChildProcessLimit(this, WirelessAdbFix.LOOPBACK, connectPort, false)
            }
            main.post {
                working = false
                finish(result.exceptionOrNull()?.let { Stage.Failed(it.localizedMessage ?: "无线调试命令执行失败") } ?: Stage.Done)
            }
        }, "wireless-adb-pairing").start()
    }

    private fun show(stage: Stage) {
        publish(stage)
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(stage))
    }

    private fun finish(stage: Stage) {
        main.removeCallbacks(timeout)
        stopDiscovery()
        publish(stage)
        val manager = getSystemService(NotificationManager::class.java)
        if (stage is Stage.Done || stage is Stage.Failed) {
            manager?.notify(NOTIFICATION_ID, notification(stage))
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            manager?.cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }

    private fun stopDiscovery() {
        val listener = discovery ?: return
        runCatching { nsd?.stopServiceDiscovery(listener) }
        discovery = null
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        stopDiscovery()
        if (current.value.let { it is Stage.Waiting || it is Stage.CodeNeeded || it is Stage.Working }) publish(Stage.Idle)
        super.onDestroy()
    }

    private fun notification(stage: Stage): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "无线调试设置",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "在“设置”界面打开时请求无线调试配对码"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, WirelessAdbPairingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentIntent(open)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
        when (stage) {
            Stage.Waiting, Stage.Idle -> builder
                .setContentTitle("配对无线调试")
                .setContentText("开启无线调试，然后点按“使用配对码配对设备”。")
                .setStyle(Notification.BigTextStyle().bigText(
                    "开启无线调试，然后点按“使用配对码配对设备”。请停留在“设置”界面：配对码会显示在此通知中。"))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, "取消", cancel).build())
            is Stage.CodeNeeded -> {
                val reply = PendingIntent.getService(this, 2,
                    Intent(this, WirelessAdbPairingService::class.java).setAction(ACTION_CODE),
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    else PendingIntent.FLAG_UPDATE_CURRENT)
                val input = RemoteInput.Builder(KEY_CODE).setLabel("6 位配对码").build()
                val text = stage.error ?: "点按“输入配对码”，输入“设置”中显示的 6 位数字。"
                builder
                    .setContentTitle(if (stage.error == null) "输入 Wi-Fi 配对码" else "请重试配对码")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    .addAction(Notification.Action.Builder(null, "输入配对码", reply).addRemoteInput(input).build())
                    .addAction(Notification.Action.Builder(null, "取消", cancel).build())
            }
            is Stage.Working -> builder
                .setContentTitle("正在配置 Steam")
                .setContentText(stage.step)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            Stage.Done -> builder
                .setContentTitle("Steam 已就绪")
                .setContentText("点按可返回 DroidDeck，现在可以关闭无线调试了。")
                .setStyle(Notification.BigTextStyle().bigText("点按可返回 DroidDeck，现在可以关闭无线调试了。"))
                .setAutoCancel(true)
            is Stage.Failed -> builder
                .setContentTitle("无线调试设置已停止")
                .setContentText(stage.error)
                .setStyle(Notification.BigTextStyle().bigText(stage.error))
                .setAutoCancel(true)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "wireless-adb-pairing"
        private const val NOTIFICATION_ID = 3
        private const val PAIRING_TYPE = "_adb-tls-pairing._tcp"
        private const val ACTION_CODE = "com.droiddeck.launcher.action.WIRELESS_ADB_CODE"
        private const val ACTION_CANCEL = "com.droiddeck.launcher.action.WIRELESS_ADB_CANCEL"
        private const val KEY_CODE = "code"
        private const val TIMEOUT_MS = 5 * 60_000L

        private val current = MutableStateFlow<Stage>(Stage.Idle)
        val stage: StateFlow<Stage> = current

        private fun publish(stage: Stage) { current.value = stage }

        fun start(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            publish(Stage.Waiting)
            val intent = Intent(context, WirelessAdbPairingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun cancel(context: Context) {
            if (current.value.let { it is Stage.Done || it is Stage.Failed || it is Stage.Idle }) {
                publish(Stage.Idle)
                return
            }
            context.startService(Intent(context, WirelessAdbPairingService::class.java).setAction(ACTION_CANCEL))
        }

        fun notificationsEnabled(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
    }
}
