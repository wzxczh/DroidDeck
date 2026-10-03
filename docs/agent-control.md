# Agent 控制

调试 APK 在 `content://com.droiddeck.launcher.agent` 暴露了一个 ContentProvider，以及一个用于启动会话的 Activity。Android 仅限 shell 的 `DUMP` 权限保护两者。它们不存在于发布 APK 中。

构建并安装调试 APK：

```sh
./gradlew assembleDebug --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接调用 provider 来执行 state、stop 和 resume：

```sh
adb shell content call --uri content://com.droiddeck.launcher.agent --method state
adb shell content call --uri content://com.droiddeck.launcher.agent --method stop
```

启动请求通过 `tools/droiddeckctl` 进行，它从 ADB shell 启动受保护的调试 Activity。直接调用 provider 的 `start` 方法会返回错误，因为后台 provider 无法可靠地打开会话屏幕。

当设置了 `ADB_SERIAL` 或 `ANDROID_SERIAL` 时，主机 CLI 使用它们解析出一个已授权设备。否则，它会去重报告相同设备序列号的传输，并且如果仍有多个设备，则要求显式指定序列号。

```sh
tools/droiddeckctl state --json
tools/droiddeckctl start steam
tools/droiddeckctl wait ready --timeout 90
tools/droiddeckctl start desktop
tools/droiddeckctl run /usr/bin/foo -- arg1 arg2
tools/droiddeckctl stop
tools/droiddeckctl resume
tools/droiddeckctl logs latest ./session-artifacts
tools/droiddeckctl screenshot ./screen.png
```

每个命令将 JSON 写入 stdout，并将其解析出的 ADB 序列号报告到 stderr。退出码为：0 表示成功，2 表示无效或被拒绝的命令，3 表示 ADB/设备错误，4 表示会话失败，5 表示超时，6 表示 artifact 或文件错误。设置 `ADB` 以选择 adb 可执行文件（或传递 `--adb`）；在命令前传递 `--serial` 以直接选择设备。

`start` 接受 `steam` 或 `desktop`。Steam 默认以 Big Picture 启动；`--ui desktop` 选择客户端的桌面 UI，`--url steam://...` 传递客户端 URL。使用 `--wait` 在 `start` 中等待 `READY`。`run` 接受程序路径和可选的 guest 参数。

`state` 响应使用 schema 1，并包括构建标签、运行时版本、会话 ID 和阶段、模式、请求的程序、挂起和首帧状态、输出大小、guest PID、失败详情以及 artifact 路径。阶段为 `IDLE`、`PREPARING`、`INSTALLING_RUNTIME`、`STARTING_COMPOSITOR`、`STARTING_GUEST`、`STARTING_STEAM`、`READY`、`SUSPENDED`、`STOPPING` 和 `FAILED`。每个会话还会在其现有 artifacts 旁边写入 `events.jsonl`。

`tools/deploy_local.sh` 使用相同的设备解析器。如果连接了多个不同设备，请使用 `ADB_SERIAL` 或 `ANDROID_SERIAL` 选择一个。