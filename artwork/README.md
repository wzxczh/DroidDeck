# DroidDeck 标记

`droiddeck-mark.svg` 是可编辑的白色分体 D 标记源文件。蓝色球体是 `#1A9FFF`；其光晕和两条分隔条是透明镂空。分隔条居中于 x=63.97，即原始 Steam Deck 符号中弯曲半部分的端点，并使用光晕的 15.83 单位厚度。该标记在其视图框中光学居中，并带有轻微向右偏移，以平衡其不对称轮廓。当需要背景时，将其放在黑色上。

Android 目前为启动器层和 Compose 标志使用光栅资源。`mipmap-*/ic_launcher_foreground.png` 文件是 SVG 的密度尺寸导出，标记保持在自适应图标安全区域内。匹配的背景是黑色。`drawable-nodpi/logo.png` 将相同标记合成在黑色圆形徽章上。