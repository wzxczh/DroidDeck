### Android Linker 命名空间绕过库
提供对 Android 9+ 中隐藏的 linker 命名空间功能的访问，并暴露一个用于 hook 库的接口，类似于 `LD_PRELOAD` 的运行时等价物。  
API 参考请见 `android_linker_ns.h` 头文件。

#### 支持范围
Android 9+  
Arm64  
  
Android 8 和 arm32 经过一些简单修改后也能获得支持；如果你有在这两个平台上使用本库的需求，欢迎提交 issue。
