package com.droiddeck.launcher.ui

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.session.SessionPrefs

// The Setup page: runtime and device checks, tools, frame generation and launch settings.

private enum class CheckState { OK, WARN, BUSY }

/** One requirement in Setup's system check: a status mark, what it is, and at most one action. */
@Composable
private fun CheckRow(state: CheckState, title: String, detail: String?, divider: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val tint = when (state) {
        CheckState.OK -> pal.good
        CheckState.WARN -> AttentionAmber
        CheckState.BUSY -> pal.signal
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
            .background(if (state == CheckState.WARN) AttentionAmber.copy(alpha = 0.07f) else Color.Transparent)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f))) {
            Icon(
                when (state) {
                    CheckState.OK -> Icons.Filled.Check
                    CheckState.WARN -> Icons.Filled.PriorityHigh
                    CheckState.BUSY -> Icons.Filled.Refresh
                },
                contentDescription = when (state) {
                    CheckState.OK -> "完成"
                    CheckState.WARN -> "需要处理"
                    CheckState.BUSY -> "处理中"
                },
                tint = tint, modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (detail != null) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) action()
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
internal fun SetupPanel(
    s: FrontEndState,
    a: FrontEndActions,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
) {
    val host = rememberMenuHost()
    var processLimitBusy by remember { mutableStateOf(false) }
    var processLimitMessage by remember { mutableStateOf<String?>(null) }
    val setProcessLimit: (Boolean) -> Unit = { enabled ->
        processLimitBusy = true
        processLimitMessage = null
        a.onSetPhantomProcessLimit(enabled) { error ->
            processLimitBusy = false
            if (error == null) {
                a.onRefreshPhantomStatus()
            } else {
                processLimitMessage = "请检查无线调试的 IP 地址与端口；若 Android 已移除该设备，请重新配对。"
                onRequestWirelessAdb(enabled)
            }
        }
    }
    val runtime = when {
        s.busy -> "处理中…"
        !s.ready -> "安装"
        s.available != null && s.available != s.installed -> "更新"
        else -> "管理"
    }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val gpuOk = remember { DeviceSupport.adreno() }
    val gpuName = remember { DeviceSupport.gpuName() }
    val limitBlocks = PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)
    val signedIn = s.offlineAccount != null
    var showLimitDetails by rememberSaveable { mutableStateOf(false) }
    val checks = 4
    val readyCount = listOf(gpuOk, s.ready && !s.busy, !limitBlocks, signedIn).count { it }
    // Five tabs instead of one long scroll; LB and RB turn them from anywhere on the page.
    val tabs = listOf("概览", "手柄", "会话", "启动器", "关于")
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabFocus = remember { List(tabs.size) { FocusRequester() } }
    var tabTurned by remember { mutableStateOf(false) }
    val pick: (Int) -> Unit = { i -> tab = i; tabTurned = true }
    val inputModeManager = LocalInputModeManager.current
    // The control a controller was on went with the old tab: it lands on the new tab itself.
    LaunchedEffect(tab) {
        if (tabTurned && inputModeManager.inputMode == InputMode.Keyboard) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { tabFocus[tab].requestFocus() }
        }
    }
    Rise(0, Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().bumpers(
                onPrevious = { pick((tab + tabs.size - 1) % tabs.size) },
                onNext = { pick((tab + 1) % tabs.size) },
            ),
        ) {
            PageHeader("设置") {
                Chip(if (readyCount == checks) "● 全部就绪" else "$readyCount/$checks 就绪", ok = readyCount == checks)
            }
            TabStrip(tabs, tab, pick, Modifier.padding(bottom = 4.dp), tabFocus)
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (tab) {
                    0 -> {
                        SectionTitle("系统检查", null)
                        // What Steam needs, one row each: green when done, one button when not. The
                        // process-limit controls only open under their row.
                        Column(modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
                            CheckRow(
                                if (gpuOk) CheckState.OK else CheckState.WARN,
                                if (gpuOk) "设备受支持" else "GPU 不受支持",
                                if (gpuOk) gpuName else "Steam 使用 Adreno 驱动渲染；$gpuName 可能出现黑屏",
                            )
                            CheckRow(
                                when { s.busy -> CheckState.BUSY; !s.ready -> CheckState.WARN; else -> CheckState.OK },
                                "Linux 运行时",
                                when {
                                    s.busy -> if (s.percent >= 0) "${s.stage} · ${s.percent}%" else s.stage
                                    !s.ready -> "未安装 · 约 3 GB，首次启动时安装"
                                    s.available != null && s.available != s.installed -> "${s.installed ?: "已安装"} · 有更新可用"
                                    else -> "${s.installed ?: "已安装"} · 已是最新"
                                },
                            ) { SecondaryButton(runtime, enabled = !s.busy, compact = true, onClick = a.onRuntime) }
                            CheckRow(
                                if (limitBlocks) CheckState.WARN else CheckState.OK,
                                "子进程限制",
                                when (s.phantomProcessStatus) {
                                    PhantomProcessStatus.ENABLED -> "已开启 · Android 可能会关闭 Steam。关闭它约需一分钟"
                                    PhantomProcessStatus.UNSET -> "未设置 · ROM 默认值可能会关闭 Steam。关闭它约需一分钟"
                                    PhantomProcessStatus.UNREADABLE -> "无法检查 · 关闭它约需一分钟"
                                    else -> PhantomProcessLimit.title(s.phantomProcessStatus)
                                },
                            ) {
                                if (limitBlocks) PrimaryButton(if (showLimitDetails) "隐藏" else "修复", compact = true) { showLimitDetails = !showLimitDetails }
                                else if (s.phantomProcessStatus != PhantomProcessStatus.NOT_APPLICABLE) {
                                    SecondaryButton(if (showLimitDetails) "隐藏" else "详情", compact = true) { showLimitDetails = !showLimitDetails }
                                }
                            }
                            AnimatedVisibility(showLimitDetails, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                // One sentence and at most three buttons. The computer route and its
                                // raw command live on the full page Wireless debugging opens.
                                Column(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
                                    Text(
                                        if (limitBlocks) PhantomProcessLimit.gateInstructions(s.phantomProcessStatus)
                                        else PhantomProcessLimit.instructions(s.phantomProcessStatus),
                                        fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    Actions {
                                        if (limitBlocks) {
                                            PrimaryButton("开发者选项", compact = true, onClick = onOpenDeveloperOptions)
                                            SecondaryButton("使用无线调试", compact = true, enabled = !processLimitBusy) { setProcessLimit(false) }
                                            SecondaryButton("重新检查", compact = true, onClick = a.onRefreshPhantomStatus)
                                        } else if (s.phantomProcessStatus == PhantomProcessStatus.DISABLED) {
                                            SecondaryButton("开启限制", compact = true, enabled = !processLimitBusy) { setProcessLimit(true) }
                                        }
                                    }
                                    if (processLimitBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                    processLimitMessage?.let { Text(it, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
                                }
                            }
                            CheckRow(
                                if (signedIn) CheckState.OK else CheckState.WARN,
                                "Steam 账户",
                                s.offlineAccount?.let { if (s.offline) "已登录 $it · 离线模式" else "已登录 $it" } ?: "点击启动并登录 Steam",
                                divider = false,
                            )
                        }
                        SectionTitle("工具", null)
                        ToolGrid(s, a)
                    }
                    1 -> {
                        val controller = s.controller
                        if (controller != null && a.controller != null) SettingsGroup("手柄") {
                            ControllerRows(host, s.oscMode, controller, a.controller)
                        }
                        if (s.controller == null || a.controller == null) Note("手柄设置不可用。")
                    }
                    2 -> {
                        SettingsGroup("会话") {
                            ChoiceRow(
                                host, "back-actions", "返回", SessionPrefs.backActionsOrder(s.backActionsInverted),
                                listOf(
                                    false to SessionPrefs.BACK_MENU_THEN_QAM,
                                    true to SessionPrefs.BACK_QAM_THEN_MENU,
                                ), s.backActionsInverted, onPick = a.onBackActionsInverted,
                            )
                            SettingsRow("帧生成", "选择帧生成模式") {
                                Box {
                                    ValueChip(s.frameGenLabel, host.open == "fg") { host.open = if (host.open == "fg") null else "fg" }
                                    FrameGenMenu(s, a, host)
                                }
                            }
                            ToggleRow(host, "logs", "会话日志", "每次会话结束后保存", s.logsEnabled) { a.onLogs() }
                            ActionRow("最新的会话日志", "随错误报告一同发送", "分享日志", a.onShareLogs)
                            ToggleRow(
                                host, "offline", "离线模式",
                                s.offlineAccount?.let { "已登录 $it" } ?: "请先登录 Steam",
                                s.offline, enabled = s.offlineAccount != null,
                            ) { a.onOffline() }
                        }
                    }
                    3 -> {
                        SettingsGroup("启动器") {
                            SettingsRow("主题", "选择启动器外观") {
                                Box {
                                    ValueChip(Themes.byId(s.theme).label, host.open == "theme") { host.open = if (host.open == "theme") null else "theme" }
                                    AnchoredMenu(host.open == "theme", onDismiss = { if (host.open == "theme") host.open = null }, title = "主题") { firstItemFocus ->
                                        Themes.all.forEachIndexed { index, theme ->
                                            MenuItem(theme.label, checked = s.theme == theme.id, focusRequester = if (index == 0) firstItemFocus else null) {
                                                a.onTheme(theme.id)
                                                host.open = null
                                            }
                                        }
                                    }
                                }
                            }
                            ToggleRow(
                                host, "home-screen", "设为主屏幕应用",
                                if (s.homeScreenEnabled) "DroidDeck 可以作为手机的主屏幕应用" else "关闭：DroidDeck 不会作为主屏幕应用",
                                s.homeScreenEnabled,
                            ) { a.onHomeScreen(it) }
                            ToggleRow(
                                host, "launcher-fullscreen", "全屏",
                                if (s.launcherFullscreen) "隐藏 Android 状态栏与导航栏" else "显示 Android 状态栏与导航栏",
                                s.launcherFullscreen,
                            ) { a.onLauncherFullscreen(it) }
                            if (s.homeScreenEnabled) {
                                ActionRow("默认主屏幕应用", s.defaultHomeLabel ?: "选择主屏幕应用", "选择", a.onHomeApp)
                            }
                        }
                        SettingsGroup("Linux 应用（测试版）") {
                            ToggleRow(
                                host, "store-enabled", "Flathub 商店",
                                if (s.storeEnabled) "商店位于菜单中。部分应用可能无法启动；日志在 Download/DroidDeck"
                                else "关闭：使用 Flatpak 从 Flathub 安装 Linux 应用与游戏",
                                s.storeEnabled,
                            ) { a.onStoreEnabled(it) }
                            ToggleRow(
                                host, "appimages-enabled", "AppImages",
                                if (s.appImagesEnabled) "“添加 AppImage”在桌面页面。仅支持 ARM64（aarch64）AppImage"
                                else "关闭：从存储空间导入 ARM64 AppImage",
                                s.appImagesEnabled,
                            ) { a.onAppImagesEnabled(it) }
                        }
                    }
                    else -> {
                        SettingsGroup("关于") {
                            ActionRow("版本", s.buildLabel, "检查新版本", a.onCheckLatestBuild)
                            ActionRow("致谢", "DroidDeck 所基于的人物与项目", "查看", a.onCredits)
                        }
                    }
                }
            }
        }
    }
}

/** The launcher's own tools as cards: four across, two by two on a narrow page. */
@Composable
private fun ToolGrid(s: FrontEndState, a: FrontEndActions) {
    val columns = if (LocalNarrowPane.current) 2 else 4
    val tools = listOf(
        ToolSpec(Icons.Outlined.Folder, "文件", "浏览与管理文件", a.onFiles),
        ToolSpec(Icons.Outlined.Extension, "Proton 版本", "安装 ARM64 Proton 构建", a.onProtons),
        ToolSpec(Icons.Outlined.Speed, "性能", "CPU 核心分配", a.onPerformance),
        ToolSpec(Icons.Outlined.VideogameAsset, "ROM 文件夹", s.romsDir ?: "选择模拟器游戏的存储位置", a.onRoms),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        for (row in tools.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (t in row) ToolCard(t, Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private class ToolSpec(val icon: ImageVector, val title: String, val detail: String, val onClick: () -> Unit)

@Composable
private fun ToolCard(t: ToolSpec, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "toolScale")
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.paneItem("tool:${t.title}")
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .border(if (hot) 2.dp else 1.dp, if (hot) pal.signal else pal.line, Shape14)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = t.onClick)
            .controllerConfirm(onClick = t.onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(t.icon, contentDescription = null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FrameGenMenu(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    FrameGenMenu(host, s.frameGenEngine, s.frameGenMultiplier, s.lsfgReady, a.onFrameGenPick)
}

/**
 * What shapes a launch, beside the game rather than three screens away in Setup. They are the
 * app-wide settings - each card opens the same page or menu Setup does.
 */
@Composable
internal fun LaunchSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    // Three across, two on a narrow page; each row's cards share one height.
    val columns = if (LocalNarrowPane.current) 2 else 3
    val controller = a.controller
    val cards = buildList<@Composable (Modifier) -> Unit> {
        add { m -> SettingCard("组件", "FEX, DXVK, VKD3D", "card:components", m) { a.onComponents(true) } }
        add { m ->
            Box(m) {
                SettingCard("帧生成", s.frameGenLabel, "card:fg", Modifier.fillMaxSize()) {
                    host.open = if (host.open == "fg") null else "fg"
                }
                FrameGenMenu(s, a, host)
            }
        }
        if (controller != null) add { m -> SettingCard("控制", "按键映射", "card:controls", m, controller.onMapping) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        for (row in cards.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (card in row) card(Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingCard(label: String, value: String, id: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "cardScale")
    val edge by animateColorAsState(if (hot) pal.signal else pal.line2, Motion.tw(220), label = "cardEdge")
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.paneItem(id)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .border(if (hot) 2.dp else 1.dp, edge, Shape14)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (hot) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
