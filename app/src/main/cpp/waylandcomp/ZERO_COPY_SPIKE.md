# Wayland 上的零拷贝窗口图层——调研 spike 与宿主侧原型

分支 `spike/wayland-zero-copy-layers`（基于 `feat/wayland-phase2`）。写于 2026-09-13。

**问题。** 目前每一帧游戏画面都是一个 dma-buf：合成器把它导入为 `VkImage`
（`vk_present.c: vkp_image_from_dmabuf`）再 blit 进自己的 swapchain（`vkp_render`），每帧一次
全屏拷贝；因此 AIO 上 Vulkan/D3D12 与 X11 的 FPS 差距约为 20 %（596 vs 752、Adreno 750 上
336 vs 430）。Wayland 让每个窗口 buffer 都能成为它自己的 Android 图层
（`ASurfaceControl` + `AHardwareBuffer`），由 SurfaceFlinger/DPU 直接合成而不拷贝。障碍在
Turnip/KGSL/bionic/Android 14 上的 dma-buf ↔ `AHardwareBuffer` 互操作：游戏跑在 Proton wcp
打包的 Wayland Turnip 上，合成器跑在 adrenotools Turnip 上。

**一段话作答。** 裸 dma-buf 永远变不成 `AHardwareBuffer`（选项 b 已封死：没有公开 API，
而且游戏的 buffer 是 `/dev/dma_heap/system` 的分配，不是 gralloc buffer）。互操作必须反过来
走：buffer 首先得是 **gralloc `AHardwareBuffer`**，它的 dma-buf fd（`native_handle->data[0]`）
才是 Vulkan 驱动要导入的东西——这正是 Turnip 在 Android 上导入每个 gralloc buffer 的方式
（`vk_android.c`）。局中的两个 Turnip 都已具备所需的一切扩展；驱动什么都不缺。缺的是一个
**WSI 钩子**：Mesa 的 Wayland WSI 永远自己分配 dma-buf，没有任何途径接收或分配合成器可见的
buffer，因此**选项 (a) 或 (c) 各需给我们的 Wayland Turnip 构建中的 `wsi_common_wayland.c`
打一个约 300 行的补丁**（banners-turnip-wayland `build_turnip_wayland_wsi.sh`，Mesa
`7cda7850`）外加一个小的私有 Wayland 协议。应用侧的任何东西都无法独自消除这次拷贝。推荐：
**选项 (a′)——游戏的 WSI 把 swapchain 图像分配为 `AHardwareBuffer`（gralloc 原生、UBWC），
经由 Turnip 已有的 AHB 路径导入，再通过 Unix socket（`AHardwareBuffer_send/
recvHandleFromUnixSocket`，与 X11 渲染器已在 `GPUImage` 上使用的机制相同）把 AHB 连同今天
已有的、经 `zwp_linux_dmabuf_v1` 传递的 dma-buf fd 一并交给合成器（于是 blit 路径保留为回
退）。** 宿主这半——每个全屏窗口一个 `ASurfaceControl` 图层、几何取自全屏模式、release fence
取自 `ASurfaceTransaction_setOnComplete`、黑色基底 surface、HUD/指针作为 Android view 叠在
上方——在本分支实现为一个开关控制的原型（`sc_layer.c`、`DROIDDECK_WAYLAND_ZERO_COPY=1`），带
一个合成器持有的 AHB 池和一次 blit，以便在驱动工作开始之前先在设备上测量 SurfaceFlinger 这
一侧。

---

## 1. Buffer 路径选项

这些选项所依据的事实（Mesa `7cda7850`，两个 Turnip 都由该 commit 构建）：

- **游戏侧目前的 buffer** 是来自 DMA heap 的 dma-buf：KGSL winsys 用 `DMA_HEAP_IOCTL_ALLOC`
  在 `/dev/dma_heap/system` 上分配可导出内存（更老的内核上是 ION），再用
  `IOCTL_KGSL_GPUOBJ_IMPORT` / `KGSL_USER_MEM_TYPE_DMABUF` 把该 fd 导入 KGSL
  （`tu_knl_kgsl.cc:84-107`、`279-296`、`375-436`、`1836-1856`）；导出则是对该 fd 的一次
  `dup()`（`kgsl_bo_export_dmabuf`，`:438-442`）。它们**不是 gralloc buffer**：没有
  `private_handle_t`，没有 metadata fd，SurfaceFlinger 的 mapper 无从导入。
- **dma-buf 导入在两个 Turnip 上都能用**：`VK_KHR_external_memory_fd`、
  `VK_EXT_external_memory_dma_buf`、`VK_EXT_image_drm_format_modifier` 都是无条件暴露的
  （`tu_device.cc:253, 354, 369`）；合成器每一帧都在证明这一点。只有
  `VK_EXT_physical_device_drm` 在 KGSL 上是关闭的（`:385`）——这与 dmabuf-v4 反馈有关（§2）。
- **Turnip 的 AHB 导入就是 dma-buf 导入。** 只要存在 u_gralloc 后端就会暴露
  `VK_ANDROID_external_memory_android_hardware_buffer`（`tu_device.cc:219, 428`），而 fallback
  后端总是存在。`vkGetAndroidHardwareBufferPropertiesANDROID` 做的是
  `AHardwareBuffer_getNativeHandle(buffer)->data[0]` → `lseek` 求大小 →
  `GetMemoryFdPropertiesKHR(DMA_BUF, data[0])`（`vk_android.c:1225-1236`）；图像用 gralloc 布局
  对应的 `VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT` 创建
  （`vk_gralloc_to_drm_explicit_layout`，`vk_android.c:140-205`；`tu_image.cc:1006-1023`）。布局
  来自 `u_gralloc_fallback.c:145-156`：在 QTI 句柄上（第一个 int 里是 magic `'gmsm'`），下一个
  int 中的 UBWC 标志 `0x08000000` 选中 `DRM_FORMAT_MOD_QCOM_COMPRESSED`，否则为
  `DRM_FORMAT_MOD_LINEAR`；pitch = `pixel_stride × bpp`，offset 0。qcom 后端
  （`u_gralloc_qcom.c`、`hw_get_module`）无法从 app 进程加载厂商 gralloc，所以实际用的就是
  fallback——本地 KGSL 构建恰好导入 `AHardwareBuffer_allocate / describe / getNativeHandle /
  acquire / release / isSupported` 和 `hw_get_module`，NEEDED `libnativewindow.so` +
  `libhardware.so`（对 `turnip608/stg/libvulkan_freedreno.so` 做 readelf 的结果）。因此
  **Wine 进程已经加载了 `libnativewindow.so`**（作为 ICD 的依赖），而 X11 渲染器也已经通过
  Unix socket 接收在 guest 进程内分配的 AHB（`DRI3Extension.java:141-150` → `GPUImage(fd)` →
  `gpu_image.c:74-86` 的 `AHardwareBuffer_recvHandleFromUnixSocket`；发送方是 X11 的
  `libvulkan_wrapper.so` WSI）。

### (a) 游戏侧：在 guest 内 `AHardwareBuffer_allocate`，导出为 dma-buf

用现有驱动是可行的，**但只能通过一个 WSI 补丁**——应用自己做不到。Mesa 的 Wayland WSI 用
`wsi_create_native_image_mem` 创建每一个原生 swapchain 图像（`wsi_common_drm.c:760-850`）：
`vkAllocateMemory` 搭配 `VkExportMemoryAllocateInfo{DMA_BUF}` → `vkGetMemoryFdKHR` →
`zwp_linux_buffer_params_v1.add(fd, …)` + `create_immed`（`wsi_common_wayland.c:3558-3620`）。
其中没有任何一处从 gralloc 分配，而 `VkNativeBufferANDROID`（Android-WSI 的导入路径，
`tu_image.cc:880-925`）只有 Android swapchain 才走得到。

补丁（在我们的 Wayland Turnip 构建中）：为 Wayland 链新增一个 `create_mem`，当合成器广播一个
私有全局 `banner_layer_buffer_v1` 时被选中：
1. `AHardwareBuffer_allocate(w, h, R8G8B8A8, GPU_COLOR_OUTPUT | GPU_SAMPLED_IMAGE)`；
   `vkGetAndroidHardwareBufferPropertiesANDROID` → `vkAllocateMemory` 搭配
   `VkImportAndroidHardwareBufferInfoANDROID`（Turnip：`tu_device.cc:3828`），bind——图像按
   gralloc 报告的 modifier/pitch 创建，即 Adreno gralloc 上就是 UBWC（§2）。
2. `wsi_image.dma_buf_fd = dup(native_handle->data[0])`，`drm_modifier` / `row_pitches[0]` 取自
   同一布局 → 现有的 `zwp_linux_dmabuf_v1` 路径原封不动：合成器继续导入 dma-buf，blit 路径可以
   保留为回退。
3. `banner_layer_buffer_v1.attach(wl_buffer, socket_fd)`：WSI 创建一个 `socketpair`，在请求里
   发送其中一端，并调用 `AHardwareBuffer_sendHandleToUnixSocket(ahb, other_end)`；合成器的请求
   处理函数执行 `AHardwareBuffer_recvHandleFromUnixSocket` 并把 AHB 存到 `struct
   dmabuf_buffer` 上。每个 swapchain 图像一次——不是每帧一次。
游戏用到的扩展：只有 `VK_ANDROID_external_memory_android_hardware_buffer`（dma-buf/modifier
扩展由同一个驱动内部使用）。注意 winevulkan 不会把 AHB 扩展暴露给 PE 代码——这里无关紧要，WSI
位于 ICD 内部的 unix 侧。工作量：WSI + 协议 + wcp 重建 2–3 天（打包交给
wine-compat-engineer）。

### (b) 把游戏现有的 dma-buf 导入为 `AHardwareBuffer`

**不行。** 没有公开 API：`AHardwareBuffer_createFromHandle` 只存在于 `vndk/hardware_buffer.h`
（由 `libnativewindow.so` 导出，所以 `dlsym` 找得到），但它接收的是一个 gralloc
`native_handle_t`，要经厂商 mapper（`IMapper::importBuffer`）校验——在 QTI 上那是一个带
`'gmsm'` magic、一个 metadata dma-buf 以及若干厂商专属 int 的 `private_handle_t`。游戏的
buffer 是光秃秃的 DMA-heap fd。围绕外来 fd 伪造一个厂商句柄属于无版本管理的私有 ABI（在
gralloc4 各发布版/OEM 之间各不相同），恰恰是会让非 Adreno/老 SoC 直接重启的那类做法。
`AHardwareBuffer_createFromHandle` 还要求调用者位于其桩所在的 VNDK namespace；只有 `dlsym`
能够到它。否决。

### (c) 合成器持有的池，提供给游戏

合成器分配 AHB，从 native handle 取出 fd，由游戏导入。游戏侧与 (a) 是同一条驱动路径，只是
去掉了 gralloc（`VK_EXT_external_memory_dma_buf` + 合成器显式给出的 modifier/pitch）。它
**仍然需要同样的 WSI 补丁**（一个做导入而非导出的 `create_mem`），此外还需要一个协议在
swapchain 图像创建之前携带 `fd + modifier + pitch + size`、在 `vkCreateSwapchainKHR` 时重新
分配（尺寸变化），以及双向的 fence 传递。`zwp_linux_dmabuf_v1` v4 反馈表达不了这些（它只能
引导 format/modifier/device；客户端仍然自己分配），而且在 KGSL 上 v4 本来就是个坑：驱动没有
DRM `dev_t`，`same_gpu` 恒为 false（`wsi_common_wayland.c:1660-1686`），Mesa 于是走
prime-blit 路径（每帧多一次拷贝）。相对 (a) 的唯一优势——由合成器决定 linear 还是 UBWC——也
站不住脚，因为 gralloc 的决定对 DPU 来说本来就是对的。工作量：3–4 天；活动部件严格多于
(a)。不推荐。

**推荐：(a)。** 它复用两个已被验证的机制（Turnip 的 AHB 导入、X11 路径的
AHB-over-socket），保留 `zwp_linux_dmabuf_v1` 作为回退传输，并把新代码限制在我们自己的 Mesa
构建加一个小协议之内。

## 2. Modifier / UBWC

- Turnip 恰好广播两个 modifier：`DRM_FORMAT_MOD_LINEAR` 和 `DRM_FORMAT_MOD_QCOM_COMPRESSED`
  （UBWC），后者在格式可 tiling 且 `ubwc_possible()` 时给出（`tu_formats.cc:416-443`）；显式
  modifier 的图像只接受这两个（`:530-575`）。`QCOM_COMPRESSED` 强制 UBWC
  （`tu_image.cc:723-727`）；显式 plane 布局会经 `fdl6_layout_image` 校验，pitch 装不下时以
  `VK_ERROR_INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT_EXT` 拒绝（`:762-806`）。两者的导出/导入
  是对称的（同一个驱动、同一套 `fdl6` 布局）。
- 合成器只广播 `LINEAR` 和 `INVALID`（`compositor.c: bind_dmabuf`、
  `uint64_t mods[] = {MOD_LINEAR, MOD_INVALID}`）；`INVALID` 被 WSI 丢弃
  （`wsi_common_wayland.c:449-451`），因此今天所有游戏 swapchain 都是 linear 的。DXVK/VKD3D
  渲染到自己的 tiling/UBWC 目标上，目前再拷贝进 linear swapchain 图像——游戏侧每帧一次全帧
  linear 写入，叠在合成器的 blit 之上。这就是那 20 % 里实打实的一部分。
- 也广播 `QCOM_COMPRESSED`：`bind_dmabuf` 里加一行。WSI 取合成器列表与驱动列表的交集
  （`wsi_common_drm.c:686-733`），而 Turnip 在被提供时偏好 UBWC；`vkp_image_from_dmabuf`
  本来就透传任何 modifier。两点注意：游戏发出的 pitch 是 UBWC 的 pixel-plane pitch（Mesa 报告
  该 modifier 的 plane 0，`:817-830`），且双方跑的是同一套 `fdl6` 代码，所以导入必须在同一块
  GPU 上成功；另外 UBWC buffer 绝不能被 CPU 路径读取（`wl_shm` 不受影响）。这与图层无关，值得
  单独做一次 A/B（实验计划第 0 步）。
- 走图层时 buffer 是 gralloc 的，因此 UBWC 由 gralloc 决定：除非设置了某个 CPU usage 位，
  QTI 会为 `GPU_COLOR_OUTPUT` 的 RGBA8 分配 UBWC（原型正是利用了这一点：
  `sc_layer.c: alloc_slot` 在 UBWC 导入失败时改用 `CPU_READ_RARELY` 重试）。DPU 原生扫描输出
  UBWC RGBA；此前在设备上的实验（GL 原生渲染，2026-06-29）发现 SDE pipe 同时接受 UBWC 和
  linear 图层——当时的障碍是 rotation+scale，而不是 UBWC。

## 3. 按图层合成（设计；原型只实现了单图层子集）

- **wl_surface → SurfaceControl。** 每个映射的 toplevel 一个
  `ASurfaceControl_createFromWindow(surfaceView_window, name)` 子节点（每个 subsurface 树按
  toplevel 拍平后也算一个）；桌面 surface 是 z 0 的基底图层（或者在 `g_hide_shell` 下换成黑色
  基底——SurfaceView 自己的 buffer）；toplevel 按 `g_toplevels` 的顺序拿到 `setZOrder`
  （`apply_zorder`），`INT_MAX` 留给应用的光标/HUD 以防它们将来移到原生层。位置/缩放：
  `ASurfaceTransaction_setGeometry(src = buffer rect, dst = output rect, transform 0)`，输出
  矩形由 blit 路径所用的同一套 `update_map()` / `draw_to_blit()` 算法算出
  （`vk_present.c: vkp_map_draw`）——letterbox、FILL 裁剪、TOP-BOTTOM 半屏算出来完全一致，所以
  **输入一致性由构造保证**：应用通过 `ViewTransformation` 以同样的输入映射触摸（见
  `WAYLAND_RUNTIME.md` 的 "Fullscreen mode"），而合成器自己的 `vkp_output_to_scene` 照常
  工作，因为映射仍然每帧更新。XR24/XB24 用 `setBufferTransparency(OPAQUE)`；
  `setBufferTransform` 保持 0——DPU 在同一根 pipe 上拒绝 rotation+scale（先前的实验），因此
  旋转的面板必须由 activity 的方向处理，而不是靠图层。
- **HUD。** 已经是一个独立图层：`PerfHudView`/`FrameRating`/光标都是 `rootView` 里位于
  `waylandSurfaceView` 之上的 Android view，即 ViewRoot 的 SurfaceFlinger 图层叠在
  SurfaceView 及其所有子 SurfaceControl 之上。无需改动；原型保持原样。
- **Vsync / 帧回调。** 保留应用的 Choreographer 节拍（`nativeVsync`）作为节奏边界：每次节拍
  时，合成器应用**一个事务**，包含自上个节拍以来所有已提交的 surface（每个变化的图层一次
  `setBuffer`，变化者带上 geometry/z），然后对这些 surface 触发 `wl_callback.done`（与今天
  一样，`fire_all_frames`）。`wp_presentation` 反馈从"我们的 present 之后"改为事务的
  `ASurfaceTransaction_setOnComplete` → `ASurfaceTransactionStats_getLatchTime` /
  `getPresentFenceFd`（API 29），给出真正上屏的时间——比今天的 `now_ns()` 更好；以后可为
  `VK_GOOGLE_display_timing` 的工作补上 `setFrameTimeline`（API 33）。没有窗口时，
  `pace_without_output` 保持不变。
- **`wl_buffer.release` 时机。** 今天被替换的 buffer 按限速器的节奏释放，这是安全的，因为
  blit 已经等过了。有了图层之后，buffer 会一直留在显示上直到 SurfaceFlinger 说不：保持
  buffer 不释放，直到**替换**它的那个事务的 OnComplete 回调送来
  `ASurfaceTransactionStats_getPreviousReleaseFenceFd`（这正是
  `ASurfaceRendererContext::transactionCompleteCallback` 所做的），在合成器线程上等这个
  fence（或者更好，交给游戏：用选项 (a)，WSI 可以把它作为 `WSI_ES_RELEASE` 点导入——
  Turnip/KGSL 实现了 `vk_sync.import_sync_file`，`tu_knl_kgsl.cc:1261-1303`），然后再
  `wl_buffer_send_release`。FPS 限速器照常工作：它只是把释放进一步推迟。Android 16 的
  `setBufferWithRelease` 在 14 上不可用。
- **Acquire fence。** 两个来源：(1) 如果内核支持 `DMA_BUF_IOCTL_EXPORT_SYNC_FILE`（6.x
  GKI），WSI 已经把自己的 present semaphore 作为 sync_file 附在 dma-buf 上
  （`wsi_drm_init_swapchain_implicit_sync`，`wsi_common_drm.c:368-395`，取决于驱动导出
  `SYNC_FD` semaphore——KGSL 会，`:1278-1303`），合成器可以对它 `EXPORT_SYNC_FILE(READ)` 并把
  fd 传给 `setBuffer`——原型会在第一帧探测这一点并把结果打出来；(2) 否则选项 (a) 的协议每次
  present 携带一个 sync_fd（`vkGetSemaphoreFdKHR`），X11 wrapper 就是这么做的。真正的零拷贝
  帧绝不能是 `-1`。
- **生命周期。** SurfaceView 销毁/重建（后台、旋转）：`vkp_apply_window_request` 已经在
  合成器线程上串行化窗口变更；图层代码退役它的 SurfaceControl（hide + `reparent(NULL)` +
  在 OnComplete 回调中 release），并在新窗口上重建。buffer 在这期间保持有效（gralloc buffer
  是与进程无关的引用计数对象），所以 resume 后的第一帧就是最后提交的那一帧。

## 4. 风险

- **HWC 回退到 GPU 合成**（全部意义就在于 DEVICE 合成）。2026-06-29 在 Adreno 750 设备上的
  实验已知：一个带 `transform 90` **又**缩放的图层会掉到 CLIENT 并把基底图层一起拖下去；同一
  个图层在面板分辨率下预旋转、src == dst 时每个图层都是 DEVICE，GPU_TARGET 为空。所以：永远
  不要给 SurfaceControl 加旋转；在原生横向的面板（掌机）上，恒等变换的游戏图层加 DPU 缩放应当
  能升为 DEVICE；在竖屏手机横过来用时则不行，那里的基底 swapchain 路径才是更好的选择——按设备
  实测决定，不要凭假设。用 `dumpsys SurfaceFlinger` 测量（图层 `composition: DEVICE/DEVICE`
  对比 `DEVICE/CLIENT`，以及 SDE pipe 表的 `GPU_TARGET` 行），保持 app 在前台、抽屉关闭。
- **图层数量。** DPU 只有几根 pipe（SDM/SM8xxx 上 4–8 根可用 RGB pipe）；2–3 个游戏图层 +
  基底 + HUD + 光标没问题，有 10 个窗口的桌面就不行——SurfaceFlinger 会把超出的部分拿到 GPU
  上合成（仍然正确，只是没有收益）。策略：只有全屏/最顶层的窗口走图层，其余一律留在基底
  swapchain 里（原型的规则）。
- **跨进程的 buffer 所有权。** 游戏的 `AHardwareBuffer` 在 guest 里活着；合成器收到的副本持有
  自己的一份 gralloc 引用，SurfaceFlinger 还有第三份。guest 在 present 中途崩溃只会让
  SurfaceFlinger 手里留下一个有效 buffer（不会黑屏闪烁）。Fd：每个 swapchain 图像一个
  dma-buf fd 加一个 socket；在 `wl_buffer` 销毁时清理（今天的 `dmabuf_buffer_unref`）。
- **Fence。** 给还在被 GPU 写入的 buffer 传 `-1` 的 acquire fence 会看到撕裂或陈旧内容；
  KGSL 没有 BO 隐式同步，所以要做出真东西，§3 里的 sync_file 路径是必需的（原型改为在 CPU 上
  等待，这是安全的）。
- **后台 / 旋转 / device lost。** 由窗口请求的串行化覆盖；合成器的 Turnip 发生 device loss
  不影响已经上屏的图层（SurfaceFlinger 拥有它们）——旧路径的 `device_lost` 消息保留。
- **非 Adreno / 老 SoC。** 这里的一切都是 NDK API 29 + gralloc 分配的 buffer；唯一厂商相关的
  部分是 `'gmsm'` 句柄嗅探，它会降级为"带 CPU 位的 linear"。与 X11 ASR 渲染器属于同一风险
  等级；在第二款 SoC 上验证之前保持 opt-in。
- **`vk_present.c` 保留。** 作为以下情况的回退：没有 `ASurfaceControl` API、多窗口场景、游戏
  上方的弹窗、图层建立之前的头几帧，以及任何合成掉到 CLIENT 的设备。原型逐帧切换
  （`compositor.c` 里的 `layer_candidate`）。

## 5. 实验计划（每步在设备上 ≤ 1 小时，按顺序执行）

前置条件：本分支的一次构建、跑在 Wayland 上的容器、AIO Graphics Test（Vulkan 与 D3D12 两个
标签页）作为负载、会话日志位于 `Download/Wayland-logs/`。

0. **UBWC modifier A/B（不涉及图层）。** 已在 `feat/wayland-ubwc` 上完成（当合成器的驱动能够
   导入时才广播，`DROIDDECK_WAYLAND_UBWC=0` = 旧的仅 linear 列表；见 `WAYLAND_RUNTIME.md` 的
   "Compressed (UBWC) game buffers"）。开关各跑一次 AIO Vulkan/D3D12。预期日志
   `vulkan: … is presenting GPU frames through Wayland: WxH, format XB24, tiled (zero-copy)`
   且没有 `dmabuf import failed`。记录这 20 % 里单是 linear swapchain 占多少。
1. **图层模式冒烟。** 把 `DROIDDECK_WAYLAND_ZERO_COPY=1` 放进容器的环境变量，启动 AIO 全屏。
   预期日志行（tag `layer`）：
   `SurfaceControl "droiddeck_wayland_game" created as a child of the screen surface`、
   `pool buffer 1920x1080 UBWC (QCOM_COMPRESSED), stride 1920 px (gralloc handle 2 fds / N ints)`
   （或者在 `import of a UBWC … failed` 之后是 `linear`——这也是一个结果：它能说明合成器
   Turnip 是否接受 gralloc 的 UBWC pitch）、`geometry: buffer 0,0-1920,1080 -> screen …`、
   `presenting 1920x1080 game frames on their own SurfaceControl layer (UBWC pool, 3 buffers)`，
   以及探测行 `kernel exports sync_file fences from the game's dma-buf …` 或
   `DMA_BUF_IOCTL_EXPORT_SYNC_FILE … failed (…)`。画面正确，HUD 和指针在上层，全屏模式切换会
   移动图层（`geometry:` 行）。
2. **合成类型。** AIO 运行时执行 `dumpsys SurfaceFlinger | grep -A3 droiddeck_wayland_game` 以及
   pipe 表里的 `GPU_TARGET` 行。游戏图层 DEVICE/DEVICE 且 GPU target 为空 = DPU 在做缩放；
   CLIENT = 在这个面板朝向上没有收益 → 试试掌机的原生方向 / 与面板相同的分辨率。
3. **宿主拷贝的代价。** 标志开与关时的 AIO Vulkan fps（预期代价相同，误差 ± 噪声，因为 blit
   只是换了目的地），以及各 5 分钟的 `dumpsys gfxinfo`/耗电。这就是衡量 WSI 补丁的基线。
4. **Fence 探测结果 → 确定 acquire fence 设计**（§3），在开始 WSI 补丁之前。
5. **WSI 补丁（选项 a）进 banners-turnip-wayland**，重建 wcp，然后在 AHB 来自 guest 的情况下
   重复步骤 1–3：需要在合成器侧加的日志行 `layer: buffer N of "<window>" received from the
   game (AHardwareBuffer WxH, UBWC)`，以及相对 X11 的 fps 差值。

工作量估计：第 0 步 = 0.5 天（含 A/B）；宿主图层原型 = 已完成（本分支，约 1 天加固：用异步
fence 替代 CPU 等待、按窗口分图层、从 `getLatchTime` 取 presentation 反馈）；选项 (a) 的 WSI +
协议 + wcp = 2–3 天 + 1 天设备验证；选项 (c) = 3–4 天 + 同样的验证；(b) = 无法构建。

## 6. 本分支的原型做了什么（`DROIDDECK_WAYLAND_ZERO_COPY=1`）

文件：`src/sc_layer.{c,h}`（新增）、`src/vk_present.{c,h}`（`vkp_image_import_dmabuf` 承担
blit 目的地角色、`vkp_blit_image`、`vkp_update_map`、`vkp_map_draw`、`vkp_window`、
`vkp_signal_first_frame`；窗口变更时图层退役）、`src/compositor.c`（`g_zero_copy`、
`layer_candidate`、`render_scene` 分支、dma-buf sync_file 探测）、`src/waylandcomp_jni.c` +
`WaylandCompositor.java`（`nativeSetZeroCopy`）、`XServerDisplayActivity.startWaylandCompositor`
（在合成器启动前从容器/快捷方式的环境变量读取该标志）。

行为：当最顶层的绘制是一帧覆盖整个场景的客户端 GPU 画面（一个全屏游戏，其上无物）时，屏幕
swapchain 以黑色呈现，该帧被 blit（1:1、`vkCmdBlitImage`、CPU 等待）进三个合成器分配的
`AHardwareBuffer` 之一（R8G8B8A8、`GPU_COLOR_OUTPUT | GPU_SAMPLED_IMAGE`，通过它的
native-handle dma-buf fd 连同 `'gmsm'` 嗅探得到的 modifier 导入合成器的 Turnip，失败则
linear 回退），该 buffer 被设置到 SurfaceView 的一个子 `ASurfaceControl` 上，几何取全屏模式，
z 1，不透明。`setOnComplete` 返回上一个 buffer 的 release fence，在该池槽位被复用之前等待它；
没有空闲槽位就丢帧（最多每 5 s 记一条日志）。任何其他场景都会隐藏图层并按老办法绘制；
SurfaceView 消失时图层退役。其他一切（帧回调、presentation 反馈、FPS 限速器、HUD 计数、
输入）保持不变。默认关闭：未设置该变量时不运行任何新代码。

还不是零拷贝——这只是接收侧。当第 5 步落地、`sc_layer_present` 拿到的是游戏自己的
`AHardwareBuffer` 而不是池槽位时，拷贝才会消失。

## 7. 第 5 步已实现（feat/wayland-zero-copy-wsi + banners-turnip-wayland `banner_ahb_wsi.py`）

按推荐采用了选项 (a′)，但有一个被构建逼出来的偏差：Wayland Turnip 是 `platforms=wayland`
构建且关闭了 Android 检测，因此其中不存在
`VK_ANDROID_external_memory_android_hardware_buffer` / `vk_android.c`。于是 WSI 自己分配
`AHardwareBuffer`（dlopen `libnativewindow.so`），像 `u_gralloc_fallback.c` 一样嗅探 gralloc
句柄判断 UBWC，并把 `native_handle->data[0]` 作为带显式 modifier + pitch 的 dma-buf 导入——
与本合成器池所用的路径相同（`vkp_image_import_dmabuf`）——前提是先用一次测试性的
`vkCreateImage` 证明驱动接受该布局（否则 linear 重试）。Fence：双向都经由 dma-buf 隐式传递
（§3 的选项 1，已由探测证实；合成器在 `wl_buffer.release` 之前把 SurfaceFlinger 的 release
fence 导回 dma-buf）。细节见 `WAYLAND_RUNTIME.md`。
