# Flatpak 和商店

两个功能都是 beta 且默认关闭。Setup → Launcher → "Linux apps (beta)" 有一个用于 Flathub 商店的开关，它显示商店侧栏项以及 Desktop 页面上的商店应用；还有一个用于 AppImages 的开关，它显示 AppImages 部分。关闭任一个都会隐藏它；已安装的应用保持已安装，并保留在 Linux 桌面的菜单中。

商店侧栏部分使用 Flatpak 将来自 Flathub 的应用安装到 Linux 运行时中。只列出 ARM64 构建。所有内容都位于 `/root/.local/share/flatpak` 的单个每用户安装中，因此不需要 Flatpak 的系统助手或 polkit。

## 将 Flatpak 放入运行时

`bannerlator-flatpak-setup` 在 proot 的假 root（`-0`）下运行，因为 pacman 和 pacman-key 拒绝任何其他 uid。运行时的 pacman 数据库只列出基础镜像。桌面和模拟器包被解包覆盖在其上而没有注册。因此，普通的 `pacman -S flatpak` 会重新安装约一百个包，其中包含 Mesa，覆盖运行时的 KGSL Turnip 构建，并运行镜像中的每个 hook，包括 mkinitcpio。所以 pacman 使用自己在 `/var/cache/droiddeck-flatpak` 下的数据库，只下载 Flatpak 添加到此镜像的九个包，并根据 Arch Linux ARM keyring 检查它们的签名。脚本然后在不运行 hooks 的情况下解包它们，并检查二进制文件链接了什么。Desktop 包通常会带来的库（PyGObject、json-glib、fuse3 等）也会被获取，但仅在它们的文件缺失时。Flathub 从脚本中携带的其 `.flatpakrepo` 副本添加为每用户 remote，因此该步骤不需要网络。每个商店命令都将其输出写入 `Download/DroidDeck/flatpak-<verb>.log`，紧邻会话日志。设置脚本还会将 Flatpak 自己的错误文本放入应用显示的失败消息中。

## 没有命名空间的 bubblewrap

Android 不给应用用户命名空间，因此 `bwrap` 无法工作。Flatpak 改为指向 `bannerlator-bwrap`（`FLATPAK_BWRAP`，由 `bannerlator-session` 和商店的命令导出）。它读取 bwrap 的命令行，包括 `--args` fd 以及 `--file` 和 `--bind-data` 载荷，然后做两件事之一：

- 一个不重映射任何东西的沙箱直接运行。Flatpak 的安装触发器（`--ro-bind / /`）和 D-Bus 代理（每个顶级目录绑定到自身）就是这种情况，它们保留 Flatpak 交给它们的文件描述符。
- 其他任何东西都通过抽象 socket `com.droiddeck.launcher.bwrap` 向应用描述，该 socket 只服务应用自己的 uid。`BwrapSpawner` 在 `cache/bwrap/<n>` 下构建树：一个用于顶级链接、目录和文件的根目录，用于 bind 内 `--tmpfs` 的临时目录，以及每个 `--bind` 通过会话自己的绑定从 guest 路径转换为 host 路径。然后它将其作为自己的 proot 在会话的 proot 旁边启动。输出、pid（用于 `--info-fd`）和退出状态流回，替身退出会杀死沙箱。

嵌套在会话 proot 中的 proot 也可以工作，但每个系统调用随后都会经过两个 tracer，在程序启动时大约慢三十倍。

spawner 还添加 rootfs 给自己程序的东西：

- **GPU。** Flathub 的 Mesa 只为 DRM 提供 Turnip，而 Android 上的 Adreno 是 KGSL。运行时自己的 `libvulkan_freedreno.so` 与 Freedesktop 运行时缺少的三个库（`libdisplay-info`、SPIRV-Tools）一起绑定进来。Vulkan 使用它，Mesa 的 GL 通过 Zink（`MESA_LOADER_DRIVER_OVERRIDE=zink`）在它上面运行。对于绘制到由 pixman 合成的桌面的应用，这会跳过，因为 Zink 无法呈现。
- **控制器。** rootfs 的 `/etc/ld.so.preload` 中的库（会话 shim 和 fake evdev 读取器）通过 `LD_PRELOAD` 以及会话的 `dev` 目录进入。
- **浏览器。** Firefox 的子沙箱被关闭（`MOZ_DISABLE_*_SANDBOX`），Chromium 和 Electron 应用使用 zypak 的 mimic 策略（`ZYPAK_ZYGOTE_STRATEGY_SPAWN=0`）。Flatpak portal 的 Spawn 拒绝 `/proc/<pid>/root` 中没有 `.flatpak-info` 的调用者，而 proot 沙箱的 root 是 host 的。
- **CPU。** `/proc/cpuinfo` 不带核心的部件号。Snapdragon 的 ARMv9 核心向 LLVM 暗示 SVE2，Qualcomm 关闭了 SVE，而 llvmpipe 的第一个 shader 因 SIGILL 死亡。

## 运行应用

前端将应用作为 `bannerlator-flatpak-run <app-id>` 的 run 模式会话启动，在 gamescope 下全屏。在 gamescope 下，它传递 `--nosocket=wayland --socket=x11` 并设置 `XDG_SESSION_TYPE=x11`。否则 Flatpak 会找到应用合成器的 `wayland-0`，窗口会在 gamescope 后面打开。gamescope 自己的 Wayland socket 不是替代方案：Chromium on Wayland 会向它命名的渲染节点询问 DRM 版本，而 KGSL 无法给出，于是中止。启动器还会在没有 session bus 时启动一个。在 Linux 桌面上，应用导出的菜单项出现在 LXQt 菜单中。当桌面由 pixman 合成时，游戏像 rootfs 自己的游戏一样通过 `droiddeck-gpu` 包装。

## 商店

`FlathubApi` 读取 flathub.org 的公共 API：collections、过滤为 `aarch64` 的搜索以及 AppStream 详情。`bannerlator-flatpak` 通过 PyGObject 驱动 libflatpak，并每行打印一个 JSON 对象（`op`、`progress`、`error`、`done`）。`FlatpakManager` 将这些行转换为商店的进度条。商店命令在它们自己的 proot 中运行，`OrphanReaper` 在会话启动时放过它们。

## AppImages

Desktop 页面上的 "Add AppImage" 从存储导入一个 ARM64、type 2 AppImage。`AppImageManager` 首先检查其 ELF 头，因此 x86_64 镜像会被拒绝并给出说明消息。然后它复制镜像进来，因为共享存储是 noexec，并将其一次性解压到 `/opt/appimages/user/<id>/app`。proot 没有 FUSE，每次启动都解压会每次花费整个镜像。名称、注释和图标来自镜像自己的 desktop entry。菜单项进入 `/usr/local/share/applications`，桌面的 GPU wrapper 会在那里拾取它。

`bannerlator-appimage-run` 启动它：

- `APPIMAGE` 保持未设置，因此应用不会提供将自己添加到菜单或就地更新。
- Firefox 分支获得与 Flatpak 相同的沙箱开关。
- Electron 应用（旁边有 `chrome-sandbox`）获得 `--no-sandbox` 和 X11。
- 当会话没有 session bus 时启动一个。
- 启动器在镜像中的任何东西仍在运行时等待，因为 Electron 应用会自行重新启动。

已知会失败：

- Obsidian 自己的 AppImage 在启动后陷入 trap（SIGTRAP）。它的 Flatpak 可以工作。
- Zen 的 AppImage 在 Firefox 的启动 GPU 探测中挂起。Firefox Flatpak 可以工作。

## x86_64 应用

Flathub 列出仅 x86_64 的应用（Heroic、Discord、Spotify、Steam），商店隐藏它们。运行它们需要在沙箱中有 x86 模拟器。Arch Linux ARM 中没有 FEX 或 box64 包；Valve 的 ARM64 Steam 可以安装其 FEX 兼容性工具，但只能通过 Steam 客户端。如果有 FEX 二进制可用，路径将是：

1. `flatpak install --arch=x86_64`。Flatpak 接受它，而 x86 运行时也是 FEX 所需的 x86 root。
2. `BwrapSpawner` 通过一个运行 FEXInterpreter 的小 wrapper，用 `proot -q` 启动那些沙箱。`-q` 传递 QEMU 风格参数。
3. GPU 驱动需要 FEX 的 thunks，或者 Mesa 在 CPU 上模拟运行。

x86 AppImages 还需要一个 x86 rootfs，用于它们不捆绑的库。