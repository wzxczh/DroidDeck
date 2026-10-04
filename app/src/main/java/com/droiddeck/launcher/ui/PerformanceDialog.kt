package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.session.SessionPrefs

class CoreRow(val core: Int, val label: String)

@Composable
fun PerformancePage(
    cores: List<CoreRow>,
    clientOverride: Boolean,
    clientCores: Set<Int>,
    gameCores: Set<Int>,
    tuSysmem: Boolean,
    zinkLazy: Boolean,
    glThread: Boolean,
    noGlError: Boolean,
    noXalia: Boolean,
    gamescopeRealtime: Boolean,
    prootNoSeccomp: Boolean,
    guestHostname: String,
    phantomWarning: String?,
    onClientOverride: (Boolean) -> Unit,
    onTuSysmem: (Boolean) -> Unit,
    onZinkLazy: (Boolean) -> Unit,
    onGlThread: (Boolean) -> Unit,
    onNoGlError: (Boolean) -> Unit,
    onNoXalia: (Boolean) -> Unit,
    onGamescopeRealtime: (Boolean) -> Unit,
    onProotNoSeccomp: (Boolean) -> Unit,
    onGuestHostname: (String) -> Unit,
    onClientCore: (Int, Boolean) -> Unit,
    onGameCore: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    val coreItems = cores.map { it.core to it.label }
    SettingsPage(
        host, title = "性能",
        lede = "CPU 核心、会话修复与主机名，下次会话生效。",
        onBack = onDismiss,
    ) {
        SettingsGroup("Steam 客户端核心") {
            ToggleRow(
                host, "override", "覆盖 Steam 自身的核心选择",
                "将 Steam、其界面辅助进程和 gamescope 固定到下方核心，每隔几秒重新应用。",
                clientOverride, onChange = onClientOverride,
            )
            MultiRow(
                host, "clientCores", "客户端核心", if (clientOverride) "为 Steam 选择核心。" else "先启用覆盖开关再选择。",
                coreItems, clientCores, enabled = clientOverride, onToggle = onClientCore,
            )
        }
        SettingsGroup("游戏核心") {
            MultiRow(
                host, "gameCores", "游戏核心",
                "为游戏选择核心。全部选中时由 Android 在所有核心上调度。",
                coreItems, gameCores, onToggle = onGameCore,
            )
        }
        SettingsGroup("客户端界面") {
            ToggleRow(
                host, "glthread", "GL 多线程",
                "可能提升 Steam 菜单的响应速度，对设备整体的影响尚未验证。",
                glThread, onChange = onGlThread,
            )
            ToggleRow(
                host, "zink", "Zink：延迟描述符",
                "延迟且紧凑的描述符，推荐用于不支持描述符缓冲的驱动。",
                zinkLazy, onChange = onZinkLazy,
            )
            ToggleRow(
                host, "noglerror", "跳过 GL 错误检查",
                "关闭每次调用时的 GL 校验。",
                noGlError, onChange = onNoGlError,
            )
            ToggleRow(
                host, "gsrealtime", "gamescope：实时 GPU 队列",
                "让合成器的 GPU 任务优先于游戏。可使帧节奏更平稳，但会占用游戏的 GPU 时间。",
                gamescopeRealtime, onChange = onGamescopeRealtime,
            )
        }
        SettingsGroup("会话修复") {
            ToggleRow(
                host, "sysmem", "Turnip：系统内存渲染",
                "默认开启：对 Steam 界面和大多数游戏更快。Adreno 710/720/722 必须开启。",
                tuSysmem, onChange = onTuSysmem,
            )
            ToggleRow(
                host, "xalia", "跳过 Steam 的 xalia 辅助",
                "关闭 Proton 的手柄导航辅助（它会消耗每个游戏的 CPU 时间）。仅当游戏需要时才关闭本开关。",
                noXalia, onChange = onNoXalia,
            )
            ToggleRow(
                host, "seccomp", "proot 不使用 seccomp",
                "可能修复部分内核上的系统调用缺失错误，但会降低性能。",
                prootNoSeccomp, onChange = onProotNoSeccomp,
            )
        }
        SettingsGroup("会话标识") {
            var draft by remember(guestHostname) { mutableStateOf(guestHostname) }
            val valid = SessionPrefs.validGuestHostname(draft) != null
            SettingsRow(
                "主机名",
                if (valid || draft.isBlank()) "会话与 Steam 向其他程序报告的本机名称。留空则恢复为 ${SessionPrefs.DEFAULT_GUEST_HOSTNAME}。"
                else "仅限字母、数字和中间的连字符，最多 63 个字符。输入合法后才会保存。",
            ) {
                OutlinedTextField(
                    draft, { v ->
                        draft = v.take(63)
                        if (draft.isBlank() || SessionPrefs.validGuestHostname(draft) != null) onGuestHostname(draft)
                    },
                    singleLine = true, isError = !valid && draft.isNotBlank(),
                    placeholder = { Text(SessionPrefs.DEFAULT_GUEST_HOSTNAME) },
                    modifier = Modifier.width(220.dp),
                )
            }
        }
        if (phantomWarning != null) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.error.copy(alpha = 0.08f))
                    .border(1.dp, colors.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                Text("Android 将会结束此会话", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
                Text(phantomWarning, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
