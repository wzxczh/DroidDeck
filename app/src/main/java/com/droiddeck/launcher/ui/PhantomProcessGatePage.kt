package com.droiddeck.launcher.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.core.WirelessAdbPairingService.Stage

@Composable
fun PhantomProcessGatePage(
    status: PhantomProcessStatus,
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onEnterAddressManually: () -> Unit,
    onCopyCommand: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val stage by WirelessAdbPairingService.stage.collectAsState()
    val environment by produceState(GateEnvironment.read(context)) {
        while (true) {
            value = GateEnvironment.read(context)
            kotlinx.coroutines.delay(2_000)
        }
    }
    val hasToggle = PhantomProcessLimit.hasDeveloperToggle()

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp && maxHeight < 500.dp
        SettingsPage(
            host = rememberMenuHost(),
            title = "只需修改一项 Android 设置",
            eyebrow = "Steam",
            lede = if (compact) null else "Android 会阻止启动大量后台进程的应用，而 Steam 会启动几十个进程。" +
                "在修改此设置之前，游戏会没有任何提示地关闭。",
            onBack = onDismiss,
            scrollContent = true,
            compactLayout = compact,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 620.dp
                val fix: @Composable ColumnScope.() -> Unit = {
                    FixGroup(hasToggle, stage, environment, compact, onOpenDeveloperOptions, onFixWithWirelessDebugging,
                        onOpenNotificationSettings, onCancel = { WirelessAdbPairingService.cancel(context) })
                }
                val side: @Composable ColumnScope.() -> Unit = {
                    StatusGroup(status, compact)
                    OtherWays(hasToggle, compact, onOpenDeveloperOptions, onFixWithWirelessDebugging, onEnterAddressManually, onCopyCommand)
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                        Column(Modifier.weight(1.2f), content = fix)
                        Column(Modifier.weight(1f), content = side)
                    }
                } else {
                    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                        fix()
                        side()
                    }
                }
            }
        }
    }
}

private data class GateEnvironment(val onWifi: Boolean, val notifications: Boolean) {
    companion object {
        fun read(context: Context): GateEnvironment {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val wifi = runCatching {
                connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(true)
            return GateEnvironment(wifi, WirelessAdbPairingService.notificationsEnabled(context))
        }
    }
}

@Composable
private fun FixGroup(
    hasToggle: Boolean,
    stage: Stage,
    environment: GateEnvironment,
    compact: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    val pairing = stage is Stage.Waiting || stage is Stage.CodeNeeded || stage is Stage.Working
    SettingsGroup(if (hasToggle) "开发者选项" else "自动修复", compact = compact) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                pairing -> PairingProgress(stage, onCancel)
                hasToggle -> {
                    Body("开启“停用子进程限制”后返回本页。请保持开发者选项开启：关闭开发者选项会把该开关一并关闭。")
                    PrimaryButton("打开开发者选项", compact = compact, onClick = onOpenDeveloperOptions)
                }
                else -> {
                    Body("此 Android 版本没有该开关，DroidDeck 将通过本设备的无线调试来修改。大约需要一分钟，无需电脑。")
                    (stage as? Stage.Failed)?.let { Body(it.error, error = true) }
                    PrimaryButton(
                        if (stage is Stage.Failed) "重试" else "自动修复",
                        compact = compact,
                        enabled = environment.onWifi && environment.notifications,
                        onClick = onFixWithWirelessDebugging,
                    )
                }
            }
            if (!hasToggle && !pairing) {
                if (!environment.onWifi) {
                    Body("无线调试需要 Wi-Fi。请先连接任意网络，无需可上网。", warn = true)
                }
                if (!environment.notifications) {
                    Body("配对码需要在通知中输入。请允许 DroidDeck 发送通知，或使用“其他方式”。", warn = true)
                    SecondaryButton("允许通知", compact = compact, onClick = onOpenNotificationSettings)
                }
            }
        }
    }
}

@Composable
private fun PairingProgress(stage: Stage, onCancel: () -> Unit) {
    val steps = listOf(
        "打开“设置 → 开发者选项 → 无线调试”并开启",
        "点按“使用配对码配对设备”",
        "在 DroidDeck 的通知中输入配对码",
    )
    val active = when (stage) {
        Stage.Waiting -> 1
        is Stage.CodeNeeded -> 2
        else -> 3
    }
    steps.forEachIndexed { index, step ->
        val done = index < active
        val current = index == active
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (done) "✓" else "${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (done) LocalPalette.current.good else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(step, style = MaterialTheme.typography.bodySmall,
                color = if (current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            when (stage) {
                Stage.Waiting -> "正在等待配对弹窗…"
                is Stage.CodeNeeded -> stage.error ?: "已找到配对弹窗，请在通知中输入其中的配对码。"
                is Stage.Working -> stage.step
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (stage is Stage.CodeNeeded && stage.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton("取消", compact = true, onClick = onCancel)
    }
}

@Composable
private fun StatusGroup(status: PhantomProcessStatus, compact: Boolean) {
    SettingsGroup("状态", compact = compact) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(PhantomProcessLimit.title(status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Body("每 2 秒检查一次，关闭后本页面会自动关闭。")
        }
    }
}

@Composable
private fun OtherWays(
    hasToggle: Boolean,
    compact: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onEnterAddressManually: () -> Unit,
    onCopyCommand: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsGroup("其他方式", compact = compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Body(if (hasToggle) "无线调试、手动地址、电脑 ADB" else "手动地址、电脑 ADB、开发者选项", modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Column(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasToggle) {
                SecondaryButton("改用无线调试", compact = true, onClick = onFixWithWirelessDebugging)
            } else {
                Body("部分 ROM 也带有该开关，可在开发者选项中搜索“子进程”。")
                SecondaryButton("打开开发者选项", compact = true, onClick = onOpenDeveloperOptions)
            }
            Body("如果通知无法使用，请自行输入弹窗中的地址和配对码。")
            SecondaryButton("手动输入地址", compact = true, onClick = onEnterAddressManually)
            Body("在装有 ADB 的电脑上执行：")
            Text(
                PhantomProcessLimit.adbCommand(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryButton("复制命令", compact = true, onClick = onCopyCommand)
        }
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, error: Boolean = false, warn: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            error -> MaterialTheme.colorScheme.error
            warn -> androidx.compose.ui.graphics.Color(0xFFFFB86B)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
fun DeveloperDisplayChoiceDialog(
    displays: List<Pair<Int, String>>,
    onMainScreen: () -> Unit,
    onSecondaryScreen: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("打开开发者选项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text("选择在哪个屏幕上打开 Android“设置”。")
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = onMainScreen) { Text("主屏幕") }
                displays.forEach { (id, label) ->
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { onSecondaryScreen(id) }) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
