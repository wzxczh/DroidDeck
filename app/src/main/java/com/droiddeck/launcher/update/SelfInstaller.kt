package com.droiddeck.launcher.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Installs one of DroidDeck's own builds over itself: download into the cache, check the file
 * against GitHub's sha256, then hand it to PackageInstaller. Android shows its own "Update this
 * app?" prompt, and the app is restarted on the new build.
 */
object SelfInstaller {
    /** The broadcast PackageInstaller answers on; the launcher registers for it while it is open. */
    const val ACTION_STATUS = "com.droiddeck.launcher.update.INSTALL_STATUS"

    /** Android lets an app install packages only once the user allows it in "Install unknown apps". */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Downloads [apk] into the cache and checks it; a download left from before is replaced. */
    fun download(context: Context, apk: AppUpdates.Apk, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates")
        FileUtils.delete(dir)
        dir.mkdirs()
        val target = File(dir, apk.name)
        val c = URL(apk.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "DroidDeck-app")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw IOException("下载请求返回 HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: apk.size
            c.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        done += r
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            if (apk.size > 0 && target.length() != apk.size) {
                throw IOException("下载大小与发布的 APK 不符，已丢弃——请重试")
            }
            if (!apk.sha256.equals(Hashes.sha256(target), ignoreCase = true)) {
                throw IOException("下载校验和不符，已丢弃——请重试")
            }
            validateDownloaded(context, target, apk)
            return target
        } catch (e: Exception) {
            FileUtils.delete(target)
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Inspect the actual downloaded APK before giving it to Android's installer. */
    private fun validateDownloaded(context: Context, file: File, apk: AppUpdates.Apk) {
        if (apk.packageName != context.packageName) {
            throw IOException("更新适用于 Android 包 ${apk.packageName}，而不是 ${context.packageName}")
        }
        if (!apk.signerSha256.equals(BuildConfig.RELEASE_SIGNER, ignoreCase = true)) {
            throw IOException("更新元数据未指定 DroidDeck 的发布签名密钥")
        }
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Android 无法读取下载的 APK")
        if (info.packageName != context.packageName) {
            throw IOException("下载的 APK 属于 ${info.packageName}，而不是 ${context.packageName}")
        }
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (code != apk.versionCode.toLong()) {
            throw IOException("下载的 APK 的 versionCode 为 $code，而发布的是 ${apk.versionCode}")
        }
        if (code < BuildConfig.VERSION_CODE.toLong()) {
            throw IOException("Android 不会用 versionCode $code 覆盖已安装的 ${BuildConfig.VERSION_CODE}")
        }

        // Before Android 9 this lineage intentionally presents the public legacy test key. Android
        // 9+ understands the v3 hand-over and must see DroidDeck's private release key as current.
        if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: throw IOException("下载的 APK 没有签名信息")
            val digests = signing.apkContentsSigners.orEmpty().map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }
            if (digests.none { it.equals(BuildConfig.RELEASE_SIGNER, ignoreCase = true) }) {
                throw IOException("下载的 APK 未使用 DroidDeck 的发布密钥签名")
            }
        }
    }

    /** Writes [apk] into an install session and commits it; the answer comes as an [ACTION_STATUS] broadcast. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Skips Android's prompt where it allows that (the app installed its current build itself).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 1 shl 16)
                    session.fsync(out)
                }
            }
            val status = Intent(ACTION_STATUS).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
        }
    }

    /** What went wrong, in words for the user, from PackageInstaller's status and message. */
    fun describeFailure(status: Int, message: String?): String = when {
        message?.contains("VERSION_DOWNGRADE") == true ->
            "Android 不会用旧版本覆盖新版本。请停留在当前构建，等更新通道跟上。"
        status == PackageInstaller.STATUS_FAILURE_CONFLICT || message?.contains("UPDATE_INCOMPATIBLE") == true ->
            "当前 DroidDeck 副本的签名不同（本地构建？），Android 不会就地更新它。"
        status == PackageInstaller.STATUS_FAILURE_STORAGE -> "可用空间不足，无法完成更新。"
        status == PackageInstaller.STATUS_FAILURE_ABORTED -> "更新已取消。"
        else -> "更新安装失败" + (message?.let { ": $it" } ?: "。")
    }
}
