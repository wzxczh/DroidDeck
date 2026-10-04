# 通知（NOTICE）- FidelityFX Optical Flow（model 3）

win-fg 的 "model 3"（FSR3 Optical Flow）内嵌的 compute shader 是对 **AMD FidelityFX SDK FSR3 Optical Flow** 算法的改编。

由源码改编的 pass（FidelityFX-SDK，`main` 分支）：

- `Kits/FidelityFX/framegeneration/fsr3/include/gpu/opticalflow/ffx_opticalflow_prepare_luma.h`
  → `of3_luma.comp`（Rec.709 LDR 亮度提取）
- `Kits/FidelityFX/framegeneration/fsr3/include/gpu/opticalflow/ffx_opticalflow_compute_luminance_pyramid.h`
  → `of3_downsample.comp`（4-tap box / 等价 SPD 的 mip 降采样）
- `Kits/FidelityFX/framegeneration/fsr3/include/gpu/opticalflow/ffx_opticalflow_compute_optical_flow_v5.h`
  → `of3_flow.comp`（带粗层级预测的 SAD 块匹配搜索）
- `Kits/FidelityFX/framegeneration/fsr3/include/gpu/opticalflow/ffx_opticalflow_scale_optical_flow_advanced_v5.h`
  → `of3_expand.comp`（把粗略光流双线性放大到显示分辨率）

这些 GLSL 改编是不依赖 subgroup、不依赖 SPD 库的重新实现，因此可以用 `glslangValidator` 为 Vulkan 1.1 编译（对 Adreno/Turnip 安全）。它们有意舍弃了 SDK 中的 HDR 传输函数处理、wave 内建函数（`ffxWaveSum`）、`msad4`、打包 uint 亮度以及 groupshared SAD 图——在本技术栈上这些没有一个能移植到纯 GLSL。参见每个 shader 里的 DEVIATION 注释。

FidelityFX SDK 以 MIT 许可证发布：

```
This file is part of the FidelityFX SDK.

Copyright (C) Advanced Micro Devices, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files(the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and /or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions :

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
