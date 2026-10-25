# Phase 1B.2：持久化导入后台

日期：2026-10-25。已实现 Android 客户端内的数据层和后台执行器，不依赖手机连接；本轮没有新增外部 HTTP 服务或导入界面。

## 已实现

- Room v21→v22 独立增量迁移新增 3 张表：唯一账号/来源任务、带顺序和失败明细的快照条目、跨快照保留的导入凭证。原 v21 迁移和原有用户表保持不变。
- 每个任务持久保存状态、完整去重快照、逐项结果、重试时间与执行权。每批最多 200 首，默认 100 首；每次 Worker 最多 20 批。暂停/进程退出后按未提交条目续跑，过期执行权可被新实例接管，旧实例不能写入。
- 网络请求在事务外执行。一个页面内的兼容歌单/成员/因果 tokens、统一 UUID/来源关系、导入凭证、结果水位和既有同步 outbox 一起提交；成功提交后才发布仓库状态。数据库写入失败整页回滚，禁止回退为独立 JSON 写入。
- 本地歌单写锁复用现有 LocalPlaylistRepository，避免后台页覆盖用户并发修改。保留名称、封面、原有歌曲和用户排序；完整初次导入按源顺序排列，失败条目补回时仅在没有用户重排证据时恢复源位置。
- 跨快照凭证识别用户已移除的歌曲，不因刷新而复活。远端空歌单/不完整响应/失败不删除本地曲目；用户删除整个目标歌单后任务停止，不自动重建。
- 取消先撤销执行权；失败项带原位置、次数和退避时间，明确区分待登录、账号变化、部分失败、临时错误和本地存储问题。所有请求结果必须覆盖完整请求批次，缺项/重项响应不能推进水位。
- UI 登录和导入 Worker 共享安全会话仓库；请求与提交之间检查内存会话代次。数据库不保存 cookies、token、响应全文或音频 URL。
- WorkManager 持久化执行，按任务唯一调度、按账号串行请求。ONE_SHOT 保留追溯但不关联；LINKED 支持显式刷新，解除关联取消任务并保留本地内容。**尚未启用定期自动刷新。**
- 同步 outbox 增加向后兼容的本地数据变更标记；保留旧记录解码，让已保存但未成功调度同步的页面在重启后重新调度。未改写云端协议，也未实现统一 UUID 的跨设备 alias 同步。

## 后台入口

AppContainer 已装配 `playlistImportBackend`，后台/后续 UI 可调用：

```kotlin
val backend = AppContainer.playlistImportBackend
val catalog = backend.listPlaylists(publicAccountId)
val jobId = backend.enqueue(publicAccountId, remotePlaylistId, PlaylistImportMode.LINKED)
backend.observeJobs() // Flow：状态、总数、成功/失败/跳过数量
backend.progress(jobId)
backend.failures(jobId) // 分页失败明细
backend.cancel(jobId)
backend.retry(jobId) // 遵守失败项退避时间
backend.refresh(jobId) // 仅刷新仍关联的来源
backend.unlink(jobId)
```

账号参数为网易云公开数字用户 ID，不接受凭据代替；实际网络读取仍要求已有登录会话。重复 enqueue 复用同一个任务和歌单；显式重新导入可重新关联此前解除的来源，普通 refresh 不会自行重新关联。

## 实际验证和限制

- 新增 7 个导入集成测试通过：磁盘数据库重开续跑/幂等、失败项重试及顺序、刷新/用户删除/解除关联、撤销后旧结果拦截、授权/换号/不完整响应、整页回滚、同步 outbox 重启恢复。
- 新增 1 个迁移测试通过：从冻结 v21 schema 建库，经过 21→22 后由 Room 验证整个 v22 schema，逐字段保留原 6 张统一关系表及删除状态、来源关联；外键检查通过。此项使用 Robolectric/本机 SQLite，**不是本轮 Android 设备迁移测试**。
- 同时选择执行已有统一库 store、同步 outbox 解码、迁移登记与 Provider 检查；没有运行全量上游测试。模块边界检查通过。
- 数据层编译、应用 Debug APK 和 database Android 测试 APK 构建通过。本轮没有手机连接，也没有安装或启动新 APK、运行真机或模拟器测试；历史 v21 的模拟器结果不可替代本轮 v22 验收。
- 原始 JUnit XML 与构建/日志哈希见 `evidence/2026-10-25/`。本轮未进行真实账号导入、在线播放、性能、功耗或 Release 签名验证。远端 CI 结果以新提交对应的 Actions 为准。

下一步是 Phase 1C 导入选择/进度/取消/重试界面。音源问题尚未完全解决：当前仍沿用上游播放器；Phase 2 的同版本严格匹配、临播解析与失败切源没有实现，不能把元数据导入成功视为可播放。
