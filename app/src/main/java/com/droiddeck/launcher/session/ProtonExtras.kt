package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.Hashes
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import org.json.JSONArray

/** Immediate download and installation of the optional ARM64 Proton builds. */
object ProtonExtras {
    private const val TAG = "ProtonExtras"
    private const val NEED_BYTES = 4L * 1024 * 1024 * 1024
    private const val RELEASES = "https://api.github.com/repos/%s/releases?per_page=15"
    @Volatile
    var installInProgress = false
        private set

    class Tool(val id: String, val name: String, val prefix: String, val repo: String, val assetPattern: Regex)

    /** [sha256] from GitHub's asset digest; [sha512] the URL of a checksum file published beside it. */
    private data class Asset(val tag: String, val name: String, val url: String, val sha512: String?, val size: Long,
                             val sha256: String? = null)

    val tools = listOf(
        Tool("ge", "GE-Proton", "GE-Proton", "GloriousEggroll/proton-ge-custom", Regex("aarch64\\.tar\\.(gz|xz)$")),
        Tool("cachyos", "proton-cachyos", "proton-cachyos", "CachyOS/proton-cachyos", Regex("arm64\\.tar\\.(gz|xz)$")),
    )

    private fun home(context: Context) = File(LinuxRuntime.rootDir(context), "root")
    private fun requests(context: Context) = File(home(context), ".bl-proton-extra")
    private fun toolsDir(context: Context) = File(home(context), ".local/share/Steam/compatibilitytools.d")

    /** The installed build's directory name (e.g. GE-Proton11-7), or null. */
    fun installed(context: Context, tool: Tool): String? =
        toolsDir(context).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(tool.prefix) && File(it, "toolmanifest.vdf").isFile }
            ?.maxByOrNull { it.name }?.name

    /** A request left by an earlier app version, still consumed by the session shim. */
    fun queued(context: Context, tool: Tool): Boolean =
        requestLines(context).any { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }

    fun unqueue(context: Context, tool: Tool) {
        val lines = requestLines(context).filterNot { it.trim() == tool.id || it.trim().startsWith(tool.id + " ") }
        val file = requests(context)
        if (lines.isEmpty()) file.delete()
        else file.writeText(lines.joinToString("\n") + "\n")
    }

    /** Deletes the installed build; Steam discovers its absence on its next start. */
    fun remove(context: Context, tool: Tool) {
        installed(context, tool)?.let { File(toolsDir(context), it).deleteRecursively() }
    }

    /** Downloads, verifies and registers the latest build without waiting for another session. */
    @Synchronized
    fun install(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (installInProgress) return "已有兼容性工具安装任务在运行"
        installInProgress = true
        return try { installNow(context, tool, onProgress) } finally { installInProgress = false }
    }

    private fun installNow(context: Context, tool: Tool, onProgress: (String, Int) -> Unit): String? {
        if (SessionState.running) return "请先停止当前会话，再安装兼容性工具"
        if (!LinuxRuntime.isInstalled(context)) return "请先安装 Linux 运行时"

        val asset = findLatestAsset(tool) ?: return "找不到 ${tool.name} 的 ARM64 版本"
        val downloads = File(context.filesDir, "proton-downloads").apply { mkdirs() }
        val archive = File(downloads, "${tool.id}-${asset.name}")
        if (asset.size > 0 && archive.length() > asset.size) archive.delete()
        val missing = (asset.size - archive.length()).coerceAtLeast(0L)
        if (StatFs(LinuxRuntime.rootDir(context).path).availableBytes < NEED_BYTES + missing) {
            return "安装 ${tool.name} 至少需要 4 GB 可用空间外加下载大小"
        }

        onProgress("正在下载 ${tool.name} ${asset.tag}", 0)
        var downloaded = false
        for (attempt in 0 until 5) {
            downloaded = Downloader.downloadFile(asset.url, archive, true) { fraction ->
                onProgress("正在下载 ${tool.name} ${asset.tag}", if (fraction < 0) -1 else (fraction * 100f).toInt().coerceIn(0, 100))
            }
            if (downloaded) break
            if (attempt < 4) {
                onProgress("正在重试下载 · 第 ${attempt + 2} 次，共 5 次", -1)
                try { Thread.sleep(3_000) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return "下载已中断"
                }
            }
        }
        if (!downloaded) return "下载失败；已保留分片文件，重试可断点续传"
        if (asset.size > 0 && archive.length() != asset.size) {
            if (archive.length() > asset.size) {
                archive.delete()
                return "下载超过发布大小，已删除"
            }
            return "下载不完整；已保留分片文件，重试可断点续传"
        }

        // One checksum has to be found and has to match: GitHub's sha256 digest, else the
        // release's own sha512 file. A download neither vouches for is not installed.
        onProgress("正在校验下载", -1)
        val verified = when {
            asset.sha256 != null -> asset.sha256.equals(Hashes.sha256(archive), ignoreCase = true)
            asset.sha512 != null -> {
                val expected = Downloader.downloadString(asset.sha512)?.let { Regex("(?i)\\b[0-9a-f]{128}\\b").find(it)?.value }
                    ?: return "无法读取发布校验和；请重试"
                expected.equals(Hashes.sha512(archive), ignoreCase = true)
            }
            else -> {
                archive.delete()
                return "${tool.name} ${asset.tag} 未发布校验和；未安装任何内容"
            }
        }
        if (!verified) {
            archive.delete()
            return "校验和不匹配；下载文件已删除"
        }

        if (SessionState.running) return "下载期间启动了会话；请先停止会话再安装兼容性工具"
        onProgress("正在安装 ${tool.name}", -1)
        return try {
            val root = LinuxRuntime.rootDir(context)
            LinuxRuntime.writeAccounts(context)
            com.droiddeck.launcher.session.SessionFiles.stage(context, root)
            val runtimeDir = File(context.filesDir, ".proton-install-rt").apply { mkdirs() }
            val guest = listOf(
                "/usr/bin/env", "-i", "HOME=/root", "USER=root", "PATH=/usr/local/bin:/usr/bin:/bin",
                "LANG=C.UTF-8", "XDG_DATA_HOME=/root/.local/share",
                "/usr/local/bin/droiddeck-proton-extra", "/root/.local/share/Steam", archive.absolutePath,
            )
            val command = LinuxRuntime.command(context, null, runtimeDir, null, guest)
            val process = ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
            val hostEnv = process.environment()
            hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(context).path
            hostEnv["PROOT_TMP_DIR"] = context.cacheDir.path
            LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { hostEnv["LD_LIBRARY_PATH"] = it }
            val child = process.start()
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    Log.i(TAG, line)
                    if (line.contains("unpacking", ignoreCase = true) || line.contains("收编")) {
                        onProgress("正在安装 ${tool.name}", -1)
                    }
                }
            }
            val status = child.waitFor()
            if (status != 0) "${tool.name} 安装失败（退出码 $status）"
            else {
                archive.delete()
                unqueue(context, tool)
                if (EsyncPacks.enabled(context)) {
                    onProgress("正在获取 droiddeck-esync 包", -1)
                    try {
                        EsyncPacks.fetchWanted(context, root, onProgress)
                    } catch (t: Throwable) {
                        Log.w(TAG, "获取 ${tool.name} 的同步数据包失败", t)
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "install ${tool.id}", e)
            e.message ?: "无法安装 ${tool.name}"
        }
    }

    private fun findLatestAsset(tool: Tool): Asset? {
        val releases = Downloader.downloadString(RELEASES.format(tool.repo)) ?: return null
        return try {
            val array = JSONArray(releases)
            for (i in 0 until array.length()) {
                val release = array.getJSONObject(i)
                if (release.optBoolean("draft", false)) continue
                val assets = release.optJSONArray("assets") ?: continue
                val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
                val archive = entries.sortedBy { it.optString("name") }.firstOrNull { tool.assetPattern.containsMatchIn(it.optString("name")) } ?: continue
                val name = archive.optString("name")
                val stem = name.substringBefore(".tar")
                val checksum = entries.firstOrNull {
                    it.optString("name").startsWith(stem) && it.optString("name").contains("sha512", ignoreCase = true)
                }?.optString("browser_download_url")?.takeIf { it.startsWith("http") }
                return Asset(
                    release.optString("tag_name"), name,
                    archive.optString("browser_download_url").takeIf { it.startsWith("http") } ?: continue, checksum,
                    archive.optLong("size", 0L),
                    Hashes.githubSha256(archive.optString("digest")),
                )
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "${tool.id} 的发布元数据", e)
            null
        }
    }

    private fun requestLines(context: Context): List<String> =
        FileUtils.readString(requests(context))?.lines()?.filter { it.isNotBlank() } ?: emptyList()
}
