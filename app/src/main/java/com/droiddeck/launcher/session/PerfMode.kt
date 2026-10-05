package com.droiddeck.launcher.session

import android.app.Activity
import android.app.GameManager
import android.app.GameState
import android.os.Build
import android.util.Log

/**
 * What a session tells Android about itself so the SoC is run for a game, not for an app.
 *
 * The manifest already makes the app a game (appCategory + game_mode_config); this is the
 * per-window half, applied when the session activity is created:
 *
 *  - NOT sustained performance mode: on Pixel and Qualcomm power HALs it caps the CPU and GPU at
 *    a level the device can hold indefinitely rather than holding a floor, which cost games and
 *    Big Picture their peak clocks. WinNative never asks for it; the thermal budget is left to
 *    the game;
 *  - the panel's fastest mode at its current size (Android 6+): a 120 Hz phone is switched to
 *    120 Hz for the menu, not only when a game votes for it. With a frame cap, the fastest mode
 *    the cap divides evenly, so every capped frame is shown for the same number of refreshes;
 *  - GameManager's game state (Android 13+): "in gameplay", so an OEM framework that releases
 *    its boost on loading screens or menus keeps it up.
 *
 * Every step is version-guarded and skipped silently below its API; the returned line is what
 * the session log shows, which is the first thing to read on a device that feels slow.
 */
object PerfMode {
    private const val TAG = "PerfMode"

    fun apply(a: Activity, fpsLimit: Int = 0): String {
        val parts = ArrayList<String>(2)

        // The fastest display mode at the panel's current size.
        parts += try {
            val display = if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay
            if (display == null || Build.VERSION.SDK_INT < 23) "显示模式未变更" else {
                val cur = display.mode
                val sameSize = display.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                val best = sameSize.filter { cadenceFits(it.refreshRate, fpsLimit) }.maxByOrNull { it.refreshRate }
                    ?: sameSize.maxByOrNull { it.refreshRate }
                if (best != null && best.modeId != cur.modeId &&
                    (best.refreshRate > cur.refreshRate + 0.5f || !cadenceFits(cur.refreshRate, fpsLimit))) {
                    a.window.attributes = a.window.attributes.apply { preferredDisplayModeId = best.modeId }
                    "显示 ${cur.refreshRate.toInt()} → ${best.refreshRate.toInt()} Hz（模式 ${best.modeId}）"
                } else "显示 ${cur.refreshRate.toInt()} Hz"
            }
        } catch (t: Throwable) { Log.w(TAG, "display mode", t); "显示模式设置失败" }

        // GameManager: what mode the OS put us in, and that we are playing.
        parts += if (Build.VERSION.SDK_INT >= 31) {
            val gm = a.getSystemService(GameManager::class.java)
            if (gm == null) "无 GameManager" else {
                val mode = when (gm.gameMode) {
                    GameManager.GAME_MODE_PERFORMANCE -> "性能"
                    GameManager.GAME_MODE_BATTERY -> "省电"
                    GameManager.GAME_MODE_STANDARD -> "标准"
                    else -> "不支持"
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    try { gm.setGameState(GameState(false, GameState.MODE_GAMEPLAY_INTERRUPTIBLE)) } catch (t: Throwable) { Log.w(TAG, "游戏状态", t) }
                }
                "游戏模式 $mode"
            }
        } else "游戏模式需要 Android 12"

        return parts.joinToString(" · ")
    }

    /** A panel rate that shows every capped frame for a whole number of refreshes (no cap: any). */
    fun cadenceFits(hz: Float, fpsLimit: Int): Boolean {
        if (fpsLimit <= 0) return true
        val ratio = hz / fpsLimit
        return ratio >= 0.98f && Math.abs(ratio - Math.round(ratio)) < 0.02f
    }
}
