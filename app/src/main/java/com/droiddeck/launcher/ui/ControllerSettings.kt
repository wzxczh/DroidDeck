package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.session.SessionPrefs

class ControllerActions(
    val onOsc: (String) -> Unit,
    val onTint: (Int) -> Unit,
    val onOpacity: (Int) -> Unit,
    val onSize: (Int) -> Unit,
    val onStickClick: (Boolean) -> Unit,
    val onAdaptiveSticks: (Boolean) -> Unit,
    val onEditLayout: () -> Unit,
    val onResetLayout: () -> Unit,
    val onMapping: () -> Unit,
    val onResetAll: () -> Unit,
)

@Composable
private fun Swatch(color: Int) {
    Box(Modifier.size(14.dp).background(Color(color), CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
}

@Composable
fun ColumnScope.ControllerRows(host: MenuHost, oscMode: String, c: ControllerPrefs.Settings, a: ControllerActions) {
    ChoiceRow(
        host, "controller-osc", "屏幕控件", "会话中触控板何时出现",
        listOf(SessionPrefs.OSC_AUTO to "自动", SessionPrefs.OSC_ALWAYS to "始终", SessionPrefs.OSC_STEAM_QAM to "Steam + QAM", SessionPrefs.OSC_NEVER to "从不"),
        oscMode, note = "“自动”在没有手柄时显示全部控件。“Steam + QAM”仅显示这些按钮。", onPick = a.onOsc,
    )
    val tintOpen = host.open == "controller-tint"
    SettingsRow("颜色", "屏幕按钮的色调", highlighted = tintOpen) {
        Box {
            ValueChip(ControllerPrefs.tints.firstOrNull { it.first == c.tint }?.second ?: "自定义", tintOpen) {
                host.open = if (tintOpen) null else "controller-tint"
            }
            AnchoredMenu(tintOpen, onDismiss = { if (host.open == "controller-tint") host.open = null }, title = "颜色") { firstItemFocus ->
                ControllerPrefs.tints.forEachIndexed { index, (color, name) ->
                    MenuItem(name, checked = c.tint == color, leading = { Swatch(color) }, focusRequester = if (index == 0) firstItemFocus else null) {
                        a.onTint(color)
                        host.open = null
                    }
                }
            }
        }
    }
    ChoiceRow(host, "controller-opacity", "不透明度", null, ControllerPrefs.opacities.map { it to "$it%" }, c.opacity, onPick = a.onOpacity)
    ChoiceRow(host, "controller-size", "按钮大小", "100% 为标准大小", ControllerPrefs.sizes.map { it to "$it%" }, c.size, onPick = a.onSize)
    ToggleRow(host, "controller-stick-click", "摇杆点按", "双击摇杆并按住可触发 L3 或 R3", c.stickClick, onChange = a.onStickClick)
    ToggleRow(host, "controller-adaptive", "自适应摇杆", "触碰已保存位置附近时显示摇杆，松开后隐藏", c.adaptiveSticks, onChange = a.onAdaptiveSticks)
    SettingsRow("布局", if (c.customLayout) "已保存自定义位置" else "已按本屏幕尺寸与握持方式摆放") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("编辑") { a.onEditLayout() }
            SecondaryButton("重置", enabled = c.customLayout) { a.onResetLayout() }
        }
    }
    val remapped = c.mapping.count { (id, target) -> id != target }
    ActionRow("按钮映射", if (remapped == 0) "每个按钮发送各自的输入" else "已重映射 ${c.mapping.size} 个按钮中的 $remapped 个", "配置", a.onMapping)
    ActionRow("重置手柄", "恢复默认颜色、不透明度、大小、摇杆行为、映射与布局", "重置", a.onResetAll)
}

@Composable
fun ControllerMappingPage(mapping: Map<String, String>, onPick: (String, String) -> Unit, onReset: () -> Unit, onBack: () -> Unit) {
    val host = rememberMenuHost()
    SettingsPage(
        host, title = "按钮映射", eyebrow = "手柄",
        lede = "选择每个屏幕按钮向游戏发送的内容。“隐藏”会移除该按钮。",
        onBack = onBack,
    ) {
        SettingsGroup("屏幕按钮") {
            for ((id, name) in ControllerPrefs.mappable) {
                ChoiceRow(host, "map-$id", name, null, ControllerPrefs.targets, mapping[id] ?: id) { onPick(id, it) }
            }
        }
        SettingsGroup("默认") {
            ActionRow("重置映射", "每个按钮重新发送各自的输入", "重置", onReset)
        }
    }
}
