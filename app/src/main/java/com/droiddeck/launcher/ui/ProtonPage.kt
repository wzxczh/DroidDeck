package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ProtonRow(val id: String, val name: String, val installed: String?, val queued: Boolean)

@Composable
fun ProtonPage(
    rows: List<ProtonRow>,
    busyId: String?,
    stage: String?,
    percent: Int,
    runtimeReady: Boolean,
    sessionRunning: Boolean,
    onInstall: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    SettingsPage(
        host,
        title = "Proton 版本",
        eyebrow = "设置",
        lede = "立即下载并安装 ARM64 Proton 构建，随后在 Steam > 属性 > 兼容性 中按游戏选择。",
        onBack = onBack,
    ) {
        if (!runtimeReady) Text(
            "请先在设置中安装 Linux 运行时，再管理兼容性工具。",
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        if (sessionRunning) Text(
            "请先停止当前会话，再安装或移除兼容性工具。",
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        SettingsGroup("可用版本") {
            for (row in rows) {
                SettingsRow(
                    row.name,
                    when {
                        row.installed != null -> "已安装 ${row.installed}"
                        row.queued -> "等待先前的请求"
                        else -> "未安装"
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            row.installed != null -> SecondaryButton("移除", enabled = busyId == null && runtimeReady && !sessionRunning) { onRemove(row.id) }
                            busyId != null -> SecondaryButton(if (busyId == row.id) "正在安装…" else "安装", enabled = false) {}
                            else -> SecondaryButton("立即安装", enabled = runtimeReady && !sessionRunning) { onInstall(row.id) }
                        }
                        if (row.queued && row.installed == null && busyId == null) {
                            SecondaryButton("取消排队", enabled = !sessionRunning) { onCancel(row.id) }
                        }
                    }
                }
                if (busyId == row.id) Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    Text(
                        if (stage != null && percent >= 0) "$stage · $percent%" else stage ?: "正在开始…",
                        fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp),
                    )
                    if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
    }
}
