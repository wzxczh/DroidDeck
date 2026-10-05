package com.droiddeck.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService

/** [tag] is shown beside the name: [BUNDLED], [DOWNLOADED], [IMPORTED], or "" for Auto / Runtime default. */
class DriverRow(val id: String, val name: String, val detail: String, val removable: Boolean, val tag: String = "") {
    companion object {
        const val BUNDLED = "内置"
        const val DOWNLOADED = "已下载"
        const val IMPORTED = "已导入"
    }
}

/** A release driver (Banners-Turnip, WinNative) that is not installed yet; [key] is its asset name. */
class DownloadRow(val key: String, val label: String, val detail: String, val progress: Int? = null)

class ModeSettings(
    val mode: String,
    val resolutionCap: Int,
    /** A fixed session size, or null for the cap and shape. */
    val customResolution: Pair<Int, Int>? = null,
    val shapeMode: String,
    val hdr: Boolean,
    val hdrReason: String?,
    val linuxRows: List<DriverRow>,
    val linuxSelected: String,
    val androidRows: List<DriverRow>,
    val androidSelected: String,
    val touchMode: String,
    val suspendPolicy: String,
    /** Steam only. */
    val oscMode: String?,
    /** Steam only: whether single and double Back actions are swapped. */
    val backActionsInverted: Boolean = false,
    val directAudio: Boolean?,
    val clientDirectAudio: Boolean = false,
    val mic: Boolean?,
    val renderer: String?,
    val gameStorage: String? = null,
    val storageOptions: List<Pair<String, String>> = emptyList(),
    val fexPreset: String? = null,
    /** Steam only: games are stretched to fill the screen (null = not a Steam page). */
    val forceFullscreen: Boolean? = null,
    /** Steam only: the client branch forced on the command line. */
    val steamChannel: String? = null,
    /** Steam only: enable the SteamOS client interface and its performance controls. */
    val steamDeckMode: Boolean = false,
    /** Steam only: what the pad is to the client (SessionPrefs.CONTROLLER_*); null outside Steam. */
    val steamController: String? = null,
    /** Steam only: start a Steam session when DroidDeck opens. */
    val runSteamAtStartup: Boolean = false,
    /** Steam only: the user's chosen Games folders; null outside Steam. */
    val addedGamesDirs: List<String>? = null,
    val addedGames: List<AddedGameRow> = emptyList(),
    val addedGamesArt: Boolean = true,
    /** Latest Banners-Turnip release: what each driver menu offers to download, and the refresh line. */
    val linuxDownloads: List<DownloadRow> = emptyList(),
    val androidDownloads: List<DownloadRow> = emptyList(),
    val releaseStatus: String = "尚未检查 - 点按刷新查找新驱动",
    val releaseChecking: Boolean = false,
    /** A bundled display driver was deleted: the page offers to restore it. */
    val canRestoreBundled: Boolean = false,
    /** Steam only: Decky Loader is managed from the Steam session settings. */
    val deckyInstalled: String? = null,
    val deckyLatestRelease: DeckyManager.Release? = null,
    val deckyChecking: Boolean = false,
    val deckyStage: String? = null,
    val deckyPercent: Int = -1,
    val deckyEnabled: Boolean = false,
    val deckySessionRunning: Boolean = false,
)

/** One added game as the settings page shows it: its folder, the chosen .exe, the other .exe files it could be. */
class AddedGameRow(val folderPath: String, val folderName: String, val exePath: String, val exeName: String, val candidates: List<Pair<String, String>>)

class ModeSettingsActions(
    val onResolution: (Int) -> Unit,
    /** Null clears it. */
    val onCustomResolution: (Pair<Int, Int>?) -> Unit = {},
    val onShape: (String) -> Unit,
    val onHdr: (Boolean) -> Unit,
    val onSelectLinux: (String) -> Unit,
    val onImportLinux: () -> Unit,
    val onRemoveLinux: (String) -> Unit,
    val onRefreshReleases: () -> Unit = {},
    /** Asset name of the release driver to download. */
    val onDownloadDriver: (String) -> Unit = {},
    val onRestoreBundled: () -> Unit = {},
    val onSelectAndroid: (String) -> Unit,
    val onImportAndroid: () -> Unit,
    val onRemoveAndroid: (String) -> Unit,
    val onTouch: (String) -> Unit,
    val onSuspendPolicy: (String) -> Unit,
    val onOsc: (String) -> Unit,
    val onBackActionsInverted: (Boolean) -> Unit = {},
    val onDirectAudio: (Boolean) -> Unit,
    val onClientDirectAudio: (Boolean) -> Unit = {},
    val onMic: (Boolean) -> Unit,
    val onRenderer: (String) -> Unit,
    val onGameStorage: (path: String, label: String) -> Unit = { _, _ -> },
    val onPickGameStorageFolder: () -> Unit = {},
    val onFexPreset: (String) -> Unit = {},
    val onForceFullscreen: (Boolean) -> Unit = {},
    val onSteamChannel: (String) -> Unit = {},
    val onSteamDeckMode: (Boolean) -> Unit = {},
    val onSteamController: (String) -> Unit = {},
    val onRunSteamAtStartup: (Boolean) -> Unit = {},
    val onPickAddedGamesDir: () -> Unit = {},
    val onForgetAddedGamesDir: (path: String) -> Unit = {},
    val onAddedGamesArt: (Boolean) -> Unit = {},
    val onAddedGameExe: (folderPath: String, path: String) -> Unit = { _, _ -> },
    val onPickAddedGameExe: (folderPath: String) -> Unit = {},
    val onDeckyInstall: (DeckyManager.Release) -> Unit = {},
    val onDeckyCheck: () -> Unit = {},
    val onDeckyEnabled: (Boolean) -> Unit = {},
    val onDeckyUninstall: () -> Unit = {},
    val onDismiss: () -> Unit,
)

@Composable
fun ModeSettingsPage(s: ModeSettings, a: ModeSettingsActions) {
    val steam = s.mode == SessionService.MODE_STEAM
    val host = rememberMenuHost()
    var confirmDeckyRemoval by remember { mutableStateOf(false) }
    // The two driver lists open as full pages over this one ("rt" = runtime, "panel" = display).
    // Coming back restores this page as it was left: the same scroll position, and controller focus
    // on the driver box that opened the page.
    var driverPage by remember { mutableStateOf<String?>(null) }
    var returnTo by remember { mutableStateOf<String?>(null) }
    val pageScroll = androidx.compose.foundation.rememberScrollState()
    val runtimeChip = remember { androidx.compose.ui.focus.FocusRequester() }
    val displayChip = remember { androidx.compose.ui.focus.FocusRequester() }
    val firstChip = remember { androidx.compose.ui.focus.FocusRequester() }
    fun openDriverPage(key: String) { returnTo = key; driverPage = key }
    androidx.compose.runtime.LaunchedEffect(driverPage) {
        if (driverPage == null) {
            // One frame first: the box has to be laid out before it can take focus. Opened from the
            // cog, focus starts on the first control (Resolution) so the d-pad works at once; back
            // from a driver page, it returns to the driver box that opened it.
            androidx.compose.runtime.withFrameNanos { }
            val target = when (returnTo) { "rt" -> runtimeChip; "panel" -> displayChip; else -> firstChip }
            runCatching { target.requestFocus() }
        }
    }
    when (driverPage) {
        "rt" -> {
            DriverPage(
                title = "运行时驱动",
                hint = (if (steam) "Steam 和游戏使用。" else "桌面应用使用。") + "下次会话生效。",
                rows = s.linuxRows, selected = s.linuxSelected, downloads = s.linuxDownloads,
                status = s.releaseStatus, checking = s.releaseChecking, importLabel = "导入 Turnip 压缩包…", canRestore = false,
                onSelect = a.onSelectLinux, onDelete = a.onRemoveLinux, onRefresh = a.onRefreshReleases,
                onDownload = a.onDownloadDriver, onImport = a.onImportLinux, onRestore = {}, onBack = { driverPage = null },
            )
            return
        }
        "panel" -> {
            DriverPage(
                title = "显示驱动",
                hint = "两种模式下合成都使用。重启应用后生效。",
                rows = s.androidRows, selected = s.androidSelected, downloads = s.androidDownloads,
                status = s.releaseStatus, checking = s.releaseChecking, importLabel = "导入 AdrenoTools 压缩包…",
                canRestore = s.canRestoreBundled,
                onSelect = a.onSelectAndroid, onDelete = a.onRemoveAndroid, onRefresh = a.onRefreshReleases,
                onDownload = a.onDownloadDriver, onImport = a.onImportAndroid, onRestore = a.onRestoreBundled,
                onBack = { driverPage = null },
            )
            return
        }
    }
    SettingsPage(
        host,
        title = if (steam) "Steam 会话" else "桌面会话",
        onBack = a.onDismiss,
        scroll = pageScroll,
    ) {
        SettingsGroup("显示") {
            val default = SessionPrefs.defaultResolutionCap(s.mode)
            var editCustom by remember { mutableStateOf(false) }
            val custom = s.customResolution
            ChoiceRow(
                host, "res", "分辨率", "下次会话生效。",
                listOf(720 to "最高 720p", 900 to "最高 900p", 1080 to "最高 1080p", 0 to "屏幕原生")
                    .map { (cap, label) -> cap to (if (cap == default) "$label - 默认" else label) } +
                    (CUSTOM to (custom?.let { "自定义 · ${it.first}×${it.second}" } ?: "自定义…")),
                if (custom != null) CUSTOM else s.resolutionCap, note = "720p 可提升菜单响应速度。",
                chipModifier = androidx.compose.ui.Modifier.focusRequester(firstChip),
                onPick = { v -> if (v == CUSTOM) editCustom = true else { a.onCustomResolution(null); a.onResolution(v) } },
            )
            ChoiceRow(
                host, "shape", "屏幕比例",
                if (custom != null) "由自定义分辨率决定。" else "自动时至少为 16:9。",
                com.droiddeck.launcher.session.SessionPrefs.shapeChoices, s.shapeMode, enabled = custom == null, onPick = a.onShape,
            )
            if (editCustom) CustomResolutionDialog(
                initial = custom,
                onSave = { size -> editCustom = false; a.onCustomResolution(size) },
                onDismiss = { editCustom = false },
            )
        }
        SettingsGroup("HDR") {
            ToggleRow(
                host, "hdr", "HDR10 输出",
                s.hdrReason?.let { "不可用：$it。" }
                    ?: "重启应用后生效。",
                checked = s.hdr && s.hdrReason == null, enabled = s.hdrReason == null, onChange = a.onHdr,
            )
        }
        SettingsGroup("驱动") {
            SettingsRow("运行时驱动", (if (steam) "Steam 和游戏使用。" else "桌面应用使用。") + "下次会话生效。") {
                ValueChip(
                    s.linuxRows.firstOrNull { it.id == s.linuxSelected }?.name ?: "运行时默认", open = false,
                    modifier = androidx.compose.ui.Modifier.focusRequester(runtimeChip),
                ) { openDriverPage("rt") }
            }
            SettingsRow("显示驱动", "两种模式下合成都使用。重启应用后生效。") {
                ValueChip(
                    s.androidRows.firstOrNull { it.id == s.androidSelected }?.name ?: "自动 - 按 GPU 选择", open = false,
                    modifier = androidx.compose.ui.Modifier.focusRequester(displayChip),
                ) { openDriverPage("panel") }
            }
        }
        SettingsGroup(if (steam) "触摸与控件" else "触摸") {
            ChoiceRow(
                host, "touch", "触摸", null,
                listOf("auto" to "自动", "touchpad" to "触摸板", "direct" to "直接"), s.touchMode,
                note = "自动：桌面用触摸板，Steam 内用直接输入。触摸板：拖动移动，轻点单击。",
                onPick = a.onTouch,
            )
            if (steam && s.oscMode != null) ChoiceRow(
                host, "osc", "屏幕控件", null,
                listOf(
                    SessionPrefs.OSC_AUTO to "自动",
                    SessionPrefs.OSC_ALWAYS to "始终",
                    SessionPrefs.OSC_STEAM_QAM to "Steam + QAM",
                    SessionPrefs.OSC_NEVER to "从不",
                ), s.oscMode,
                note = "无手柄时自动显示全部控件。Steam + QAM 仅显示这些按钮。", onPick = a.onOsc,
            )
            if (steam && s.steamController != null) ChoiceRow(
                host, "controller", "手柄", "手柄对 Steam 的呈现方式。下次会话生效。",
                listOf(
                    SessionPrefs.CONTROLLER_DECK to "Steam Deck 手柄",
                    SessionPrefs.CONTROLLER_XBOX360 to "Xbox 360 手柄",
                ), s.steamController,
                note = "Steam Deck 手柄：Steam 将其识别为 Deck 原装手柄，带快速访问按键与设备陀螺仪。" +
                    "Xbox 360 手柄：旧版本的普通手柄；快速访问通过 Guide+A 打开。",
                onPick = a.onSteamController,
            )
            if (steam) ChoiceRow(
                host, "back-actions", "返回", SessionPrefs.backActionsOrder(s.backActionsInverted),
                listOf(
                    false to SessionPrefs.BACK_MENU_THEN_QAM,
                    true to SessionPrefs.BACK_QAM_THEN_MENU,
                ), s.backActionsInverted, onPick = a.onBackActionsInverted,
            )
        }
        SettingsGroup("会话") {
            ChoiceRow(
                host, "suspend", "后台行为",
                "应用离开屏幕或屏幕关闭时本会话的行为。",
                listOf(
                    SessionPrefs.SUSPEND_AUTO to "自动",
                    SessionPrefs.SUSPEND_MANUAL to "手动",
                    SessionPrefs.SUSPEND_NEVER to "从不",
                ),
                s.suspendPolicy,
                note = "自动：在后台暂停，回到前台时继续。手动：在后台暂停并等待继续。从不：保持会话继续运行。",
                onPick = a.onSuspendPolicy,
            )
        }
        if (steam) SettingsGroup("启动") {
            ToggleRow(
                host, "steam-startup", "DroidDeck 启动时运行 Steam",
                "启动 DroidDeck 时打开 Steam 会话。",
                s.runSteamAtStartup, onChange = a.onRunSteamAtStartup,
            )
        }
        if (steam) SettingsGroup("Decky") {
            val updateAvailable = s.deckyInstalled != null && s.deckyLatestRelease != null &&
                s.deckyInstalled != s.deckyLatestRelease.tag
            val status = when {
                s.deckyStage != null -> s.deckyStage
                s.deckyChecking -> "正在检查兼容版本…"
                s.deckyInstalled == null && s.deckyLatestRelease != null -> "已可安装 ${s.deckyLatestRelease.tag}。"
                s.deckyInstalled == null -> "未找到兼容版本，请稍后再检查。"
                s.deckyLatestRelease == null -> "已安装 · ${s.deckyInstalled}"
                updateAvailable -> "有可用更新 · ${s.deckyLatestRelease.tag}"
                else -> "已是最新 · ${s.deckyInstalled}"
            }
            SettingsRow("加载器", status) {
                val action = when {
                    s.deckyStage != null -> "处理中…"
                    s.deckyChecking -> "检查中…"
                    s.deckyInstalled == null && s.deckyLatestRelease != null -> "安装最新版"
                    s.deckyInstalled != null && updateAvailable -> "更新"
                    else -> "检查"
                }
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    SecondaryButton(
                        action,
                        enabled = s.deckyStage == null && !s.deckyChecking && !s.deckySessionRunning,
                    ) {
                        if (s.deckyLatestRelease == null || (s.deckyInstalled != null && !updateAvailable)) a.onDeckyCheck()
                        else s.deckyLatestRelease?.let(a.onDeckyInstall)
                    }
                    if (s.deckyInstalled != null) SecondaryButton(
                        "卸载",
                        enabled = s.deckyStage == null && !s.deckySessionRunning,
                    ) { confirmDeckyRemoval = true }
                }
            }
            if (s.deckyInstalled != null) SettingsRow(
                "Decky",
                if (s.deckyEnabled) "随 Steam 启动。开启期间，本设备上的其他应用可能可以控制 Steam。"
                else "已关闭。Decky 保持禁用，Steam 的远程调试端口保持关闭。",
            ) {
                Switch(
                    checked = s.deckyEnabled,
                    onCheckedChange = a.onDeckyEnabled,
                    enabled = !s.deckySessionRunning && s.deckyStage == null,
                )
            }
            if (s.deckyStage != null && s.deckyPercent >= 0) {
                LinearProgressIndicator(
                    progress = { s.deckyPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (steam && s.steamChannel != null) SettingsGroup("客户端") {
            ToggleRow(
                host, "steamdeck", "Steam Deck 模式",
                "启用 Steam 的 Deck 界面与快速访问性能悬浮控件。下次会话生效。",
                s.steamDeckMode, onChange = a.onSteamDeckMode,
            )
            ChoiceRow(
                host, "channel", "客户端分支", "会话强制使用的 Steam 客户端版本。下次会话启动时生效；客户端可能会自行更新一次。",
                listOf("publicbeta" to "公开测试版", "steamdeck_publicbeta" to "Steam Deck 公开测试版"), s.steamChannel,
                note = "公开测试版是此前所有会话使用的分支。Steam Deck 公开测试版是 Deck 模式所需的频道（在公开测试版上它每次启动都会重装同一客户端），也是 Armada 引导所用的频道；除非你在此选择，Deck 模式会自动选用它。",
                onPick = a.onSteamChannel,
            )
        }
        if (steam && s.addedGamesDirs != null) SettingsGroup("已添加游戏") {
            for (dir in s.addedGamesDirs) {
                val n = s.addedGames.count { it.folderPath.startsWith("$dir/") }
                ActionRow(
                    dir.substringAfterLast('/').ifEmpty { dir }, dir + " · " + (if (n == 0) "未找到含 .exe 的游戏文件夹" else "$n 个游戏${if (n == 1) "" else ""}") + "。" + stringResource(R.string.added_games_forget_hint),
                    "忘记", onClick = { a.onForgetAddedGamesDir(dir) },
                )
            }
            ActionRow(
                if (s.addedGamesDirs.isEmpty()) "游戏文件夹" else "另一个游戏文件夹",
                stringResource(R.string.added_games_import_hint),
                "添加…", onClick = a.onPickAddedGamesDir,
            )
            ToggleRow(
                host, "addedArt", "Steam 封面素材",
                "没有自己封面图的游戏会按文件夹名从商店获取同名游戏的胶囊图、页头图、主视觉和 Logo。你自己的素材优先：把 cover.jpg（或 poster、boxart、文件夹名）、header.jpg、hero.jpg、logo.png 或 icon.png 放进游戏文件夹或其 art 子文件夹。",
                s.addedGamesArt, onChange = a.onAddedGamesArt,
            )
            for (g in s.addedGames) ChoiceRow(
                host, "added:" + g.folderPath, g.folderName, "启动 ${g.exeName}" + (if (s.addedGamesDirs.size > 1) " · 位于 " + g.folderPath.substringBeforeLast('/').substringAfterLast('/') else ""),
                g.candidates + ("__pick__" to "选择其他文件…"), g.exePath,
                note = "游戏文件夹中找到的 .exe 文件；除非你另行选择，默认选中与文件夹同名的文件，否则选最大的。",
                onPick = { path -> if (path == "__pick__") a.onPickAddedGameExe(g.folderPath) else a.onAddedGameExe(g.folderPath, path) },
            )
        }
        if (steam && s.fexPreset != null) SettingsGroup(stringResource(R.string.game_settings_title)) {
            ChoiceRow(
                host, "fex", stringResource(R.string.fex_preset_title), stringResource(R.string.fex_next_launch),
                FexPreset.all.map { it.id to stringResource(it.label) }, s.fexPreset,
                note = stringResource(FexPreset.byId(s.fexPreset).detail), onPick = a.onFexPreset,
            )
            GameEnvironmentRow()
            if (s.forceFullscreen != null) ToggleRow(
                host, "fill", "拉伸填满屏幕",
                "让会自行调整窗口大小的游戏（FlatOut）保持全屏。若游戏在角落里显得很小（Quake 3），请关闭。下次会话生效。",
                s.forceFullscreen, onChange = a.onForceFullscreen,
            )
        }
        if (steam && s.directAudio != null && s.mic != null) SettingsGroup("音频") {
            ToggleRow(host, "da", "游戏的 DirectAudio", "绕过 PulseAudio，降低游戏音频延迟。", s.directAudio, onChange = a.onDirectAudio)
            ChoiceRow(
                host, "clientAudio", "Steam 客户端音频", "经典为 0.1.5 使用的 AAudio 输出；DirectAudio 走中继。下次会话生效。",
                listOf("classic" to "经典", "directaudio" to "DirectAudio"), if (s.clientDirectAudio) "directaudio" else "classic",
                onPick = { id -> a.onClientDirectAudio(id == "directaudio") },
            )
            ToggleRow(host, "mic", "麦克风", "使用设备麦克风进行语音聊天。", s.mic, onChange = a.onMic)
        }
        if (steam && s.gameStorage != null) SettingsGroup("游戏存储") {
            val custom = s.gameStorage.isNotEmpty() && s.gameStorage != "off" && s.storageOptions.none { it.second == s.gameStorage }
            val options = buildList {
                add("" to ("自动 - 插卡时使用 SD 卡" + (if (s.storageOptions.isEmpty()) "（当前没有）" else "")))
                add("off" to "仅内部存储")
                for ((label, path) in s.storageOptions) add(path to label)
                if (custom) add(s.gameStorage to "文件夹：${s.gameStorage}")
            }
            val open = host.open == "storage"
            SettingsRow(
                "第二 Steam 库",
                stringResource(R.string.second_library_import_hint),
                highlighted = open,
            ) {
                androidx.compose.foundation.layout.Box {
                    ValueChip(options.firstOrNull { it.first == s.gameStorage }?.second?.substringBefore(" -") ?: "-", open) { host.open = if (open) null else "storage" }
                    AnchoredMenu(
                        open, onDismiss = { if (host.open == "storage") host.open = null }, title = "第二 Steam 库",
                        note = "从 SD 卡或共享存储流式读取素材的游戏可能卡顿，建议放在内部存储。",
                    ) { firstItemFocus ->
                        options.forEachIndexed { index, (path, label) ->
                            MenuItem(label, checked = path == s.gameStorage, focusRequester = if (index == 0) firstItemFocus else null) {
                                a.onGameStorage(path, if (path.isEmpty() || path == "off") "" else label.substringBefore(" ·"))
                                host.open = null
                            }
                        }
                        MenuItem("选择文件夹…", checked = false) { host.open = null; a.onPickGameStorageFolder() }
                    }
                }
            }
        }
        if (!steam && s.renderer != null) SettingsGroup("渲染器") {
            ChoiceRow(
                host, "renderer", "桌面渲染器", "负责合成桌面。",
                listOf("vulkan" to "vulkan - GPU", "gles2" to "gles2 - GPU（实验性）", "pixman" to "pixman - 软件渲染"), s.renderer,
                note = "使用 Vulkan 时，桌面上的程序在自己的窗口内用 GPU 绘制；若无法启动，" +
                    "桌面会改用 pixman。使用 pixman 时，从菜单打开的游戏和模拟器会改为在 GPU 上全屏运行" +
                    "（右键可打开桌面窗口）。",
                onPick = a.onRenderer,
            )
        }
    }
    if (confirmDeckyRemoval) AlertDialog(
        onDismissRequest = { confirmDeckyRemoval = false },
        title = { Text("卸载 Decky Loader？") },
        text = { Text("移除加载器，保留你的插件与设置。") },
        confirmButton = {
            TextButton(onClick = { confirmDeckyRemoval = false; a.onDeckyUninstall() }) { Text("卸载") }
        },
        dismissButton = { TextButton(onClick = { confirmDeckyRemoval = false }) { Text("取消") } },
    )
}

/** The Resolution menu's "Custom…" entry. */
private const val CUSTOM = -1

/** Width × height for the session, with the common handheld shapes one tap away. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomResolutionDialog(initial: Pair<Int, Int>?, onSave: (Pair<Int, Int>) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(initial?.first?.toString() ?: "") }
    var h by remember { mutableStateOf(initial?.second?.toString() ?: "") }
    val parsed = SessionPrefs.parseResolution("${w}x$h")
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义分辨率") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "会话的显示尺寸。它会取代分辨率上限与屏幕比例；与屏幕比例不符时会出现黑边。",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    val numbers = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    androidx.compose.material3.OutlinedTextField(
                        w, { v -> w = v.filter(Char::isDigit).take(4) }, label = { Text("宽度") },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                    Text("×", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        h, { v -> h = v.filter(Char::isDigit).take(4) }, label = { Text("高度") },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    for ((pw, ph, tag) in listOf(Triple(960, 720, "4:3"), Triple(1024, 768, "4:3"), Triple(1280, 960, "4:3"), Triple(1280, 800, "16:10"), Triple(1152, 648, "16:9"), Triple(1280, 720, "16:9"))) {
                        androidx.compose.material3.AssistChip(
                            onClick = { w = pw.toString(); h = ph.toString() },
                            label = { Text("$pw×$ph · $tag", fontSize = 12.sp) },
                        )
                    }
                }
                if (parsed == null && (w.isNotEmpty() || h.isNotEmpty())) Text(
                    "范围为 320×240 到 3840×2160。", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text("使用") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
