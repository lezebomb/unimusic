# UniMusic 开发记录

基于 NeriPlayer 继续开发的原生 Android 统一音乐客户端；保留上游实现和 GPL 许可，采用渐进模型/数据库迁移。

当前以[续跑检查点](RESUME_CHECKPOINT.md)为准（2026-10-28）。Room为v23，Debug可构建；“发现／我的”、网易云批量导入、本地推荐反馈控制、独立歌曲标识同步入口均已交付对应子阶段。尚无正式Release。

| 功能 | 已有证据与边界 |
| --- | --- |
| 网易云导入/播放 | [2M](PHASE_2M_ACCOUNT_IMPORT_AND_DEVICE_AUDIO.md)：实际登录、两份5首导入、无重复重导和正常完整音频样本播放 |
| B站/GD、酷我 | B站/GD样本见2M；[2L](PHASE_2L_NATIVE_AUDIO_AND_PLAYABILITY.md)酷我正常免费音频样本实际解码/进度通过，不再是只有目录。覆盖率未验收 |
| QQ音乐 | 正常搜索/音频接口和登录入口已接，匿名拒绝正确保留；真实QQ账号正向音频仍未验收 |
| YouTube | [2P](PHASE_2P_GOOGLE_LOGIN_AND_AUTHENTICATED_SOURCE_PROBE.md)Google会话验证通过，实际音频仍失败；按用户要求暂缓取流排查 |
| 搜索/备用来源 | [2R](PHASE_2R_EXPLICIT_VERSION_AND_VOICE_GATES.md)保守原唱/版本门槛、[2AH](PHASE_2AH_COMPLETE_VERSION_RANKING.md)完整版本证据排序、[2AJ](PHASE_2AJ_NETEASE_SEARCH_VERSION_ALIASES.md)网易云搜索版本别名、[2T](PHASE_2T_SELECTIVE_SEARCH_RETRY.md)单源重试、[2W](PHASE_2W_SEARCH_TIMINGS.md)分来源计时、[2X](PHASE_2X_SOURCE_FAILURE_ISOLATION.md)失败隔离、[2AD](PHASE_2AD_PARALLEL_REMEMBERED_SOURCES.md)缓存在线源并行、[2AE](PHASE_2AE_NATIVE_COOLDOWN_GUARD.md)原生故障冷却、[2AI](PHASE_2AI_CONTEXT_FAILURE_EFFECTS.md)旧上下文失败隔离、[2AK](PHASE_2AK_NATIVE_TO_NETEASE_FALLBACK.md)QQ/酷我→网易云备用、[2AG](PHASE_2AG_SHORT_LIVED_SEARCH_METADATA.md)短期元数据复用；不承诺未测的速度/原唱准确率 |
| 后台计算 | [2U](PHASE_2U_CURRENT_SEARCH_EVIDENCE.md)并发证据、[2V](PHASE_2V_PREPARED_SEARCH_QUERY.md)查询复用、[2Y](PHASE_2Y_BACKGROUND_ONLINE_PAGES.md)在线页后台解析、[2Z](PHASE_2Z_COOPERATIVE_SEARCH_CANCELLATION.md)协作取消、[2AA](PHASE_2AA_NETEASE_SEARCH_SESSION.md)账号查询恢复 |
| 推荐/同步 | 3A～3C本机隐藏/反馈/清除；[3D.8](PHASE_3D8_GITHUB_IDENTITY_SYNC.md)GitHub/WebDAV歌曲身份/元数据子集与冲突选择，真实双设备/上传/Media3采样仍未验收 |
| 设备/CI | [4G](PHASE_4G_DEVICE_SEARCH_AND_RETRY.md)Mate60单次聚合搜索与失败源重试；[4H](PHASE_4H_GENRE_SELECTION_SEMANTICS.md)分类选中语义、最新2AK已装，手机联网异常下新反向音频/缓存命中待验收；2AD/2AE/2AF远端CI成功 |

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
