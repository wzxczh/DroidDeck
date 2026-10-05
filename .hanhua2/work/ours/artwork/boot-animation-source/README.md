# DroidDeck 启动动画

此源文件伴随应用捆绑的单个 Steam 启动影片：
`app/src/main/assets/steam-startup/droiddeck-startup.webm`（1280×720，30 fps，纯黑背景，VP8 视频
带 Opus 音频）。`droiddeck-boot.html` 是可编辑动画；下面的脚本和 WAV 以捆绑的播放配置渲染动画。仓库中不保留其他 MP4 或 WebM 导出。

从仓库根目录，用以下命令渲染影片：

    python3 artwork/boot-animation-source/render.py 1280 720 30 app/src/main/assets/steam-startup/droiddeck-startup.webm dark

捆绑配置与 1080p/60 相比，每秒解码像素减少 78%。VP8 还降低了 Steam 启动时的软件解码成本。将捆绑影片保持在 720p/30；更高分辨率导出用于分享。

应用在每次会话前将该文件暂存到 Steam 的 `config/uioverrides/movies` 文件夹。如果 Steam 仍选择其内置启动影片，DroidDeck 会在 Steam 启动前将此影片设为设备的启动默认值。用户之后选择的自定义启动影片会被保留。

要重新生成音轨，请从本目录运行以下命令：

    python3 events.py
    python3 sound.py

动画和音频都是程序化生成的；包含的 WAV 是捆绑渲染所用的音轨。

`droiddeck-boot.html` 包含整个动画。每一帧都是时间的纯函数，因此预览页面和视频渲染绘制完全相同的内容。在浏览器中打开它即可拖动、放慢或逐帧步进（方向键）。

## 重新渲染视频

    pip install playwright && playwright install chromium   # 还需要带 libvpx 的 ffmpeg
    python3 render.py 1280 720 30 droiddeck-boot-1280x720.webm
    python3 render.py 1920 1080 60 droiddeck-boot-1920x1080.mp4    # .mp4 = H.264 用于分享
    python3 render.py 1280 720 30 droiddeck-boot-dark-1280x720.webm dark   # 深色版本

每一帧都获得真实运动模糊（180° 快门，自适应子帧）。输出为 .webm 的 VP8 或 .mp4 的 H.264，均为 BT.709，因此球体在压缩后保持 #1A9FFF。

## 声音

每个声音都在 `sound.py` 中合成（无采样），并放在动画报告的精确物理事件上，因此即使你更改时序，撞击仍保持帧精确。调性：D 大调。

    python3 events.py         # 从动画导出撞击时间、摇摆、碎片速度
    python3 sound.py          # -> droiddeck-boot-sound.wav（-18 LUFS，-1 dBFS 峰值）
    python3 embed_sound.py    # 将其放入预览页面
    python3 render.py ...     # 自动混入 wav（.webm 中为 Opus，.mp4 中为 AAC）

音轨由最终和弦构建。每个碎片落在其一个音符上，作为柔和、弱音的音调（腿 D，球 A 带更安静的回声，碗 F#），摇摆和跳跃在静默中播放，锁定以相同的柔和声音落下并一起播放所有音符。调整 `sound.py` 中的提示表：`# 1. the stack` 下的 `tock(...)` 调用，`# 4. lock` 下的和弦列表，以及房间混响混合（`.14 * wet`）。

## 在哪里调整

- 下落时序：`archDrop`、`ballDrop`、`domeDrop`（开始时间、高度、弹性 `e`）。
- 平衡动作：`SWAY` 关键帧（时间、倾斜）。值越大 = 摇摆越大。碗领先，球追赶它（`sway(t - .12)`），腿跟随（`sway(t - .10)`）。
- 大摇摆时的接住跳：`HOP`（开始时间、持续时间、横向距离、高度）。
- 跳跃前下蹲：`crouchA/B/D`。
- 跳跃和锁定：`FL`（上升时间、顶点高度、锁定时间、碎片散开距离）。
- 每个版本的颜色（碎片、背景、阴影、锁定光晕）：`THEMES`（light、dark）。
- 总长度：`DUR`。

## 安装为 Steam 启动影片

将 .webm 放入 `~/.steam/root/config/uioverrides/movies/`，然后在
Steam > 设置 > 自定义 > 启动影片 中选择它。