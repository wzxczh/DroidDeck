# DroidDeck 汉化 — 源码翻译规则（子任务共用）

## 总原则
把用户可见的英文字符串字面量**就地替换**为简体中文。只改字符串内容，不改代码结构、不改标识符、不改行数（除非必须）。

## 必须翻译
- Compose `Text(...)`、`title =`、`label =`、`placeholder =`、`hint`、`note`、对话框正文、按钮文字、菜单项、toast、通知标题/正文、`contentDescription`（无障碍朗读）、SectionTitle/Chip/PrimaryButton/SecondaryButton/SettingsGroup/SettingsRow/ChoiceRow/ToggleRow/ActionRow/QuickAction/ToolSpec/SettingCard/PageHeader 等组件的文案参数。
- `values/strings.xml` 默认资源保持英文不动（`values-zh-rCN` 已是中文）。

## 保留原文（禁止翻译）
1. **协议/键/ID**：`host.open` 的键（`"fg"`、`"theme"`、`"storage"`…）、`focus.track(page, …)`、`paneItem("…")` 的 id、`paneItem("btn:$text")` 这类用显示文本拼的 id（翻译显示文本即可，id 会自动跟随，**不要单独改 id 字符串**）、SharedPreferences 键、`MODE_STEAM`、`== STEP`/`== FAIL` 标记前缀、JSON/`op` 值（`"progress"`、`"error"`、`"done"`）、`url=`/`session=`、`original ` 前缀、Intent action、notification CHANNEL_ID、log TAG。
2. **正则/解析器**：`Regex("…")`、`startsWith("…")`/`removePrefix("…")`/`contains("…")` 中用于**匹配外部或自身输出**的模式——如 `LoadingState` 的 `Downloading update (…KB)`、`downloading Steam: …`、`SessionLoading.stageOf/readableStep` 的关键词、`SessionActivity.progressFor` 的 `Downloading/Verifying`、`LogRedactor` 全部、`SessionService` 的 `url=/session=`。**如需翻译这类输入源（如脚本 step 文案），必须同步修改对应解析器，两侧一起改。**
3. **系统 ABI 值**：`BatteryComponent` 写入 sysfs 的 `Charging/Discharging/Full/Not charging/Unknown/Critical/Low/Normal/Battery/System/Li-ion` —— Steam 客户端按 Linux power_supply 约定读取，**保持英文**。
4. **技术标识**：驱动/组件/Proton 版本名（`GE-Proton`、`proton-cachyos`、`STABILITY` 等 FEX 预设 id）、文件名、路径、URL、命令行、设备名（`BAT0`）、`appmanifest_`、`steamapps`。
5. **外部程序输出**：Steam/Flatpak/pacman 自己打印的行只翻译我们显示时包的外壳，不改外部原词（除非同步改解析器）。
6. 日志 TAG、`Log.i(TAG, …)` 的 TAG 参数。

## 格式规则
- 保留所有 `$var`、`${...}`、`%s`、`%1$s`、`%d`、`%.1f`、`\n`、`\"` 转义、`·` 分隔符；只翻译其中的英文单词。
- 英文引号改为中文引号时**不得**破坏 Kotlin 转义（字符串内用 `“ ”` 安全，`\"` 可去掉或保留其结构）。
- 复数：`"$n game${if (n == 1) "" else "s"}"` → 改为 `"$n 个游戏"` 并删除复数分支是**改代码**，允许的最小改动是保留结构只译词；如复数分支仅是 `s`，可把两分支都译为同一中文（如 `"" `→`""`、`"s"`→`""`），保持表达式合法。
- 术语（与 values-zh-rCN 一致）：会话、运行时、帧生成、屏幕控件、配置文件、快捷方式、Steam 库、着色器缓存、兼容性工具、掌机、驱动、桌面、组件、商店、加载中…、取消、保存、删除、重命名。
- 保留专有名词：Steam、Proton、Flatpak、FEX、DXVK、VKD3D、Mesa、Turnip、gamescope、Wayland、Adreno、Decky、Big Picture、Quick Access/QAM、AppImage、LXQt、RPCS3 等。
- 文案长度：UI 多为窄布局，中文宜简洁。

## 跨文件耦合清单（由主协调者 Phase 6 统一处理，子任务不得自行翻译）
1. **加载管线**：`LoadingState.kt`（`== STEP` 解析、`UPDATE_PROGRESS`/`INSTALL_COUNT` 正则、显示串）、`SessionLoading.kt`（`stageOf`/`readableStep` 关键词、LoadStage 列表）、`SessionActivity.kt` 的 `progressFor`/`installRuntime`/`installDesktop`/`loading.step = "Starting the session…"`、`LinuxRuntimeInstaller.java` 的 stage token（`Downloading`/`Verifying`）、`tools/linuxfs/.../bannerlator-session` 与 `bannerlator-steam-install` 的 `step` 行 —— 这些必须两侧同步改，**子任务跳过这些位置**，只翻译其中纯显示且不被解析的文案（LoadStage 列表、错误收尾句可翻译，关键词匹配函数不动，最后由协调者统一）。
2. **单文件内耦合**（子任务可改，但必须两侧同步）：
   - `ComponentsPage.kt`：`confirmTitle.startsWith("Delete"/"Restore")` 与 `ask(...)` 的标题。
   - `FrontEndGames.kt`：`lastPlayedText()` 返回 `"Last played …"` 与 `removePrefix("Last played ")`。
   - `FlatpakManager.kt`：`== STEP`/`== FAIL` 前缀（前缀不译，其后文本可译）；`refLabel()` 输出被 `FlathubApiTest` 断言（如翻译需同步改测试期望值）。
   - `AppImageManager.kt`：`problem()` 消息被 `AppImageManagerTest` 断言包含 `x86_64`/`type 2`/`not an AppImage` —— 翻译时保留这些子串或同步改测试。
3. **整体排除**：`LogRedactor.kt`（全部是匹配外部英文日志的正则，且被测试断言）；`values/strings.xml`（默认英文资源）。
4. `SessionService.kt` 解析 `url=`/`session=` 的行不译；`ComponentsManager.kt` 的 `original ` 前缀不译。

## 完成后自检
- grep 自己负责的文件确认没有遗漏明显英文 UI（`Text("`、`title = {`、toast）。
- 确认没有改动上述"禁止翻译"清单。
- 确认每个改动行的引号/大括号数量与原来一致（本机无法编译）。
