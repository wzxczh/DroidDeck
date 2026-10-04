# Phase 6 — 加载管线耦合统一方案（由协调者亲自执行）

## 耦合链
```
bannerlator-session / bannerlator-steam-install  (脚本 echo "== STEP <time> <text>")
        ↓ session.log
LoadingState.read()  → stepText = 剥掉 "== STEP " 与时间；INSTALL_COUNT/UPDATE_PROGRESS 正则
        ↓
SessionLoading.stageOf(step) / readableStep(step)  → 关键词分类
        ↓ LoadingOverlay 显示
SessionActivity: loading.step = "Starting the session…"/"downloading the desktop"/progressFor()
LinuxRuntimeInstaller stage token: "Downloading"/"Verifying" (被 progressFor startsWith 消费)
```

## 关键事实
1. `UPDATE_PROGRESS = Regex("Downloading update \\((\\d+) of (\\d+) KB\\)")` 匹配的是 **Steam 客户端自己的英文输出**（外部程序）→ 正则**不译**；其显示串 "Downloading the Steam client update · $p%" 是我们的 → 可译。
2. `INSTALL_COUNT = Regex("downloading Steam: (\\S+) \\((\\d+)/(\\d+)\\)")` 匹配**我们脚本** bannerlator-steam-install 的 echo → 若译脚本 echo，必须同步改此正则。
3. `stageOf` 关键词：`linux runtime`、`starting the session`、`desktop`、`starting the steam client`、`steam`、`client`、`proton`、`library`、`compatibility`。
4. `readableStep` 前缀：`download`、`checking the linux`、`unpacking`、`installing`。
5. SessionActivity 自产 step 文案："Starting the session…"、"downloading the desktop"、"downloading $what · …"、"checking $what"、"unpacking $what"、收尾错误句。
6. LinuxRuntimeInstaller stage："Downloading"、"Verifying"（被消费）→ 不译 token，但可把 `progressFor` 的分支保持对英文 token 判断、只译其输出。

## 执行策略（保证分类不破）
采用"**中文句中保留英文关键词**"混合策略，最小改两侧：
- 脚本 step 文本译为中文但**保留关键英文词**：如 `starting the Steam client` → `正在启动 Steam 客户端`（含 "steam"，stageOf `steam && "steam" in t` 仍命中，且 `starting the steam client` 分支改为其小写中文匹配——直接把 stageOf 的该分支改为 `"启动 steam 客户端" in t || "starting the steam client" in t` 双条件，向后兼容）。
- `stageOf` 每个分支加**中英双条件**（`||`），保证脚本翻译前后都能命中，且脚本若未译也正常。
- `readableStep` 前缀同样加中文前缀：`下载`、`校验 linux`、`解包`、`安装`（与我方输出的新文案对齐）。
- `progressFor` 输出译为中文（"正在下载 $what · x / y MB"、"正在校验 $what"、"正在解包 $what"），`stage.startsWith("Downloading")` 判断保持英文 token 不变。
- `LoadingState` 显示串译中文，两个正则：
  - UPDATE_PROGRESS 保持英文（匹配 Steam 输出）。
  - INSTALL_COUNT：脚本 echo 译为 `正在下载 Steam: $block ($i/$total)`，正则改 `"""正在下载 Steam: (\S+) \((\d+)/(\d+)\)"""`。
- `bannerlator-session` 其余 `step "..."` 全部译中文（保留 Steam/Proton/ARM64 等词）。
- `bannerlator-flatpak-setup` 的 STEP/FAIL：FlatpakManager 只剥前缀直接显示 → 可译（不在 stageOf 链上，但 StorePage 显示）→ 归入本阶段一并译（其消费端 FlatpakManager 无关键词匹配，已确认只 startsWith 前缀）。
- 其它脚本 echo（droiddeck-desktop、droiddeck-gpu、steamos-*、bannerlator-appimage-run/flatpak-run/seed-redists/steam-compat/steam-launch）：给用户/日志读 → 译；先 grep 确认无 app 侧关键词消费。

## 执行顺序（等 046c9106 完成后）
1. 改 bannerlator-session、bannerlator-steam-install（脚本侧）。
2. 改 LoadingState.kt（显示串 + INSTALL_COUNT 正则）。
3. 改 SessionLoading.kt（stageOf/readableStep 双语条件 + LoadStage 文案——若子任务已译 LoadStage，核对关键词一致）。
4. 改 SessionActivity.kt progressFor 输出 + 收尾错误句 + loading.step 赋值（若子任务已译，核对 stageOf 兼容）。
5. 其余脚本 echo。
6. 全局校验。
