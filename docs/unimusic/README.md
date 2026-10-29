# UniMusic 开发记录

[当前搜索规则与边界](SEARCH_BEHAVIOR.md)：歌手/作品意图、跨源录音证据、版本和可播状态、列表锚定及实际验收。

最新：[3F推荐缓存上下文](PHASE_3F_RECOMMENDATION_CACHE_CONTEXT.md)：日缓存核对清除代次/反馈开关和健康状态；4 JVM/Debug通过，保留用户记录与旧缓存兼容。

最新：[2BB并行歌手索引](PHASE_2BB_PARALLEL_ARTIST_LOOKUP.md)：歌曲与歌手索引并行，保留取消/权限/录音门槛；4 JVM/Debug通过，无真机速度声明。

最新：[3E试听偏好隔离](PHASE_3E_PREVIEW_FEEDBACK_ISOLATION.md)：明确试听不产生跳过/听完/重播偏好，实际收听统计保留；4 JVM/构建/装机通过，断连后设备采样待补。

最新：[2BA录音核对与搜索锚点](PHASE_2BA_RECORDING_EVIDENCE_AND_SEARCH_ANCHOR.md)：补DJ/混合语言版本标签；跨源同录音核对后再提升，后台重排保持真正顶部，原词实机稳定。

最新：[2AZ持续试听标识](PHASE_2AZ_PERSISTENT_PREVIEW_NOTICE.md)：播放器持续显示试听状态，保留缓存证据；Mate60两段试听实际可见提示，完整版未验收。

最新：[2AY歌手/作品搜索意图](PHASE_2AY_ARTIST_AND_WORK_SEARCH.md)：乐队查询优先相关歌手及署名歌曲，聚合/网易云实机通过；样本仅20秒试听，完整版未验收。

最新：[2AX可用来源与推荐v4](PHASE_2AX_ACTIVE_SOURCES_AND_RECOMMENDATION_CREDITS.md)：QQ/YT按用户要求停用，保留数据；推荐合唱成员偏好与多样性。乐队查询翻唱排序已在2AY修正。

最新：[2AW网易云搜索可播性](PHASE_2AW_NETEASE_SEARCH_AUDIO_CHECKS.md)：限量后台校验/同版本排序，手机点击地址已校验结果已实际播放；账号歌单保留。

最新：[2AV服务失败与四路实播](PHASE_2AV_SOURCE_SERVICE_ERRORS_AND_DEVICE_AUDIO.md)：换网后网易云/酷我/B站/GD样本解码，服务失败单独处理；QQ和YouTube仍未实播。

最新：[2AU播放失败反馈](PHASE_2AU_PLAYBACK_FAILURE_FEEDBACK.md)：网络/超时不再统一误报权限或登录；已保留数据装机。

最新：[2AT手机播放/会话恢复](PHASE_2AT_DEVICE_NETWORK_AND_SESSION_RECOVERY.md)：用户换网后酷我与网易云实际解码通过，新包已保留数据安装；受限歌曲仍须当前账号权限。

基于 NeriPlayer 继续开发的原生 Android 统一音乐客户端；保留上游实现和 GPL 许可，采用渐进模型/数据库迁移。

当前以[续跑检查点](RESUME_CHECKPOINT.md)为准（2026-10-29）。Room为v23，Debug可构建；“发现／我的”、网易云批量导入、本地推荐反馈控制、独立歌曲标识同步入口均已交付对应子阶段。尚无正式Release。

| 功能 | 已有证据与边界 |
| --- | --- |
| 网易云导入/播放 | [2M](PHASE_2M_ACCOUNT_IMPORT_AND_DEVICE_AUDIO.md)：实际登录、两份5首导入、无重复重导和正常完整音频样本播放 |
| B站/GD、酷我 | B站/GD样本见2M；[2L](PHASE_2L_NATIVE_AUDIO_AND_PLAYABILITY.md)酷我正常免费音频样本实际解码/进度通过，不再是只有目录。覆盖率未验收 |
| QQ音乐 | 已按用户要求停用在线来源，保留历史身份/本地数据 |
| YouTube | 已按用户要求停用在线来源，保留原Google会话和历史数据 |
| 搜索/备用来源 | [2R](PHASE_2R_EXPLICIT_VERSION_AND_VOICE_GATES.md)保守原唱/版本门槛、[2AH](PHASE_2AH_COMPLETE_VERSION_RANKING.md)完整版本证据排序、[2AJ](PHASE_2AJ_NETEASE_SEARCH_VERSION_ALIASES.md)网易云搜索版本别名、[2AN](PHASE_2AN_ARTIST_CREDIT_BOUNDARIES.md)歌手署名边界、[2T](PHASE_2T_SELECTIVE_SEARCH_RETRY.md)单源重试、[2W](PHASE_2W_SEARCH_TIMINGS.md)分来源计时、[2X](PHASE_2X_SOURCE_FAILURE_ISOLATION.md)失败隔离、[2AD](PHASE_2AD_PARALLEL_REMEMBERED_SOURCES.md)缓存在线源并行、[2AE](PHASE_2AE_NATIVE_COOLDOWN_GUARD.md)原生故障冷却、[2AI](PHASE_2AI_CONTEXT_FAILURE_EFFECTS.md)旧上下文失败隔离、[2AK](PHASE_2AK_NATIVE_TO_NETEASE_FALLBACK.md)QQ/酷我→网易云备用、[2AM](PHASE_2AM_FOREGROUND_PLAYBACK_PRIORITY.md)点击播放取消后台校验、[2AO](PHASE_2AO_RECENT_PRIMARY_FAILURE_HINT.md)近期主源失败调度、[2AP](PHASE_2AP_KUWO_MEDIA_FAILURE_RECOVERY.md)酷我实际媒体失败恢复、[2AQ](PHASE_2AQ_NATIVE_FAVORITE_IDENTITY.md)出站收藏身份隔离、[2AR](PHASE_2AR_NATIVE_PUBLIC_PAGES.md)规范官方页面分享、[2AS](PHASE_2AS_DESKTOP_KUWO_TRANSPORT.md)桌面正常流传输诊断、[2AG](PHASE_2AG_SHORT_LIVED_SEARCH_METADATA.md)短期元数据复用；不承诺未测的速度/原唱准确率 |
| 后台计算 | [2U](PHASE_2U_CURRENT_SEARCH_EVIDENCE.md)并发证据、[2V](PHASE_2V_PREPARED_SEARCH_QUERY.md)查询复用、[2Y](PHASE_2Y_BACKGROUND_ONLINE_PAGES.md)在线页后台解析、[2Z](PHASE_2Z_COOPERATIVE_SEARCH_CANCELLATION.md)协作取消、[2AA](PHASE_2AA_NETEASE_SEARCH_SESSION.md)账号查询恢复 |
| 推荐/同步 | 3A～3C本机隐藏/反馈/清除；[3D.8](PHASE_3D8_GITHUB_IDENTITY_SYNC.md)GitHub/WebDAV歌曲身份/元数据子集与冲突选择，真实双设备/上传/Media3采样仍未验收 |
| 设备/CI | [4G](PHASE_4G_DEVICE_SEARCH_AND_RETRY.md)Mate60单次聚合搜索与失败源重试；[4H](PHASE_4H_GENRE_SELECTION_SEMANTICS.md)分类选中语义、最新2AR已装，[2AL](PHASE_2AL_OFFLINE_SEARCH_FEEDBACK.md)网络提示已实测；本轮联网请求失败，新反向音频/缓存命中待验收；2AD/2AE/2AF远端CI成功 |

各报告明确区分合成测试、构建、地址校验和真实音频；新标识同步不上传账号/本地路径/播放权限，不重写旧库ID、关系和显示字段。保留已登录账户与歌单，禁止destructive migration。工具链复用及未执行项见检查点。

- [源码架构审计](ARCHITECTURE_AUDIT.md)
- [真实构建/检查基线和恢复条件](BASELINE.md)
- [当前 Android 开发环境与复现命令](TOOLCHAIN.md)
- [Phase 1A 实现、验证与实际边界](PHASE_1A.md)
- [Phase 1B.1 网易云只读导入 Provider](PHASE_1B_PROVIDER.md)
- [Phase 1B.2 持久导入后台和调用接口](PHASE_1B_BACKEND.md)
- [Phase 2A 音源获取、严格筛选与实际限制](PHASE_2A_SOURCES.md)
- [Phase 2B 聚合搜索、分页与版本排序](PHASE_2B_SEARCH.md)
- [Phase 2C 酷我目录后台与自动候选优化](PHASE_2C_CATALOG_AND_MATCHING.md)
- [Phase 2D 目录接入、身份隔离与排名修正](PHASE_2D_CATALOG_SEARCH.md)
- [Phase 2E 持久来源选择、导入操作和本地推荐](PHASE_2E_SOURCE_CHOICES_AND_LIBRARY.md)
- [Phase 2F UUID 临播、四源候选和每日快照](PHASE_2F_UUID_RESOLUTION_AND_DAILY_CACHE.md)
- [Phase 2G 来源能力与显式校验](PHASE_2G_SOURCE_AVAILABILITY.md)
- [Phase 2H 搜索并发、共享解析预算与原唱匹配边界](PHASE_2H_LATENCY_AND_ORIGINAL_MATCHING.md)
- [提供 APK 的 REA 6.8 静态审计与证据](APK_SOURCE_AUDIT.md)
- [MusicFree、LX、InnerTune 与 Spotube 音源机制调研](MUSIC_SOURCE_RESEARCH.md)
- [分阶段开发、文件清单与验收](IMPLEMENTATION_PLAN.md)
- [统一模型、导入和无损迁移设计](DATA_MODEL_AND_MIGRATION.md)
- [性能与匹配质量测量协议](PERFORMANCE_PROTOCOL.md)
- [上游来源、子模块和许可证](SOURCE_PROVENANCE.md)

```powershell
python tools_pub/unimusic/baseline.py
```

该命令只执行轻量结构检查并记录环境。Android 模式和未验收项目详见基线文档，结构检查通过不能证明在线音乐可用。
