# DroidDeck - 进度日志

DroidDeck 应用（`com.droiddeck.launcher`；在 2026-09-23 之前称为 SteamDeck，`com.steamdeck.launcher`——该日期之前的条目保留旧名称）的持续工程日志。最新状态在前，然后是时间线，然后是经验教训和待办事项。它是 README（应用*做什么*）和 `docs/releases/`（每个版本说了什么）的配套文档——这里记录的是*它如何走到这里以及当前处于什么状态*。

---

## 2026-09-29 - `feat/controller-input`：按 SteamOS 的方式处理手柄

在 AYN Thor 上设备测试（Katamari 在 Proton Experimental ARM64 下）。

- **为什么**：客户端把手柄读作 Xbox 360 控制器，而游戏读取*同一个*节点，但带有 Steam Input 的虚拟身份（28de:11ff），因为客户端自己的虚拟手柄需要 `/dev/uinput`，却从未出现。因此 Steam Input 的布局从未到达游戏，QAM 是一个定时的 Guide+A 组合键。InputPlumber 本身无法在沙箱中运行（root、uinput、uhid、udev、D-Bus），所以它所提供的东西改在 interposer 中模拟。
- **`/dev/uinput` 替身**（`FAKE_EVDEV_UINPUT=1`，libfakeinput）：客户端创建的手柄变成 `/dev/input/event16+`，由应用自有格式的环形缓冲区支撑，带有客户端的名称、ids 和 bits；其写入就是该环形缓冲区的事件。其他设备（虚拟键盘和鼠标）被接受并丢弃。`inputudev.c` 从每个节点旁边的 `.uevent` 文件向 Wine 的 HID 总线描述这些节点，并且当某个节点存在时，把应用的手柄描述为它们本来的 Xbox 360 手柄，因此 Wine 只读取 Steam Input 的输出。已验证：客户端创建 “Microsoft X-Box 360 pad 0”，在游戏启动时重新创建它，游戏绑定它（`Controller 0 uses xinput : true`）。
- **Steam Deck 控制器**（`FAKE_EVDEV_DECK=1`，Steam 会话）：`/dev/hidraw16`，通过 `SteamDeckPad.kt` 绑定进的 sysfs 树找到（usb_device → interface 2 → hid 28de:1205 → hidraw），每 4 ms 从 ring 0 流式传输 Deck 的 64 字节状态报告，并像 InputPlumber 那样应答 feature reports。systemd 261 的 libudev 拒绝不在 sysfs 上的 syspath，因此 `fstatfs`/`statfs` 为该树报告 sysfs。它开启时，应用的 evdev 节点被撤回，只有 `steam` 进程能看到 Deck。QAM 是一个真实按键（快照 bit 11）。已验证：客户端列出一个 Steam Deck Controller（V1 HID protocol）并运行其握手（0x83、0xAE、0x81/0x87 lizard off、0x8F）。
- **设置**：Steam 页面 → Touch & controls → **Controller**：*Steam Deck controller*（默认）或 *Xbox 360 controller*（早期版本的手柄，QAM 通过 Guide+A；游戏仍然获得 Steam Input 的虚拟手柄）。调试开关：`droiddeck-no-uinput`（回到 28de:11ff 伪装）、`droiddeck-no-deck-pad`（强制 Xbox 360 手柄）。
- **陀螺仪**：`PadMotion.kt` 将手持设备自身的陀螺仪和加速度计（4 ms 采样，会话在屏幕上时）送入 ring 0 事件之后的 IMU 块，先转向屏幕，然后转向 Deck 的轴和单位；Deck 报告携带它们。已验证：Steam 的 Gyro Calibration 页面随 Thor 移动（sh5001 IMU）；静止时加速度计读数为 1 g。
- **第二屏上的 Deck 握把 + 触控板**（`DeckControlsPanel.kt`，在手柄是 Deck 控制器时提供）：四个握把的标签页（2x2）、任一带点击条的触控板，以及两个触控板在握把上方排成一行；触控板上的第二根手指会点击它。OLED 为真黑色。握把、手柄位置、触摸、点击和压力通过 ring 0 事件之后的同一个块（`DeckControls.kt`）与陀螺仪一起进入 Deck 报告。
- **尚未完成**：蓝牙手柄自己的 IMU（DualSense）；游戏在虚拟手柄上的震动直接送到振动器，而不是通过客户端返回。
- **已检查的先前技术**：WinNative 和 Bannerlator 从未模拟 uinput 或 hidraw；Bannerlator 的 `-steamdeck` 模式止步于“虚拟手柄从未到达”，这就是缺失的 uinput。

## 2026-09-25 - main `8cdedbe`：开箱即用的 melonDS、Big Picture 中的触摸、日志隐私、Decky

状态：**main = `8cdedbe`**（PR #22 合并）。除非另有标记，在 AYANEO Pocket FIT 上设备测试（重新打包的测试构建）。gamescope 现在从 release **`gamescope-3.16.29-p3`** 暂存。

- **PR #18 → `9369dc3`**（`feat/melonds-preset`）——melonDS 和会话：
  - **melonDS 预设**（`bannerlator-pad-defaults`）：上屏尽可能大，下屏单独在旁边，居中（Horizontal + Emphasize top；Hybrid 重复上屏），锐利像素；从前端全屏（`-f`）以面板自身分辨率；guide 按钮切换全屏，退出到一个 1x 宽的 16:9 窗口，其菜单由 gamescope 拉伸到可用大小；DS/DSi BIOS、固件和 NAND 在 `ROMs/nds/{bios,firmware}` 中找到（BIOS 按校验和）。现有安装已迁移一次。
  - **桌面**：`QT_QPA_PLATFORM="wayland;xcb"`（melonDS AppImage 只附带 xcb，并且会立即退出）；一条 labwc 窗口规则让 melonDS 以 800x600 打开，居中。
  - **HUD** 跟随游戏窗口，而不是 gamescope 的 1x1 光标或 Qt 菜单（在 60 时读到 0 fps）。
  - **光标**：在手柄 / 屏幕控件输入时隐藏（Bannerlator 的 1.2 s 规则），并显示程序自己的形状——labwc 的调整大小箭头、I 型光标、隐藏（Bannerlator 的 `wl_pointer.set_cursor` 移植；这里新增：labwc 的 GPU 光标是一个 UBWC dma-buf，通过 `vkp_image_readback` 读回）。
  - **Steam 桌面切换**：“Steam Desktop UI” 打开桌面，并在其中运行 Steam 的桌面客户端（在 gamescope 下客户端无论怎样都会选择 Big Picture）；抽屉的 **DESKTOP** 按钮从 Big Picture 执行此操作；重新启动会原地重启 singleTop activity（从它调用 `startActivity` 被吞掉）。Big Picture 自己的 Power → Switch to Desktop 仍然挂起：它在 `steamos-session-select` 回退之前等待 SteamOS Manager（已包含 stub；session bus 没有帮助——试过并回滚）。
- **PR #19 → `ce0da01`**（`fix/log-privacy`）——会话日志中没有凭据或身份：设备的公共地址（客户端的 IPv6 检查每次会话约记录 20 次 “external address”），`network.txt` 地址按其类型记录，MAC 被掩码，每个 Steam AccountName/PersonaName（普通账户名在 UI 日志的登录行中泄露），并且每个文本文件在会话结束时和 share-logs zip 中被清理。已验证：83 个会话文件夹中 0 次命中。
- **PR #16 → `5270574`**（Decky Loader，Kurt）——合并时 Steam CEF 调试标记绑定到 supervisor 开关（它是在安装时创建的；在 Android 上任何应用都能访问 127.0.0.1:8080——已从另一个 uid 证明）。Decky 自己的更新器仍然指向官方项目（那里没有 ARM64 构建）。
- **PR #20 → `036317f`**（Kurt）——本地构建携带 CI 载荷（NDK proot、打过补丁的 gamescope）。
- **PR #22 → `8cdedbe`**（`feat/steam-touch`）——Big Picture 中的触摸：gamescope `0110` 在嵌套后端绑定 `wl_touch`，并送入 wlserver 的触摸路径（Big Picture 设置 Passthrough：点击和滑动行，如同 Deck）；`0111` 让嵌套指针在 Passthrough 中 warp（触控板模式：悬停和点击即点击可用）；合成器的指针回退不再因丢失 finger-up 而卡住。
- **设计中，未构建**：抽屉的页面点作为 QAM 风格图标（Display / Controls / Settings），选中的那个在一行中“向前走”；模拟 `droiddeck-drawer-tabs-preview.html`。

## 2026-09-24（下午）- main `94f9935`：PS1、控制器焦点、自有签名密钥、CI 加固

状态：**main = `94f9935`**（PR #41 合并），main 构建运行 36036219740——第一个用 DroidDeck 自己的密钥签名（`droiddeck-signed-standard`）。自 0.1.6 以来没有 tag、没有 release。除非另有标记，在 AYANEO Pocket FIT 上设备测试。**main 现在受保护**（见下）。

- **PR #34 → `63d7051`，PR #38 → `c7998a8`**（`feat/duckstation`、`feat/duckstation-play`）——PS1 和前端：
  - **像 ARMSX2 一样的 DuckStation**：玩家 1 在手柄上，BIOS 来自 `ROMs/psx/bios`，`psx` 作为游戏列表，Vulkan，guide → 暂停菜单，从 rail 使用 `-batch -bigpicture -fullscreen`。Crash Bandicoot 和 Tekken 3 从桌面和前端都以 60 fps 运行。
  - **一键游玩**：DuckStation 和 ARMSX2 的首次设置已替用户回答（`SetupWizardIncomplete = false`）；ARMSX2 自己命名其 BIOS dump（`[Filenames] BIOS`，先美国 dump，每次启动前重新检查）——PCSX2 需要命名文件，DuckStation 会在其文件夹中找到。DuckStation 不再警告无法抑制屏幕保护程序（这里没有 `org.freedesktop.ScreenSaver`）。
  - **每张碟一个 tile**：`.cue`/`.gdi`/`.m3u` 隐藏它命名的文件（Tekken 3 显示 4 次，每个 `(Track N).bin` 一次）；`bios`/`firmware` 文件夹被跳过（BIOS 显示为游戏）。
  - **控制器焦点**：rail 项在打开时被描边（应用以触摸模式启动，此时 clickables 拒绝焦点——应用现在先请求键盘模式）；Right 在其主按钮上进入页面，从页面边缘 Left 返回 rail，再次 Right 返回上次使用的 tile 或按钮（显式跟踪——`FocusRequester.saveFocusedChild` 恢复到网格，而不是 tile）；从 tile 打开的页面在主按钮上获得焦点（焦点过去随 tile 消失，下一次按键落在 Steam 上）；从页面按钮 Down 进入第一个 tile。
- **PR #41 → `94f9935`**（`feat/release-signing`）——签名、文件访问、公开前的加固：
  - **自有签名密钥**（RSA 4096，由 `tools/release/make-release-key.sh` 在设备上生成，摘要在 `keystore/release-signer.sha256` 中为 `b241ea7d…5d3f0`）。在此之前每个构建——包括 0.1.6——都用公开 AOSP testkey 签名，所以任何人都能签一个“更新”安装覆盖 DroidDeck。
  - **移交**（APK Signature Scheme v3 轮换）：v1/v2 由 testkey 签，v3 由我们的密钥签，带 lineage testkey → ours，testkey rollback 关闭，`--rotation-min-sdk-version 28`。在 FIT 上用一次性密钥和包证明：一个 0.1.6 风格的安装会更新并保留其数据；仅用 testkey 签名的 apk 被拒绝；用攻击者自己的从 testkey 开始的 lineage 签名的 apk 也被拒绝；仅用我们的密钥签名的 apk（MT Manager / APK Tool M 制作的东西）可安装在移交安装之上和全新安装上，但不能安装在仅 testkey 的安装之上。
  - **CI**：`sign.yml`（可复用，每个包一个 matrix job）在每次非 pull-request 构建后运行（环境 `signing-main` 在 main 上，`signing-branch` 在其他地方；密钥是环境 secret）。`release.yml`（dispatch，version）将 main 提交的精确 Build APK artifact 签为四个包：`com.droiddeck.launcher`、`com.tencent.ig`、`com.antutu.benchmark.full`、`com.ludashi.benchmark`（`tools/release/variants.txt`；manifest 重命名也覆盖 `.documents` 和 `.home` task affinity）。
  - **Documents provider**（`files/AppFilesProvider.kt`）：Android 文件选择器中的应用数据文件夹（“Open from” → DroidDeck，按包标题），限制在数据文件夹内（`../` id 被拒绝，指向其外的链接被隐藏），删除绝不跟随链接。
  - **加固**：pull requests 使用 `pull_request` 构建，而不是 `pull_request_target`（一个 pull request 可以写入 main 的签名构建恢复的缓存）；如果提交了私钥文件，构建失败；`.gitignore` 覆盖密钥文件；**main 受保护**——pull request + 通过 `build` + 一次 approval，无 force-push 或删除，owner 可为其自己的 pull request 覆盖。公开前扫描历史：无秘密。
  - Max 和 Kurt 获得密钥（jks、p12、pk8 + x509.pem、lineage）以及一个密码和三份简明指南（MT Manager / APK Tool M 签名、移交、pull requests / 测试构建 / releases 如何工作）。
- **今天还合并了**（Kurt）：#20 Back 快捷键（会话菜单、Steam QAM）、#24 启动器上的构建身份、#33 每次启动的 Android 显示选择、#35 CI 构建清洁快照、#37 分体 D 标志、#40 会话抽屉中按住摇杆导航更快；（The412Banner）#32 拉伸游戏填满屏幕、Quake 引擎游戏以会话尺寸开窗。

## 2026-09-24 - main `08a6769`：GPU 桌面、像 PS3 的模拟器、ARMSX2、游戏封面

状态：**main = `08a6769`**（PR #29 合并），main 构建运行 35983098806，暂存为 `Download/DroidDeck-main-08a6769(-Genshin).apk`。自 0.1.6 以来没有 tag、没有 release。**除非另有标记，在 AYANEO Pocket FIT（Adreno 750）上设备测试。**

- **PR #25 → `f6f7470`**（`feat/desktop-gpu`）——GPU 上的桌面：
  - **打过补丁的 wlroots**（`tools/wlroots`，release `wlroots-0.20.2-p1`，暂存于 `/usr/local/lib/droiddeck-wlroots`）：为 KGSL 替身节点提供 Vulkan DMA-BUF 分配器，该节点不是 DRM 设备——原版 wlroots 在 `drmModeCreateLease` 失败 → “unable to create allocator”；linux-dmabuf 在此类节点上跳过其 GEM 导入检查。labwc 的 **vulkan** 和 **gles2** 渲染器可启动；**vulkan 是默认**。
  - 合成器：toplevel 的**初始 commit 用 configure 应答**（labwc on vulkan 永远等待——wlroots 在该 commit 后将其 toplevel 移到私有队列）；**`xdg_positioner` / `xdg_popup` / `wl_output` 请求不再中止**应用（NULL 实现；labwc 旁边的 gamescope 曾把应用带崩）；没有 Wine 桌面时，新窗口接管按键。
  - 回退：labwc 退出或在 20 s 内不应答其 socket → pixman，按渲染器 + wlroots md5 + 驱动记住（`~/.droiddeck-renderer-failed`，通过选择渲染器清除）。在 pixman 上，Game/Emulator 菜单项通过 `droiddeck-gpu` 运行（它们自己的 gamescope 在 wayland-0 上）。
  - 模拟器：在 Steam 外手柄是普通 Xbox 360 控制器（SDL 在 Steam 外忽略 Steam 的虚拟手柄）；`bannerlator-pad-defaults` 为每个模拟器播种 Player 1（源验证格式），并且绝不覆盖用户配置；大核绑定（`program_cores`，选择时游戏核心）；rail 程序使用桌面的 Linux 驱动（一个 shader cache）；RPCS3 一次性移动到 `Async Recompiler (multi-threaded)`（每次启动不再有 6650-variant interpreter 预编译）。
  - **PC 键盘**（抽屉：Keyboard [Hardware] [Android]）：Esc、F1-F12、编辑块、修饰键（sticky）、方向键；真实 evdev 按键；屏幕的 47%。
  - 已证明：Vulkan 和 gles2 桌面、Firefox on Vulkan、RPCS3 God of War II HD 在窗口中以及从 rail（手柄绑定、无预编译、核心 2-7）、pixman 回退路径、Steam 会话。
- **PR #26 → `6980428`**（`feat/armsx2`）——**PS2 是 ARMSX2**（PCSX2 fork，带 ARM64 重编译器，GPL-3.0）。上游 PCSX2 只有 `pcsx2/arm64/RecStubs.cpp`：NFS Underground 2 以 17 fps 运行，一个核心 100%；ARMSX2 在 proot 下以 **59.9 fps / 100%** 运行它，所有重编译器 + fastmem 开启。PCSX2 离开前端和目录。
- **PR #29 → `08a6769`**（`feat/armsx2-polish`）：
  - 从 rail 运行 ARMSX2：`-batch -bigpicture -fullscreen`；guide → 暂停菜单 → Close Game 返回应用。首次运行：PCSX2 设置 + 记忆卡复制一次；新玩家获得 ARMSX2 的设置，BIOS 文件夹在 `ROMs/ps2/bios`（或 `ps2/firmware`），`ps2` 作为游戏列表，Vulkan，控制器在 Player 1。用户自带 BIOS 和游戏。
  - 目录 `pages`——每个内存页大小一个文件；ARMSX2 4K + 16K。
  - 屏幕控件：rail 游戏和 Steam 在无控制器时 Auto，桌面上关闭；抽屉中处处有设置；rail 游戏的抽屉以模拟器为标题。
  - 前端：Back/B 后退一级（模拟器 → Desktop，游戏 → 模拟器），而不是离开应用；游戏最多找到 3 层文件夹深（`ps2/games/<game>/`）；**盒装封面**（libretro-thumbnails 按名称精确/宽松，xlenore/ps2-covers 按 PS2 serial，GameTDB 按 GC/Wii ID，PS3 碟自己的 `ICON0.PNG`），保存在 `files/covers`；宽封面完整显示在模糊副本上；无 dump 标签的标题。
- **winlator-contents**（目录）：ARMSX2 `nightly-20260923-92aa2174da` 镜像（`armsx2.AppImage` 4K、`armsx2-16k.AppImage`），PCSX2 从 `desktop.json` 移除（PRs #12、#13）；镜像工作流只列出正在添加的文件（重新运行会重新获取并覆盖每个列出的文件，而 DuckStation 的上游链接会移动）。
- **尚未设备游玩**：DuckStation、Dolphin、Cemu、melonDS、PPSSPP、RetroArch（配置文件和封面已从其来源写入）。已知缺口：键盘 Esc 在 rail 游戏中不会打开 ARMSX2 的暂停菜单（guide 会）。

## 2026-09-23（晚上）- 重命名 SteamDeck → DroidDeck

- **应用名、应用 ID 和代码包**：DroidDeck、`com.droiddeck.launcher`（曾是 `com.steamdeck.launcher`），源代码在 `app/src/main/java/com/droiddeck/launcher` 下，68 个 JNI 函数随之重命名。新的应用 ID 意味着 DroidDeck **安装在 SteamDeck 旁边**，而不是覆盖它：运行时、Steam 登录和设置都从新开始。
- **下载**：会话日志到 `Download/DroidDeck/`；开关文件是 `droiddeck-env`、`-osc`、`-driver`、`-tu-debug`、`-no-pad`、`-pad-log`、`-no-hud`、`-wlr-renderer`。旧的 `steamdeck-*` 名称不再读取。
- **运行时内部**：`droiddeck-desktop`、Firefox 的 `droiddeck.js`、`~/.droiddeck-desktop-debug`、`~/.cache/droiddeck`、快捷方式标签 `droiddeck-app`、`X-DroidDeck-AppId`、`droiddeck-rumble` socket。“Desktop installed” 现在测试 `usr/bin/labwc`（它测试的是启动器，而应用自己暂存启动器）。
- **有意保留**——Valve 的 Steam Deck：`-steamdeck` 标志、`steamdeck_publicbeta`/`steamdeck_stable`、“Steam Deck mode”、`steamdeck-packages.steamos.cloud`。以及托管桌面包内的名称：`steamdeck.png`、`steamdeck-steam(.desktop)`、`steamdeck-bigpicture.desktop`（重命名它们意味着重新托管它）。
- CI artifact `droiddeck-apk`；删除了一个误提交的 `__pycache__/*.pyc`。

## 2026-09-23（下午）- main `90c7574`：首次运行引导、Desktop & apps 页面、4:3 显示

状态：**main = `90c7574`**（PR #14 合并），main 构建运行 35895701784。无 tag、无 release；下一版本仍未定。**以下除音频默认值（Kurt，AYN Thor）外均未设备测试。**

- **PR #11 → `f79dc2d`**（`fix/directaudio-default`）：Steam 客户端音频默认回到 0.1.5 经典 sink（`module-aaudio-classic-sink.so`，与 0.1.5 的 arm64 模块逐字节相同）；DirectAudio 是 Steam cog 中的一个选择（“Steam client audio”，pref `clientDirectAudio`，默认关闭）。游戏默认保持 DirectAudio 开启；麦克风默认开启，RECORD_AUDIO 询问一次。旧的 armhf sink 从 bundle 中移除；bundle 在其内容变化时重新解包（stamp = BUNDLE_STAMP + pulseaudio.tzst 的 CRC32）。Relay 记录一行 heartbeat；Setup › Session logs › Share latest logs。Kurt 在 Thor 上：经典“工作完美”。
- **PR #12 → `0ddb0be`**（`feat/play-installs-runtime`）：
  - `586c9c2` Play 和 Desktop UI 在无运行时下启用；会话的加载屏幕下载、检查和解包它（“downloading the Linux runtime · 332 of 791 MB”），然后启动会话。失败会在加载屏幕上以原因结束。
  - `f437957` Desktop 做同样的事：如果缺少运行时，然后从 `desktop.json` 获取 `desktop` 包（426 MB），然后 LXQt。Play、Desktop UI 和 Desktop 都在任何下载前给出非 Adreno 警告（`MainActivity.startSession`）。
  - `cbd3bc9` Desktop & apps 是窗格中的页面（pageKey `apps`），不是对话框：每个 tier 一个 rail 风格下拉，带 installed/total 胶囊（Native ARM64 打开），每个包 Install/Remove，以及正在安装的包的步骤行和进度条在其自己的行中。离开页面不会停止安装。
- **PR #14 → `90c7574`**（`fix/exact-panel-shape`），来自 4:3 手持设备（RP Nova）上的用户报告——“面板的形状”仍然给出 16:9：
  - 原因：`SessionActivity` 将显示尺寸设为 `maxOf(panel aspect, 16:9)`（可折叠设备保护），所以在 4:3 面板上两个选择都是 16:9 带黑边。
  - `9c47095` Shape › **Exactly this panel (4:3, 3:2…)**——面板自身宽高比，无下限。其他两个不变；可折叠设备仍默认 16:9。在 cog 和会话内菜单中（`SessionPrefs.shapeChoices`）。
  - `ad2f9f8` Resolution › **Custom…** 按模式（`customRes.<mode>`，320×240-3840×2160，偶数化），带 4:3 / 16:10 / 16:9 预设。它取代 cap 和 shape（Shape 变灰）；选择 cap 会清除它；设备报告列出它。不在会话内菜单中。
  - 注意：分支从 `cbd3bc9` 切出，不是 `0ddb0be`——同一棵树。
- 暂存：`SteamDeck-play-installs-runtime-586c9c2.apk`（`21460acd…`）、`…-f437957.apk`（`174e74a6…`）、`SteamDeck-apps-page-cbd3bc9.apk`（`c2cfbebe…`）、`SteamDeck-custom-res-ad2f9f8.apk`（`d02ee5ce…`，= main 的内容）。引导流程的 HTML 预览：`/sdcard/Download/SteamDeck-onboarding-preview.html`。
- CI 注意：分支 push 不构建（`build.yml` = main + PRs）；用 `gh workflow run build.yml --ref <branch>` 调度它。同一分支上较新的 dispatch 会取消较旧的运行。
- 未决：Kurt 的 PR #13 “Add Steam second-screen controls”（`steamdeck-second-screen`）——未审查；现在需要合并到 `90c7574`。
- **下一步：**（1）全新安装流程：卸载、安装、先按 Desktop（运行时 + desktop，约 1.2 GB），然后在无下载情况下 Play；（2）设备上的 Desktop & apps 页面（下拉、行内进度条、安装中 Back）；（3）Nova 报告者测试 Exactly this panel / Custom 960×720；（4）PR #13。

## 2026-09-23 - 分支 `feat/armada-and-lineage`

- Steam Deck 模式：仅 `-steamdeck`；SteamOS 助手 stub 从 apk 暂存到 `/usr/bin` 和 `/usr/bin/steamos-polkit-helpers` 下（那里缺失的 `steamos-update` 就是“Update Error”对话框；`jupiter-dock-updater --check` 回答 7，所以没有 dock firmware 行）；Deck 模式默认 `steamdeck_publicbeta` 通道（在 `publicbeta` 上每次启动都会重新安装客户端并丢失启动 URL）；Steam cog 中的客户端分支行。
- QAM 电池：`session/BatteryComponent.kt` 从 BatteryManager 写入 BAT0/BAT1，绑定到 `/sys/class/power_supply` 上。云存档在设备上检查：同步，没有损坏。
- 添加游戏：任意数量的 Games 文件夹，每个绑定到 `/root/Games` 下；在 ARM64 Proton 下客户端 `shortcuts.vdf` 中每个子文件夹一个快捷方式；每个游戏选择 exe；封面来自文件夹，否则来自 Steam 商店（capsule、header、hero、logo）复制到客户端网格。
- gamescope：运行时的 3.16.29 在 CI 中重建（`tools/gamescope`，release `gamescope-3.16.29-p1`），带 Armada 的 ARM64 客户端修复、realtime-queue 开关和 gamepad cursor 修复；暂存覆盖 `/usr/local/bin`。Steam 模式下游戏窗口强制全屏（Steam 菜单后游戏回到左上角）。
- 音频：自己的 PulseAudio sink 在 `tools/aaudio-sink` 中，由 CI 构建进 bundle。DirectAudio 开启时，客户端声音经过 relay 的共享 ring（`module-directaudio-sink`，sink 名为 DirectAudio）；关闭时，`module-aaudio-sink`。在 proot 内 AAudio 只给 20 ms 突发，relay 得到 4 ms。
- Winlator 代码重写或移除（SessionPart、HostEnvironment、HostProcess、PadState、cpp/framegen）；震动现在由 `session/RumbleComponent.kt` 提供。
- 前端架子整体布局，让 d-pad 能到达每个 tile。
- 托管运行时和桌面包未动。

##  0.1.5 发布 2026-09-23 03:17 - 已知良好点

- **Tag `0.1.5` = `d2f4a84`**（bump commit，versionCode 6），说明 `docs/releases/0.1.5.md` 在之后位于 main。私有 release [0.1.5](https://github.com/The412Banner/SteamDeck/releases/tag/0.1.5) 和公开 [`SteamDeck-0.1.5`](https://github.com/The412Banner/winlator-contents/releases/tag/SteamDeck-0.1.5) 在 winlator-contents 上，均为 Latest，asset `SteamDeck-0.1.5.apk` 21,093,959 字节，sha256 `85b42954…`，运行 35813465422。暂存为 `/sdcard/Download/SteamDeck-0.1.5.apk`。
- 自 0.1.4 以来加入的内容（58 commits）：motion 前端、设置作为带锚定菜单的页面、会话抽屉、三个主题（Paper 默认）和新图标、run-as-a-game + libpci 修复（Fold 上 Big Picture 117 fps）、客户端界面开关、FEX 预设、720p 默认、TZ、非 Adreno 门控、16 KB 应用库、两个屏幕摇杆 + bar-aware 布局、启动时折叠分类、Kurt 的 #3（Back 键）/ #4（图标）/ #5（本地构建助手）、pull-request CI + 贡献账本。
- 发布时未测试：摇杆/bar 布局、FEX 预设、Deck 模式、桌面 → Steam 移交、`perf:` 行。回滚：`git reset --hard 8e58e8e`（0.1.4）+ `SteamDeck-0.1.4.apk`。
- 切割后：winlator-contents 上的公开 release 得到私有标题，该 repo 的 README 增加 SteamDeck 部分（latest release、`linuxfs.json`、`desktop.json`）——每次切割都刷新两者。在 FIT 的客户端二进制上验证：没有 `-steampal` 标志；Deck 标志是 `-steamdeck` / `-steamos3`（Performance 页面上的 Deck 模式）、`-gamepadui`、`-steamos`。
- **接下来，按顺序：**（1）0.1.5 的设备通过——Fold 上游戏中的摇杆和 bar 布局、会话日志中的 `perf:` 行和 720p、一个 FEX 预设、桌面 → Steam 移交；（2）决定摇杆点击手势（双击或长按）；（3）Kurt 的 draft PR #1（Decky 安装器）在他标记 ready 时构建——它针对 main 编译，少两个游离 `.kotlin` 文件；（4）重建六个 PulseAudio 预构建为 16 KB 对齐；（5）合成器 Vulkan present 路径上可选 FIFO/mailbox 开关；（6）远离 Valve 标记的重命名仍未决（图标现在是“A”）。

## 2026-09-22（晚）- motion 前端 + run-as-a-game，分支 `feat/frontend-motion`（未合并）

- 前端围绕 motion 系统重建（`ui/FrontEndScreen.kt`，相同 state/actions API）：rail 的选择是一个在行间弹跳的胶囊；子列表展开并错开其子项；页面变化沉出并层叠进入，覆盖在选择封面的模糊冲洗上；tile 在焦点时抬起/环绕/发光并弹出播放徽章；启动按钮扫过光泽并在按下时压扁；会话 activity 在前端上方升起（`res/anim/session_*`）。时长遵循系统 animator scale。
- 会话现在作为游戏运行：manifest `appCategory=game` + `game_mode_config`（Performance mode 欢迎；OS FPS cap 和降采样被拒绝）、持续性能模式、面板在其尺寸下的最快模式、GameManager 游戏状态，以及合成器线程上的 ADPF hint session，用每个呈现帧的间隔喂入（`session/PerfMode.kt`、`session/PerfHints.kt`、`nativeCompositorTid`）。会话日志中的一行 `perf:` 说明设备上启用了什么。
- 应用库链接 16 KB 对齐。仍然仅 4 KB（预构建，需要重建）：libpulse、libpulseaudio、libpulsecommon-13.0、libpulsecore-13.0、libsndfile、libltdl。
- 会话显示现在默认上限 720p，客户端和桌面一样（`SessionPrefs.defaultResolutionCap`）；用户选择的 cap 仍然优先。cog 的列表标记每个模式的默认值。
- 构建：r1 `7ddfb57`（仅 motion），r2 `546c063`（+ run-as-a-game），r3 `d1ced87`（+ 客户端 720p），r4 `fb2a3d4`（+ 桌面 720p）——全部 CI 绿，均未设备测试。暂存为 `SteamDeck-motion-r1..r4.apk`；r4 有所有内容。
- r5 `1ba803b` + guest 中的 TZ；r6 `39272cf` Setup 折叠、“Linux desktop environment”、网格上方帮助链接（r4 已在 FIT 上看到运行）；r7 `af3f377` 无弹窗设置：cog 和 Performance 是窗格中的页面，每个值下方有锚定菜单，frame generation / logs / offline 在 rail 上原地打开。**r7 快进合并到 `main`（`af3f377`），main 的自动构建暂存为 `SteamDeck-r52.apk`；无 tag、无 release。**
- r8 `90ff23a`（仅分支，未合并）：会话抽屉以前端的装扮，带相同的行和菜单（Now / Next session / leave），FEXCore 预设从 Bannerlator 继承（`core/FexPreset`，Steam 设置页面 + 抽屉，FEX_* 进入 Steam 会话的环境；默认 = 之前的 FEX 默认），文件管理器和选择器锁定横屏。
- 合作者：`maxjivi05`（push）和 `xXJSONDeruloXx`（push，2026-09-22 邀请）在私有 repo 上。
- pull requests 的 CI（2026-09-23 早）：`Build APK` 在 `pull_request_target` 上针对 main 运行（冲突的 pull request 永远不会触发 `pull_request`），在 head 检出 pull request 并带完整历史，自行合并 main，并留下一条保持最新的评论：可合并 + 已构建（apk 在运行上），或冲突文件和 hunks，或来自保留 Gradle 日志的编译器错误。Drafts 等待。Fork pull requests 可未经批准运行（repo 设置）。用一次性 pull request（#2，已关闭）两种方式证明。`Contributions ledger` 通过 `.github/scripts/contributions.sh` 在 open/merge/close 时重写 README 的贡献者表，由 Actions bot 提交到 main。

##  检查点 2026-09-22 22:xx - 前端的已知良好点

- **`main` @ `36cde7d`**，APK `SteamDeck-r51.apk` 已暂存（sha `adb5acf5…`，运行 35761437514）=
  frontend-r6 + docs。内部相同 versionCode 5 / 0.1.4；**0.1.5 尚未切割**。
- **今晚在 Pocket FIT 上证明：** 前端渲染和导航（rail、封面、方形模拟器图标、焦点描边）；RPCS3 列出 Tomb Raider（子文件夹中的 ISO）和 God of War II HD（其 HDD）；God of War II HD 在 gamescope 下以约 40 fps 启动游戏
  （`session-20260922-133044`：`run: rpcs3.AppImage --no-gui …NPUA80491/USRDIR/EBOOT.BIN`，10 s 内屏幕上 399 帧）。
- **尚未证明：** 三次修复后的桌面→Steam 移交（r47–r50）；从 rail 运行 FlatOut（带 rungameid 的 Steam 会话）；Running tile；音量键；自动 SD 游戏存储（Install drive 下拉）；Steam desktop-UI 会话；Tomb Raider ISO 启动。
- **未决决定：** 在传播前将应用重命名远离 Valve 的标记（提供的候选：Linuxlator / Pocketscope / Portascope）；重命名 = label、图标文本、`applicationId`（全新安装 + 运行时重新下载）、`Download/SteamDeck/` 日志文件夹、repo、release-tag 模式。
- **回滚：** `git checkout 36cde7d`（或 0.1.4 tag `8e58e8e` 为最后 release），从 Downloads 重新安装 `SteamDeck-r51.apk` / `SteamDeck-0.1.4.apk`。
- 松散结尾：`ui/MainScreen.kt` 保留 ConfirmDialog/CreditsDialog/EmulatorHelpDialog，但其 `MainScreen`/tiles 已死；worktree `~/steamdeck-frontend` 仍存在（分支已合并）。

## 当前状态（2026-09-22，晚上）

- **`main` @ 前端合并**（`feat/frontend` 快进）：启动器主屏幕——Steam ▸ games、Desktop ▸ emulators ▸ games、Running tile、焦点描边。第一个从应用证明的模拟器游戏：God of War II HD 在 RPCS3 中，gamescope 下在 Pocket FIT 上约 40 fps。
- 自 0.1.4 在 main 上未发布：音量键到 Android；gamescope 下的模拟器；ROMs chip + ? explainer；sysmem copy + ENOSYS hint；自动游戏存储（一张卡 = 一个 Steam 库）；客户端的游戏在桌面上，带 `steam` shim；桌面→Steam 移交（从 FIT 日志修复三个原因：Surface detach race、被替换会话的退出结束新会话、共享日志文件夹）；前端。下一个 release = 0.1.5，一旦移交看到工作。

## 0.1.4 时的状态（2026-09-22）

- **最新 release：0.1.4** - 私有：https://github.com/The412Banner/SteamDeck/releases/tag/0.1.4
  · 公开：https://github.com/The412Banner/winlator-contents/releases/tag/SteamDeck-0.1.4
  `main` @ `8e58e8e`，CI 运行 35682844106，versionCode 5，APK sha256 `4b53ce6a…`（20,957,823 B）。
- **运行时：** `linuxfs-r9` 在 winlator-contents（790 MB），唯一剩下的运行时 release；桌面包来自 `steamdeck-desktop-r1`。应用在每次启动时将自己的脚本（`bannerlator-*`）、驱动、`bannerlator-netmanager` 和 DirectAudio 部分重写进已安装运行时，因此这些中的修复无需重新下载即可到达已安装运行时。
- **已发布功能集：** 在应用内 Wayland 合成器上 gamescope 下的原生 ARM64 Steam 客户端；一个 LXQt-on-labwc 桌面，带 Firefox 和一架模拟器；Proton 工具（GE / CachyOS）作为下载；图形驱动可按模式导入；DirectAudio + 麦克风；客户端/游戏核心掩码 + 四个会话开关；NetworkManager 替身（Max 的）；overlay 恢复；ROMs 文件夹 + Storage 绑定进会话 home；Bannerlator 的 File Manager；每个启动模式一个 cog（分辨率、形状、HDR10、驱动、触摸、OSC/音频、渲染器）；每个会话日志文件夹在崩溃后存活；残留进程清扫；帧生成（Win-FG、LSFG）。
- **验证级别：** 每个 release 都是 CI 绿并暂存到维护者设备。在硬件上证明（Pocket FIT / Fold）：登录、商店、安装 + 启动（FlatOut 144 Hz）、帧生成、控制器 + OSC、声音、桌面 + Firefox、会话中折叠、驱动导入、日志文件夹、ANR 修复、网络页面。**尚未证明：** 0.1.4 添加的一切（模拟器内的 ROMs 文件夹、File Manager、分辨率上限、HDR10、崩溃清扫、孤儿清扫、抽屉的 Steam 菜单）、DirectAudio *语音*、核心掩码的效果、离线启动、软键盘、任何带游戏的模拟器、Adreno 710。
- **未决现场报告（Thor Pro，8 Gen 2）：** 触控板模式点击不点击（代码发送点击；通过阅读未找到原因）；Ballionaire / Geometry Wars 中 1–2 分钟后崩溃（没有日志存活——0.1.4 的清扫会留下 `crash.log`）；Fold 的 10–20 fps 客户端菜单（Chromium → ANGLE → Zink 在实验性 A8xx Turnip 上）；Fold 崩溃循环（xalia ENOSYS 风暴——开关已发布，未测试）；FlatOut 在 Resume 后缩小（gamescope 强制 swapchain extent）。

## Release 流水线（每个版本如何切割）
1. 在 `main` 上工作，推送 → `Build APK` 运行 `assembleRelease`（AOSP test key，v1+v2+v3 由 CI 用 zipalign + apksigner 重新签名）→ artifact `steamdeck-apk`。开发构建保留最后 release 的 versionCode/versionName；release 在其自己的提交中在 `app/build.gradle` 提升两者。
2. 验证：`gh run view <id> --json conclusion`（永远不要相信运行列表的第一行——`workflow_dispatch` 运行会被并发取消，以利于 push 触发的运行），下载 artifact，sha256 它。
3. 暂存：`cp` 到 `/sdcard/Download/SteamDeck-rN.apk`（开发）或 `SteamDeck-X.Y.Z.apk`（release）。绝不 `pm install`——维护者安装。
4. 私有 release `X.Y.Z` 目标为**完整 40 字符 sha**（短 sha 被拒绝），说明来自会话草稿中的 draft，`--latest`。公开 release `SteamDeck-X.Y.Z` 在 winlator-contents 上，带 README 风格说明；每个版本一个公开 release。
5. README 账本、此日志、记忆。

## 时间线
- **2026-09-19 - 0.1 基础工作。** 在用户要求下从 Bannerlator 的 gamescope 运行时中抽出：一个屏幕，一个按钮。r2 在 Pocket FIT 上到达 Big Picture 登录。包重命名为 `com.steamdeck.launcher`（r4），前台服务，测试 key，屏幕控件。
- **2026-09-20 - 0.1。** Linux Steam 客户端和桌面；运行时 r6→r9 在一天内（Proton 自选择、proot 发布、刷新率、视频、首次运行到达游戏）。面向非 Linux 用户的公开说明页面。
- **2026-09-21 - 0.1.1。** 追赶 Bannerlator：驱动选择（两个列表，按模式）、overlay 恢复 + GameHub force-stop、DirectAudio + 麦克风、核心掩码、四个会话开关作为 toggle、每会话日志文件夹并清理凭据、通过 Banners-Turnip r3 的 Adreno 710 路径。
- **2026-09-22 - 0.1.2。** teardown ANR（主线程上收集日志）修复；两行合成器日志（帧大小变化、窗口重命名）；README 账本。
- **2026-09-22 - 0.1.3。** Max 的 NetworkManager 替身移植（客户端的网络页面）；对 WinNative 的 shim 审计 = 零功能漂移。
- **2026-09-22（稍后）- 0.1.4 之后，在 main 上。** 音量键；gamescope 下的模拟器（桌面不能：labwc on pixman 不提供 dma-buf）；ROMs chip；sysmem 警告 + ENOSYS 提示；自动游戏存储；客户端的游戏在桌面上（`steam` shim 移交给 gamescope 会话）；在 FIT 日志中发现并修复移交的三个故障；前端合并（分支 `feat/frontend`，r1–r6）；God of War II HD 从 rail 在 RPCS3 中启动。
- **2026-09-22 - 0.1.4。** ROMs 文件夹 + Storage 在会话 home 中；Bannerlator 的 File Manager 整体移植；每个模式一个 cog（railed 设置窗口），带分辨率上限、形状、面板上的 HDR10 门控、驱动、触摸、OSC/音频/渲染器；两列主菜单；崩溃安全日志文件夹 + Session logs toggle；OrphanReaper；抽屉 Steam 菜单。公开 release 重组：每版本一个，旧的 `Steamdeck` release 和运行时 r1–r8 删除。

## 架构（东西在哪里）
- `MainActivity` / `ui/MainScreen.kt` - 主屏幕；`ui/ModeSettingsDialog.kt` 是 cogs；`ui/*Dialog.kt` 是其余。
- `SessionActivity` - Surface、输入（触摸、触控板、手柄、键盘）、抽屉、HDR 决策、合成器启动（`wayland/CompositorHost`、`wayland/WaylandCompositor`）。
- `session/SessionService` - proot + gamescope/labwc + PulseAudio + DirectAudio relay + 网络链接；绑定（`/root/Storage`、`/root/ROMs`）；guest 的环境；teardown；`OrphanReaper`；`SessionArtifacts` + `CrashHandler` + `SessionPaths` 用于日志文件夹；`SessionPrefs`。
- `files/` - Bannerlator 的 File Manager（`FileManagerScreen.kt` 及其助手）、picker activity 和 `InAppFilePicker` intent API。
- `gpu/` - Turnip（bionic，用于合成器）和 Linux Turnip（glibc，用于运行时）管理器。
- `tools/linuxfs/overlay/usr/local/bin/bannerlator-*` - guest 脚本，由 `SessionFiles` 在每次启动时暂存进运行时（CI 只打包 `bannerlator-*` 名称）。
- `app/src/main/cpp/waylandcomp/` - 合成器（与 Bannerlator 共享血统）。

## 经验教训（不要重复这些）
- teardown 中任何东西都不能在主线程上做批量文件系统工作（0.1.1 的 ANR）。
- 对多行变量做 CI closure 检查必须先展平它（`libaaudio.so` 拒绝）。
- 运行时脚本必须命名为 `bannerlator-*`，否则 CI 永远不会打包它。
- `gh release create --target` 需要完整 sha。
- proot 绑定对程序的“Computer”列表不可见；把用户需要的东西放在 home 下。
- 合成器每个应用进程只决定一次其驱动和 HDR 门控——任何改变它们的东西都在应用完全关闭后应用，UI 必须说明这一点。
- Bannerlator 的 File Manager 在容器钩子被切断后可机械移植（`port_fm.py` 锚点）；不要手工编辑 2,500 行。
- 在 GitHub 上删除 release asset 可能会丢掉一个同级 asset——重新列出并恢复。
- 没有实现的资源会让 libwayland 在其第一个请求时中止——合成器交出的每个接口都需要一个，即使是 no-op（positioner、popup、output）。
- KGSL 替身不应答任何 DRM ioctl：`drmIsMaster()` 将其读作“master”；在把 GPU fd 当作 DRM 前检查 `drmGetVersion()`。
- 从源代码验证模拟器的配置值：RPCS3 的 GUI 说“Async Shader Recompiler”，配置值是“Async Recompiler (multi-threaded)”。
- 上游 PCSX2 没有 ARM64 重编译器；其正确的 ARM64 构建仍然慢如解释器。
- Android 应用以触摸模式启动，此时 Compose clickables 拒绝焦点：打开时 `requestFocus()` 什么也不做，直到应用请求键盘输入模式。
- 管道末尾的 `grep -q`（或 `head`）会提前退出；在 `pipefail` 下这会在随机时使整行失败。先捕获输出，再搜索它。
- 可复用工作流获得的权限不会超过其调用者给予的权限，并且只有 `secrets: inherit` 才能看到环境 secrets。命名不存在环境的工作流会创建它，且不受保护。
- `pull_request_target` 与 pull request 的代码共享 main 的缓存——绝不与签名构建一起使用。
- apksigner 按版本将签名者打印为“Signer #1”、“Signer (minSdkVersion=…)”或“V3.0 Signer:”。

## 待办 / 下一步
- 在 FIT 上一个一个试玩其他模拟器：Dolphin、Cemu、melonDS、PPSSPP、RetroArch（手柄配置文件和封面存在；尚未玩任何）。DuckStation 已于 2026-09-24 完成。
- **Kurt 的 PR #39（Non-Launcher flavor）按写法破坏签名**：它用 `droiddeck-home-apk` / `droiddeck-non-launcher-apk` 替换 `droiddeck-apk` artifact，而 `sign.yml` 和 `release.yml` 读取它，并且它与今天的 `build.yml` 冲突。合并前适配：签两个 edition（一个 release 将是 editions × packages）。
- repo 公开后：向 `release` 环境添加必需 reviewer（Pro 计划的私有 repo 不允许）。
- 用新密钥的第一个 release（0.1.7）：运行 `release.yml`，发布；0.1.6 用户通过移交迁移。
- rail 游戏中的键盘 Esc 不会到达 ARMSX2 的暂停菜单（guide 会）。
- 准备好时 0.1.7 pre-release（以上所有仅在 main 上）。
- 仅在测试后固定更新的 ARMSX2 nightly（它每天变化）。
- 在 FIT 上设备证明 0.1.4（上面的列表）。
- Thor Pro：触控板点击；一旦 `crash.log` 到达，调查 1–2 分钟游戏内崩溃。
- Xfce 作为第二桌面 shell（Max 的分支在 labwc 上运行 XFCE 4.20）——一个目录包 + Desktop cog 中的 shell 选择；舒适，不是性能。
- 非 Deck 手柄的 Quick Access Menu——Back 双击（500 ms）现在路由到现有 Guide+A 组合键；会话内菜单、Steam 设置和 Setup › Session 可交换单击和双击 Back 动作。仍需要设备确认。
- FlatOut 缩小：尝试通过 `steamdeck-env` 设置 `vk_wsi_force_swapchain_to_current_extent=false`。
- Max 更严格的 `winnative-directaudio` 防护；`winnative-epic-launch`（一个功能）。
- 在任何真正公开前重命名：“SteamDeck”是 Valve 的商标。（已于 2026-09-23 完成：DroidDeck。）