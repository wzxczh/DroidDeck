# Android 驱动包格式

## 内容
- 遵循 `Schema` 的 `meta.json`
- `<driver>.so` 主驱动共享库
- `*.so` 主驱动库依赖的全部库；如果它们想替换掉系统版本的库，必须修改其 soname

## 示例
- `meta.json`：
```json
{
  "schemaVersion": 1,
  "name": "Qualcomm v615 Vulkan driver",
  "description": "Proprietary Vulkan driver for Adreno 6xx and Adreno 7xx",
  "author": "ByLaws",
  "packageVersion": "2",
  "vendor": "Qualcomm",
  "driverVersion": "0.615.0",
  "minApi": 27,
  "libraryName": "vulkan.ad0615.so"
}
```

- `vulkan.ad0615.so`：打过补丁的主驱动
- `notadreno_utils.so`、`notdmabufheap.so`、`notgsl.so`、`notllvm-glnext.so`、`notllvm-qgl.so`：各自驱动版本专用库的 patched-soname 版本
- `android.hardware.graphics.mappershim.so`、`vendor.qti.hardware.display.mapperextensionsshim.so`、`vendor.qti.hardware.display.mappershim.so`：qtimapper-shim 库

## 创建
我建议运行 blob-patcher.py，用它对来自设备转储的一组 blob 打补丁（mapper ver 设为 5），然后从 adrenotools 的 releases 页面把 qtimapper-shim 文件复制进来。
```bash
$ mkdir outpkg
$ patch.py <device dump> outpkg vulkan.adreno.so vulkan.ad0615.so 1
$ vim outpkg/meta.json
$ cp qtimapper-shim-rel/* outpkg
```

## 模式（Schema）
```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "ADPKG Schema",
  "type": "object",
  "properties": {
    "schemaVersion": {
      "type": "number",
      "example": 1
    },
    "name": {
      "type": "string",
      "example": "Qualcomm Adreno Driver"
    },
    "vendor": {
      "enum": [
        "Qualcomm",
        "Mesa"
      ],
    },
    "driverVersion": {
      "type": "string",
      "pattern": "^[0-9]+.[0-9]+.[0-9]+(-.+)?$",
      "example": "512.604.0"
    },
    "author": {
      "type": "string",
      "example": "Billy Laws"
    },
    "description": {
      "type": "string",
      "description": "Additional description of the driver, this shouldn't contain redundant information that is already covered by the other fields such as the version and only denote details important for the user"
    },
    "packageVersion": {
      "type": "string",
      "example": "3029-bylaws"
    },
    "minApi": {
      "type": "number",
      "description": "The minimum Android API version required by the driver to function correctly",
      "example": 27
    },
    "libraryName": {
      "type": "string",
      "description": "The name of the main shared library object",
      "example": "vulkan.adreno.so"
    },
  },
  "required": [
    "schemaVersion",
    "name",
    "author",
    "packageVersion",
    "vendor",
    "driverVersion",
    "minApi",
  ]
}
```
