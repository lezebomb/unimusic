# Phase 2I：复用音源笔记中的原站优先与持久映射

日期：2026-10-27。基于 `0c4df844`，Kotlin 原生接入现有 UUID/播放链，Room 保持 v23。

## 本轮实际改变

用户提供的 2024 年笔记中，可直接复用的核心是：保留正常原站、按失败条件进入备用来源、记住成功的来源 ID、失效后重新搜索。本轮补齐的是**自动成功映射持久化和复用**；原有人工确认、严格匹配、失败冷却及共享解析预算继续复用，不将它们记为本轮新实现。

原来 B站自动来源只有进程内 10 分钟缓存；Room 的普通候选观察不能证明自动解析成功，只有 CONFIRMED 会参与持久来源选择。现在自动解析通过元数据/版本/权限检查且获得地址后，将来源 ID 和证据保存在现有 `song_match`。重启后可直接重新解析该 ID，跳过关键词搜索，不保存临时媒体 URL。

自动链路为：

1. 原网易云正常完整音频立即返回，保留已有音质降级逻辑。
2. 原站失败/超时/仅试听后，尝试已保存的自动映射；缓存候选本地优先、随后按最近成功时间排序。
3. 缓存未命中或复核失败，继续已有的本地匹配、用户启用的 B站自动发现。
4. 各来源均失败时，按既有设置返回试听或登录/失败状态。

人工显式确认的来源继续作为用户偏好在原站前尝试；这与自动替补来源分开。缓存不使关闭的自动切源功能重新启用；内部调用的 allowLocalFallback/allowAutoBiliFallback 限制也继续生效。

## 缓存、速度与一致性

- `algorithm_version=1` 仍是普通发现/显式探测证据；v2 表示通过当前严格自动规则且获得可用地址。它不表示真实播放完成，不给来源授予权限。
- 自动映射 TTL 为 24 小时；目标标题、歌手、专辑、时长、版本指纹变化即失效。查询排除未来时间证据。展示/显式探测同一条未变化的候选不续期、不升级缓存。
- Room 专用查询按目标 UUID、指纹、算法、时间窗口、NONE 决策过滤，最多返回 8 条；运行时跳过冷却来源后最多试 2 条。复核按精确键读取一条记录，不再重复扫描 200 条展示列表和逐条读取所有人工决策。
- 共享解析预算内，自动缓存阶段最多 1.2 秒，每条最多 1 秒；它继续受原有总 8 秒协程预算约束。独立调用时为 2.5/2 秒。这些是代码预算，并非实际网络/首声测量；不保证阻塞式调用都及时取消。
- NO_MATCH/NO_PERMISSION/PREVIEW_ONLY/UNAVAILABLE 会使旧自动证据失效；网络/超时保留 ID 并短期冷却。缓存和关键词搜索统一使用 B站 aid 标识，避免同一个失败视频在同一轮再次请求。
- 自动映射命中仍要求最新视频和精确 CID、歌手名称佐证、版本/时长相容、正常完整非 DRM 音频和当前会话。已缓存 CID 不等同用户手工确认，不降低首次自动匹配的门槛。
- 写缓存前事务比较 SourceDecisionStamp；清除、拒绝或确认增加 revision，在途旧结果不能重新启用旧决策。清除同时去掉自动证据。失效操作带旧观察时间和 revision，只修改仍匹配的旧记录，不能抹掉较新成功或用户覆盖。
- 自动媒体使用 `match-<UUID>-<实际音源表示>` 缓存键，复用现有预取交接和播放故障冷却保护。队列和收藏身份不随物理来源改变。

没有采用笔记中“前五个时长接近就取第一条，找不到就取第一条”的规则。继续保留标题、歌手、版本、时长和歧义检查；名称佐证仍不是经过验证的歌手身份，也没有音频指纹，不能承诺 100% 原唱。

## 2024 接口的当前核实

2026-10-27 本机普通 HTTPS GET [GD 官方 API 文档](https://music-api.gdstudio.xyz/api.php) 返回 HTTP 200，文档更新日期为 2026-09-16。其当前列出的可用源为 netease、joox、bilibili，不含 kuwo；注明每 5 分钟最多 50 次请求、API 使用需注明 GD音乐台，并限制下载、传播和商用。这是服务方文档，不是单曲可播放/内容授权验证。没有请求第三方曲目 URL 或音频。

[上游发布记录](https://github.com/UnblockNeteaseMusic/server/releases)可见 v0.28.0 修复过酷我搜索，增加 bilivideo/bodian，并调整默认来源；[GD 作者说明](https://github.com/UnblockNeteaseMusic/server/issues/1395)也表明接口曾更换、公开使用需遵守 API 页面规则。不能据 2024 样本固定 pyncmd→kuwo 的字符串判断当前可用性。

本轮未新增 GD 或酷我音频适配器，酷我仍为 METADATA_ONLY。GD 的公开文档可访问不等于该音源已在 App 中可播；其使用范围与现有缓存/下载功能的衔接仍需明确。不移植 LSPatch/Node 流量劫持、伪造 package URL 或会员/DRM Hook；核心调度直接接入原生播放器。

## 验证和产物

仅执行 3 个直接相关测试类，结果和 APK 哈希以 [validation.json](evidence/2026-10-27/phase2i/validation.json) 及相邻原始 JUnit XML 为准：

- SourceMatchRoomStoreTest：5 个方法，新增 2 个，涵盖真实 Room 磁盘重开、普通观察不晋升/不续期、过期/时钟回退/目标编辑、精确读取、拒绝/清除修订和旧失效操作不能覆盖新成功。
- BiliRecordingMatchTest：7 个方法，增加新缓存键的真实故障/离线例外断言。
- PlayerManagerNeteaseAutoSourceSwitchTest：3 个现有物理音源键检查。
- 模块边界和 Debug APK 构建。

首次新磁盘重开测试遇到 SQLiteCantOpenDatabaseException；缩短测试方法名后通过，与已知 Windows 临时路径长度问题一致。未改生产数据库设置。初次失败日志保留为 `build/unimusic-toolchain/phase2i-validation.log`，修正后为 `phase2i-validation-retry.log`；最后专用 SQL 查询修改的验证记录为 `phase2i-final.log`。

命令：

```powershell
.\gradlew.bat '-Dorg.gradle.java.installations.paths=D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1' :local:testDebugUnitTest --tests moe.ouom.neriplayer.data.unified.SourceMatchRoomStoreTest :playback:runtime:testDebugUnitTest --tests moe.ouom.neriplayer.core.player.resolver.netease.BiliRecordingMatchTest --tests moe.ouom.neriplayer.core.player.resolver.netease.PlayerManagerNeteaseAutoSourceSwitchTest :app:assembleDebug --max-workers=2 --console=plain
python -X utf8 tools_pub/quality/module_boundaries.py
```

没有设备/模拟器安装、真实账号播放、切源耗时/首声、人工原唱标注集或音频指纹验收。没有新增音源覆盖率结论。上阶段 [2H CI](https://github.com/lezebomb/unimusic/actions/runs/40428919546) 已确认成功，本阶段 CI 需按新提交单独检查。
