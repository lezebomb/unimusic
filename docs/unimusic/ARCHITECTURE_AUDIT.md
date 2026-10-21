# UniMusic Phase 0：源码架构审计

审计日期：2026-10-21（Asia/Shanghai）。源码固定为 NeriPlayer `b9b6caf39bc65ca252f1c6a632ec24320cec68bb`；来源和许可证见 [SOURCE_PROVENANCE.md](SOURCE_PROVENANCE.md)。以下结论来自真实 Kotlin、Gradle、Room schema、测试和工作流源码阅读。**源码中存在实现不代表已验证线上可用。**

## 1. 结论与当前阶段

适合采用渐进扩展。上游已具备 Kotlin/Compose、多平台客户端、Media3 播放、Room 用户数据库、WorkManager 同步、播放统计、缓存及大量测试。保留这些能力，避免替换播放器、旧歌曲身份和同步协议。

优先处理三件事：建立稳定的统一歌曲身份与显式来源映射；把平台歌单可靠导入用户拥有的本地库；用严格版本识别和权限检查替代启发式自动切源。搜索相关性排序不能充当同一录音的身份验证。

本次审计与基准尝试已经执行。Android 构建在编译业务代码前因缺少 JDK 17 终止，API 37、指定 NDK/CMake 亦未就绪；JUnit/Room 仪器测试未执行。没有 APK 或可报告的设备性能结果。真实日志和状态见 [BASELINE.md](BASELINE.md)。涉及生产数据库的 Phase 1 迁移须在工具链和迁移验证条件满足后启用。

## 2. 模块与依赖方向

`settings.gradle.kts` 从 `gradle/owned-modules.txt` 加载 14 个 owned library 模块：`common`、`network`、`native`、`model`、`database`、`playback:logic`、`playback:runtime`、`download:logic`、`download:runtime`、`lyrics`、`platform`、`local`、`sync`、`listentogether`。另有 app、KSP、hidden-api 和歌词子模块。

- `model` 放跨模块数据模型；`database` 定义 Room 实体、DAO 和迁移。
- `platform` 封装三平台接口及账号，部分平台缓存已直接依赖数据库。
- `local` 负责本地歌单、历史/统计、设备文件与同步宿主。
- `sync` 封装序列化、冲突合并、远端传输；`playback:logic/runtime` 分开规则与 Android 播放实现。
- app 通过 `core/di/AppContainer` 和播放器依赖入口组装；新的导入服务应沿用此边界，避免 UI 直接驱动多表写入。

所有 `src/main/java` 路径均在既有目录下；以下文件为实际阅读的关键入口，并非只检查文件名。

## 3. 搜索：ExploreViewModel / ExploreScreen

证据：[ExploreViewModel.kt](../../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/ExploreViewModel.kt)，[ExploreScreen.kt](../../app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/explore/ExploreScreen.kt)。

- **已有/可复用**：`SearchSource` 切换三平台及链接识别；网易云支持歌曲/歌单/歌手；B 站支持分 P 选择；YouTube 有功能开关。`searchJob`、`searchMoreJob` 和 `searchRequestVersion` 抑制过期结果；网易云/B 站可分页；列表增量合并使用平台稳定键。
- **排序实现**：`rankExploreSongSearchResults` 使用 `SearchTextMatcher.score` 对当前平台结果排序，未命中项保留在后。`mergeExploreSearchResults` 按 `stableKey` 去重。二者都不是跨平台同录音匹配。
- **缺口/缺陷**：一次只搜索 `selectedSearchSource`，没有统一本地索引、多平台流式结果、统一歌曲聚合及独立 provider 状态；YouTube 界面搜索返回后 `searchHasMore=false`。需要把输入防抖、每源超时/限流、缓存 TTL、错误隔离集中到搜索引擎。
- **性能风险**：网易云搜索界面调用阻塞的 `searchSongs`，尽管置于 IO dispatcher 并丢弃旧结果，取消 Job 并不保证底层 HTTP 立即取消；客户端已有 `searchSongsCancellable` 可复用。新引擎使用可取消 transport，避免仅在 UI 层忽略结果。
- **正向约束**：当前常规搜索映射为元数据，未见为每行立即解析全部音频 URL；保留此性质。
- **拟改**：新增 model 的 provider/search 模型，platform 的 provider 适配与限流，local 的本地查询，app 的上述两个入口；保留各平台独立搜索作为高级来源筛选。

## 4. SongItem / SongIdentity / 来源

证据：[SongItem.kt](../../modules/model/src/main/java/moe/ouom/neriplayer/data/model/SongItem.kt)，[SongIdentity.kt](../../modules/model/src/main/java/moe/ouom/neriplayer/data/model/SongIdentity.kt)，[SongIdentityExtensions.kt](../../modules/local/src/main/java/moe/ouom/neriplayer/data/identity/SongIdentityExtensions.kt)，[SyncSongIdentity.kt](../../modules/sync/src/main/java/moe/ouom/neriplayer/data/sync/identity/SyncSongIdentity.kt)。

- **已有/可复用**：SongItem 保存歌曲元数据、原始/自定义字段、歌词、文件引用、`channelId/audioId/subAudioId`、歌单上下文、稳定来源键与同步 membership tokens。身份扩展已处理远端 ID、YouTube ID 和本地文件路径变化。
- **缺口**：SongIdentity 为 `id + album + mediaUri` 的兼容身份；没有独立统一 UUID，没有经过验证的跨平台多来源关系，没有录音版本、ISRC/MBID、来源许可证据和人工拒绝映射实体。
- **数据风险**：直接改 `stableKey()` 会连带改变收藏、歌单关系、下载、队列及同步删除语义；仅比较歌曲名称会合并不同录音。B 站 uploader 不是录音原唱，`subAudioId/cid` 不能丢失；同一 YouTube 视频的不同 playlistContext 通常不应生成不同录音身份。
- **拟改**：新增 `model/.../unified/` 和 additive Room 表，以适配器桥接旧 identity；第一阶段不改 SongItem 构造参数和既有稳定键。详见 [DATA_MODEL_AND_MIGRATION.md](DATA_MODEL_AND_MIGRATION.md)。

## 5. 平台 API 与账号

### 网易云

证据：[NeteaseClient.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/client/NeteaseClient.kt)，[NeteaseCookieRepository.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/auth/NeteaseCookieRepository.kt)，[NeteaseQrLoginClient.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/api/auth/NeteaseQrLoginClient.kt)。

- **已有/可复用**：会话 cookies、二维码/网页登录相关能力、歌曲详情、歌单、收藏、搜索、每日推荐、私人 FM 和多音质请求。`getUserPlaylists(userId, offset, limit)` 已支持分页，另有可取消歌曲/歌单详情接口。
- **安全基础**：Cookie repository 使用 Android Keystore/MasterKey 与 EncryptedSharedPreferences，迁移旧存储并有 cookie 格式校验。`SavedCookieAuthState.Valid` 的本地判定基于登录 cookie 是否存在，**不等于服务器已确认会话或歌曲权限有效**。
- **限制**：客户端使用平台 Web API/WEAPI/EAPI，不是获得第三方授权的证明；接口可能变更、限流、返回登录/区域/会员限制。导入只读用户有权访问的元数据，不将导入成功解释为获得音频播放权。
- **拟改**：复用登录和 session，添加只读 `PlaylistImportProvider`、分页状态和账号上下文校验；不新增账号密码存储。

### Bilibili

证据：[BiliClient.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/client/BiliClient.kt)。

- **已有/可复用**：Web/WBI 搜索、收藏夹分页、视频信息和分 P、可用音频格式、Cookie 会话；HTTP 连接/读取超时分别为 10s/15s。
- **缺口/限制**：搜索结果包含标题、UP 主、时长、播放量等，不能直接证明官方发行、原唱或授权；B 站视频 ID 与分 P cid 共同确定媒体上下文。HTTP 超时不能替代统一引擎每源整体截止时间。收藏夹导入和播放许可都需独立处理。
- **拟改**：新增 provider 适配和可用性/权利证据接口，保留现有视频、分 P 及收藏夹功能。

### YouTube Music

证据：[YouTubeMusicClient.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/youtube/api/client/YouTubeMusicClient.kt)，[YouTubeAuthRepository.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/youtube/auth/YouTubeAuthRepository.kt)，[YouTubeFeatureGate.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/youtube/config/YouTubeFeatureGate.kt)。

- **已有/可复用**：Innertube/Web Remix 搜索、用户库、歌单 continuation、主页和会话刷新。`getPlayableAudio` 从 player 响应提取音频格式；存在功能开关。
- **限制/缺陷**：这些源码能力不能视为官方 OAuth/Data API 授权，也不能证明独立音频播放符合平台政策。新统一音源选择器不得默认将其加入合法独立音频来源池。
- **降级设计**：保留元数据或授权允许的歌单功能，按适用授权使用官方播放/外部打开；无法确认允许的独立音频模式时返回 `ExternalPlaybackRequired`。既有上游实现保留待专项评估，不能宣称已符合 UniMusic 合规目标。
- **依据**：[YouTube 开发者政策](https://developers.google.com/youtube/terms/developer-policies) 对音视频分离、下载和后台播放器有明确限制；具体适用范围仍需结合实际授权和接入方式审查。
- **拟改**：provider capabilities/eligibility、app 来源菜单与外部播放降级提示；不将 cookie 登录扩展为新的绕过路径。

## 6. PlayerManager / PlayerUrlResolver

证据：[PlayerManager.kt](../../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt)，[PlayerManagerUrlExtensions.kt](../../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/PlayerManagerUrlExtensions.kt)，[PlayerUrlResolver.kt](../../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/url/PlayerUrlResolver.kt)。

- **已有/可复用**：Media3/ExoPlayer、队列持久化、媒体会话、预取、音频焦点、后台/设备路由、缓存、过期 URL 刷新、恢复与 watchdog。真正的 URL 调度在 `PlayerManagerUrlExtensions.resolveSongUrl`；`PlayerUrlResolver.kt` 主要定义音质、格式和响应转换规则，不能只改后者。
- **网易云兜底**：逐级音质尝试，区分 RequiresLogin、试听与无权限；无权限/试听时可能尝试已匹配本地源和 B 站自动源，再回退试听。暂态错误和权限/区域/下架原因尚无统一跨平台类型。
- **缺口**：没有统一 UUID 输入的 SourceResolver、版本/权限资格门、候选失败冷却、每来源成功率和连接耗时统计；质量排序仍需结合编码/来源，不能把标题“无损”或码率当作母带质量证明。
- **安全风险**：`resolveSongUrl` 日志包含 `streamUrl/currentUrl`，网易云解析路径记录完整 `resp`；可能包含签名播放 URL 或敏感查询参数。需改为白名单结构化日志，不记录 cookie、token 和完整鉴权 URL。
- **拟改**：新增独立 SourceResolver + PlayerManager adapter，调用既有播放引擎；切换物理来源时保留统一身份、收藏和队列，只有版本及时间轴兼容才恢复进度。

## 7. 自动网易云 → B 站切源：最高风险

证据：[PlayerManagerNeteaseAutoSourceSwitch.kt](../../modules/playback/runtime/src/main/java/moe/ouom/neriplayer/core/player/resolver/netease/PlayerManagerNeteaseAutoSourceSwitch.kt)。

- **已有**：组合歌名/歌手查询并附加“无损”，每轮最多取 6 个候选；选择视频分 P，解析真实可用音频，最多保留 2 个备用来源，提供独立 B 站缓存键。
- **实际打分**：标题命中 +55，歌手命中 +25，时长差 8s 内 +30、20s 内 +22、45s 内 +12。`selectNeteaseAutoBiliPage` 将视频候选分数与分 P 文本分数相加，再与 70 比较。
- **明确缺陷**：没有 Live/翻唱/伴奏/Remix/变速/剪辑硬拒绝；没有权威录音 ID、可信发行者验证、用户拒绝优先、候选分差拒绝或校准概率。重复累加相似文本使 70 门槛不能解释为“70% 可信”。静态推导中，视频与分 P 都只命中原曲名就可能累计 110，即使没有歌手或时长证据。
- **性能风险**：多个查询依次召回，最多 6 个候选逐个查详情及音频，持续寻找备用源；首次可用结果出现后仍可能等待备用解析，拉长首次出声时间。没有完整请求总预算或基于可信映射的持久缓存。
- **测试缺口**：当前 `PlayerManagerNeteaseAutoSourceSwitchTest` 只看到 3 个缓存键测试，不是人工标注的录音版本识别评估；不能据此宣称错误自动匹配率达标。
- **拟改**：先按标题/歌手/版本/时长/可信渠道过滤，再检查实际权限，证据不足或近分候选交给用户；首个合格来源与备用来源解析分开预算。保留开关和旧功能入口，启用新策略时用 feature gate 灰度，不沿用旧阈值作为新置信度。

## 8. 本地音乐库与 Room

证据：[LocalPlaylistRepository.kt](../../modules/local/src/main/java/moe/ouom/neriplayer/data/local/playlist/LocalPlaylistRepository.kt)，[LocalPlaylistEntities.kt](../../modules/database/src/main/java/moe/ouom/neriplayer/data/local/database/entity/LocalPlaylistEntities.kt)，[NeriUserDataDatabase.kt](../../modules/database/src/main/java/moe/ouom/neriplayer/data/local/database/NeriUserDataDatabase.kt)，[NeriUserDataDatabaseVersion.kt](../../modules/database/src/main/java/moe/ouom/neriplayer/data/local/database/NeriUserDataDatabaseVersion.kt)。

- **已有/可复用**：Room v20；`local_playlist/track/playlist_member/playlist_member_token` 有外键、排序与身份索引；durable payload 保留旧 SongItem。Repository 提供初始化等待、事务式 mutation、收藏/歌单增删改、删除恢复、顺序和同步变更记录。历史、统计、下载与平台缓存也在数据库中。
- **保护措施**：1→20 显式迁移链、schema 导出、打开前只读版本检查；新版数据库被旧版打开时拒绝并保留文件。实际 builder 使用 `addMigrations`，未使用 destructive fallback。
- **缺口**：不存在统一 UUID、来源一对多关系、导入来源/水位/任务、版本证据与人工覆盖。现有本地歌单已能容纳多平台 SongItem，但这不是跨来源统一录音库。
- **风险**：整歌单 StateFlow/快照路径在超大库中可能增加分配；需要测量后以 DAO 分页/摘要改进。不可直接拿新 DAO 绕开旧 repository 的同步 mutation/outbox。
- **拟改**：`model/.../unified`、`database/.../entity/unified`、`dao/unified`、迁移 `20→21`、local 的桥接与分页 repository。保留旧表/主键、收藏系统 ID、历史行及 tokens；支持关闭新读路径回到旧库。

## 9. 网易云歌单读取、导入与收藏同步

证据：[LibraryViewModel.kt](../../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/LibraryViewModel.kt)，[NeteaseCollectionDetailViewModel.kt](../../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/playlist/NeteaseCollectionDetailViewModel.kt)，[NeteasePlaylistSync.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/playlist/NeteasePlaylistSync.kt)，[NeteasePlaylistCacheRepository.kt](../../modules/platform/src/main/java/moe/ouom/neriplayer/platform/netease/NeteasePlaylistCacheRepository.kt)。

- **已有/可复用**：我的歌单/收藏/专辑列表、详情缓存和取消保护；详情按 trackIds 补齐歌曲，每 300 首查询详情，最终按 trackIds 恢复顺序；本地歌单创建/添加可复用。`NeteasePlaylistSync` 有向网易云喜欢/目标歌单写入的比较和批量添加能力，不能误认为它就是入站统一库导入器。
- **明确缺陷**：LibraryViewModel 的 `loadPlaylists` 只调用 `getUserPlaylists(userId)` 默认 offset=0/limit=30，没有逐页循环。详情补全对所有 chunks 启动 `async` 后 `awaitAll`，并发未显式限流，且要等整批返回才组装；失败时没有统一持久导入 checkpoint。
- **缺口**：账号库批量导入、来源唯一约束、远端空列表保护、幂等刷新、每页提交、进程死亡恢复、失败项重试、一次性/持续关联和解除关联。
- **拟改**：新增只读 provider 和 WorkManager 导入任务；任务/来源/水位进入 Room，cookie 保留在原账号仓库。来源键包含账号上下文，切换账号或授权失效要暂停；本地手工修改不被来源刷新覆盖。

## 10. GitHub / WebDAV 同步

证据：[SyncDataModels.kt](../../modules/model/src/main/java/moe/ouom/neriplayer/data/model/sync/SyncDataModels.kt)，[SyncDataSerializer.kt](../../modules/sync/src/main/java/moe/ouom/neriplayer/data/sync/codec/SyncDataSerializer.kt)，[SyncPlaylistMerger.kt](../../modules/sync/src/main/java/moe/ouom/neriplayer/data/sync/merge/playlist/SyncPlaylistMerger.kt)，[SyncPlaylistSongMergePolicy.kt](../../modules/sync/src/main/java/moe/ouom/neriplayer/data/sync/merge/song/SyncPlaylistSongMergePolicy.kt)，`modules/local/.../data/sync/{github,webdav}`。

- **已有/可复用**：SyncData 默认版本 `2.0`，ProtoNumber 编码、GZIP 和旧错误字段布局兼容；歌单、收藏、历史、播放统计、歌词覆盖及删除记录。合并处理并发修改、确定性 payload、membership 因果 tokens 和 tombstones；传输已有版本条件/并发重试、GitHub/WebDAV 后台 Worker。
- **安全基础**：同步凭据使用加密偏好存储，SyncData 是显式数据模型，不包含账号 Cookie/password/token 字段。Manifest 禁用备份，Android cloud-backup/device-transfer 排除 files/database/sharedpref/external/root。
- **缺口**：统一 UUID/别名图、平台映射、人工接受/拒绝、导入来源、推荐反馈无协议字段或独立 dataset。
- **兼容风险**：给 ProtoBuf 加可选字段只解决“旧客户端能读”问题；旧客户端重新编码上传时可能丢弃未知字段，这是需要验证的推断，不能当作已兼容。删除、合并和 UUID 冲突还需要新语义。
- **拟改**：新增受能力版本保护的 unified dataset/sidecar manifest，复用条件写/重试；旧 `2.0` payload 原样保留并桥接。先完成双版本读写/旧客户端回写保留测试，再启用跨设备统一映射。永不加入 credentials、签名 URL、设备私有文件路径或音频数据。

## 11. 推荐、统计与 UI

证据：[HomeViewModel.kt](../../app/src/main/java/moe/ouom/neriplayer/ui/viewmodel/tab/HomeViewModel.kt)，[PlaybackStatsTracker.kt](../../modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/runtime/stats/PlaybackStatsTracker.kt)，`modules/local/.../data/stats/PlaybackStatsRepository.kt`，`app/.../ui/navigation/AppNavigationScaffold.kt`。

- **已有/可复用**：网易云每日推荐、私人 FM、雷达/歌单和首页栏目；登录/账号上下文变化会重载，具有栏目局部状态。统计采集已区分真实收听时长、15s 周期 flush、单曲结束、手动 seek 和循环，使用事件 UUID 与累计统计。Mini Player、全屏播放、主题和大屏适配可保留。
- **缺口**：本应用的可解释内容推荐、跳过/不喜欢等显式反馈、每日推荐快照及多样性策略；现有统计中的播放次数不能直接解释为完整播放次数。主导航及平台分块库尚未重构为“发现/我的”统一入口。
- **风险/限制**：不要把平台推荐包装成 UniMusic 算法，不上传私人播放历史以换取默认外部推荐；不要为改 UI 删除本地导入、下载、设置或无障碍入口。
- **拟改**：model 的 PlayEvent/RecommendationSnapshot，local 推荐与反馈存储、HomeViewModel 的栏目来源标记、LibraryViewModel 的统一列表、导航 scaffold/graph 的双标签改造；设置移至“我的”内，播放 UI 和服务继续复用。

## 12. 测试、构建与性能基线

证据：`gradle/libs.versions.toml`、`buildSrc/build.gradle.kts`、`build-logic/convention/.../Version.kt`、`.github/workflows/android_ci.yml`、`tools_pub/quality/module_boundaries.py`、Room schemas 1→20。

- **已有/可复用**：JUnit、Robolectric、MockWebServer、coroutines-test、Compose UI 和 Room MigrationTestHelper；已有迁移链/数据保留测试、队列/恢复/同步冲突测试；CI 覆盖 JVM、lint、CRAP、模块/领域依赖、instrumented 和 native。发现约 1995 个 `*Test.kt` 路径（app JVM + modules，包含模块仪器测试），不代表本次运行数量。
- **基线工具链**：Gradle 9.6.1、AGP 9.4.1、Kotlin 2.4.10（buildSrc 另固定 Kotlin 2.2.0）、Compose BOM 2026.06.01、Media3 1.10.1、Room 2.8.4、WorkManager 2.11.2；Java toolchain 17，compileSdk 37/minSdk 28/targetSdk 36，NDK 27.0.12077973、CMake 3.28.0+。
- **构建风险**：首次配置需要下载工具链/依赖，JitPack、歌词子模块、本地 FFmpeg AAR 和 native SDK 都是复现条件。现有 Android CI 的 push 分支只列 `master`，UniMusic 默认 `main` 的代码推送不会触发此项，需在进入功能开发时修正分支覆盖。
- **性能已有**：下载/归档/SAF 等专项时序及规模测试；未见覆盖本任务完整搜索→匹配→首次出声流程的 Macrobenchmark 模块。`fetchFullPlaylistTracks` 并发、自动切源串行详情/备用解析、库全量快照是测量优先级，不宣称已经测得瓶颈。
- **本次结果**：详见 [BASELINE.md](BASELINE.md)，Android 构建/测试受环境阻塞；结构检查单独记录，不等同于 Android 功能测试。线上账号、蓝牙/耳机/锁屏、功耗及 APK 安装均未验收。
- **拟改**：本次新增 [baseline.py](../../tools_pub/unimusic/baseline.py) 控制小范围可复现检查；后续仅对变更范围运行必要测试，迁移/身份/权限等不可替代的正确性测试必须保留。

## 13. 风险顺序与后续入口

| 优先级 | 风险 | 开发决策 |
| --- | --- | --- |
| P0 | 升级/回滚丢失用户库或改变稳定键 | 新表/适配器、旧表保留、Room 数据保留迁移门禁 |
| P0 | 翻唱/现场误匹配并自动播放 | 明确版本拒绝、可信来源/权限门、歧义转人工 |
| P0 | 凭据或签名 URL 进入日志/同步 | 原加密账号仓库、白名单日志、同步字段白名单 |
| P1 | 批量导入遗漏/重复/账号切换串库 | 分页、来源唯一键、事务 checkpoint、账号上下文隔离 |
| P1 | 旧客户端回写丢弃新映射 | 独立 dataset、能力协商和旧客户端回写测试 |
| P1 | 搜索/大歌单任务过量并发 | 每源预算、取消、限流、分批提交和首批结果计时 |
| P2 | 本地推荐缺失、导航平台分割 | 复用统计与主题，后续接入统一库视图 |

具体阶段、文件清单、验收和依赖次序见 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)；数据库/同步兼容设计见 [DATA_MODEL_AND_MIGRATION.md](DATA_MODEL_AND_MIGRATION.md)；性能测量协议见 [PERFORMANCE_PROTOCOL.md](PERFORMANCE_PROTOCOL.md)。
