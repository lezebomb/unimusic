# Phase 2J：GD 公开音频线路与当前 API 调研

日期：2026-10-27，基于 c9a08ba1。Room 保持 v23；没有迁移或用户数据删除。

## 已接入 App 的线路

新增 `gdstudio_netease` 原生音频适配器。播放器“来源”菜单可看到“GD音乐台 · 网易云 ID”，可以显式校验、确认、拒绝或清除。原网易云歌曲直接提供其精确 ID；其他来源只能复用已发现的网易云精确 ID 和原有严格匹配，不用 GD 的同名搜索结果猜原唱。GD 不是网易云官方直连。

仅打开候选菜单不会请求 GD 取流接口。用户点击校验或确认后下次播放，先用正常网易云详情复核 ID、歌手、版本、时长，再匿名请求 GD 的公开开发者接口；不会向 GD 传平台 Cookie、密码或第三方登录票据。没有将所有失败歌曲自动送往 GD，也没有将其写入 2I 的自动成功缓存。

GD 没有合格地址时，继续原有正常源/备用链路。显式确认的公开来源阶段最多 5 秒、每候选最多 4.5 秒，继续受整轮共享 8 秒解析预算限制；GD 请求自身 4 秒超时。预算针对本次实测冷连接客户端耗时调整，不是首声或网络成功保证。

客户端每个进程每 5 分钟最多 50 个请求，超限立即返回，不排队和重试。限制不能控制同 IP 的其他软件，仍以服务端响应为准。仅接受 HTTPS、无 URL 内凭据、预期网易云 CDN 子域名和 MP3 地址；空 URL、0 码率、代理/任意主机、清单及错误响应均不当作成功。API 的 size 字段实测与文档单位不一致，没有把它写成可信长度或完整音频证明。

## 在线播放范围

按 [GD 官方文档](https://music-api.gdstudio.xyz/api.php) 当前的个人学习、非商用及禁止下载/传播说明，本线路只提供在线播放，并显示 GD音乐台（music.gdstudio.xyz）归属。

- 媒体 URL 带内部 fragment 标记，不进入 HTTP 请求。播放数据源在 CacheDataSource 外按此标记选择上游，绕过磁盘音频缓存的读写；即使播放器未采用 custom cache key，也保留该策略。
- 两个媒体预取入口都排除该标记。不把 GD 客户端接到下载模块；原站下载继续使用原有原站接口与权限。
- 播放链接分享入口和 Listen Together 公共 URL 白名单策略均拒绝仅在线播放的地址。歌名/原平台元数据不因此删除。
- 不引入 LSPatch、流量劫持、会员状态改写或 DRM Hook。

这表示开发者公开接口已接线，不表示我们核实了第三方全部内容的授权链、保证所有商业歌曲可听，或完成了真机播放。

## 实际联网结果

在本机普通 HTTPS 下进行了少量匿名请求：

| 精确网易云 ID | 请求音质 | API 响应 | CDN HEAD |
| --- | --- | --- | --- |
| 186016 | 128 | HTTP 200，空 URL / br=0 | 未请求 |
| 28815250 | 320 | HTTP 200，返回 URL | HTTP 200，audio/mpeg，12,087,507 bytes |
| 436514312 | 320 | HTTP 200，返回 URL | HTTP 200，audio/mpeg，13,137,546 bytes |

HEAD 两次分别约 0.14/0.08 秒；API 请求约 1.67/2.33 秒。它们是桌面网络单次样本，不是 App 首声或切源性能。额外以**实际 GdStudioClient Kotlin 类**执行了 1 个手动启用的联网 smoke，ID=28815250，获得合格地址；该用例含客户端构造约 3.431 秒。没有请求音频响应体或保存音频。

GD 搜索“晴天 周杰伦”的前两条返回的是其他歌手/版本，进一步说明不能采用旧笔记的“第一条兜底”。本轮只用精确网易云 ID 解析，不将这次搜索结果映射进用户音乐库。

脱敏结果见 [network-probes.json](evidence/2026-10-27/phase2j/network-probes.json)。带签名 CDN URL 的原始响应只保留在被忽略的 `build/unimusic-toolchain/source-probe-2026-10-27/`，未提交。

## 用户要求的其他软件与 API

| 一手资料 | 实际机制与当前结论 |
| --- | --- |
| [MusicFree 官方示例插件](https://github.com/maotoumao/MusicFreePlugins) | 原示例使用公开接口并过滤收费/试听；README 当前表示已移除国内音乐厂商源。不能据旧插件列表认定仍可用。 |
| [LX 自定义源协议](https://lxmusic.toside.cn/desktop/custom-source) | 元数据交给源脚本，musicUrl 请求返回播放地址。平台名和脚本协议本身不提供账号或完整曲库。 |
| [InnerTune](https://github.com/z-huang/InnerTune)、[Spotube 当前 README](https://github.com/team-spotube/spotube/blob/master/README.md) | 前者以 YouTube Music 为来源，后者描述插件架构；不是全部商业平台的匿名完整音频 API。 |
| [NeteaseCloudMusicApiEnhanced/api-enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced) | 持续维护的第三方网易云 API，可自建服务或调用库；README 同时含“解灰”功能。可研究普通搜索/详情/账号接口，不直接部署其默认附加解灰功能。UniMusic 已有原生网易云客户端，无需为获取同一目录重复部署 Node。 |
| [QQMusicApi](https://l-1124.github.io/QQMusicApi/)、[取流说明](https://l-1124.github.io/QQMusicApi/tutorial/download/) | 有正常搜索、详情、CDN 和音频地址接口；取流需要凭证，并有无权限、票据失败、设备限制结果。试听不等于完整歌曲。项目中已有 QQ 搜索/歌词/详情客户端，但还没有 QQ 账号仓库或完整播放适配器。 |
| [腾讯云官方 QQ 音乐服务](https://cloud.tencent.com/document/product/1081/67456) | 存在官方登录授权/歌曲/免登录专区能力，适用于其 H5/IoT SDK 环境，不能直接当成任意 Android 客户端免账号取流 API。未进行该业务接入。 |

未在未知第三方演示服务输入 Cookie，未使用别人账号或票据，未把可见源码、接口 HTTP 200 或库许可当成音乐内容授权。

## 验证

8 个直接相关检查通过：GD 客户端 3 个离线契约/边界检查、数据源绕过缓存 1 个、原网易云最新元数据 1 个、中央分享 URL 策略 2 个、实际 Kotlin 客户端联网取址 1 个（人工设置环境变量启用，默认 CI 不联网）。Debug APK、模块边界和新增平台客户端依赖范围检查通过；最终结果及哈希见 [validation.json](evidence/2026-10-27/phase2j/validation.json)。

日志：`phase2j-validation.log`、`phase2j-live-and-final.log`、`phase2j-final-build.log`、`phase2j-platform-boundaries.log`、`phase2j-ltw-boundaries.log` 和 `phase2j-boundaries.log`，均在 `build/unimusic-toolchain/`。

没有手机/模拟器安装、真实账号、实际音频解码/播放、GD 来源菜单交互、完整性/原唱标注或播放性能验收。用户已追加要求分别登录平台；下一阶段优先检查现有网易云/B站/YouTube 入口，再补 QQ 正常登录和安全凭证链，不把拥有账号当作拥有全部歌曲权限。
