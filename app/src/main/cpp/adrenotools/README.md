### Adreno Tools
一个用于在无 root 情况下应用 Adreno GPU 驱动修改/替换的库。目前支持加载自定义 GPU 驱动（例如 [turnip](https://docs.mesa3d.org/android.html#building-using-the-android-ndk)）、启用 BCn 纹理，并重定向文件操作，从而允许在无 root 的情况下访问 shader 转储并修改[驱动配置文件](https://gist.github.com/bylaws/04130932e2634d1c6a2a9729e3940d60)。

#### 文档
API 记录在 `include/adrenotools` 头文件中。

#### 支持范围
Android 9+
Arm64

如需支持其他平台，请提交 issue。

### 常见问题

#### 有示例项目吗？

有一个简单的极简项目 [AdrenoToolsTest](https://github.com/darksylinc/AdrenoToolsTest)，演示如何让 libadrenotools 正常工作。

#### 我该如何用它更新手机上的驱动？apk 在哪里？

你不能。这个库**不是**用来安装进 Android 的，也**不是**面向最终用户的。
这个库面向的是其他开发者。

每个应用都必须显式地使用 libadrenotools，才能把自定义驱动加载进应用/游戏。

#### 我该如何用这个库让 \<最喜欢的游戏\> 用上更新的驱动？

见上一个问题。是否添加支持并使用这个库，取决于游戏开发者。

你可以联系他们请其添加支持；但这超出了我们的能力范围。
