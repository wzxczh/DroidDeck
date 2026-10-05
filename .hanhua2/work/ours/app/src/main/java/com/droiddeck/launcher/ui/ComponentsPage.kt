package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.ComponentsManager.CatalogItem
import com.droiddeck.launcher.session.ComponentsManager.Snapshot
import java.text.DateFormat
import java.util.Date

/** One installed row: a Proton's original bundle (one per Proton build) or a stored package. */
private class InstalledItem(
    val name: String, val detail: String, val tag: String,
    val selected: Boolean, val removable: Boolean,
    /** Stored package file, or null for an original. */
    val file: String?,
    /** The Proton build an original belongs to. */
    val protonVersion: String?,
)

private val GOLD = Color(0xFFF2C66D)
private val ROW_SHAPE = RoundedCornerShape(10.dp)

/**
 * FEX, DXVK and VKD3D-Proton per Proton, as a full page opened from the rail's "Components" entry.
 * A title row with the Nightlies refresh, then the Proton and the component (LB / RB turn it), what
 * is in use, and the installed packages beside the ones on the Nightlies - stacked when the page is
 * narrow. Tapping an installed row swaps it in; the refresh button is the only thing that goes online.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ComponentsPage(
    snapshot: Snapshot?,
    catalog: List<CatalogItem>,
    catalogAt: Long,
    protonId: String?,
    comp: String,
    checking: Boolean,
    busy: String?,
    downloads: Map<String, Int>,
    onProton: (String) -> Unit,
    onComp: (String) -> Unit,
    onSwap: (String) -> Unit,
    onRestore: (String) -> Unit,
    onCancelQueued: () -> Unit,
    onDeletePackage: (String) -> Unit,
    onDeleteOriginal: (String) -> Unit,
    onDownload: (CatalogItem) -> Unit,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    requestInitialFocus: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val narrow = LocalNarrowPane.current
    BackHandler(onBack = onBack)
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var confirmTitle by remember { mutableStateOf("") }
    var about by remember { mutableStateOf(false) }
    var protonMenu by remember { mutableStateOf(false) }
    fun ask(title: String, body: String, action: () -> Unit) { confirmTitle = title; confirm = body to action }

    val views = snapshot?.protons ?: emptyList()
    val view = views.firstOrNull { it.proton.id == protonId } ?: views.firstOrNull()
    val label = ComponentsManager.LABEL[comp] ?: comp
    val checked = if (catalogAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(catalogAt * 1000)) else "从未"
    val nightlies = if (busy != null) "$busy…" else if (catalogAt > 0) "Nightlies 检查于 $checked" else "Nightlies 从未检查"

    Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
        // ---- title, and the one thing that goes online -----------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "组件", fontSize = if (narrow) 22.sp else 26.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            if (!narrow) Text(nightlies, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ToolIcon(Icons.Outlined.Info, "关于组件") { about = true }
            ToolIcon(Icons.Outlined.Refresh, "检查 Nightlies 是否有新组件包", busy = checking, enabled = !checking, onClick = onRefresh)
        }
        if (narrow) Text(nightlies, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

        // ---- which Proton, which component ------------------------------------------------------
        val comps = ComponentsManager.COMPONENTS
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            if (view != null) Box {
                ValueChip(view.proton.name, protonMenu, modifier = Modifier.widthIn(max = 300.dp).heightIn(min = 44.dp)) { protonMenu = !protonMenu }
                AnchoredMenu(protonMenu, onDismiss = { protonMenu = false }, title = "Proton",
                    note = "Steam 更新时会替换 Valve 自带的 Proton；其原始文件按版本分别保留。") { first ->
                    views.forEachIndexed { i, v ->
                        val swaps = v.components.values.count { it.activeFile != null }
                        MenuItem(
                            v.proton.name, checked = v.proton.id == view.proton.id,
                            detail = v.proton.version + (if (swaps > 0) " · 已交换 $swaps" else " · 全部原始") + (if (v.inUseByGame) " · 游戏运行中" else ""),
                            focusRequester = if (i == 0) first else null,
                        ) { onProton(v.proton.id); protonMenu = false }
                    }
                }
            }
            TabStrip(comps.map { ComponentsManager.LABEL.getValue(it) }, comps.indexOf(comp).coerceAtLeast(0), { onComp(comps[it]) })
        }

        // ---- what is in use -----------------------------------------------------------------------
        if (view != null) {
            val st = view.components.getValue(comp)
            val fixedAt = if (view.reappliedAt > 0) " · 启动游戏时重新应用 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(view.reappliedAt * 1000)) else ""
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface)
                    .border(1.dp, pal.line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp).heightIn(min = 28.dp),
            ) {
                Box(Modifier.size(8.dp).background(if (st.queued != null) Color(0xFFFFB86B) else pal.good, CircleShape))
                Text(
                    (if (st.queued != null) "等待中：${st.queued} · 游戏关闭后生效" else "$label 使用中：${st.inUse}") + fixedAt,
                    fontSize = 14.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (st.queued != null) FocusText("取消", pal.signal, onClick = onCancelQueued)
                else if (!narrow) Text(
                    if (view.inUseByGame) "等待游戏关闭" else "更改将在下次启动游戏时生效",
                    fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1,
                )
            }
        }

        // ---- the lists ------------------------------------------------------------------------------
        when {
            snapshot == null -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text("正在读取 Proton…", fontSize = 14.sp, color = colors.onSurfaceVariant)
            }
            view == null -> Text(
                "Linux 运行时中尚未安装 Proton。请先启动一次 Steam 客户端以下载其 ARM64 Proton，或在设置中添加 GE-Proton / CachyOS。",
                fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp),
            )
            else -> {
                val p = view.proton
                val st = view.components.getValue(comp)
                val build = ComponentsManager.safeName(p.version)
                val originals = view.originals.filter { it.comp == comp }
                val stored = snapshot.packages.filter { it.comp == comp }
                val activeIsOriginal = st.activeFile == null
                val rows = originals.map { o ->
                    val isCurrent = o.protonVersion == build
                    InstalledItem(
                        "原始 · ${o.protonVersion}",
                        o.label.substringAfterLast(" · ", "").ifEmpty { o.label } + if (isCurrent) " · 当前版本" else " · 早期版本",
                        "原始", activeIsOriginal && isCurrent, !isCurrent, null, o.protonVersion,
                    )
                } + stored.map { s ->
                    InstalledItem(s.version, String.format("%.1f MB", s.size / 1048576.0), "已存储", s.file == st.activeFile, true, s.file, null)
                }
                val storedNames = snapshot.packages.map { it.file }.toSet()
                val available = catalog.filter { it.comp == comp && ComponentsManager.safeName(it.file) !in storedNames }
                val first = remember { FocusRequester() }
                LaunchedEffect(p.id, comp, requestInitialFocus) {
                    if (requestInitialFocus) runCatching { first.requestFocus() }
                }

                val installed: @Composable () -> Unit = {
                    SettingsGroup("已安装 · ${rows.size}") {
                        if (rows.isEmpty()) Text(
                            "$label 尚无已保存的内容。打开本页时会把该 Proton 自带文件保存为原始版本。",
                            fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                        rows.forEachIndexed { i, item ->
                            InstalledLine(
                                item, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                onSelect = {
                                    if (item.selected) return@InstalledLine
                                    val waits = if (view.inUseByGame) " 当前有游戏运行在该 Proton 上，更改会等游戏关闭后生效。" else " 更改将在下次启动游戏时生效。"
                                    if (item.file != null) ask("交换 $label？", "要把 ${item.name} 写入 ${p.name} 吗？其自带文件仍会保存为原始文件包。$waits") { onSwap(item.file) }
                                    else ask("恢复 $label？", "要把 ${item.protonVersion} 的原始文件恢复回 ${p.name} 吗？$waits") { onRestore(item.protonVersion!!) }
                                },
                                onDelete = {
                                    if (item.file != null) ask("删除 ${item.name}？", "其包文件将从应用中删除。你可以随时重新下载。") { onDeletePackage(item.file) }
                                    else ask("删除此原始文件？", "$label 的 ${item.protonVersion} 原始文件属于 ${p.name} 的早期版本。已安装版本的原始文件会始终保留。") { onDeleteOriginal(item.protonVersion!!) }
                                },
                            )
                        }
                    }
                }
                val nightly: @Composable () -> Unit = {
                    SettingsGroup("Nightlies 上 · ${available.size}") {
                        if (available.isEmpty()) Text(
                            if (catalogAt == 0L) "刷新以列出 Nightlies 上的组件包。" else "自上次检查以来没有新内容。",
                            fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                        available.forEach { d -> AvailableLine(d, downloads[d.file], enabled = busy == null) { onDownload(d) } }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    if (narrow) { installed(); nightly() }
                    else Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) { installed() }
                        Column(Modifier.weight(1f)) { nightly() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                        SmallButton("导入 .wcp", onClick = onImport)
                        if (!activeIsOriginal && originals.any { it.protonVersion == build }) FocusText("恢复此 Proton 的原始文件", pal.signal) {
                            ask("恢复 $label？", "要把 ${p.name} 的原始 $label 恢复回去吗？") { onRestore(build) }
                        }
                    }
                }
            }
        }
    }

    if (about) AlertDialog(
        onDismissRequest = { about = false },
        title = { Text("组件") },
        text = {
            Text(
                "Steam 客户端运行各 Proton 时所用的 FEX、DXVK 与 VKD3D-Proton。\n\n" +
                    "点按已安装的行即可将其交换进去，更改会在下次启动游戏时生效。若该 Proton 上有游戏正在运行，交换会等游戏关闭后进行。\n\n" +
                    "每次启动游戏前都会复查你的选择：若有外部改动了 Proton 的文件（例如 Steam 的启动测试），会先恢复原状，页面会显示“启动游戏时重新应用”。\n\n" +
                    "每个 Proton 版本自带的文件都会保存为它的原始文件，Steam 更新也不会丢失。组件包来自 Nightlies 的 “-Linux” 发布（点按刷新）或“导入 .wcp”。",
                fontSize = 14.sp,
            )
        },
        confirmButton = { FocusText("确定", pal.signal) { about = false } },
    )
    confirm?.let { (body, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(confirmTitle) },
            text = { Text(body, fontSize = 14.sp) },
            // Opens on Cancel, so a stray A press on a controller never swaps or deletes anything.
            confirmButton = {
                val del = confirmTitle.startsWith("删除")
                FocusText(if (del) "删除" else if (confirmTitle.startsWith("恢复")) "恢复" else "交换", if (del) colors.error else pal.signal) { confirm = null; action() }
            },
            dismissButton = {
                val cancelFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
                FocusText("取消", colors.onBackground, modifier = Modifier.focusRequester(cancelFocus)) { confirm = null }
            },
        )
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, description: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (hot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
            .border(if (hot) 2.dp else 1.dp, if (hot) pal.signal else pal.line2, RoundedCornerShape(12.dp))
            .hoverable(src).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick),
    ) {
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Icon(icon, contentDescription = description, tint = colors.onBackground, modifier = Modifier.size(20.dp))
    }
}

/**
 * A small button. Resting: grey outline (blue text when [accent]). Focused or hovered: a solid blue
 * fill with an outline in the text colour - unmistakable in a column of identical buttons.
 */
@Composable
private fun SmallButton(text: String, enabled: Boolean = true, accent: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = (src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value) && enabled
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (hot) pal.signal else colors.surfaceVariant)
            .border(2.dp, if (hot) colors.onBackground else pal.line2, RoundedCornerShape(10.dp))
            .hoverable(src).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (!enabled) colors.onSurfaceVariant else if (hot) pal.onSignal else if (accent) pal.signal else colors.onBackground,
        )
    }
}

@Composable
private fun Tag(text: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val (fg, bg) = when (text) {
        "原始" -> GOLD to Color(0x22F2C66D)
        "使用中" -> pal.onSignal to pal.signal
        "新" -> pal.good to Color(0x224CD37F)
        else -> colors.onSurfaceVariant to Color.White.copy(alpha = 0.07f)
    }
    Text(
        text.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun InstalledLine(item: InstalledItem, modifier: Modifier = Modifier, onSelect: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else if (item.selected) pal.signal.copy(alpha = 0.07f) else Color.Transparent),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).then(modifier)
                .border(2.dp, if (hot) pal.signal else Color.Transparent, ROW_SHAPE)
                .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onSelect)
                .controllerConfirm(onClick = onSelect)
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp).border(2.dp, if (item.selected) pal.signal else colors.onSurfaceVariant, CircleShape)) {
                if (item.selected) Box(Modifier.size(9.dp).background(pal.signal, CircleShape))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (item.selected) pal.signal else colors.onBackground, modifier = Modifier.weight(1f, fill = false),
                    )
                    Tag(item.tag)
                }
                Text(item.detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            if (item.selected) Tag("使用中")
        }
        // The trash column is always there (empty when the row can't be deleted) so rows line up.
        if (!item.removable) Spacer(Modifier.padding(end = 6.dp).size(40.dp))
        else {
            val delSrc = remember { MutableInteractionSource() }
            val delHot = delSrc.collectIsFocusedAsState().value || delSrc.collectIsHoveredAsState().value
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(end = 6.dp).size(40.dp).clip(ROW_SHAPE)
                    .background(if (delHot) colors.error.copy(alpha = 0.14f) else Color.Transparent)
                    .border(2.dp, if (delHot) colors.error else Color.Transparent, ROW_SHAPE)
                    .hoverable(delSrc).clickable(interactionSource = delSrc, indication = null, onClick = onDelete)
                    .controllerConfirm(onClick = onDelete),
            ) { Icon(Icons.Outlined.Delete, contentDescription = "删除 ${item.name}", tint = if (delHot) colors.error else colors.onSurfaceVariant, modifier = Modifier.size(19.dp)) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun AvailableLine(d: CatalogItem, progress: Int?, enabled: Boolean, onDownload: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.file.removeSuffix(".wcp"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${d.release} · " + String.format("%.1f MB", d.size / 1048576.0), fontSize = 13.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (progress == null) SmallButton("下载", enabled = enabled, accent = true, onClick = onDownload)
        else Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
            Text(if (progress < 0) "…" else "$progress%", fontSize = 13.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (progress < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}
