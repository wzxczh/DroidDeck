# Steam 游戏导入

Second Library 仍然注册所选的 Steam 库。它现在还会扫描其直接游戏子文件夹和 `steamapps/common` 中的 Windows 可执行文件，使用与 Added Games 相同的导入器。现有 Steam 清单被保留。

`bannerlator-steam-games watch` 在客户端旁边运行。它通过现有清单的安装目录、游戏或所选可执行文件旁边的 `steam_appid.txt`，或来自 Steam 商店搜索的带显式国家的唯一规范化标题来识别游戏。匹配还接受末尾的 Windows Edition 和 INTERGRADE 后缀；多个匹配仍然未解决。Artwork 的模糊匹配故意不用于身份识别。有歧义或未解决的标题保留为非 Steam 游戏。

所有权来自正在运行的原生 ARM64 客户端的 `BIsSubscribedApp`，通过版本化的 SteamClient020 / SteamUser021 / STEAMAPPS_INTERFACE_VERSION008 接口。该助手从该连接获取账户，而不是从公共配置文件。不需要 API 密钥、密码、配置文件可见性更改或 CEF 调试端口。每个探测都在一个有界子进程中运行，因此库失败不会使导入器或客户端崩溃。

生成的快照存储在该账户的 `userdata/<id>/config` 下，并在登录时每 30 秒刷新一次。标题查找每五分钟以及当来源变化时重试，因此早期的网络故障不会结束监视器。更改的状态和探测失败被写入会话日志。启动和退出后导入使用所选账户的最后成功快照。因此，新识别的标题从快捷方式开始，并在 Steam 退出并再次启动后成为 Steam 条目。不可用的客户端或离线首次启动回退到快捷方式；现有快照在离线时仍然可用。Steam 在启动时仍然强制执行当前许可证。

对于没有清单的已确认游戏，导入器将源文件夹链接到内部 Steam 库，并写入一个已安装清单，其中 `Universe=1` 和 `StateFlags=4`。Steam 忽略没有 universe 字段的清单。这注册现有文件；它不声称经过验证的构建 ID 或 depot 清单。Steam 仍然可能要求验证、更新或可再发行组件。Steam 更新和卸载作用于链接的游戏文件，因此导入的文件夹必须是可写的。现有 Steam 清单和目录被保留。只有早期导入器生成的精确最小清单被迁移，且匹配的源符号链接仍然存在。如果注册失败，游戏保留为快捷方式。一旦 Steam 接管安装，就在 Steam 中管理它；忘记一个 Added Games 文件夹只会移除其快捷方式。

该账户的 `.droiddeck-routes.json` 将快捷方式 ID 映射到真实 Steam 应用 ID，以便 Android 启动器使用与 Steam 相同的标题和 Proton prefix。其他账户不继承这些路由。受管理的快捷方式被替换；用户创建的快捷方式和不可读的快捷方式文件被保留。两条路由都使用现有的 ARM64 Proton 注册。

## 验证

运行 `python3 -m unittest discover -s tools/tests -p 'test_*.py'` 和 Android 单元测试。测试覆盖身份和版本匹配、网络恢复、账户隔离、拥有/未拥有/未知路由、遗留清单迁移、清单保留、文件系统冲突、快捷方式保留，以及版本化 IPC 调用/清理契约。2026-09-28，ARM64 Steam 客户端（1790545198）通过 ISteamApps 确认了 Final Fantasy VII Rebirth (2909400)、Final Fantasy VII Remake Intergrade (1462040) 和 Final Fantasy XV (637650) 的所有权和安装状态，所有三个安装路径都解析到原始 second-library 文件夹。旧的受管理快捷方式被移除。这验证了库注册，而不是完整的游戏启动。未拥有回退和账户隔离由自动化测试覆盖。

参考：[Steam Apps
API](https://partner.steamgames.com/doc/api/ISteamApps#BIsSubscribedApp)，[Valve 的
版本化接口](https://github.com/ValveSoftware/Proton/tree/proton_11.0/lsteamcli
ent/steamworks_sdk_154)。