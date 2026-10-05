## 处理 Adreno Vulkan blob 的工具

blob-patcher.py 是一个脚本，用于从解包的 ROM zip 生成 adrenotools 可加载的驱动  
qtimapper-shim 让更新的驱动可以在缺少新版 mapper HAL 支持的设备上工作
acc-shim 用于向底层基于 LLVM 的 shader 编译库传递参数


