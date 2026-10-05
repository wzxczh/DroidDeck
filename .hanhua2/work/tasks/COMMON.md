# DroidDeck 汉化子任务执行手册（第二轮·版本更新合并后）

你是 DroidDeck 汉化项目的子任务执行者。项目根目录：`D:\DroidDeck`。

## 第一步
读规则文件 `D:\DroidDeck\findings.md`，严格遵守，尤其「保留原文（禁止翻译）」清单。

## 背景
项目刚把上游新版本合并进已汉化的本地版，全部合并冲突已由协调者按"取上游新代码"解决。因此你负责的文件里：
- 已有的中文（上游没改动、保留下来的）——**不要动**；
- 上游新带入的英文——**你的任务**。

## 硬约束
1. **不调用 git、不编译、不构建**。
2. 只改**字符串字面量的内容**；不改代码结构、不改标识符、不增删行（除非翻译必须）。
3. 不碰 `.hanhua-orig/`、`.hanhua2/` 目录，不碰你清单之外的文件（读不限）。
4. 用 read/grep/glob/edit/write 这些工具操作文件；**不要**用 shell 重定向写文件。
5. 项目必须保持可编译：改完后每个文件的引号必须配对、括号/大括号必须平衡。

## 文件清单与增量对照
- 你的清单：`D:\DroidDeck\.hanhua2\work\tasks\<你的任务ID>.txt`（每行一个相对路径）。
- 清单里 kind=changed 的文件：上游改动的对照 diff 在 `D:\DroidDeck\.hanhua2\work\delta\<路径，把 / 换成 __>.diff`（`+` 开头的行 = 最终文件里上游带入的新内容）。你的工作就是把其中**用户可见的英文**译成简体中文。
- kind=new 的文件：整文件都是上游新增，但**只译用户可见的英文串**（UI 文案、toast、通知、对话框、用户可见的错误/日志消息）；注释、日志 TAG、标识符不译。
- 上游原文（theirs 树）在 `D:\DroidDeck\.hanhua2\work\theirs\`，可用作对照。

## 必须保持英文（协议/解析/ABI/测试保护 —— findings.md 有完整版）
- **加载管线**：guest 脚本的 `step` 行、`LoadingState` 的解析正则与显示 token、`SessionLoading.stageOf/readableStep` 关键词、`SessionActivity.progressFor` 的 Downloading/Verifying、`INSTALL_COUNT`/`UPDATE_PROGRESS` —— 一律不译（其他子任务同样不译，所以不会不一致）。特别注意：`droiddeck-steam-install` 的 `正在下载 Steam:` echo 与 `LoadingState.INSTALL_COUNT` 正则**当前两侧都是中文，必须保持原样**。
- `LogRedactor` 全部；`BatteryComponent` 写 sysfs 的值（Charging/Discharging/…）；`url=`/`session=` 行；`== STEP`/`== FAIL` 前缀；`refLabel()` 输出；`AppImageManager.problem()` 中被测试断言的 `x86_64`/`type 2`/`not an AppImage` 子串；`original ` 前缀；日志 TAG。
- 技术标识：Steam、Proton、FEX、GE-Proton、proton-cachyos、droiddeck-*、Adreno、gamescope、Wayland、文件名、路径、URL、命令行、设备名、JSON/`op` 值（`progress`/`error`/`done`）、Intent action、SharedPreferences 键、`R.string.*` 资源名。

## 耦合同步原则
若翻译某个串可能有消费者（正则解析器、测试断言、脚本回显）：先用 grep 全库找消费者；**两侧都改得动就同步改，拿不准就保留英文**，并在报告里写明。

## 格式要求
- 保留全部占位与转义：`%1$s`、`%d`、`%.1f`、`$var`、`${...}`、`\n`、`\"`、`·`；只译其中的英文单词。
- Kotlin/Java 字符串内的英文双引号改用中文 `“ ”`（勿破坏转义结构）。
- 文案简洁（UI 窄屏）；术语与 `values-zh-rCN/strings.xml` 一致（会话、运行时、帧生成、屏幕控件、配置文件、快捷方式、Steam 库、着色器缓存、兼容性工具、掌机、驱动、桌面、组件、商店、加载中…、取消、保存、删除、重命名）。
- 保留专有名词：Steam、Proton、Flatpak、FEX、DXVK、VKD3D、Mesa、Turnip、gamescope、Wayland、Adreno、Decky、Big Picture、Quick Access/QAM、AppImage、LXQt、RPCS3 等。
- Markdown 文档：正文全部译成中文；代码块、命令、路径、URL、标识符原样保留；标题译；图片链接不动。

## 各任务类型要点
- **kotlin（K\*）**：重点 `Text(`、`title =`、`label =`、`placeholder`、`hint`、`note`、对话框、按钮、菜单、toast、通知、`contentDescription`、`stringResource` 不要改回硬编码。上游已迁移到字符串资源的位置**不要改**（资源值由协调者统一处理）。
- **cpp（C\*）**：`banner_log`/`__android_log_print`/`printf` 的消息串；**printf 格式符序列必须逐字保留**；TAG 参数不译；协议错误串（`wl_resource_post_error`）不译。
- **scripts（S\*）**：guest 脚本的 `echo`/`step` 输出——`step` 行保持英文或保持现状（已是中文的不要动），其他面向用户的消息可译；改前 grep 消费者（Kotlin 解析、python 测试断言），两侧同步或保留英文。
- **docs（D\*）**：按上面 Markdown 要求整篇处理（只处理你清单内文件）。
- **tests（T\*）**：测试断言必须与被测源码**当前实际输出**一致——先看源码现在是中文还是英文，再同步断言；测试内部标识、路径、协议值不译。

## 完成自检与报告
- 自检每个改过的文件：引号配对、括号平衡、行数未意外变化。
- 报告格式（简短，别贴文件内容）：
  1. 每个文件的改动处数；
  2. 你**保留英文**的协议串（文件:行 简述）；
  3. 发现的耦合风险 / 需要协调者跟进的事项。
