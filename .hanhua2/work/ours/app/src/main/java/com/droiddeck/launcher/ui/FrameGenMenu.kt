package com.droiddeck.launcher.ui

import androidx.compose.runtime.Composable
import com.droiddeck.launcher.gpu.FrameGen

/**
 * The frame generation picker, anchored to whatever opened it (host key "fg"). One copy for Setup,
 * a game's launch settings and the session drawer, so they say the same thing.
 */
@Composable
internal fun FrameGenMenu(
    host: MenuHost, engine: String, multiplier: Int, lsfgReady: Boolean,
    onPick: (engine: String, multiplier: Int) -> Unit,
) {
    AnchoredMenu(host.open == "fg", onDismiss = { if (host.open == "fg") host.open = null }, title = "帧生成") { firstItemFocus ->
        val need = if (lsfgReady) null else "需要在 Steam 中安装 Lossless Scaling"
        fun pick(e: String, m: Int) { onPick(e, m); host.open = null }
        MenuItem("关闭", checked = engine == FrameGen.ENGINE_OFF, focusRequester = firstItemFocus) { pick(FrameGen.ENGINE_OFF, 2) }
        for (m in 2..4) MenuItem(FrameGen.label(FrameGen.ENGINE_WINFG, m), checked = engine == FrameGen.ENGINE_WINFG && multiplier == m) { pick(FrameGen.ENGINE_WINFG, m) }
        for (m in 2..4) MenuItem(FrameGen.label(FrameGen.ENGINE_LSFG, m), checked = engine == FrameGen.ENGINE_LSFG && multiplier == m, enabled = lsfgReady, detail = need) { pick(FrameGen.ENGINE_LSFG, m) }
    }
}
