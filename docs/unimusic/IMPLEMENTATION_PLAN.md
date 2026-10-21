# UniMusic 分阶段实施与验收

状态日期：2026-10-21。工具链已补齐；[Phase 1A](PHASE_1A.md) 的 UUID、6 张增量表、分页回填及后台任务已实现，14 个相关 JVM 测试、4 个 Android 迁移测试通过，Debug APK 构建/模拟器安装启动成功。Phase 1B 开发中，统一 UI 和完整导入流程尚未交付。

## 执行约定

- 基于固定上游继续开发，保留播放器、数据库/同步链、稳定功能及 GPL 许可；每阶段提交独立 commit，较大阶段可按可审阅子阶段拆分 commit。完成后推送本仓库。
- 本地验证只选择变更直接相关的少量测试；不在每轮反复执行全部历史测试。身份、迁移、导入幂等、权限、同步冲突和队列这些关键断言不能省略。
- 所有阶段记录变更、设计、实际命令/结果、可获得性能数据、限制与下一步；没跑账号/真机测试就明确标注。
- 自动播放先保证版本正确和有权播放。无法确认时保留候选供用户选择，不用提高自动播放数量换取错误匹配。

## Phase 0：审计与基线（已完成）

产物：

- [ARCHITECTURE_AUDIT.md](ARCHITECTURE_AUDIT.md)：逐模块已有能力、复用点、缺口、性能与权限风险、拟修改入口。
- [SOURCE_PROVENANCE.md](SOURCE_PROVENANCE.md)：固定上游/子模块与 GPL 来源。
- [BASELINE.md](BASELINE.md) 及 evidence：实际工具链、构建失败、轻量结构检查和未执行项。
- [DATA_MODEL_AND_MIGRATION.md](DATA_MODEL_AND_MIGRATION.md)：统一模型、导入事务、兼容/回滚和同步设计。
- [PERFORMANCE_PROTOCOL.md](PERFORMANCE_PROTOCOL.md)：可复现测量约定，没有虚构毫秒或百分比。
- `tools_pub/unimusic/baseline.py`：标准库实现的环境/结构/可选 Android 基线运行器。

退出条件：审计结论有真实源码依据，基线确实尝试，阻塞如实归档；完成独立提交。继续 Phase 1 的条件为 JDK 17、API 37、上游 NDK/CMake/依赖可用，且能执行关键 Room 迁移验证。无需提供真实平台凭据来开发 Fake provider。

## Phase 1：统一库与网易云批量导入

按数据依赖拆分三个子阶段，避免一次替换旧 SongItem。

### 1A：统一模型与安全迁移

状态：已完成，详见 [PHASE_1A.md](PHASE_1A.md)。当前以兼容投影方式运行，UI 未切换统一库，跨设备 UUID 同步仍属 Phase 3。

- `modules/model/src/main/java/moe/ouom/neriplayer/data/model/unified/`：UnifiedSong、Source、Playlist、Membership、Origin、导入状态/权限类型。
- `modules/database/.../entity/unified/`、`dao/unified/`、`migration/library/UnifiedLibraryMigration.kt`：新增关系、唯一索引、20→21、schema；修改 `NeriUserDataDatabase.kt` 注册。
- `modules/local/.../data/unified/`：旧 identity 桥接、分批回填与可恢复进度，保持收藏/历史和同步 mutation。
- 验证：旧库升级 schema 与原表数据逐项不丢失、回填重复/中断重启、同源并发插入不重复、不同 B 站分 P 不合并。
- 产物：模型源码、Room schema/迁移测试、设计更新；未测量时性能仍标 N/A。

### 1B：入站 Provider 与导入任务

- model 定义 PlaylistImportProvider contract；platform 新增 Netease adapter，复用安全 session、分页歌单与可取消详情调用。
- local 新增导入 coordinator/WorkManager Worker，页级事务 checkpoint、账号检查、失败项重试、取消/恢复、一次性与持续关联。
- 复用 repository 写入路径并扩展事务入口，不把现有“向网易云上传收藏”误用为“从网易云导入”。
- FakeMusicProvider 放共享 testFixtures，控制分页、失败、空结果、授权过期、慢响应及 snapshot 变化。
- 验证：两次导入单一歌单、保留顺序/封面/元数据、空远端不删除、取消后恢复、失败明细重试、解除来源保留本地修改；只选覆盖这些行为的紧凑测试。

### 1C：现有库接入与操作入口

- 修改 `LibraryViewModel.kt`、现有 library screen/歌单菜单和 AppContainer 依赖装配。
- 新增导入选择/进度状态、重试/取消/解除关联入口；统一库由 provider 无关列表呈现，来源详情仅在菜单显示。
- 不提前重写导航/全屏播放器；Phase 4 再完成整套双标签视觉。
- 验证：一个 Compose 导入状态流程及一个 Fake provider→Room→旧库适配器集成流程。实际账号导入单列待授权后验收。

阶段验收：旧音乐库无损，首次批量导入与重复刷新可靠，离线仍能读已导库；给出明确成功/部分失败/待登录状态，不声称全部曲目可播放。

## Phase 2：统一搜索与高精度匹配

1. 先做匹配安全基座：Normalization、VersionClassification、SongMatch/Override repository、硬冲突拒绝、来源资格类型。分析上游加分与分 P 双重累计，旧 70 分阈值不能迁成概率。
2. `UnifiedSearchEngine` 使用 Flow/Coroutines、supervisorScope/每源预算、平台限流、去抖和请求取消。先返回本地/首个远端批次，合并有证明的同录音；页游标、错误和加载状态按 provider 保存。
3. 复用 SearchTextMatcher 做展示相关性，新增版本/歌手别名/发行/时长/ISRC/MBID/可信渠道证据，不为搜索列表批量解析 URL。缓存按平台政策 TTL 与账号上下文隔离。
4. `SourceResolver(unifiedSongId)` 在播放前惰性解析：允许的本地源→高可信且当前有权播放的同版本在线源。失败类型涵盖网络、授权过期、下架、区域、会员、误配和 URL 过期；权限类不做伪造鉴权重试。
5. 接入 `PlayerManagerUrlExtensions` 与原 PlayerManager，队列/收藏身份不随 source 变化；合格首源先返回，备用源按独立预算准备。

文件入口：`model/.../unified`、platform 新 provider/matching、local 匹配持久化、`ExploreViewModel/ExploreScreen`、`playback/runtime/.../resolver` 及 `PlayerManagerNeteaseAutoSourceSwitch.kt`。

核心验证：同平台分页去重、跨平台可信映射聚合、版本冲突/相近候选拒绝、人工拒绝优先、一个平台失败其他批次仍显示、过期请求无结果污染、仅临播解析、失败切源不破坏队列。真实人工标注集单独评估：错误自动匹配数/自动接受数、召回覆盖率、拒绝率与样本量同时报告，研究目标 <1% 不能由几个合成单测代替。

## Phase 3：本地推荐与同步

- 保留平台每日推荐/私人 FM/雷达入口；新增本地内容/规则排序、显式 SKIP/DISLIKE/COMPLETE/REPEAT 事件和去重汇总。
- 每日快照保存 song UUID、栏目、理由、算法版本及来源标签；时间衰减、跳过降权、新颖性与歌手多样性，避免只推已熟悉歌曲。
- 修改 `HomeViewModel.kt` 接入本地栏目，在 UI 标明“网易云提供”或“UniMusic 推荐”；默认不上传播放历史到第三方推荐服务。
- unified dataset 采用独立 schema/capability，复用 GitHub/WebDAV 条件写与重试；保留旧 2.0 数据路径，验证旧客户端回写不会抹掉新字段。
- 同源 UUID 冲突通过 canonical alias 合并；用户拒绝、删除和离线新增使用因果信息，不能仅凭最后时间覆盖。

验证：推荐去重/跳过降权/多样性/来源标签；两设备离线并发、删除冲突、同源重复导入、老协议往返、新旧备份恢复、凭据白名单排除。各选紧凑关键集，不每轮运行整个上游套件。

## Phase 4：双标签 UI、端到端与性能

- 在现有 Compose navigation/scaffold 中合并为“发现/我的”；我的包含收藏/所有本地或已导歌单/历史/下载，平台信息放详情和来源菜单，设置保持可访问。
- 复用 Mini Player 和全屏播放器、浅深主题/大屏/无障碍；UniMusic 名称、原创资源、源码入口和签名/包名迁移说明一起交付。
- 根据 Performance Protocol 测量后才优化索引、首批结果、导入、解析/首声、恢复和后台耗电；保留前后原始样本与网络/设备条件，不承诺未测过的目标。
- 少量端到端链路：登录→导入→库→搜索/版本选择→播放→收藏→两设备同步；真机锁屏/耳机/蓝牙/后台切换单独验收。
- 核心闭环稳定后运行 Debug assemble，确认真实 APK 文件、哈希和安装结果再提供路径。Release 需要签名材料及分发条件就绪，构建失败时不生成虚假下载链接。

## 下一步顺序与未决条件

1. 工具链与 1A 迁移验证已完成；无需重复下载或重做 Phase 0。
2. 继续 1B/1C 的只读 Provider、持久导入事务与 UI；不能以适配器可调用代替完整导入验收。
3. 进入功能阶段时将必要 CI push 分支覆盖 `main`，并区分变更范围 smoke 与发布前关键集；保留原有 CI 能力。
4. 实际账号/平台授权仅用于线上验收，不阻塞 Fake provider 开发；任何平台暂不可接入时明确标记其 capability 与降级方式。

Phase 1 的工具链与 Room 数据保留前置条件已满足。
