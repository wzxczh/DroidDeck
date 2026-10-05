# 面向 Linux Steam 客户端的 DirectAudio（中继构建）

这是为**在 Android 上的 Linux 运行时里运行的 Valve ARM64 Proton 下的游戏**准备的驱动——也就是 Bannerlator 搭载原生 Steam 客户端的 linuxfs 会话——在这种场景下游戏进程是一个 glibc 进程，无法自己调用 Android 的音频库。

分两部分，两者都必需：

| 部分 | 是什么 | 运行位置 |
|---|---|---|
| `aarch64-unix/winedirectaudio.so` 加两个 PE 外壳 | Wine 的 `mmdevapi` 驱动，glibc 构建，**内部不含 AAudio** | 在游戏内，位于 Linux rootfs 中 |
| `directaudio-relay` | 拥有 AAudio 输出与麦克风流的小型 bionic 程序 | 在 Android 一侧，运行在应用的 uid 下 |

二者通过一个 unix socket 相连。驱动发起连接，发送它的启动配置，并拿回两个共享内存环形缓冲：一个由它填入混合后的游戏音频，另一个由助手填入麦克风音频。助手负责播放和录音；驱动继续做它一直在做的其他所有事情。

## 用的是哪个 Proton

在 `ValveSoftware/wine` 的 `proton_11.0` 上构建。它实现的私有 `mmdevapi` 接口在 **Proton 11.0 (ARM64)**、**Proton Experimental (ARM64)** 和我们自己的 Wine-11 层之间逐字节相同（2026-09-19 核对），因此这一个构建就能服务其中任何一个。将来基于 Wine 12 的 Proton 需要重新构建——这个接口没有版本号，不匹配的表现是静默无声，而不是报错。

## 把驱动装在 Proton 旁边（而不是装进 Proton 里）

Steam 会校验并更新它的 Proton depot，因此加在 `steamapps/common/Proton .../files/lib/wine/` 下的文件不会保留下来。把驱动放进属于它自己的目录，再让 Wine 指向它：

```
<somewhere>/directaudio/lib/wine/aarch64-unix/winedirectaudio.so
<somewhere>/directaudio/lib/wine/aarch64-windows/winedirectaudio.drv
<somewhere>/directaudio/lib/wine/i386-windows/winedirectaudio.drv
```

并为游戏进程设置：

```
WINEDLLPATH=<somewhere>/directaudio/lib/wine
```

Wine 会在追加各架构子目录之后搜索 `WINEDLLPATH` 条目，因此 PE 外壳和 unixlib 都能在其中被找到。在 Bannerlator 的运行时里，导出它的自然位置是 `bannerlator-proton` 兼容性工具包装，每次 Proton 启动都要经过它。

## 选择驱动

`mmdevapi` 只会尝试注册表中列出的驱动（或者它内置的 `pulse,alsa,oss,coreaudio` 列表——那里面并不认识这一个）。在游戏的 prefix 中设置：

```
wine reg add "HKCU\Software\Wine\Drivers" /v Audio /d directaudio /f
```

在 Proton 下，prefix 是 `compatdata/<appid>/pfx`；用 Proton 自带的 `bin-arm64/wine` 把这个 prefix 作为 `WINEPREFIX` 来运行该命令，或者在包装脚本把启动交给 Proton 之前写入这个键值。

## 运行助手

```
directaudio-relay --socket <path> [--log]
```

- **在应用的 uid 下**运行它，就像应用已经运行它的 PulseAudio 守护进程那样。Android 按调用方 uid 检查 `RECORD_AUDIO`，因此以任何其他方式启动的助手能播放，但不能录音。
- 在游戏**之前**启动它：驱动只在 Wine 选择音频驱动时问一次"有助手在吗？"，如果没有就回退为不可用。
- socket 路径必须从 Linux rootfs 内部可达。Bannerlator 把应用的 files 目录和它的运行时目录按各自原有路径绑定进会话，因此位于这两者之下的任何 socket 在两侧都能原样工作。
- 助手可以服务任意数量的游戏进程，每个进程各得自己的流。它在 `SIGTERM` 时退出；某个游戏退出只会拆掉那个游戏的流。

## 告诉驱动助手在哪里

```
BANNER_AUDIO_DIRECT_RELAY=<the same socket path>
```

未设置时，驱动会寻找 `$XDG_RUNTIME_DIR/directaudio-relay`。

## 其余一切保持不变

所有 `BANNER_AUDIO_DIRECT_*` 开关的含义与它们在进程内构建中的含义相同：`_PERF`、`_ADAPTIVE`、`_DECAY`、`_MS`、`_MAXMS`、`_BF`、`_MBF`、`_EXCLUSIVE`、`_WATCHDOG`、`_STALL_MS`、`_DECAY_*`、`_LOG`、`_PERIOD_MS`、`_MINPERIOD_MS`，以及实时配置信箱 `_RUNTIME`（助手监视这个文件；它必须是一个助手在 Android 一侧读得到的路径）。**`BANNER_AUDIO_DIRECT_MIC=1`** 与从前完全一样地暴露采集端点：游戏连接时助手打开输入流（`VOICE_COMMUNICATION` 预设），并在游戏第一次开始采集时将其激活。

有一处不同：多了一次交接，也就是这个环形缓冲。助手的回调从中拉取一段数据，所以在预设所控制的 AAudio 缓冲之外，驱动还排着两段数据（在 192 帧的设备上约 8 ms），如果某次回调发现环形缓冲不足，助手会提高这个数量。`get_latency` 报告的是两者之和，因此按它来确定自己缓冲大小的游戏看到的仍是真实情况。

## 验证它在运行

- 助手一侧：`logcat -s DA-Relay:I` 会先显示 `hello from <exe>`，然后是 `open: buffer ... ring target ...`，麦克风则是 `capture open` / `capture start`。
- 游戏一侧：会话日志中带有 `DirectAudio: relay: connected - ...`。
- 游戏进程的 `/proc/<pid>/maps` 中能看到 `winedirectaudio.so`，而**没有** `libaaudio.so`（它在助手的 maps 里）。AudioFlinger 的 track 归助手的 pid 所有。
