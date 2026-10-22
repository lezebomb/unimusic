# Phase 1B.1：网易云只读导入 Provider

日期：2026-10-21。已完成可调用的入站元数据适配器；完整批量导入任务和界面尚未接入。

## 本次实现

- model 提供 `PlaylistImportProvider` 与歌单分页、完整有序 ID 快照、歌曲批次、逐项失败和类型化错误 contract；接口不传 cookies、音频 URL 或远端写操作。
- platform 的 `NeteasePlaylistImportProvider` 复用 NeteaseClient 安全会话与可取消请求，按页读取创建/收藏歌单，从详情读取 trackIds 快照，再按最多 200 个 ID 获取歌曲元数据；响应顺序重排为原请求顺序。
- 快照包含公开账号 ID、来源、歌单名称/封面及修订摘要。trackCount 不等于完整 ID 数时拒绝不完整快照；详情中缺失的歌曲显式返回失败条目，不静默缩小导入总数。
- 每次操作验证服务端账号 ID，并在请求前后检查不含凭据的内存会话对象身份。授权过期、账号切换、访问拒绝、缺失项、无效响应与临时错误分开返回，协程取消原样传播。
- local 的共享 testFixtures 增加 FakeMusicProvider，可配置分页、快照变化、缺失项、失败类型、授权变化和请求边界延迟，后续导入任务可离线开发。
- `unimusic_smoke.yml` 为 main 新增定向检查，保留原上游 CI；选择本轮相关 JVM 测试和数据库 Android 迁移测试，不在每次 main push 执行全部上游测试。

## 实际验证

3 个离线 Provider 测试全部通过，覆盖分页及创建/收藏歌单、有序快照/详情/封面、缺失项、空歌单与不完整响应、错误账号、授权过期、请求中换号及取消传播。完整应用 Debug 构建成功；未执行真实网易云账号或远端平台请求验收。GitHub 工作流已配置，运行结果需在推送后的 Actions 中确认。

原始 [JUnit XML](evidence/2026-10-21/phase1b/TEST-moe.ouom.neriplayer.platform.netease.importing.NeteasePlaylistImportProviderTest.xml) 已归档；工作流 YAML 解析通过，Android 两个迁移测试类分别筛选，保留独立报告。

2026-10-22 核对发现首次远端 [Actions 运行](https://github.com/lezebomb/unimusic/actions/runs/39563381265) 在安装 SDK 包时失败：runner 的 PATH 没有 sdkmanager，退出码 127，尚未执行测试。工作流已改为从 ANDROID_HOME / ANDROID_SDK_ROOT 定位 SDK 内的可执行文件，与现有 NDK 安装 action 保持一致；修复提交触发新的远端验证，不能把本地测试通过记作该次远端成功。

## 下一步：Phase 1B.2 / 1C

1. 使用新的独立增量迁移增加 ImportJob/ImportFailure 与快照条目，不改写已提交的 20→21 迁移；账号/来源唯一任务，页级事务同时提交兼容歌单、UUID 关系、进度与同步 mutation。
2. 加入取消/重试/进程恢复、失败明细、来源重复导入/刷新、跨页重复和无进展检测。远端空列表、授权失败、不完整快照不删除本地内容；用户修改与删除状态优先。
3. 接入 AppContainer / LibraryViewModel 和导入选择、进度、重试/取消/解除关联界面。当前版本没有对用户暴露半成品导入按钮，也没有声明已能完成账号批量导入。
