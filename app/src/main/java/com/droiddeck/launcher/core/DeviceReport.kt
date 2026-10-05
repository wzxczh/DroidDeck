package com.droiddeck.launcher.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.EsyncPacks
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `device.txt`: what this device is and what the session was told to do, written the moment a
 * session starts so a log can be read months later without asking the reporter a single question.
 *
 * **Nothing identifying goes in here.** No serial number, no Android ID, no IMEI, no account name,
 * no network names - only what the hardware is, what the ROM is, and which of our own settings
 * were in effect. The one thing that comes close is the build fingerprint, which names the ROM and
 * its build date and is the same string on every unit of that model.
 */
object DeviceReport {
    private const val TAG = "DeviceReport"

    fun write(context: Context, target: File, mode: String) {
        try {
            target.writeText(build(context, mode))
        } catch (e: Exception) {
            Log.w(TAG, "无法写入 $target", e)
        }
    }

    fun build(context: Context, mode: String): String {
        val b = StringBuilder()
        fun h(title: String) {
            b.append('\n').append(title).append('\n').append("-".repeat(title.length)).append('\n')
        }
        fun k(key: String, value: Any?) {
            b.append(key.padEnd(24)).append(value ?: "未知").append('\n')
        }

        b.append("DroidDeck 会话报告\n")
        b.append("========================\n")
        k("写入时间", SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.US).format(Date()))
        k("会话模式", when (mode) { SessionService.MODE_DESKTOP -> "桌面（labwc/LXQt）"; SessionService.MODE_RUN -> "gamescope 下的程序"; else -> "Steam 客户端（gamescope）" })

        h("应用")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            k("版本", "${info.versionName} (${info.longVersionCode})")
        }
        k("包名", context.packageName)
        k("targetSdk", context.applicationInfo.targetSdkVersion)
        k("原生库目录", context.applicationInfo.nativeLibraryDir)

        h("设备")
        k("型号", "${Build.MANUFACTURER} ${Build.MODEL}")
        k("设备 / 产品", "${Build.DEVICE} / ${Build.PRODUCT}")
        k("主板 / 硬件", "${Build.BOARD} / ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            k("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        k("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        k("安全补丁", Build.VERSION.SECURITY_PATCH)
        k("版本构建", Build.DISPLAY)
        k("构建指纹", Build.FINGERPRINT)
        k("内核", System.getProperty("os.version"))
        k("支持的 ABI", Build.SUPPORTED_ABIS.joinToString(", "))

        h("CPU 与内存")
        k("核心数", CpuCores.all.size)
        b.append("各核频率上限")
        b.append(" ".repeat(18))
        b.append(CpuCores.all.joinToString(", ") { c ->
            "cpu$c " + (CpuCores.maxGhz(c)?.let { String.format(Locale.US, "%.2f GHz", it) } ?: "?")
        })
        b.append('\n')
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            k("内存总量", FileUtils.sizeToString(mi.totalMem))
            k("可用内存", FileUtils.sizeToString(mi.availMem))
        }
        runCatching {
            val fs = StatFs(context.filesDir.path)
            k("应用存储可用", FileUtils.sizeToString(fs.availableBlocksLong * fs.blockSizeLong))
        }
        runCatching {
            val fs = StatFs(Environment.getExternalStorageDirectory().path)
            k("共享存储可用", FileUtils.sizeToString(fs.availableBlocksLong * fs.blockSizeLong))
        }

        h("GPU")
        k("KGSL gpu_model", readSys("/sys/class/kgsl/kgsl-3d0/gpu_model"))
        k("KGSL chip id", readSys("/sys/class/kgsl/kgsl-3d0/gpu_chipid"))
        k("系统 Vulkan ICD", if (File("/vendor/lib64/hw/vulkan.adreno.so").exists()) "/vendor/lib64/hw/vulkan.adreno.so" else "不在常见路径")

        h("显示")
        k("会话输出", SessionState.outputSize?.let { "${it.first}x${it.second}" })
        k("会话刷新率", String.format(Locale.US, "%.2f Hz", SessionState.refreshHz))
        val panelSize = com.droiddeck.launcher.session.SessionDisplay.panelSize(context)
        k("分辨率（Steam）", SessionPrefs.resolutionChoice(context, SessionService.MODE_STEAM, panelSize))
        k("分辨率（桌面）", SessionPrefs.resolutionChoice(context, SessionService.MODE_DESKTOP, panelSize))
        k("折叠屏", context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle"))

        h("驱动")
        val gpu = com.droiddeck.launcher.gpu.GpuInfo.detect()
        k("GPU", "${gpu.name} · ${gpu.family.label} · ${gpu.supportText}" + if (gpu.oneUi8Gen2) " · One UI 8 Gen 2" else "")
        k("驱动模式", SessionPrefs.gpuDriverMode(context) +
            (com.droiddeck.launcher.gpu.DriverPairs.recommendedKey(gpu, com.droiddeck.launcher.gpu.DriverPairs.from(
                com.droiddeck.launcher.gpu.TurnipReleases.cached(context)))?.let { "（推荐配对：$it）" } ?: ""))
        val turnip = TurnipDriver(context)
        val androidChoice = SessionPrefs.androidDriver(context)
        k("显示驱动（选定）", if (androidChoice.isEmpty()) "自动 -> ${turnip.autoId()}" else androidChoice)
        k("  名称 / 版本", "${turnip.displayName(if (androidChoice.isEmpty()) turnip.autoId() else androidChoice)} ${turnip.driverVersion(if (androidChoice.isEmpty()) turnip.autoId() else androidChoice)}".trim())
        k("  已导入可用项", turnip.enumerateImported().ifEmpty { listOf("无") }.joinToString(", "))
        val lm = LinuxVulkanDriverManager(context)
        run {
            val id = SessionPrefs.linuxDriver(context)
            val label = if (id.isEmpty()) "运行时默认（运行时自带的 Turnip）"
            else "${lm.getDriverName(id)} ${lm.getDriverVersion(id)}".trim() +
                lm.getMinGlibc(id).let { if (it.isEmpty()) "" else "（glibc $it+）" } +
                if (lm.isInstalled(id)) "" else "  [缺失 — 回退到运行时自带的]"
            k("Linux 驱动", label)
        }
        k("运行时自带的 ICD", LinuxRuntime.vulkanIcd(context)?.path)

        h("运行时")
        k("已安装版本", LinuxRuntimeInstaller.installedVersion(context))
        k("运行时就绪", LinuxRuntime.isInstalled(context))
        k("桌面已安装", DesktopCatalog.desktopInstalled(context))
        k("运行时根目录", LinuxRuntime.rootDir(context).path)

        h("生效的设置")
        k("客户端核心覆盖", SessionPrefs.clientCpusOverride(context))
        k("  客户端核心", CpuCores.listOrAll(SessionPrefs.clientCpus(context)))
        k("  游戏核心", CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(context)).ifEmpty { "全部核心（未下发限制）" })
        k("Turnip 系统内存", SessionPrefs.tuSysmem(context))
        k("Zink 惰性描述符", SessionPrefs.zinkLazy(context))
        k("线程化 GL（glthread）", SessionPrefs.glThread(context))
        k("关闭 GL 错误检查", SessionPrefs.noGlError(context))
        k("Steam Deck 模式", SessionPrefs.steamDeckMode(context))
        k("Steam 手柄", SessionPrefs.steamController(context))
        k("缩放滤镜", (SessionPrefs.upscalerChoices.firstOrNull { it.first == SessionPrefs.upscaler(context) }?.second ?: "关闭") +
            "（锐度 ${SessionPrefs.upscaleSharpness(context)}%）")
        k("FEX 预设", SessionPrefs.fexPreset(context).ifEmpty { "FEX 默认" })
        k("跳过 xalia", SessionPrefs.noXalia(context))
        k("Wine 同步", SessionPrefs.syncBackend(context))
        EsyncPacks.status(LinuxRuntime.rootDir(context)).let { k("droiddeck-esync 包", "已安装 ${it.installed} 个，需要 ${it.wanted} 个") }
        k("Linux x86（FEX）", if (com.droiddeck.launcher.runtime.LinuxFex.ready(context)) "已就绪" else "未配置")
        k("gamescope 实时调度", SessionPrefs.gamescopeRealtime(context))
        k("proot 不启用 seccomp", SessionPrefs.prootNoSeccomp(context))
        k("proot 快速路径", SessionPrefs.prootFastPath(context))
        k("客户机主机名", SessionPrefs.guestHostname(context))
        k("游戏使用 DirectAudio", SessionPrefs.directAudio(context))
        k("游戏拉伸填满屏幕", SessionPrefs.forceFullscreen(context))
        k("16:9 拉伸至面板", SessionPrefs.stretch16x9(context))
        k("客户端音频", if (SessionPrefs.clientDirectAudio(context)) "DirectAudio" else "经典")
        k("麦克风", SessionPrefs.micEnabled(context))
        k("屏幕控件", SessionPrefs.oscMode(context))
        k("触摸模式", SessionPrefs.touchMode(context))
        k("性能 HUD", SessionPrefs.hudEnabled(context))
        k("游戏存储", when (val g = SessionPrefs.gameStorage(context)) {
            "" -> "自动（" + (com.droiddeck.launcher.session.GameStorage.effective(context)?.path ?: "未插存储卡") + "）"
            SessionPrefs.GAME_STORAGE_OFF -> "仅内部存储"
            else -> g
        })

        h("Android 进程限制")
        k("幻影进程监控", PhantomProcessLimit.reportValue(PhantomProcessLimit.read(context)))

        h("Download 目录中的设备开关文件")
        for (name in listOf("droiddeck-env", "droiddeck-tu-debug", "droiddeck-driver",
                            "droiddeck-osc", "droiddeck-no-pad",
                            "droiddeck-no-hud", "droiddeck-wlr-renderer")) {
            val f = File(Environment.getExternalStorageDirectory(), "Download/$name")
            if (f.isFile) k(name, FileUtils.readString(f)?.trim()?.replace('\n', ' ')?.ifEmpty { "（存在，为空）" } ?: "（存在）")
        }

        b.append("\n此处不收集任何可识别个人身份的信息：没有序列号，没有设备或广告\n")
        b.append("标识符，没有账户名，没有网络名称。可原样附在问题反馈中。\n")
        return b.toString()
    }

    private fun readSys(path: String): String? = FileUtils.readString(File(path))?.trim()?.ifEmpty { null }
}
