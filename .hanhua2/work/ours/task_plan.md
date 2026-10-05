# DroidDeck 全项目汉化 — 任务计划

## 目标
阅读全部 MD 文件理解项目后，对整个项目做完整汉化。用户确认：
- **方式**：直接把源码/文档中的英文替换为中文（不抽取字符串资源）。
- **范围**：① 应用全部可见 UI ② 项目自有 MD 文档 ③ 第三方 README ④ 日志与技术输出。
- **约束**（后加）：全程及结束后**不做任何编译**；不调用 git.exe。

## 阶段状态

### Phase 1 盘点 ✅
- 29 个 MD 通读完毕；耦合清单与规则写入 findings.md。

### Phase 2/3 文档 ✅
- tools/proot/PATCHES.md、directaudio README、adrenotools×6、dxbc README、winfg NOTICE、ZERO_COPY_SPIKE.md 全部译完。
- `dxbc/LICENSE.md` 保留英文（zlib 许可证法律文本）。
- `values/strings.xml` 默认资源同步中文（77 键与 zh-rCN 完全一致）。

### Phase 4 应用 UI ✅（6 并行子任务全部回收）
- fee01556 前端 15 文件 ≈221 处；8aa2d2e1 向导 9 文件 202 行；046c9106 会话 12 文件 246 行；42799b75 文件管理器 16 文件 ≈145 处；e68d4652 商店组件 19 文件 256 行；68545eef 散件 19 文件 77 处。

### Phase 5 日志与技术输出 ✅
- 5a4bcf54 Kotlin/Java 50 文件 308 处。
- 2298213b C/C++ waylandcomp+fakeinput 337 处（verify.ps1 0 失败）。
- 5f4053bf+590edbec wl_color_mgmt.c 189 行（verify.ps1 全 21 文件 0 失败）。
- 协调者直翻：SessionService 42 行、Log.w 遗漏 52 处、ProtonExtras 15 条、framegen/lsfg 5 文件、SessionEvents/Artifacts marker。

### Phase 6 加载管线耦合 ✅
- 脚本 step ↔ LoadingState 正则 ↔ stageOf/readableStep 双语 ↔ progressFor 四侧同步完成。
- BACK_* 常量、releaseStatus、"Linux runtime driver"、HDR refused 外壳等跨文件措辞统一完成。

### Phase 7 终检 ✅（8 项扫描全过，未编译）
1. 测试断言串 8 项完整；2. 协议/解析串 16 项完整；3. BACK_* 零残留；
4. 158 文件含中文行引号配对 OK；5. 短词 UI 0 残留；6. 通知/Toast/对话框 0 残留；
7. MD 除许可证外全中文；8. 资源 77 键一致。

### Phase 8 收尾 ⏳（进行中）
- [ ] b5e232cf：guest 脚本消息（tools/linuxfs 约 50 处，含测试保护约束）
- [ ] 回收该任务 → 报告用户

## 报告需涵盖的"未覆盖说明"
- `.github/workflows/*.yml`（CI 开发者配置，不在四项范围内）
- `artwork/boot-animation-source/droiddeck-boot.html` 预览页控件文字（开发者工具，非成品视频）
- `dxbc/LICENSE.md`（许可证法律文本）
- 第三方禁区未动：adrenotools/thirdparty/termux/winfg 源码
- 刻意保留英文的协议/ABI/外部输出串（详见 findings.md 与 progress.md）
- `.hanhua-orig/` 备份目录 + verify.ps1 待用户决定去留
