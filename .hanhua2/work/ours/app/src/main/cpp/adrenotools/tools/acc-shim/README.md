## Adreno Compiler Collection 参数 shim

### 待办（TODO）
为它制作某种 API，使参数可以通过 adrenotools 实时更改。

### 编译
```
$ sed -i 's/libllvm-glnext/notreal-glnext/g' libllvm-glnext.so
$ mv libllvm-glnext.so notreal-glnext.so
$ aarch64-linux-android28-clang  vk_acc_shim.cpp -o notllvm-glnext.so --shared -fpic
$ sed -i 's/libllvm-glnext/notllvm-glnext/g' vulkan.adreno.so
```
