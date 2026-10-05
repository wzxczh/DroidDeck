package com.droiddeck.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.frontend.Library
import java.io.File

// The front end's right-hand pane and the page it shows: backdrop, desktop card and emulators.

@Composable
internal fun Pane(
    s: FrontEndState, selected: String, a: FrontEndActions, page: (@Composable () -> Unit)?, modifier: Modifier,
    onSelect: (String) -> Unit, onAndroidAppClick: (HomeApp.LaunchableApp) -> Unit,
    onOpenDeveloperOptions: () -> Unit, onRequestWirelessAdb: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
      CompositionLocalProvider(LocalNarrowPane provides (maxWidth < NarrowPaneWidth)) {
        val backdropArt: File? = when {
            s.pageKey != null && page != null -> null
            selected.startsWith("app:") -> s.steamGames.firstOrNull { "app:${it.appId}" == selected }?.art
            selected == "games" -> s.steamGames.maxByOrNull { it.lastPlayed }?.art
            selected.startsWith("rom:") -> romFor(s, selected)?.second?.art
            else -> null
        }
        Backdrop(backdropArt)
        AnimatedContent(
            // Picking another game changes the detail beside the list, not the whole page.
            targetState = if (page != null && s.pageKey != null) s.pageKey else if (selected.startsWith("app:")) "games" else selected,
            transitionSpec = {
                (fadeIn(Motion.tw(300, 80)) + slideInVertically(Motion.tw(420, 80)) { it / 24 })
                    .togetherWith(fadeOut(Motion.tw(170)) + slideOutVertically(Motion.tw(170)) { -it / 40 })
                    .apply { targetContentZIndex = 1f }
            },
            label = "pane",
        ) { key ->
            if (page != null && key == s.pageKey) page()
            else Content(s, if (key == "games") selected else key, a, Modifier.fillMaxSize(), onSelect, onAndroidAppClick, onOpenDeveloperOptions, onRequestWirelessAdb)
        }
      }
    }
}

@Composable
private fun Backdrop(art: File?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Crossfade(targetState = art, animationSpec = Motion.tw(700), label = "backdrop") { a ->
        // A game's own art, blurred, where it has some; otherwise the plain ground with one quiet
        // glow of the theme's signal colour - a colour per title hash read as noise.
        if (a != null && Build.VERSION.SDK_INT >= 31) {
            Box(modifier = Modifier.fillMaxSize().alpha(0.26f).blur(70.dp)) {
                AsyncImage(model = a, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Brush.radialGradient(listOf(pal.signal.copy(alpha = 0.07f), Color.Transparent), center = Offset.Zero, radius = 1400f)))
        }
    }
    Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.35f)))))
}

private fun romFor(s: FrontEndState, selected: String): Pair<Library.Emulator, Library.Rom>? {
    val parts = selected.split(":")
    val e = s.emulators.firstOrNull { it.id == parts.getOrNull(1) } ?: return null
    val g = e.games.getOrNull(parts.getOrNull(2)?.toIntOrNull() ?: -1) ?: return null
    return e to g
}

@Composable
private fun Content(
    s: FrontEndState, selected: String, a: FrontEndActions, modifier: Modifier,
    onSelect: (String) -> Unit, onAndroidAppClick: (HomeApp.LaunchableApp) -> Unit,
    onOpenDeveloperOptions: () -> Unit, onRequestWirelessAdb: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val detailPosterWidth = if (LocalConfiguration.current.screenHeightDp < 600) 72.dp else 120.dp
    val narrow = LocalNarrowPane.current
    val padH = if (narrow) 16.dp else 22.dp
    val padV = if (narrow) 12.dp else 18.dp
    // Setup scrolls inside itself, under its tabs.
    if (selected == "setup") {
        Column(modifier = modifier.padding(horizontal = padH, vertical = padV)) {
            SetupPanel(s, a, onOpenDeveloperOptions, onRequestWirelessAdb)
        }
        return
    }
    // Steam is a full-bleed wall of the library with Play over it.
    if (selected == "steam") {
        SteamHome(s, a, modifier)
        return
    }
    // The Store scrolls and navigates inside itself (its tabs and an app's page).
    if (selected == "store" && s.storeEnabled) {
        StorePage(s, a, modifier)
        return
    }
    // The Games tab lays out its own list and detail.
    if (selected == "games" || selected.startsWith("app:")) {
        GamesPage(s, a, selected, onSelect, modifier)
        return
    }
    // Every other page scrolls as one - header, hero and grid - so a short screen reaches the grid
    // instead of showing a sliver of it under a fixed hero. A focused tile scrolls itself into view.
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = padH, vertical = padV)) {
        when {
            selected == "android-apps" && s.isHomeApp -> {
                Rise(0) { PageHeader("Android 应用") { Chip("${s.androidApps.size} 个应用", ok = false) } }
                if (s.androidApps.isEmpty()) Rise(3) { Note("未找到可启动的 Android 应用。") }
                else Rise(3, Modifier.fillMaxWidth()) {
                    ArtGrid(s.androidApps.map { app ->
                        Tile(
                            app.label,
                            null,
                            null,
                            "android:${app.packageName}",
                            onClick = { onAndroidAppClick(app) },
                            iconBitmap = app.icon,
                        )
                    })
                }
            }
            selected == "desktop" -> {
                val installed = s.emulators.filter { it.installed }
                val available = s.emulators.filter { !it.installed }
                Rise(0) {
                    PageHeader("桌面") {
                        if (s.desktopInstalled) Chip("● 桌面已安装", ok = true) else Chip("首次打开时安装", ok = false)
                    }
                }
                Rise(2) { DesktopCard(s, a) }
                if (installed.isNotEmpty()) {
                    Rise(3) { SectionTitle("模拟器", "${installed.size} 个已安装") }
                    Rise(4) { EmulatorGrid(installed, first = true, onSelect = onSelect) }
                }
                if (s.storeEnabled) Rise(5) { InstalledAppsGrid(a) }
                if (s.appImagesEnabled) Rise(5) { AppImagesSection(a, s.ready) }
                if (available.isNotEmpty()) {
                    Rise(5) { SectionTitle("可安装", available.size.toString()) }
                    Rise(6) { EmulatorGrid(available, first = installed.isEmpty(), onSelect = onSelect) }
                }
            }
            selected.startsWith("emu:") -> {
                val e = s.emulators.firstOrNull { "emu:${it.id}" == selected }
                if (e == null) Note("未安装。") else {
                    val pkgId = Library.packageId(e.id)
                    val pkg = pkgId?.let { id -> s.packages?.firstOrNull { it.id == id } }
                    Rise(0) {
                        BackLink("桌面") { onSelect("desktop") }
                    }
                    Rise(1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                        ) {
                            Image(painterResource(e.iconRes), null, modifier = Modifier.size(52.dp))
                            Column {
                                Text(e.name, fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                                Text(
                                    e.system.replaceFirstChar { it.uppercase() } + if (e.installed) "" else " · 未安装",
                                    fontSize = 14.sp, color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (e.installed) {
                        Rise(3) {
                            Actions {
                                PrimaryButton("打开 ${e.name}", enabled = !s.busy, main = true) { a.onEmulator(e) }
                                BusyChip(s)
                                SecondaryButton("ROM 文件夹", onClick = a.onRoms)
                                if (pkg != null) SecondaryButton(
                                    if (pkg.kind == "appimage") "移除" else "隐藏",
                                    enabled = s.packageBusyId == null && !s.sessionRunning,
                                ) { a.onRemovePackage(pkg.id) }
                            }
                        }
                        Rise(4) { SectionTitle("游戏", e.games.size.toString()) }
                        if (e.games.isEmpty()) Rise(5) {
                            Note(
                                if (s.romsDir == null) "请选择 ROM 文件夹。"
                                else if (e.id == "retroarch") "在 RetroArch 中浏览到 /root/ROMs。"
                                else "将 ${e.system} 游戏添加到 ROMs/${e.system.substringBefore(' ')}.",
                            )
                        }
                        else Rise(5, Modifier.fillMaxWidth()) {
                            ArtGrid(e.games.mapIndexed { index, g -> Tile(g.name, if (g.art != null) "已安装" else g.hostPath.extension.uppercase().ifEmpty { "文件夹" }, g.art, "rom:${e.id}:$index", e.iconRes) { onSelect("rom:${e.id}:$index") } }, wide = e.games.none { it.art != null })
                        }
                    } else {
                        if (pkg != null) Rise(2) {
                            Actions {
                                PrimaryButton(
                                    if (s.packageBusyId == pkg.id) "安装中…" else "安装 ${e.name}",
                                    enabled = s.packageBusyId == null && s.ready && !s.packageCatalogLoading && !s.sessionRunning,
                                ) { a.onInstallPackage(pkg.id) }
                                if (s.sessionRunning) ActionChip("停止会话后安装", ok = false)
                                else if (!s.ready) ActionChip("需要运行时", ok = false)
                            }
                        }
                        Rise(3) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                                Note(when {
                                    s.packageCatalogLoading -> "正在加载安装详情…"
                                    pkg == null -> "暂时无法获取安装详情。软件包目录可访问时请重试。"
                                    !s.ready -> "请先在设置中安装 Linux 运行时，再安装桌面应用。"
                                    s.sessionRunning -> "请先停止当前会话，再安装桌面应用。"
                                    pkg.notes.isNotBlank() -> pkg.notes
                                    else -> "将 ${e.name} 安装到 Linux 桌面运行时。"
                                })
                            }
                        }
                        if (s.packageBusyId == pkg?.id) Rise(4) {
                            val stage = s.packageStage
                            Text(
                                if (stage != null && s.packagePercent >= 0) "$stage · ${s.packagePercent}%" else stage ?: "启动中…",
                                fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (s.packagePercent >= 0) LinearProgressIndicator(progress = { s.packagePercent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                        if (pkg?.kind == "tar") Rise(5) { Note("隐藏只会将其从桌面移除；文件仍保留在 Linux 运行时中。") }
                    }
                }
            }
            selected.startsWith("rom:") -> {
                val pair = romFor(s, selected)
                if (pair == null) Note("该游戏已从 ROM 文件夹中移除。") else {
                    val (e, g) = pair
                    Rise(0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BackLink(e.name) { onSelect("emu:${e.id}") }
                            Eyebrow("桌面 · ${e.system}")
                        }
                    }
                    Rise(1) { Title(g.name) }
                    Rise(2) {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(g.guestPath, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.surface).padding(horizontal = 10.dp, vertical = 6.dp))
                                Spacer(Modifier.height(14.dp))
                                Actions {
                                    Image(painterResource(e.iconRes), null, modifier = Modifier.size(40.dp))
                                    PrimaryButton("在 ${e.name} 中启动", enabled = !s.busy, main = true) { a.onRom(g) }
                                    if (s.busy) BusyChip(s) else ActionChip(g.hostPath.extension.uppercase().ifEmpty { "文件夹" }, ok = false)
                                }
                            }
                            if (g.art != null && !narrow) Poster(g.art, g.name, Modifier.width(detailPosterWidth))
                        }
                    }
                    val others = e.games.filter { it !== g }
                    if (others.isNotEmpty()) {
                        Rise(3) { SectionTitle("同样位于 ${e.name}", null) }
                        Rise(4, Modifier.fillMaxWidth()) {
                            ArtGrid(others.map { x ->
                                val index = e.games.indexOf(x)
                                Tile(x.name, if (x.art != null) "已安装" else x.hostPath.extension.uppercase().ifEmpty { "文件夹" }, x.art, "rom:${e.id}:$index", e.iconRes) { onSelect("rom:${e.id}:$index") }
                            }, wide = others.none { it.art != null })
                        }
                    }
                }
            }
            else -> Note("该页面已不存在。请在左侧选择一个分区。")
        }
    }
}

/** The Linux desktop itself, above the emulators that run in it. */
@Composable
private fun DesktopCard(s: FrontEndState, a: FrontEndActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    val actions: @Composable () -> Unit = {
        Actions {
            // Enabled without a runtime or the desktop: the session's loading screen installs them first.
            PrimaryButton(if (s.desktopInstalled) "打开桌面" else "安装并打开桌面", enabled = !s.busy, main = true, onClick = a.onDesktop)
            Cog(onClick = a.onDesktopSettings)
            BusyChip(s)
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().clip(Shape16).background(colors.surface).border(1.dp, pal.line, Shape16).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp).clip(Shape14).background(colors.surfaceVariant).border(1.dp, pal.line2, Shape14),
            ) { Icon(Icons.Outlined.DesktopWindows, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(26.dp)) }
            Column(modifier = Modifier.weight(1f)) {
                Text("Linux 桌面", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Text("LXQt、Firefox 与你的模拟器", fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!narrow) actions()
        }
        if (narrow) actions()
    }
}

/** Emulators as wide tiles - three across, or a list on a narrow page. */
@Composable
private fun EmulatorGrid(emulators: List<Library.Emulator>, first: Boolean, onSelect: (String) -> Unit) {
    val columns = if (LocalNarrowPane.current) 1 else 3
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        emulators.chunked(columns).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { i, e ->
                    key(e.id) {
                        EmulatorTile(e, Modifier.weight(1f).fillMaxHeight(), isFirst = first && r == 0 && i == 0) { onSelect("emu:${e.id}") }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One emulator: its icon, system and games. One not yet installed says so in words and keeps full
 * contrast - dimming it read as disabled rather than one press from installing.
 */
@Composable
private fun EmulatorTile(e: Library.Emulator, modifier: Modifier, isFirst: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "emuScale")
    val system = e.system.replaceFirstChar { it.uppercase() }
    val detail = if (e.installed && e.id != "retroarch") "$system · ${e.games.size} 个游戏" else system
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.paneItem("tile:emu:${e.id}").then(if (isFirst) Modifier.firstTile() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else if (e.installed) colors.surface else Color.Transparent)
            .border(if (hot) 2.dp else 1.dp, if (hot) pal.signal else pal.line, Shape14)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Image(painterResource(e.iconRes), contentDescription = null, modifier = Modifier.size(if (e.installed) 44.dp else 36.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(e.name, fontSize = if (e.installed) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = if (e.installed) 13.sp else 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!e.installed) Text(
            "安装", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.signal,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, pal.line2, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
