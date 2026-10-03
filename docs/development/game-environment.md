# Steam 游戏环境

打开 **Games → Game environment**，就在 **FEX preset** 正下方，在 Steam 设置或会话内抽屉中。选择所有 Proton 游戏、已安装的游戏，或输入 Steam 应用 ID。对于非 Steam 快捷方式，使用其数字 `compatdata` 目录 ID（无符号 32 位）。更改在下次游戏启动时应用，包括 Steam 已在运行时。正在运行的游戏必须重启。原生 Linux 游戏和 Steam 本身不在此编辑器的范围内。

顺序是继承的进程环境、选定的 FEX 预设和内置设置、共享编辑，然后是游戏特定编辑。因此编辑器条目会胜过匹配的 Steam 启动选项变量。**Remove** 显式取消设置变量。**Restore inherited settings** 移除覆盖。**Reset this profile** 只清除该配置文件的覆盖。值是字面字符串，没有 shell 展开；只有当消费程序期望它们时才需要引号。

## 默认值和可用建议

初始配置启用 Mesa shader 缓存，使用 `MESA_SHADER_CACHE_DISABLE=false`，设置 `VKD3D_FEATURE_LEVEL=12_2` 和 `VKD3D_SHADER_MODEL=6_9`，并保留选定的 FEX 预设。现有编辑和显式移除优先于这些默认值。FEX 自己的默认值仍然是默认预设。VKD3D/DXVK 中的 shader 缓存、同步、光线追踪和诊断日志在其他方面保留运行时的默认值。

变量名选择器提供 24 个预定义变量和一个 Custom 条目。像 WinNative 一样，已知变量使用开关、值下拉、多选列表或数字/文本字段。功能级别和 shader 模型下拉也接受自定义值。自定义变量有可编辑的名称和字面值。

| 变量 | 用途 |
| --- | --- |
| `VKD3D_FEATURE_LEVEL` | D3D12 能力覆盖；默认为 `12_2`。 |
| `VKD3D_SHADER_MODEL` | Shader 模型覆盖；默认为 `6_9`。 |
| `VKD3D_CONFIG` | 每游戏 workaround，例如 `nodxr`；不是通用性能预设。 |
| `MESA_SHADER_CACHE_MAX_SIZE` | Mesa shader 缓存的存储预算，例如 `1G`。 |
| `mesa_glthread` | OpenGL 线程化；用受影响的游戏测试。 |
| `DXVK_CONFIG` | DXVK 配置选项；最近的构建接受 `dxvk.maxFrameRate = 60`。 |
| `VKD3D_FRAME_RATE` | D3D12 帧率限制。 |
| `DXVK_HUD`、`PROTON_LOG` | 可选诊断；普通游玩时保持未设置。 |
| `PROTON_USE_WINED3D` | D3D9–11 OpenGL 回退，用于兼容性测试。 |
| `PROTON_USE_XALIA` | Proton 的手柄导航助手开关。 |

强制 `12_2` 或 `6_9` 会改变报告的能力；它无法实现缺失的 Vulkan 功能。移除任一条目以对该能力使用自动检测。其他选择器建议是可编辑的起始值，并且仅在添加并保存时启用。较旧或自定义组件可能支持不同的选项集。Android Wine wrapper、ALSA-server、Box64 和打过补丁的 async-DXVK 选项在编辑器中被标识为需要不同/自定义组件；自定义条目仍然允许。旧的 `DXVK_FRAME_RATE` 环境变量已从当前 DXVK 中移除，因此不提供为建议。普通游玩时优先使用会话帧率限制器。

## 启动路径和参考

WinNative 的 Linux 会话过滤仅 Android 选项，将用户变量合并到 FEX 预设上，并将结果传递给 `env -i` 后再启动 Steam。其 Windows/Wine 图形助手还将选定的功能级别映射到 `VKD3D_FEATURE_LEVEL`。已审查源代码：[WinNative Linux 会话](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/runtime/display/XServerDisplayActivity.java)，[变量编辑器](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/shared/ui/widget/EnvVarsView.java)，[图形配置](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/feature/settings/drivers/DXVKConfigUtils.java)。

DroidDeck 改为在 `/root/.config/droiddeck/game-environment.json` 发布一个原子 JSON 快照。它的 Valve ARM64 Proton wrapper 和采用的第三方 Proton wrapper 都执行 `bannerlator-game-env`，它每次真实游戏启动时读取该快照，并使用 `execvpe` 启动 Proton。Probe prefix `compatdata/0` 和非启动动词不变。格式错误的配置会回退到继承的环境，而不评估其内容。有符号的非 Steam prefix ID 被规范化为无符号 ID。

上游参考：[VKD3D 能力解析](https://github.com/HansKristian-Work/vkd3d-proton/blob/master/libs/vkd3d/device.c)，[VKD3D 选项](https://github.com/HansKristian-Work/vkd3d-proton#environment-variables)，[Proton 运行时选项](https://github.com/ValveSoftware/Proton/tree/proton_11.0#runtime-config-options)，[Mesa 变量](https://docs.mesa3d.org/envvars.html)，[DXVK 变量](https://github.com/doitsujin/dxvk#environment-variables)，[DXVK 帧率限制器更改](https://github.com/doitsujin/dxvk/releases)。

验证：`./gradlew testDebugUnitTest` 和 `python3 -m unittest discover -s tools/tests -p test_game_environment.py`。实际游戏兼容性取决于已安装的 Proton、VKD3D 和 Vulkan 驱动；这些检查并不确立每台设备都支持功能级别 12_2 或 SM 6_9。