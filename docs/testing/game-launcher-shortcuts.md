# 游戏启动器快捷方式

DroidDeck 通过规范化的启动链接暴露已安装的 Steam 标题与 AddedGames：

```text
droiddeck://game/<无符号十进制游戏 ID>
```

该 ID 就是 `steam://rungameid/` 接受的值，包括占据无符号 64 位范围的 AddedGames 快捷方式 ID。应用只接受精确的 URI 形式，且只有在最新的库扫描确认该游戏存在之后才接受。深度链接不接受路径、Steam URL、额外参数、前导零，以及无符号 64 位范围之外的 ID。扫描文件的前端可以改为传入导出的 `.droiddeck` 启动文件的 file 或 content URI。

每个游戏页面的**快捷方式**菜单通过 Android 的固定快捷方式请求提供**添加到主屏幕**，以及**复制启动链接**。当启动器调用 `ACTION_CREATE_SHORTCUT` 时，DroidDeck 会打开游戏页，并在用户选择游戏后返回 Android 的标准快捷方式结果。

装好调试版应用后，用当前库中的一个已安装游戏 ID 演练链接路由：

```sh
adb shell am start -n com.droiddeck.launcher/.MainActivity \
  -a android.intent.action.VIEW -d 'droiddeck://game/620'
```

用下面的命令打开标准快捷方式选择器：

```sh
adb shell am start -n com.droiddeck.launcher/.MainActivity \
  -a android.intent.action.CREATE_SHORTCUT
```

冷链接会以 `steam://rungameid/<id>` 作为启动 URL 启动常规 Steam 会话。热链接只在 Steam 会话运行期间被接受。服务会等待 READY、恢复被挂起的会话，然后写入一个仅含十进制的请求供现有 Steam 客户端消费。桌面与程序会话保持活跃，并报告在当前会话结束前无法启动该游戏。

用以下命令构建包含原生/音频包的完整本地调试 APK：

```sh
DROIDDECK_BUILD_VARIANT=debug tools/build_local.sh
```

聚焦的 JVM 测试套件是 `./gradlew app:testDebugUnitTest`。在设备上验证冷启动、热启动、恢复、排队与拒绝路径时，请查看任务报告与会话日志。

## Thor 验收运行

在 AYN Thor（Android 13 / API 33，序列号 `adb-d234a848-fv2FDl (2)._adb-tls-connect._tcp`）上验证。`app/build/outputs/apk/debug/app-debug.apk` 处的完整调试 APK 通过 `adb install -r --no-incremental` 安装，未卸载也未清除应用数据。

- 从游戏页面固定 **Geometry Wars: Retro Evolved**，在 Android 的固定快捷方式数据库中确认 `game:8400`，然后点按该真实主屏幕图标。冷会话以首帧到达 READY；`Download/DroidDeck/2026-10-02-27-steam/session.log` 记录了 `steam://rungameid/8400`、Steam 游戏 ID 8400、`GeometryWars.exe`，以及 gamescope 对应用 8400 的聚焦。
- 从游戏菜单复制链接。Android 剪贴板预览显示 `droiddeck://game/8400`。对该 URI 的一次 ACTION_VIEW 复用了会话 `2026-10-02-27-steam`（guest PID 14740、gamescope PID 14762）；其事件日志记录了 `steam.game_launch_requested`，没有新建合成器或 guest 进程。
- 在通用 Steam 会话处于 `STARTING_STEAM` 时发送另一个游戏链接。会话 `2026-10-02-28-steam` 在记录排队启动之前到达 READY，其日志说明它通过正在运行的客户端启动了游戏 8400。
- 按 Home 挂起热 Steam 会话。点按同一固定快捷方式恢复了会话 `2026-10-02-27-steam`（guest PID 14740），并在 `session.resumed` 之后派发游戏请求。
- 带前导零、带查询的溢出与未知 ID 的链接让代理保持 IDLE 且无 guest。使用 `/usr/bin/sleep 300` 的 MODE_RUN 会话在打开游戏链接前后保持同一会话与程序；没有请求 Steam 启动。
- ACTION_CREATE_SHORTCUT 在一次全新扫描后打开了游戏选择器；选定标题后选择器结束。由 shell 启动的 activity 没有调用方可检查返回的载荷，因此端到端的结果 extras 未在设备上直接捕获。Robolectric 覆盖了结果 API 调用与快捷方式 intent 元数据。
- 既有的游戏文件与 Proton 前缀控件仍能在文件管理器中打开 Geometry Wars 的已安装文件夹与 `compatdata/8400/pfx`。

设备日志保留在 `/sdcard/Download/DroidDeck/2026-10-02-27-steam` 与 `.../2026-10-02-28-steam` 下。本次运行的截图位于 `/tmp/game-launcher-thor-*.png`、`/tmp/files.png` 与 `/tmp/prefix.png`。本次运行添加的唯一快捷方式是 `game:8400`，验证后已删除。
