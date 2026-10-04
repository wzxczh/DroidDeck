package com.droiddeck.launcher.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.WirelessAdbFix
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun WirelessAdbFixPage(
    onBack: () -> Unit,
    desiredEnabled: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onPair: (String, Int, String, (String?) -> Unit) -> Unit,
    onFindConnectPort: (String, (Int?) -> Unit) -> Unit,
    onApply: (String, Int, Boolean, (String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val savedAddress = remember { WirelessAdbFix.savedConnectionAddress(context) }
    var step by rememberSaveable { mutableStateOf(if (savedAddress == null) 0 else 1) }
    var pairingAddress by rememberSaveable { mutableStateOf("") }
    var connectionAddress by rememberSaveable { mutableStateOf(savedAddress.orEmpty()) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val pairingEndpoint = parseAdbAddress(pairingAddress)

    val pairNow: () -> Unit = {
        parseAdbAddress(pairingAddress)?.let { endpoint ->
            keyboardController?.hide()
            focusManager.clearFocus()
            busy = true
            editing = false
            message = null
            messageIsError = false
            onPair(endpoint.host, endpoint.port, pairingCode) { error ->
                pairingCode = ""
                if (error != null) {
                    busy = false
                    message = error
                    messageIsError = true
                } else {
                    step = 1
                    busy = true
                    message = "配对成功，正在查找无线调试端口…"
                    onFindConnectPort(endpoint.host) { discoveredPort ->
                        if (discoveredPort == null) {
                            connectionAddress = formatAddressHost(endpoint.host) + ":"
                            busy = false
                            message = "配对成功，请输入“设置”中的端口。"
                        } else {
                            connectionAddress = formatAdbAddress(endpoint.host, discoveredPort)
                            message = "已找到设备，正在应用设置…"
                            onApply(endpoint.host, discoveredPort, desiredEnabled) { applyError ->
                                busy = false
                                if (applyError == null) {
                                    step = 2
                                    message = null
                                } else {
                                    message = "自动应用失败，请检查下方地址后重试。$applyError"
                                    messageIsError = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val applyAddress: () -> Unit = {
        parseAdbAddress(connectionAddress)?.let { endpoint ->
            keyboardController?.hide()
            focusManager.clearFocus()
            busy = true
            editing = false
            message = null
            messageIsError = false
            onApply(endpoint.host, endpoint.port, desiredEnabled) { error ->
                busy = false
                if (error == null) {
                    step = 2
                    message = null
                } else {
                    message = error
                    messageIsError = true
                }
            }
        }
    }
    val pairAgain: () -> Unit = {
        step = 0
        pairingAddress = ""
        pairingCode = ""
        message = null
        messageIsError = false
    }

    BackHandler { if (!busy) onBack() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactSplit = maxWidth < 620.dp && maxHeight < 500.dp
        val form: @Composable () -> Unit = {
            WirelessStepForm(
                step = step,
                pairingAddress = pairingAddress,
                onPairingAddressChange = { pairingAddress = it },
                pairingCode = pairingCode,
                onPairingCodeChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                connectionAddress = connectionAddress,
                onConnectionAddressChange = { connectionAddress = it },
                busy = busy,
                message = message,
                messageIsError = messageIsError,
                compact = compactSplit,
                editing = editing,
                onEditingChanged = { editing = it },
                focusManager = focusManager,
                desiredEnabled = desiredEnabled,
                canPair = pairingEndpoint != null && pairingCode.length == 6,
                onPair = pairNow,
                onApply = applyAddress,
                onPairAgain = pairAgain,
            )
        }
        SettingsPage(
            host = rememberMenuHost(),
            title = when {
                compactSplit && step < 2 -> "无线调试"
                step == 2 -> "子进程限制已更新"
                else -> "修改子进程限制"
            },
            eyebrow = "设置",
            lede = when {
                compactSplit && step == 0 -> "如果没有该选项，请改用无线调试配对。"
                compactSplit && step == 1 -> "配对成功，请在无线调试中查看 IP 地址和端口。"
                compactSplit -> "子进程限制已更新。"
                step == 0 -> "如果开发者选项中没有子进程设置，请使用第 2 步。依次打开 Android“设置 → 开发者选项 → 无线调试 → 使用配对码配对设备”。"
                step == 1 -> "配对成功，请使用下方地址；如有变化，请在“无线调试 → IP 地址和端口”中更新。"
                else -> "已通过无线调试更新子进程限制。"
            },
            onBack = { if (!busy) onBack() },
            action = if (compactSplit && step < 2) {
                { SecondaryButton("开发者选项", compact = true, enabled = !busy, onClick = onOpenDeveloperOptions) }
            } else null,
            scrollContent = !compactSplit,
            compactLayout = compactSplit,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 620.dp
                if (step == 2) {
                    Column(
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SettingsGroup("子进程限制", compact = compactSplit) {
                            Text(
                                "Android 已确认限制当前为${if (desiredEnabled) "开启" else "关闭"}状态。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                        PrimaryButton("完成", enabled = !busy, onClick = onBack)
                    }
                } else if (compactSplit) {
                    Column(
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        form()
                        if (!editing) CompactComputerFallback(desiredEnabled)
                    }
                } else {
                    val routes: @Composable () -> Unit = {
                        FallbackOrder(desiredEnabled, busy, onOpenDeveloperOptions)
                    }
                    if (wide) {
                        Row(
                            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) { routes() }
                            Column(Modifier.weight(1f)) { form() }
                        }
                    } else {
                        Column(
                            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            routes()
                            form()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WirelessStepForm(
    step: Int,
    pairingAddress: String,
    onPairingAddressChange: (String) -> Unit,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    connectionAddress: String,
    onConnectionAddressChange: (String) -> Unit,
    busy: Boolean,
    message: String?,
    messageIsError: Boolean,
    compact: Boolean,
    editing: Boolean,
    onEditingChanged: (Boolean) -> Unit,
    focusManager: FocusManager,
    desiredEnabled: Boolean,
    canPair: Boolean,
    onPair: () -> Unit,
    onApply: () -> Unit,
    onPairAgain: () -> Unit,
) {
    if (step == 0) {
        SettingsGroup("2 · 配对无线调试", compact = compact) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
            ) {
                AdbTextField(
                    value = pairingAddress,
                    onValueChange = onPairingAddressChange,
                    label = "配对弹窗的 IP 地址和端口",
                    placeholder = "192.168.1.42:37123",
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                    compact = compact,
                    onFocusChange = onEditingChanged,
                    onNext = { focusManager.moveFocus(FocusDirection.Next) },
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AdbTextField(
                        value = pairingCode,
                        onValueChange = onPairingCodeChange,
                        label = "配对码（PIN）",
                        placeholder = "6 位数字",
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                        compact = compact,
                        onFocusChange = onEditingChanged,
                        onDone = {
                            focusManager.clearFocus()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton("配对", compact = compact, enabled = !busy && canPair, onClick = onPair)
                }
                StatusMessage(busy, message, isError = messageIsError)
            }
        }
    } else {
        SettingsGroup("2 · 通过无线调试应用", compact = compact) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
            ) {
                AdbTextField(
                    value = connectionAddress,
                    onValueChange = onConnectionAddressChange,
                    label = "连接的 IP 地址和端口",
                    placeholder = "192.168.1.42:45678",
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    compact = compact,
                    onFocusChange = onEditingChanged,
                    onDone = { focusManager.clearFocus() },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatusMessage(busy, message, Modifier.weight(1f), isError = messageIsError)
                    SecondaryButton("重新配对", compact = compact, enabled = !busy, onClick = onPairAgain)
                    PrimaryButton(
                        if (desiredEnabled) "开启限制" else "关闭限制",
                        compact = compact,
                        enabled = !busy && parseAdbAddress(connectionAddress) != null,
                        onClick = onApply,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AdbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    compact: Boolean = false,
    onFocusChange: (Boolean) -> Unit = {},
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val onValueChangeLatest = rememberUpdatedState(onValueChange)
    val onNextLatest = rememberUpdatedState(onNext)
    val onDoneLatest = rememberUpdatedState(onDone)
    val onFocusChangeLatest = rememberUpdatedState(onFocusChange)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val inputHeight = if (compact) 44.dp else 52.dp
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(inputHeight)
                .background(colors.surfaceVariant, shape)
                .border(1.dp, if (focused) palette.signal else palette.line2, shape)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    EditText(viewContext).apply {
                        isSingleLine = true
                        setHorizontallyScrolling(true)
                        background = null
                        setPadding((2 * context.resources.displayMetrics.density).roundToInt(), 0, 0, 0)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 14f)
                        setTextColor(colors.onBackground.toArgb())
                        setHintTextColor(colors.onSurfaceVariant.toArgb())
                        contentDescription = label
                        inputType = if (keyboardType == KeyboardType.Number) {
                            InputType.TYPE_CLASS_NUMBER
                        } else {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                        imeOptions = imeActionFlag(imeAction) or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        hint = placeholder
                        setOnEditorActionListener { _, actionId, event ->
                            val isEnter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            when {
                                imeAction == ImeAction.Next && (actionId == EditorInfo.IME_ACTION_NEXT || isEnter) -> {
                                    onNextLatest.value?.invoke()
                                    true
                                }
                                imeAction == ImeAction.Done && (actionId == EditorInfo.IME_ACTION_DONE || isEnter) -> {
                                    onDoneLatest.value?.invoke()
                                    true
                                }
                                else -> false
                            }
                        }
                        setOnFocusChangeListener { _, hasFocus ->
                            focused = hasFocus
                            onFocusChangeLatest.value(hasFocus)
                        }
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                onValueChangeLatest.value(s?.toString().orEmpty())
                            }
                        })
                    }
                },
                update = { editText ->
                    editText.setTextColor(colors.onBackground.toArgb())
                    editText.setHintTextColor(colors.onSurfaceVariant.toArgb())
                    editText.hint = placeholder
                    editText.contentDescription = label
                    if (editText.text.toString() != value) {
                        val cursor = if (editText.hasFocus()) editText.selectionStart.coerceAtLeast(0) else value.length
                        editText.setText(value)
                        editText.setSelection(min(cursor, value.length))
                    }
                },
            )
        }
    }
}

@Composable
private fun FallbackOrder(
    desiredEnabled: Boolean,
    busy: Boolean,
    onOpenDeveloperOptions: () -> Unit,
) {
    SettingsGroup("其他修改方式") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("1 · 开发者选项（优先尝试）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text("将“限制子进程”设为${if (desiredEnabled) "开启" else "关闭"}。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton("打开开发者选项", enabled = !busy, onClick = onOpenDeveloperOptions)
            Text("3 · 电脑 ADB（最后手段）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
            Text("仅在无法使用无线调试时使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                PhantomProcessLimit.adbCommand(desiredEnabled),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompactComputerFallback(desiredEnabled: Boolean) {
    SettingsGroup("3 · 电脑 ADB（最后手段）", compact = true) {
        Text(
            PhantomProcessLimit.adbCommand(desiredEnabled),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatusMessage(busy: Boolean, message: String?, modifier: Modifier = Modifier, isError: Boolean = false) {
    if (busy) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(message ?: "处理中…", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        message?.let {
            Text(
                it,
                modifier = modifier,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class AdbEndpoint(val host: String, val port: Int)

private fun parseAdbAddress(value: String): AdbEndpoint? {
    val address = value.trim()
    val host: String
    val portText: String
    if (address.startsWith("[")) {
        val closingBracket = address.indexOf(']')
        if (closingBracket <= 1 || address.getOrNull(closingBracket + 1) != ':') return null
        host = address.substring(1, closingBracket)
        if (':' !in host) return null
        portText = address.substring(closingBracket + 2)
    } else {
        val colon = address.lastIndexOf(':')
        if (colon <= 0 || address.indexOf(':') != colon) return null
        host = address.substring(0, colon)
        portText = address.substring(colon + 1)
    }
    if (host.isBlank() || host.any(Char::isWhitespace)) return null
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return AdbEndpoint(host, port)
}

private fun formatAddressHost(host: String): String = if (':' in host) "[$host]" else host
private fun formatAdbAddress(host: String, port: Int): String = "${formatAddressHost(host)}:$port"

private fun imeActionFlag(action: ImeAction): Int = when (action) {
    ImeAction.Next -> EditorInfo.IME_ACTION_NEXT
    ImeAction.Done -> EditorInfo.IME_ACTION_DONE
    ImeAction.Go -> EditorInfo.IME_ACTION_GO
    ImeAction.Search -> EditorInfo.IME_ACTION_SEARCH
    else -> EditorInfo.IME_ACTION_UNSPECIFIED
}
