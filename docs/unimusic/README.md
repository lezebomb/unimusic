# UniMusic 开发记录

基于 NeriPlayer 继续开发的原生 Android 统一音乐客户端；保留上游实现和 GPL 许可，采用渐进模型/数据库迁移。

当前：Phase 1A、1B.1 Provider 与 1B.2 导入后台已完成。Room v22 支持持久化任务、原库兼容分批事务、取消/重试/恢复和关联刷新；新增 8 个关键 JVM 测试通过，本轮 Debug 构建成功。导入 UI、完整音源匹配与真实账号/设备验收仍待完成。

- [源码架构审计](ARCHITECTURE_AUDIT.md)
- [真实构建/检查基线和恢复条件](BASELINE.md)
- [当前 Android 开发环境与复现命令](TOOLCHAIN.md)
- [Phase 1A 实现、验证与实际边界](PHASE_1A.md)
- [Phase 1B.1 网易云只读导入 Provider](PHASE_1B_PROVIDER.md)
- [Phase 1B.2 持久导入后台和调用接口](PHASE_1B_BACKEND.md)
- [分阶段开发、文件清单与验收](IMPLEMENTATION_PLAN.md)
- [统一模型、导入和无损迁移设计](DATA_MODEL_AND_MIGRATION.md)
- [性能与匹配质量测量协议](PERFORMANCE_PROTOCOL.md)
- [上游来源、子模块和许可证](SOURCE_PROVENANCE.md)

```powershell
python tools_pub/unimusic/baseline.py
```

该命令只执行轻量结构检查并记录环境。Android 模式和未验收项目详见基线文档，结构检查通过不能证明在线音乐可用。
