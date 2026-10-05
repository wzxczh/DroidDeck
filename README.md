<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/droiddeck-banner-dark.svg">
    <img alt="DroidDeck" src="artwork/droiddeck-banner-light.svg" width="100%">
  </picture>
</p>

DroidDeck 将 SteamOS 体验带到 Android：在你的 Adreno 掌机上以 Big Picture 运行 Valve 的 Steam 客户端，Windows 游戏通过 Valve 的 ARM64 Proton 运行。

<p align="center"><a href="https://discord.gg/JRGAvawjsm"><img src="https://img.shields.io/badge/Discord-Join%20the%20community-5865F2?logo=discord&logoColor=white" alt="加入 DroidDeck Discord"></a></p>

<p align="center"><img src="docs/releases/media/0.2.0/launch-into-steam.gif" width="80%" alt="在 Android 主屏幕上点击 DroidDeck 并进入 Steam Big Picture"></p>

> 注意：DroidDeck 没有独立的官方网站。不要点击任何自称 DroidDeck 团队的网站上的下载链接。

## 要求和安装

在受支持的 Adreno 设备（730 或更新，或 8xx）上使用 Android 9 或更新版本。不支持 Mali、Xclipse、PowerVR 和 Adreno 710。无需 root。为运行时预留约 3 GB，为桌面和模拟器再预留 1.1 GB。从 [发布](https://github.com/Droid-Deck/DroidDeck/releases) 安装 APK，安装 Linux 运行时，然后点 **游玩** 并登录。Steam 在首次启动时下载。安装 **桌面与应用** 以使用桌面和模拟器。**商店** 使用 Flatpak 从 Flathub 安装 Linux 应用和游戏（ARM64 构建），还可选择安装 AppImage 和设置脚本。

在 Steam 启动前，你必须在开发者选项中关闭 **限制子进程**。如果开发者设置中没有该选项（Android 12 和 13 设备），Steam 首次启动时会显示一个“修复”按钮，它会帮助自动完成设置过程。

## 社区

加入 [DroidDeck Discord](https://discord.gg/JRGAvawjsm) 获取帮助、Preview 构建和设备报告。Bug 报告发到其 **#bug-reports** 论坛；附上 `Download/DroidDeck/` 中的会话文件夹，这样日志也会一并带上。

## 构建

在安装了 Docker、Java 17、Android SDK/NDK 和 `zstd` 的情况下运行 `tools/build_local.sh`。它从 PulseAudio 13.0 构建 ARM64 音频接收器，并将它们打包到 APK 中，位于 `app/build/outputs/apk/release/app-release.apk`。将 `DROIDDECK_PA13_SOURCE_DIR` 设置为现有的 PulseAudio 13.0 源代码目录以跳过下载。要将 APK 安装到已连接的设备上，请运行 `tools/deploy_local.sh`。

## 限制

兼容性和性能因设备而异；硬件验证有限。桌面合成使用软件渲染。在 proot 下 Firefox 沙箱被削弱。诊断问题时请查看 `Download/DroidDeck/` 中的会话日志。

## 致谢和许可

GPL-3.0。运行时、shim、输入和控制器工作基于 WinNative 和 Bannerlator (maxjivi05)。LSFG 帧生成源自 Camille LaVey 与 [Eden](https://eden-emu.dev) 模拟器项目的工作，遵循 [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)，由 [@maxjivi05](https://github.com/maxjivi05) 移植到 WinNative 和 DroidDeck；它需要你自备 [Lossless Scaling](https://store.steampowered.com/app/993090/)，且不附带其任何着色器。x86 AppImage，以及任何自身运行时无法解包的 AppImage，使用 VHSgunzo (MIT) 的 [uruntime](https://github.com/VHSgunzo/uruntime) 解包，随附未修改的副本及其许可证。见 [LICENSE](LICENSE)。Steam 和 Proton 属于 Valve Corporation；此项目与 Valve 无关联。
