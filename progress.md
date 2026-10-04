# DroidDeck 汉化 — 进度日志

## 最终状态（全部阶段完成，除 1 个收尾子任务）

### Phase 1-3 文档（完成）
- 29 个 MD 全部中文；`dxbc/LICENSE.md`（zlib 许可证法律文本）按规则保留英文。
- `values/strings.xml` 默认资源同步为中文（与 zh-rCN 一致）。

### Phase 4 应用 UI（完成，6 子任务 + 协调者）
- 前端启动器 15 文件 ≈221 处；主页向导 9 文件 202 行；会话 12 文件 246 行；文件管理器 16 文件 ≈145 处；商店/组件/驱动 19 文件 256 行；散件 19 文件 77 处。
- 耦合双侧同步：lastPlayedText、ComponentsPage confirmTitle、Tag when 分支、DriverRow 常量（内置/已下载/已导入）、BUNDLED 比较、MultiRow 汇总。

### Phase 5 日志与技术输出（完成）
- Kotlin/Java：50 文件 308 处（5a4bcf54）+ 协调者补 SessionService 25 行 Log.i/w、52 处 Log.w 遗漏（扫描模式 [dive] 漏 w 的修正）、ProtonExtras 15 条消息、SessionEvents payload、components/artifacts marker 文案。
- C/C++：waylandcomp+fakeinput 337 处/430 行（2298213b，verify.ps1 通过）；framegen/lsfg 5 文件 22+ 行（协调者：statusName、engine/governor/shaders/dll 日志）；wl_color_mgmt.c 189 行（5f4053bf+590edbec 协作，verify.ps1 全项目 21 文件 0 失败）。
- 保留英文（正确）：compositor "changed its frame size"/"renamed"（docs/releases/0.1.2.md 引用）、wl_resource_post_error 协议串 12 处、"environment"（strstr 依赖）、sysfs ABI 值、LogRedactor 全部、Steam 自身输出正则。

### Phase 6 加载管线耦合（完成）
- bannerlator-session 全部 step/echo 译中文（含两次编辑事故修复）。
- bannerlator-steam-install "正在下载 Steam:" ↔ LoadingState.INSTALL_COUNT 正则同步。
- LoadingState 显示串译中文；UPDATE_PROGRESS 保持英文（匹配 Steam 外部输出）。
- SessionActivity：loading.step 赋值、progressFor 输出、installRuntime/Desktop 收尾句、what 参数。
- SessionLoading：stageOf 加中英双条件（向后兼容英文）、readableStep 加中文前缀。
- SessionPrefs BACK_* 常量译中文（grep 证实仅显示用途、不持久化）。
- DriverMenus/ModeSettingsDialog releaseStatus 措辞统一（"点按刷新"）。
- TurnipDriver "Linux runtime driver" 引用统一为"运行时驱动"（与 UI 标题一致）。
- SessionActivity HDR 外壳句（refused: → 被拒绝：）与 HdrSupport.reason 中文对齐。

### 终检结果（全部通过，未做任何编译——遵用户指示）
1. 测试断言串 8 项完整（x86_64/type 2/not an AppImage/refLabel/LogRedactor/Incomplete copy）。
2. 协议与解析串 16 项完整（INSTALL_COUNT 中英同步、url=/original /ABI 值/stageOf 双条件/progressFor token/environment/docs 引用行/exec 行）。
3. BACK_* 旧英文值 0 残留，新值定义 2 处（使用走常量名）。
4. 158 个源文件含中文行引号配对全 OK。
5. 短词 UI 残留 0（唯一命中 "none" 为解析协议值）。
6. 通知/Toast/对话框标题 0 英文残留。
7. MD：除许可证外全中文。
8. C++ verify.ps1：21 文件 0 失败（行数/字符串骨架/说明符序列/tag 序列）。

### 进行中
- [ ] b5e232cf：guest 脚本消息汉化（tools/linuxfs 约 50 处，含测试保护约束）——最后一个任务。

## 历史协调记录
- 子任务并发冲突 2 次（wl_color_mgmt.c 双写、306e5465 僵尸）：已通过叫停 590edbec/306e5465、独占授权 5f4053bf 解决。
- .hanhua-orig 备份 + verify.ps1 工具链（C++ 任务建立）已完成使命，待用户决定是否删除。
