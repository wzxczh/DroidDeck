# DroidDeck 汉化 — 第二轮（版本更新合并）· 已完成

## 最终状态：全部阶段完成 ✅（未编译、未调用 git —— 遵用户指示）

### 交付内容
1. **冲突合并 518/518**：三方取证（自写 Python 读 .git 对象）→ 489 自动 + 11 人工复核 + 18 特殊文件 + 6 删除；统一"取上游新代码、保本地修复、其英文交翻译"。写回 80 文件、删除 bannerlator-*×5 与 AppImageState.kt（上游重构为 UserApps）。
2. **资源**：values-zh-rCN ← 上游完整中文 + 补 92 键；values 默认 ← 同步中文（1020 键双文件一致，0 非标识英文）。
3. **增量翻译**（16 个子智能体 + 协调者直翻收尾）：
   - Kotlin/Java、C/C++、guest 脚本、全部 38 个 MD 文档（除许可证）。
   - 协调者直翻：session 层 33 处（direct_fixes 批次共 76 处替换）、README、0.3.0 发布说明、testing×2、3 个 development 文档、两份 PATCHES、proot-performance（315 行）、android-clipboard、game-launcher-shortcuts、thor-sd。
4. **断言同步**：DirectAudio（4 处）、FEXServer beside、尚无前缀——源与 pytest 两侧同批修改；gradle 测试 25+ 文件由 K6/K3/K5 逐一核对一致。

### 终检结果（全绿）
| 检查 | 结果 |
|---|---|
| 真冲突标记 `^<{7} ` | 0（build.yml 内为 CI 脚本字面量） |
| res XML + AndroidManifest 解析 | 全部通过 |
| R.string/R.plurals 引用 ⊆ 资源键 | 0 缺失 |
| 括号/引号平衡 vs 上游（557 文件） | 0 失配 |
| 结构分类 | OK-THEIRS 665 / OK-OURS 142 / REVIEW 19（逐个人工核验为预期：译后文档、双语解析器、同步资源、嵌套引号行） |
| MISSING 8 | 二进制资产 UTF-8 读取误报（逐个 exists 确认存在） |
| 英文 UI 残留（Text/title/hint/toast/通知/资源值） | 0（54 候选全为 Compose 动画 label id 误报） |
| 全库 38 个 MD 英文段 | 0（除 dxbc/LICENSE.md=按规则保留；命令行/表格单元=误报） |
| 测试断言↔源码 | audit_tests 28 项全为 fixture/协议/失败消息；本轮翻译的原文仅 3 处被 pytest 断言且已同步 |
| gamescope p5 / wlroots sha256 | GitHub release 资产摘要 API 核对一致 |

### 构建保障
- 本地修复（EXPORT 宏顺序、wlroots sha、build.sh 权限、DeviceReport 布局）经 `audit_local.py` 证明位于冲突区之外或复核保留。
- fork 缺 gamescope-p5 资产会导致默认构建失败 → `tools/build_local.sh` 加了一行上游仓库回退下载（可逆）。
- LoadingState INSTALL_COUNT ↔ droiddeck-steam-install 中文 echo 双侧核验一致；Decky 下载前缀两侧同步；stageOf 双语条件完好。

## 保留英文（有意，勿视为遗漏）
- **协议/解析**：step 行、`== STEP/FAIL/DONE/appimage` 头、INSTALL_COUNT/UPDATE_PROGRESS/Downloading/Verifying、FEX_MISSING、`not an AppImage`/`type 2`、sysfs ABI 值、Intent action、JSON op/键、TAG、`original ` 文件前缀、D-Bus 接口名、HTTP/JS 串、RPCS3 配置值、Local Drive (/)、gamescope 参数。
- **断言对（整对保留）**：droiddeck-esync say() ↔ test_esync_packs、AppImage 4 token ↔ test_appimage_run、steam-install 通道串 ↔ test_steam_install、game-env could-not-start、netmanager 连接名等。
- **范围外（沿用第一轮口径）**：`.github/workflows`、`*.gradle`、代码注释、locale 资源（ja/ko/es/zh-rHK/zh-rTW=上游自带他语言）、许可证（dxbc/LICENSE.md、uruntime-LICENSE、LICENSE）、dev 工具（tools/release、tools/proot/bench、build 脚本、pytest 其余 fixture）、品牌/硬件标识（Win-FG、Denuvo、Snapdragon GSR、Adreno 6xx…）。

## 事故与处置记录
- 会话临时目录被系统清理（子任务全失活）→ 派生数据全部重建并永久迁入 `D:\DroidDeck\.hanhua2\work\`。
- 子进程写工作区受限（ACL 正常，属会话策略）→ 批量写入走 danger-full-access 授权调用（共 5 次）。
- 部分子任务续答轮工具被禁 → 探测唤醒；后按用户指示停止全部子任务，由主模型直翻收尾。
- 自查出的笔误 2 处（日期 2026-01-02、Winlander）当场修正。

## 待用户决定
- `.hanhua-orig/`（第一轮备份，已入 git 跟踪）与 `.hanhua2/`（本轮工具链）去留。
- 如需提交（需允许调用 git）或实际跑一次构建验证，另行指示。

---

# 第一轮（旧版本汉化，已随合并保留）

### Phase 1-3 文档（完成）
- 29 个 MD 全部中文；`dxbc/LICENSE.md` 按规则保留英文；`values/strings.xml` 默认资源同步中文。

### Phase 4 应用 UI（完成）
- 前端启动器 15 文件 ≈221 处；主页向导 9 文件 202 行；会话 12 文件 246 行；文件管理器 16 文件 ≈145 处；商店/组件/驱动 19 文件 256 行；散件 19 文件 77 处。

### Phase 5 日志与技术输出（完成）
- Kotlin/Java 50 文件 308 处；C/C++ 337 处/430 行 + wl_color_mgmt 189 行（verify.ps1 通过）；framegen/lsfg 5 文件 22+ 行。

### Phase 6 加载管线耦合（完成）
- 脚本 step ↔ LoadingState 正则 ↔ stageOf/readableStep 双语 ↔ progressFor 四侧同步；BACK_* 常量、releaseStatus 等跨文件措辞统一。

### Phase 7 终检（完成，未编译）：8 项扫描全过（详见 git 历史）。
