# UniMusic 开发记录

基于 NeriPlayer 继续开发的原生 Android 统一音乐客户端；保留上游实现和 GPL 许可，采用渐进模型/数据库迁移。

当前：已完成统一库、网易云持久导入后台及 2A–2E 音源/搜索子阶段，并接入歌单导入界面、本地推荐和持久来源选择。Room 当前 v23；用户确认/拒绝不会被后台旧结果覆盖。酷我仍为独立元数据目录。真实账号/设备播放、统一 UUID 临播与跨设备新映射同步继续开发，验证依据见阶段报告。

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
