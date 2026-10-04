package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Android's phantom process monitor can kill the many child processes used by a Steam session. */
enum class PhantomProcessStatus {
    NOT_APPLICABLE,
    DISABLED,
    ENABLED,
    UNSET,
    UNREADABLE,
}

object PhantomProcessLimit {
    private const val SETTING = "settings_enable_monitor_phantom_procs"

    private const val MAX_PHANTOM = "2147483647"
    private const val PREFS = "phantom-process-limit"
    private const val PREF_ANDROID_12_OFF = "android12-off"

    fun usesDeviceConfig(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk == Build.VERSION_CODES.S

    fun shellCommands(enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): List<String> = when {
        usesDeviceConfig(sdk) && enabled -> listOf(
            "device_config delete activity_manager max_phantom_processes",
            "device_config set_sync_disabled_for_tests none",
        )
        usesDeviceConfig(sdk) -> listOf(
            "device_config set_sync_disabled_for_tests persistent",
            "device_config put activity_manager max_phantom_processes $MAX_PHANTOM",
        )
        else -> listOf("settings put global $SETTING ${if (enabled) "true" else "false"}")
    }

    fun verifyCommand(sdk: Int = Build.VERSION.SDK_INT): String =
        if (usesDeviceConfig(sdk)) "device_config get activity_manager max_phantom_processes"
        else "settings get global $SETTING"

    fun verified(output: String, enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): Boolean {
        val value = output.trim()
        return if (usesDeviceConfig(sdk)) (value == MAX_PHANTOM) != enabled
        else value == if (enabled) "true" else "false"
    }

    fun adbCommand(enabled: Boolean, sdk: Int = Build.VERSION.SDK_INT): String =
        shellCommands(enabled, sdk).joinToString(" && ") { "adb shell $it" }

    fun adbCommand(): String = adbCommand(false)

    fun rememberAndroid12(context: Context, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ANDROID_12_OFF, off).apply()
    }

    fun read(context: Context, sdk: Int = Build.VERSION.SDK_INT): PhantomProcessStatus {
        if (sdk < Build.VERSION_CODES.S) return PhantomProcessStatus.NOT_APPLICABLE
        if (usesDeviceConfig(sdk)) {
            val off = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ANDROID_12_OFF, false)
            return if (off) PhantomProcessStatus.DISABLED else PhantomProcessStatus.UNREADABLE
        }
        val global = try {
            Settings.Global.getString(context.contentResolver, SETTING)
        } catch (_: Exception) {
            return PhantomProcessStatus.UNREADABLE
        }
        return status(global, systemProperty(OVERRIDE_PROPERTY))
    }

    /**
     * The value Android itself acts on, in FeatureFlagUtils.isEnabled's order: the global setting
     * when it is set, otherwise the feature-flag override property. Developer options' "Disable
     * child process restrictions" writes only the property, so a device switched off there has no
     * global setting at all and must not be reported as unset.
     */
    fun status(global: String?, override: String?): PhantomProcessStatus {
        val value = global?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: override?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return when (value) {
            "false", "0" -> PhantomProcessStatus.DISABLED
            null -> PhantomProcessStatus.UNSET
            else -> PhantomProcessStatus.ENABLED
        }
    }

    private const val OVERRIDE_PROPERTY = "persist.sys.fflag.override.$SETTING"

    private fun systemProperty(name: String): String? = try {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String
    } catch (_: Exception) {
        null
    }

    fun hasDeveloperToggle(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun blocksSteam(status: PhantomProcessStatus): Boolean =
        status != PhantomProcessStatus.NOT_APPLICABLE && status != PhantomProcessStatus.DISABLED

    fun title(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED -> "子进程限制已开启"
        PhantomProcessStatus.UNSET -> "子进程限制未设置"
        PhantomProcessStatus.UNREADABLE -> "子进程限制无法检查"
        PhantomProcessStatus.DISABLED -> "子进程限制已关闭"
        PhantomProcessStatus.NOT_APPLICABLE -> "无需子进程限制设置"
    }

    fun instructions(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET, PhantomProcessStatus.UNREADABLE -> fixSentence() + "如果无法操作，请将本设备连接到装有 ADB 的电脑并运行下面的命令，然后回到这里重新检查。"
        PhantomProcessStatus.DISABLED -> "Steam 会话可以启动了。"
        PhantomProcessStatus.NOT_APPLICABLE -> "此 Android 版本不使用该限制。"
    }

    fun gateInstructions(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.ENABLED, PhantomProcessStatus.UNSET, PhantomProcessStatus.UNREADABLE -> fixSentence()
        else -> instructions(status)
    }

    fun fixSentence(sdk: Int = Build.VERSION.SDK_INT): String =
        if (hasDeveloperToggle(sdk)) "在开发者选项中开启“停用子进程限制”，之后请保持开发者选项开启。"
        else "此 Android 版本没有该开关，请使用无线调试在本设备上修改。"

    fun reportValue(status: PhantomProcessStatus): String = when (status) {
        PhantomProcessStatus.DISABLED -> "已关闭（正常：系统不会结束会话的子进程）"
        PhantomProcessStatus.ENABLED -> "已启用（系统可能在无日志的情况下结束会话；请关闭“限制子进程”）"
        PhantomProcessStatus.UNSET -> "未设置（沿用 ROM 默认值；DroidDeck 需要明确的已关闭值）"
        PhantomProcessStatus.UNREADABLE -> "无法读取（DroidDeck 未能验证该设置）"
        PhantomProcessStatus.NOT_APPLICABLE -> "不适用（Android 12 之前没有此限制）"
    }
}
