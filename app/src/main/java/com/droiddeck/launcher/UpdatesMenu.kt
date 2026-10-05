package com.droiddeck.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.ui.UpdatesActions
import com.droiddeck.launcher.ui.UpdatesState
import com.droiddeck.launcher.update.AppUpdates
import com.droiddeck.launcher.update.SelfInstaller

/**
 * The Updates page's state: the channels' latest builds, the one followed, and an update being
 * downloaded or installed. Checked when the launcher comes to the front - opening the app, and
 * coming back from a session - at most once a minute.
 */
internal class UpdatesMenu(private val activity: Activity, private val ui: Handler) {
    // Read in start(): the activity has no context yet while its fields are made.
    var catalog by mutableStateOf<AppUpdates.Catalog?>(null)
    var follow by mutableStateOf(AppUpdates.Follow(AppUpdates.Channel.STABLE))
    var checking by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var stage by mutableStateOf<String?>(null)
    var percent by mutableIntStateOf(-1)
    var askPermission by mutableStateOf(false)
    // An install waiting for the user to allow "Install unknown apps"; it goes on when they come back.
    private var pending: AppUpdates.Release? = null
    private var lastCheck = 0L
    private var request = 0

    fun state() = UpdatesState(catalog, follow, checking, error, stage, percent, askPermission)

    fun actions() = UpdatesActions(
        onCheck = { check(force = true) },
        onFollow = ::choose,
        onInstall = ::install,
        onAllowInstalls = {
            askPermission = false
            runCatching { activity.startActivity(SelfInstaller.permissionIntent(activity)) }
                .onFailure { error = "无法打开 Android 的“安装未知应用”设置"; pending = null }
        },
        onDismissPermission = { askPermission = false; pending = null },
    )

    val hasUpdate: Boolean get() = AppUpdates.hasUpdate(catalog, follow)

    /** On every return to the launcher: go on with an install the user just allowed, and check if due. */
    fun onResume() {
        pending?.let { r ->
            if (SelfInstaller.canInstall(activity)) { pending = null; install(r) }
        }
        if (!SessionState.running) check(force = false)
    }

    fun check(force: Boolean) {
        if (checking || stage != null) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60_000) return
        lastCheck = now
        val id = ++request
        checking = true
        Thread({
            val result = runCatching { AppUpdates.refresh(activity) }
            ui.post {
                if (id != request) return@post
                checking = false
                result.onSuccess {
                    catalog = it
                    follow = AppUpdates.follow(activity, it)
                    error = null
                }.onFailure { if (force) error = "无法检查：${it.message ?: it.javaClass.simpleName}" }
            }
        }, "app-updates").start()
    }

    private fun choose(f: AppUpdates.Follow) {
        AppUpdates.setFollow(activity, f)
        follow = f
        error = null
    }

    private fun install(r: AppUpdates.Release) {
        if (stage != null) return
        val apk = r.apk ?: run { error = "此构建没有适用于当前 DroidDeck 副本的下载。"; return }
        AppUpdates.installBlock(r)?.let { error = it; return }
        if (SessionState.running) { error = "请先停止正在运行的会话，再更新"; return }
        if (!SelfInstaller.canInstall(activity)) { pending = r; askPermission = true; return }
        error = null
        stage = "正在下载"
        percent = 0
        Thread({
            val result = runCatching {
                val file = SelfInstaller.download(activity, apk) { pct -> ui.post { percent = pct } }
                ui.post { stage = "正在安装"; percent = -1 }
                SelfInstaller.install(activity, file)
            }
            result.onFailure { e -> ui.post { stage = null; error = e.message ?: "更新失败" } }
        }, "app-update-install").start()
    }

    /** PackageInstaller's answer: Android's confirm prompt to show, or why it failed. Success restarts the app. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    if (confirm == null) { stage = null; error = "Android 未提示安装此更新"; return }
                    stage = "等待确认"
                    activity.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> stage = null
                else -> {
                    stage = null
                    error = SelfInstaller.describeFailure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                }
            }
        }
    }

    /** From onCreate: the last check, the channel followed, and PackageInstaller's answers. */
    fun start() {
        catalog = AppUpdates.cached(activity)
        follow = AppUpdates.follow(activity, catalog)
        val filter = IntentFilter(SelfInstaller.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(receiver, filter)
    }

    fun unregister() {
        runCatching { activity.unregisterReceiver(receiver) }
    }
}
