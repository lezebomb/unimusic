# 免费音乐客户端的音源机制调研

查看日期：2026-10-26。按用户要求查看项目自身文档和公开接口说明；下列结论描述架构，不能当成这些软件或每个第三方插件当前可播放的实测结果。

| 项目及一手资料 | 实际获取方式 | UniMusic 可借鉴的部分 |
| --- | --- | --- |
| [MusicFree](https://github.com/maotoumao/MusicFree) | 播放器自身不集成平台音源，CommonJS 插件分别实现搜索、播放、专辑、歌单导入和歌词；播放器处理分页与缓存。具体音频来自插件实现，项目名称中的“免费”不代表内置了完整商业曲库。 | 用统一 Provider 接口装配各来源，分开元数据发现和音频解析。 |
| [LX Music 自定义源协议](https://lxmusic.toside.cn/desktop/custom-source) | 应用把歌曲元数据和期望音质传给源脚本；脚本的 `musicUrl` 请求返回播放地址。协议列出各平台标识和音质能力，并没有让播放器自身持有所有音频。 | 显式声明来源能力、按需解析 URL、按来源报告失败，避免把搜索成功视为取流成功。 |
| [InnerTune](https://github.com/z-huang/InnerTune) | 项目直接说明以 YouTube/YouTube Music 为搜索、音频与视频来源，支持账号与缓存。它不是网易云/QQ 会员音频的统一授权服务。 | 复用 UniMusic 已有且可关闭的 YouTube 接入，保留平台与具体视频身份。 |
| [Spotube 当前 README](https://github.com/team-spotube/spotube/blob/master/README.md) | 当前版本描述为插件驱动：元数据、歌单和音频来源可由社区或用户提供。不能把历史版本的“固定 Spotify 元数据 + YouTube 音频”概括为所有当前实现。 | 将目录/歌单 Provider 和 Audio Source 的职责分开，避免绑定唯一源。 |

## 本轮落实

- 新增原生 Kotlin 的 `FederatedSearchProvider` 与 `FederatedSearchEngine`，没有引入第三方脚本执行环境或下载未知源脚本。上述项目仅作为架构参考，未复制其源码。
- “聚合”一次查本地、网易云、B 站和已开启的 YouTube。此处复用了四个现有来源，并未新增 QQ/酷狗等尚无完整播放适配的平台，也没有声称四个来源都无需登录或网络条件。
- 各来源独立分页、错误和预算，首个批次立即显示；重复 ID 只在该来源中去重，不凭曲名合并跨平台录音。
- 搜索阶段不解析音频地址，点击播放继续走原有平台解析器和 Phase 2A 的备用源门槛。搜索版本排序是展示相关性，不是同录音证明。

下一步增加新 Provider 时，需分别交付搜索、正常播放能力、账号/区域/外部播放器限制和真实响应验证；只有元数据接口就标为元数据能力。原生 Provider 接口不等于已经兼容 MusicFree/LX 的插件协议。
