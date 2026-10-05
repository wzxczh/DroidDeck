## QtiMapper-shim

一个在新版 Adreno 图形驱动所使用的 IQtiMapper(2,3,4) API 与 gralloc1 之间做转换的 shim，而 gralloc1 是 sdm845 上唯一可用的 gralloc API。

### 方法

它暴露与新版 mapper blob 相同的 ABI，并 stub 足够多的函数，迫使 HAL 回退到 2.0 的 mapper/mapperextension API，随后再用一个直通到 gralloc1 的 shim 接管它。这样这些 blob 就可以被改成加载 stub 库，参见 blob-patcher 脚本。
这段代码相当丑陋，但对这个用途来说足够可用；有些函数尚缺实现，一旦驱动开始使用它们就需要补上。


### 构建

把它扔进一棵 lineage 源码树，然后：
```
make vendor.qti.hardware.display.mappershim
make vendor.qti.hardware.display.mapperextensionsshim
make android.hardware.graphics.mappershim
```
再从 outputs 目录里取出 SO
