# 应用携带的 gamescope 补丁

由 `.github/workflows/build-gamescope.yml` 在 Linux 运行时附带的精确 gamescope（3.16.29，Arch Linux ARM 的包，相同构建选项）之上构建，并在每次会话启动时从 apk 暂存覆盖到 `/usr/local/bin/gamescope`——托管运行时镜像从未被触碰。二进制的共享库需求在发布任何内容之前会根据 `runtime-sonames.txt`（运行时自己的库列表）检查。

- `0002-steamcompmgr-fallback-appid-focus.patch` - Armada (armada-os/armada)，逐字。
- `0009-fix-arm64-steam-night-mode.patch` - Armada，逐字：ARM64 客户端以不同方式打包夜间模式属性；滑块不起作用。
- `0019-steamcompmgr-arm64-virtual-white.patch` - Armada，逐字：色温滑块的 (x, y) 从 ARM64 客户端作为单个 64 位元素到达；y 从 x 恢复。
- `0020-color-p3-red-is-wide-gamut.patch` - Armada，逐字。
- `0100-realtime-queue-and-gamepad-cursor.patch` - 此应用，Armada 的两个补丁手工移植到 3.16.29：按请求的实时优先级 Vulkan 队列（`GAMESCOPE_FORCE_VULKAN_REALTIME=1`）无需 CAP_SYS_NICE，而 proot 永远不可能拥有（在 KGSL Turnip 上是 no-op，它只有一个提交队列优先级）；以及跟随 XTest 移动的 X 指针的手柄驱动光标精灵（它一直冻结）。X 指针只在绘制光标图像时被请求——显示时每个 vblank，为不活动隐藏时每 50 ms——因为每次请求都是对 Xwayland 在绘制线程上的阻塞式往返；没有图像时，使用 wlserver 的位置，如同上游所做。
- `0110-wayland-backend-touch.patch` - 此应用：嵌套 Wayland 后端只绑定了宿主的指针和键盘，因此手机屏幕上的手指从未到达 Steam。它现在也绑定 `wl_touch`，并将每根手指交给 wlserver 的触摸路径（`wlserver_touchdown` / `motion` / `up`）——Steam Deck 的触摸屏驱动的那条——因此触摸做什么遵循客户端的触摸模式（Steam 的 Big Picture 设置 Passthrough：真实触摸，行在手指下滚动）。手指 id 偏移一，因为嵌套指针已经移动 wlserver 的触摸 0。
- `0111-wayland-pointer-warps-in-passthrough.patch` - 此应用：嵌套指针的运动作为触摸 0 发送到 wlserver，而在 Passthrough（Big Picture 的触摸模式）中，未按下的触摸的运动不会移动任何东西——因此在应用的触控板模式中，Steam 客户端看不到悬停，点击落在指针最后所在的位置。运动现在也总是 warp 真实指针（`bAlwaysWarpCursor`），其他触摸模式已经这样做。
- `0112-restore-iconified-game-on-resume.patch` - 此应用：Steam 菜单是一个 overlay，在不改变焦点窗口的情况下接受输入，而全屏 wine 游戏在失去输入时会最小化自己。gamescope 只在焦点窗口变化时将窗口从 iconic 中取出，而 wine 不会激活它认为 iconic 的窗口，因此在 Resume 后游戏保持最小化：黑屏，其小标题在左上角（Titanfall 2，GE-Proton 11）。iconify 请求被记住，窗口在输入返回它之前回到 NormalState，然后焦点再次移交。根窗口上的 `GAMESCOPE_RESTORE_FOCUS_WINDOW` 从外部请求相同的恢复（会话脚本的 resume 监视器）。

Armada 的另外十六个补丁是用于原生显示的 DRM/lease/HDR-on-KMS 工作，此应用的 Wayland 托管 gamescope 永远不会到达，或者需要比运行时更新的 gamescope。