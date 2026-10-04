package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import java.io.File
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.DriverRow

/**
 * The Linux and Android driver menus: the drivers each can pick from, the release downloads the
 * last check found, and what each mode is set to. The launcher screen keeps one and hands its
 * rows to the mode settings dialog.
 */
internal class DriverMenus(private val activity: Activity, private val ui: Handler) {
    var linuxRows by mutableStateOf<List<DriverRow>>(emptyList())
    /** The latest Banners-Turnip release as each driver menu offers it (see [refreshReleaseRows]). */
    var linuxDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var androidDownloads by mutableStateOf<List<com.droiddeck.launcher.ui.DownloadRow>>(emptyList())
    var releaseStatus by mutableStateOf("尚未检查 - 点按刷新查找新驱动")
    var releaseChecking by mutableStateOf(false)
    var canRestoreBundled by mutableStateOf(false)
    /** Asset name -> download percent, while it downloads. */
    private val releaseProgress = HashMap<String, Int>()
    var linuxSteam by mutableStateOf("")
    var linuxDesktop by mutableStateOf("")
    var androidRows by mutableStateOf<List<DriverRow>>(emptyList())
    var androidSelected by mutableStateOf("")

    fun refreshDrivers() {
        val lm = LinuxVulkanDriverManager(activity)
        fun origin(id: String) = if (TurnipReleases.isDownloaded(activity, id)) DriverRow.DOWNLOADED else DriverRow.IMPORTED
        linuxRows = LinuxVulkanDriver.optionValues(activity).map { id ->
            if (id.isEmpty()) DriverRow("", "运行时默认", "运行时内置的 Turnip", false)
            else DriverRow(
                id, lm.getDriverName(id),
                listOfNotNull(
                    lm.getDriverVersion(id).takeIf { it.isNotEmpty() },
                    lm.getMinGlibc(id).takeIf { it.isNotEmpty() }?.let { "glibc $it+" },
                ).joinToString(" · "),
                true, origin(id),
            )
        }
        linuxSteam = SessionPrefs.linuxDriver(activity, SessionService.MODE_STEAM)
        linuxDesktop = SessionPrefs.linuxDriver(activity, SessionService.MODE_DESKTOP)
        val td = TurnipDriver(activity)
        val auto = td.autoId()
        androidRows = buildList {
            add(DriverRow(
                TurnipDriver.AUTO, "自动 - 由 GPU 选择",
                if (auto == "system") "系统 Vulkan：此 GPU 没有内置构建" else "${td.displayName(auto)}（内置）",
                false,
            ))
            for (id in td.visibleBundled()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, DriverRow.BUNDLED))
            for (id in td.enumerateImported()) add(DriverRow(id, td.displayName(id), td.driverVersion(id), true, origin(id)))
        }
        canRestoreBundled = td.hiddenBundled().isNotEmpty()
        androidSelected = SessionPrefs.androidDriver(activity)
        refreshReleaseRows()
    }

    /**
     * Import off the main thread - a driver zip is a few MB and the glibc check reads the whole
     * library - then say what happened. A refusal's message is the user-facing reason.
     */
    fun importDriver(uri: Uri, linux: Boolean) {
        val name = activity.displayNameOf(uri)
        Thread({
            val problem = try {
                if (linux) LinuxVulkanDriverManager(activity).installDriver(uri, name)
                else TurnipDriver(activity).installFromZip(uri, name)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "驱动导入", e)
                "导入失败：${e.message}"
            }
            ui.post {
                android.widget.Toast.makeText(
                    activity, problem ?: "已导入 ${name ?: "驱动"}",
                    if (problem != null) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                ).show()
                refreshDrivers()
            }
        }, "import-driver").start()
    }

    /**
     * Delete an imported or downloaded driver. A mode still set to it goes back to its default, so a
     * session never starts on a driver that is gone; a release download is forgotten, so the menu
     * offers it again.
     */
    fun deleteDriver(id: String, linux: Boolean) {
        if (linux) {
            LinuxVulkanDriverManager(activity).removeDriver(id)
            for (mode in listOf(SessionService.MODE_STEAM, SessionService.MODE_DESKTOP)) {
                if (SessionPrefs.linuxDriver(activity, mode) == id) SessionPrefs.setLinuxDriver(activity, mode, "")
            }
        } else {
            val td = TurnipDriver(activity)
            if (id in TurnipDriver.BUNDLED) td.hideBundled(id) else td.remove(id)
            if (SessionPrefs.androidDriver(activity) == id) SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
        }
        TurnipReleases.forget(activity, id)
        android.widget.Toast.makeText(activity, "已删除 ${id}", android.widget.Toast.LENGTH_SHORT).show()
        refreshDrivers()
    }

    /** The download entries and the refresh line, from what the last check found. */
    fun refreshReleaseRows() {
        val check = TurnipReleases.cached(activity)
        val lm = LinuxVulkanDriverManager(activity)
        val td = TurnipDriver(activity)
        fun rows(linux: Boolean) = check?.assets.orEmpty()
            .filter { it.linux == linux }
            .filter { a -> TurnipReleases.installedId(activity, a) { id -> if (linux) lm.isInstalled(id) else td.isInstalled(id) } == null }
            .map { a ->
                val mb = "%.1f MB".format(a.size / 1_048_576.0)
                com.droiddeck.launcher.ui.DownloadRow(a.name, "${a.source} ${a.tag}", "${a.label} · $mb", releaseProgress[a.name])
            }
        linuxDownloads = rows(linux = true)
        androidDownloads = rows(linux = false)
        if (!releaseChecking) releaseStatus = when (check) {
            null -> "尚未检查 - 点按刷新查找新驱动"
            else -> "最新版本：" + check.latest.joinToString(" · ") { "${it.first} ${it.second}" } +
                (if (check.failed.isEmpty()) "" else " · ${check.failed.joinToString()} 无法访问") +
                " · 检查于 ${ago(check.checkedAt)}"
        }
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "$m 分钟前"
            m < 48 * 60 -> "${m / 60} 小时前"
            else -> "${m / (24 * 60)} 天前"
        }
    }

    /** Only when the user taps refresh: nothing goes online on its own. */
    fun checkLatestTurnip() {
        if (releaseChecking) return
        releaseChecking = true
        releaseStatus = "正在检查 Banners-Turnip 和 WinNative…"
        Thread({
            val problem = try { TurnipReleases.refresh(activity); null } catch (e: Exception) {
                Log.w(TAG, "latest Turnip check", e); e.message ?: "检查失败"
            }
            ui.post {
                releaseChecking = false
                refreshReleaseRows()
                if (problem != null) releaseStatus = "无法检查：$problem"
            }
        }, "turnip-release-check").start()
    }

    /** Download one release driver and import it through the same importer a picked zip uses. */
    fun downloadReleaseDriver(assetName: String) {
        val asset = TurnipReleases.cached(activity)?.assets?.firstOrNull { it.name == assetName } ?: return
        if (releaseProgress.containsKey(assetName)) return
        releaseProgress[assetName] = 0
        refreshReleaseRows()
        Thread({
            var file: java.io.File? = null
            val problem = try {
                file = TurnipReleases.download(activity, asset) { pct ->
                    ui.post { releaseProgress[assetName] = pct; refreshReleaseRows() }
                }
                val uri = Uri.fromFile(file)
                val id = if (asset.linux) LinuxVulkanDriverManager(activity).installDriver(uri, asset.name)
                         else TurnipDriver(activity).installFromZip(uri, asset.name)
                TurnipReleases.recordDownload(activity, asset, id)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                Log.w(TAG, "发布版驱动下载", e)
                "下载失败：${e.message}"
            } finally {
                file?.let { com.droiddeck.launcher.core.FileUtils.delete(it) }
            }
            ui.post {
                releaseProgress.remove(assetName)
                android.widget.Toast.makeText(
                    activity, problem ?: "已安装 ${asset.name.removeSuffix(".zip")} - 请在菜单中选择",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                refreshDrivers()
            }
        }, "download-turnip").start()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

internal fun Context.displayNameOf(uri: Uri): String? = if (uri.scheme == "file") uri.lastPathSegment else try {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (e: Exception) {
    null
}

