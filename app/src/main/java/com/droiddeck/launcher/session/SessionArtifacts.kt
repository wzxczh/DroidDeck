package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.core.LogRedactor
import com.droiddeck.launcher.core.SessionLogCapture
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finishes a session's folder: the compositor's log, the Steam client's logs scrubbed line by
 * line, Android's crash buffer, and a marker that says the folder is complete.
 *
 * Three callers, because a session ends three ways. [collect] runs at an ordinary stop. The
 * app's uncaught-exception handler ([CrashHandler]) runs it as the process dies, so a crash in
 * our own code leaves a full folder and not a half one. And [finishAbandoned] runs at the next
 * app start for a folder that has no marker: the process was killed outright (Android's phantom
 * process killer, a native crash in the compositor, the battery) and nothing of ours got to run.
 * Everything written *during* the session - session.log, app.log, wayland.log, audio.log, the
 * device report - is already on disk at that point; only the pieces gathered at the end were
 * missing, and the crash buffer keeps its entries after the process is gone, which is the whole
 * reason it is worth coming back for.
 */
object SessionArtifacts {
    private const val TAG = "SessionArtifacts"

    /** Written last; a folder without it did not get its ending. */
    const val COMPLETE_MARKER = ".complete"

    /** Every file in the folder has been through the redactor, own addresses and accounts included (-2: accounts added). */
    private const val SCRUBBED_MARKER = ".scrubbed-2"

    /** Everything the end of a session gathers, into [dir]. Safe to call for a dead session. */
    fun collect(context: Context, dir: File, reason: String) {
        try {
            val wayland = File(dir, "wayland.log")
            if (!wayland.exists()) {
                WaylandCompositor.currentLogFile()?.takeIf { it.isFile }?.let { src ->
                    src.copyTo(wayland, overwrite = true)
                }
            }
            LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
            copySteamLogs(context, dir)
            // A session the system killed leaves its trace here and nowhere else.
            SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
            scrubFolder(dir)
            try { File(dir, SCRUBBED_MARKER).writeText("已清理 ${now()}\n") } catch (e: Exception) {}
            SessionEvents.record("session.artifacts_collected", mapOf("reason" to reason), dir)
            File(dir, COMPLETE_MARKER).writeText("已收集：$reason，于 ${now()}\n")
        } catch (e: Exception) {
            Log.w(TAG, "收集会话产物", e)
        }
    }

    /**
     * Once, for session folders written before every file was scrubbed and before the device's own
     * addresses were (network.txt listed them; the client's IPv6 check logs "external address"
     * into steam/connection_log.txt): the whole folder, steam/ included, through the redactor.
     * A marker records it. Runs at app start with [finishAbandoned].
     */
    @Synchronized
    fun scrubOlder(context: Context) {
        val current = SessionPaths.current()
        val dirs = LinuxRuntime.debugLogDir().listFiles { f ->
            f.isDirectory && f.name.startsWith("session-") && f != current && !File(f, SCRUBBED_MARKER).exists()
        } ?: return
        if (dirs.isEmpty()) return
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        dirs.forEach { dir ->
            scrubFolder(dir)
            File(dir, "steam").takeIf { it.isDirectory }?.let { scrubFolder(it) }
            try { File(dir, SCRUBBED_MARKER).writeText("已清理 ${now()}\n") } catch (e: Exception) {}
        }
        Log.i(TAG, "已清理 ${dirs.size} 个较早的会话文件夹")
    }

    /**
     * The session's own files (session.log, app.log, desktop.log, the Steam desktop client's
     * steam-desktop.log, ...) were written as they happened, unscrubbed; this pass puts every one
     * through the redactor before the folder can be shared. A file is rewritten only if a line
     * changed. steam/ was scrubbed on the way in.
     */
    private fun scrubFolder(dir: File) {
        dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.forEach { f ->
            if (!LogRedactor.isText(f)) return@forEach
            try {
                val tmp = File(dir, ".${f.name}.scrub")
                tmp.bufferedWriter().use { w -> LogRedactor.scrubTo(f, w) }
                if (tmp.length() != f.length() || tmp.readBytes().contentEquals(f.readBytes()).not()) tmp.renameTo(f) else tmp.delete()
            } catch (e: Exception) {
                Log.w(TAG, "无法清理 ${f.name}", e)
            }
        }
    }

    /** Steam's logs: redacted into steam/, never copied verbatim. */
    private fun copySteamLogs(context: Context, dir: File) {
        val logs = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/logs")
        if (!logs.isDirectory) return
        val out = File(dir, "steam").apply { mkdirs() }
        logs.listFiles { f -> f.isFile && f.length() < 8L * 1024 * 1024 }?.forEach { src ->
            try {
                File(out, src.name).bufferedWriter().use { w ->
                    src.forEachLine { line -> w.write(LogRedactor.redact(line)); w.newLine() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法清理 ${src.name}", e)
            }
        }
        Log.i(TAG, "已收集 ${out.listFiles()?.size ?: 0} 个 Steam 日志并清理，存入 $out")
    }

    /**
     * Every session folder without a marker gets its ending now. Only the newest of them gets the
     * Steam logs - the runtime holds one set, and it belongs to the last session that ran; an
     * older folder would be handed logs that are not its own. Runs on a worker thread at app
     * start; nothing here touches the session that is about to begin.
     */
    @Synchronized
    fun finishAbandoned(context: Context) {
        val parent = LinuxRuntime.debugLogDir()
        val abandoned = parent.listFiles { f ->
            f.isDirectory && f.name.startsWith("session-") && !File(f, COMPLETE_MARKER).exists()
        }?.sortedBy { it.name } ?: return
        if (abandoned.isEmpty()) return
        val current = SessionPaths.current()
        LogRedactor.learnFromRuntime(LinuxRuntime.rootDir(context))
        abandoned.forEachIndexed { i, dir ->
            if (dir == current) return@forEachIndexed
            val newest = i == abandoned.lastIndex
            try {
                File(dir, "ended-without-teardown.txt").writeText(
                    "本会话的进程在未执行自身清理的情况下结束——被 Android 结束、\n" +
                        "发生原生崩溃，或设备关机——所以下面的文件是在应用下次启动时、\n" +
                        "于 ${now()} 收集的。\n" +
                        "会话期间写入的日志（session.log、app.log、wayland.log、\n" +
                        "audio.log、device.txt）已在磁盘上，保持当时的状态不变。\n" +
                        (if (newest) "" else "未包含 Steam 的日志：后续会话已将其覆盖。\n") +
                        "crash.log 是应用下次启动时 Android 崩溃缓冲区的内容——若本次\n" +
                        "会话死于崩溃，对应条目就在其中（除非设备已重启）。\n"
                )
                if (newest) {
                    copySteamLogs(context, dir)
                }
                SessionLogCapture.dumpCrashBuffer(File(dir, "crash.log"))
                SessionEvents.record("session.artifacts_recovered", mapOf("newest" to newest), dir)
                scrubFolder(dir)
                File(dir, COMPLETE_MARKER).writeText("已收集：延迟处理，于下次应用启动，${now()}\n")
                Log.i(TAG, "已处理被遗弃的会话文件夹 $dir")
            } catch (e: Exception) {
                Log.w(TAG, "无法完成 $dir", e)
            }
        }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
