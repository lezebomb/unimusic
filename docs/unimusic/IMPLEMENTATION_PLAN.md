# UniMusic 分阶段实施与验收

状态日期：2026-10-28。4A 已完成发现/我的两标签与我的快捷入口、设置和返回兼容；7项定向 JVM、Debug 构建、边界和 Mate60 基本导航通过，详见 [4A](PHASE_4A_DISCOVER_AND_MY_LIBRARY.md)。用户最新要求暂缓 YouTube 取流排查、先开发其他功能；现有 Google/网易云授权与音乐库保留。2P 的 Google 服务端账号成功、音频仍失败不变，不重新登录。3A已补30天暂不推荐/撤销/恢复、私有摘要持久化，3个JVM及Mate60流程通过；1D已交付默认关闭的已关联歌单每日刷新，3个JVM、一个Mate60调度测试和最终构建通过；4B已将我的本地搜索移后台，7个JVM及Mate60流程通过；4C已补名称/原创图标/自己的源码与上游署名及真实构建版本，3B已接本机SKIP/COMPLETE/REPEAT实现和衰减规则，7个JVM/合成手机存储检查通过，实际Media3采样与反馈同步未验收；3C已加入本机反馈关闭/清除及持久开关，3个JVM和最终APK通过，系统中止覆盖安装所以新UI未验收；继续UUID数据集同步等剩余事项，设备/账号/音频/性能验收仍按实际阶段单列。

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

1B.1 与 1B.2 的后台实现已完成，见 [Provider](PHASE_1B_PROVIDER.md) 和 [后台报告](PHASE_1B_BACKEND.md)。失败明细保存在快照条目中；导入凭证跨快照保留用户移除记录。WorkManager 导入器与旧库回填 Worker 分开，1C 已加入导入 UI；1D已补默认关闭的本机可选每日刷新；真实账号导入范围以2M报告为准。

- model 定义 PlaylistImportProvider contract；platform 新增 Netease adapter，复用安全 session、分页歌单与可取消详情调用。
- local 新增导入 coordinator/WorkManager Worker，页级事务 checkpoint、账号检查、失败项重试、取消/恢复、一次性与持续关联。
- 复用 repository 写入路径并扩展事务入口，不把现有“向网易云上传收藏”误用为“从网易云导入”。
- FakeMusicProvider 放共享 testFixtures，控制分页、失败、空结果、授权过期、慢响应及 snapshot 变化。
- 验证：两次导入单一歌单、保留顺序/封面/元数据、空远端不删除、取消后恢复、失败明细重试、解除来源保留本地修改；只选覆盖这些行为的紧凑测试。

### 1D：可选定期刷新（已交付）

见 [1D报告](PHASE_1D_OPTIONAL_LINKED_REFRESH.md)：默认关闭，约24小时联网/电量约束，健康完成关联歌单才刷新，已有排队仅补建Work；取消/失败/暂停和一次性导入不自动重启。没有真实24小时或OEM后台耐久验收。

### 1C：现有库接入与操作入口

状态：操作入口已在 2E 并行完成，详见 [2E](PHASE_2E_SOURCE_CHOICES_AND_LIBRARY.md)；当前统一库通过旧库兼容投影展示，真实账号/Compose交互验收待设备。

- 修改 `LibraryViewModel.kt`、现有 library screen/歌单菜单和 AppContainer 依赖装配。
- 新增导入选择/进度状态、重试/取消/解除关联入口；统一库由 provider 无关列表呈现，来源详情仅在菜单显示。
- 不提前重写导航/全屏播放器；Phase 4 再完成整套双标签视觉。
- 验证：一个 Compose 导入状态流程及一个 Fake provider→Room→旧库适配器集成流程。实际账号导入单列待授权后验收。

阶段验收：旧音乐库无损，首次批量导入与重复刷新可靠，离线仍能读已导库；给出明确成功/部分失败/待登录状态，不声称全部曲目可播放。

## Phase 2：统一搜索与高精度匹配

2AD对最多两条已缓存自动在线源并行，首合格成功即返回；共享取消不再全量冷却，本地/人工顺序沿用。新3/共10 JVM、Debug通过，见[2AD](PHASE_2AD_PARALLEL_REMEMBERED_SOURCES.md)。无真实切源音频性能结论，待装机入口检查。

2AC修复酷我Provider公共搜索状态依赖导致的CI失败，改标准Socket超时异常和精确JDK登记；平台0违规、4项JVM、Debug通过，见[2AC](PHASE_2AC_KUWO_TIMEOUT_BOUNDARY.md)。手机已重连，待最终包一次安装。

2AB在公共网络封装中丢弃并关闭已取消等待者的迟到响应，不执行转换器；4项JVM、Debug通过，CI补对应关键集，见[2AB](PHASE_2AB_LATE_NETWORK_RESPONSE.md)。无实际网络/CPU节省或真机新验收。

2AA修复网易云单源在会话变化/登录恢复后继续当前查询，取消旧请求并保留显示词，4项JVM、Debug通过，见[2AA](PHASE_2AA_NETEASE_SEARCH_SESSION.md)。没有实际账号切换/真机回调验收。

2Z为本地索引筛选和聚合评分/排序/映射加入协作取消，保留索引和正确发布；新2/共7项JVM、Debug通过，见[2Z](PHASE_2Z_COOPERATIVE_SEARCH_CANCELLATION.md)。无大库耗时/帧率结论。

2Y将在线页解析/排名移后台并复用网易云JSON根对象，新1/共8项JVM、Debug通过，见[2Y](PHASE_2Y_BACKGROUND_ONLINE_PAGES.md)。无实际帧率/网络性能结论。

2X隔离播放候选内部超时/取消/解析错误并继续备用，原生并行源即使自行取消也报告完成；父取消与严格门槛保持。新4/共11项JVM、Debug通过，见[2X](PHASE_2X_SOURCE_FAILURE_ISOLATION.md)。真实故障切源/音频未验收。

2W新增可展开的每源本批次搜索耗时与明确超时分类，7项JVM、Debug通过，见[2W](PHASE_2W_SEARCH_TIMINGS.md)。虚拟时序不是线上速度，实际UI/计时待真机验收。

2V复用不可变查询规范化/分词，内层评分去掉中间列表；7项关键JVM及Debug通过，见[2V](PHASE_2V_PREPARED_SEARCH_QUERY.md)。未测真实CPU/网络性能，不更改匹配阈值。

2U已解决元数据后台排序与可播探测交错的旧证据回填，预计算比较等级；2个新/共10项JVM、Debug通过，见[2U](PHASE_2U_CURRENT_SEARCH_EVIDENCE.md)。无新增线上性能/音频验收。

2T已分离健康分页/失败重试，补无进展分页和内部timeout结束保护，8项相关JVM/最终APK/边界通过，见[2T](PHASE_2T_SELECTIVE_SEARCH_RETRY.md)。无真实设备/网络耗时或音频验收。

2S已预计算发现排序证据并隔离搜索键字段，7项JVM/最终APK/边界通过，见[2S](PHASE_2S_DISCOVERY_RANKING_AND_KEYS.md)。规则优先级保持，无实测CPU/网络性能声明。

2R已补显式合成人声/版本词掩盖门槛与自动证据v3，8项相关JVM、最终APK/边界通过，见[2R](PHASE_2R_EXPLICIT_VERSION_AND_VOICE_GATES.md)。仍为保守元数据规则，无真实听辨/声纹或准确率结论；手动选择和Room数据保留。

2Q已交付聚合本地检索后台化、本地与5个在线槽分离、按查询缓存本地分页，6项相关JVM/最终APK/边界通过，见[2Q](PHASE_2Q_AGGREGATE_SEARCH_SCHEDULING.md)。无真实网络耗时/设备性能结论，未修改原唱/权限门槛。

2A 已完成现有播放路径的元数据/版本门槛、分 P 歧义拒绝、可取消/有预算的临播解析和暂时失败冷却，详情见 [实现与边界](PHASE_2A_SOURCES.md)。这不是完整 Phase 2：来源权利能力注册、SongMatch/Override 持久化、统一搜索和 `SourceResolver(unifiedSongId)` 尚未实现。合成测试没有建立实际错误率或覆盖率结论。

2B 已增加现有四来源聚合搜索与版本排序，复用原搜索的 300ms 输入去抖及 generation 取消，独立游标/预算/失败可见；点击播放才解析 URL。现阶段保留跨平台候选，不做无证据的录音合并。音源机制参考与实际范围见 [调研](MUSIC_SOURCE_RESEARCH.md) 和 [2B 报告](PHASE_2B_SEARCH.md)。后续仍需来源权利能力、持久匹配/拒绝和统一 UUID 临播入口。

2C 完成 [酷我仅目录 Provider 与 B 站候选优化](PHASE_2C_CATALOG_AND_MATCHING.md)，并用最新 REA 6.8 CLI 完成 [提供样本的安全静态审计](APK_SOURCE_AUDIT.md)。酷我普通 HTTPS 搜索已在本机取得 5 条元数据，尚无音频可用性结论；新目录 Provider 仅后台装配，未混入可播放 SongItem。新增 6 个测试方法通过。

2D 将该目录以独立 Catalog 类型接入聚合界面，严格限制官方外链，并保留 Song 专用播放队列。修复全角版本识别，查询排名移出主线程、缓存同查询分数并限制版本偏好权重，详见 [2D](PHASE_2D_CATALOG_SEARCH.md)。这不是酷我完整音频接入或可信跨平台录音合并。

2E 已完成 [持久匹配/人工覆盖、导入界面和本地推荐初版](PHASE_2E_SOURCE_CHOICES_AND_LIBRARY.md)，含独立 22→23 增量迁移；拒绝/清除修订能使在途旧结果失效。2F 已提供精确 UUID 反查和临播兼容入口、四源候选及会话/预取保护，详见 [2F](PHASE_2F_UUID_RESOLUTION_AND_DAILY_CACHE.md)；UI与队列仍是兼容SongItem，没有独立权利注册表，规则分不是校准概率。

2G完成 [来源能力与显式音频地址校验](PHASE_2G_SOURCE_AVAILABILITY.md)。能力登记不是权利注册，用户点击后只解析单个合格来源；检查成功不等于实际播放完成。

2H按用户反馈修复独立解析预算叠加，并收紧首次自动B站选择；候选不唯一、教学/评论标签或仅标题提及原歌手时不强行自动播放。详细边界及未测项见 [2H](PHASE_2H_LATENCY_AND_ORIGINAL_MATCHING.md)。

2I参考用户2024音源笔记，将自动成功映射从内存扩为Room持久缓存，原站失败后优先重解析缓存ID，再发现新候选；缓存命中保持首次自动匹配门槛和当前会话/人工修订检查，限制缓存阶段预算并避免同一失败视频重复请求。专用SQL/精确键复核减少展示列表扫描；没有新增音频平台，当前接口核实与实际验证见 [2I](PHASE_2I_PERSISTENT_SOURCE_FALLBACK.md)。

2J已按实际公开API响应接入GD网易云的精确ID在线播放。用户手动确认，地址不用于磁盘音频缓存、预取、下载或音频链接分享；8个关键检查（含1次实际Kotlin客户端联网）、Debug构建及相关边界通过。当前其他软件/API机制、服务限制和未验收项见 [2J](PHASE_2J_PUBLIC_AUDIO_SOURCE.md)。

2K按用户可分别登录的要求，复核已有网易云/B站/YouTube入口并补QQ网页登录、安全保存、身份校验及退出修订保护。QQ浏览器会话独立进程/数据目录，校验只信当前登录身份；不会以保存凭证代替真实登录或完整播放验收。见 [2K](PHASE_2K_PLATFORM_SIGN_IN.md)。下一步接QQ正常音频能力，继续核实酷我，不把登录卡片当已完成音频Provider。

2L已接原生QQ / 酷我音频与有预算的并发自动匹配；相同录音内优先返回当前有完整地址的来源，拒绝版本/歌手/时长冲突并保留账号权限。聚合搜索每源至多异步探测三个候选（每源4.5秒，成功停止），相关性与版本门槛后提升地址校验成功的结果。详见2L报告及真机日志，QQ账号正向和曲库准确率仍待后续验收。

2M 已完成网易云真实账号登录及两份 5 首歌单的顺序/UUID/无重复重导，正常原生音频、B站及 GD 样本真机播放通过。YouTube 播放失败和 QQ 账号正向未验收明确保留；修复登录页日志隐私、底部视口及旧 CI 能力断言。详见 [2M](PHASE_2M_ACCOUNT_IMPORT_AND_DEVICE_AUDIO.md)，后续先定位 YouTube 和 B站等待，不重复短信登录或导入全部歌单。

2N 已完成 YouTube 文本 HTTP 的取消与阶段预算、共享任务截止及去除整套外层重试，并增加只含阶段/类型的真机诊断。9 个关键 JVM 检查和最终构建通过，但 YouTube 实际播放仍失败；正常界面也复核无地址，不能因搜索有结果标记接通。B站此次是缓存样本，不据此报告冷启动提升；详见 [2N](PHASE_2N_PLAYBACK_CANCELLATION_AND_DIAGNOSTICS.md)。

2O 对照原版预热与 12 秒签名窗口，修正直接 YouTube 播放/共享后台的预算并保持自动回退 8 秒。新增完整匿名客户端轮次后明确拒绝、来源 AUTH_REQUIRED 与专属登录提示，实际仍未返回音频。9 个关键检查及最终构建通过，详见 [2O](PHASE_2O_UPSTREAM_PLAYBACK_AND_ACCESS_FEEDBACK.md)。

2P 已完成真实 Google 登录与服务端账号验证，授权在手机加密存储并重启读回。WEB_REMIX 的账号响应为 OK、有 16 条签名格式，尚未取得最终音频地址；下一步定位地址/签名/流访问环节，无需再次索要账号。详见 [2P](PHASE_2P_GOOGLE_LOGIN_AND_AUTHENTICATED_SOURCE_PROBE.md)。

1. 先做匹配安全基座：Normalization、VersionClassification、SongMatch/Override repository、硬冲突拒绝、来源资格类型。分析上游加分与分 P 双重累计，旧 70 分阈值不能迁成概率。
2. `UnifiedSearchEngine` 使用 Flow/Coroutines、supervisorScope/每源预算、平台限流、去抖和请求取消。先返回本地/首个远端批次，合并有证明的同录音；页游标、错误和加载状态按 provider 保存。
3. 复用 SearchTextMatcher 做展示相关性，新增版本/歌手别名/发行/时长/ISRC/MBID/可信渠道证据，不为搜索列表批量解析 URL。缓存按平台政策 TTL 与账号上下文隔离。
4. `SourceResolver(unifiedSongId)` 在播放前惰性解析：允许的本地源→高可信且当前有权播放的同版本在线源。失败类型涵盖网络、授权过期、下架、区域、会员、误配和 URL 过期；权限类不做伪造鉴权重试。
5. 接入 `PlayerManagerUrlExtensions` 与原 PlayerManager，队列/收藏身份不随 source 变化；合格首源先返回，备用源按独立预算准备。

文件入口：`model/.../unified`、platform 新 provider/matching、local 匹配持久化、`ExploreViewModel/ExploreScreen`、`playback/runtime/.../resolver` 及 `PlayerManagerNeteaseAutoSourceSwitch.kt`。

核心验证：同平台分页去重、跨平台可信映射聚合、版本冲突/相近候选拒绝、人工拒绝优先、一个平台失败其他批次仍显示、过期请求无结果污染、仅临播解析、失败切源不破坏队列。真实人工标注集单独评估：错误自动匹配数/自动接受数、召回覆盖率、拒绝率与样本量同时报告，研究目标 <1% 不能由几个合成单测代替。

## Phase 3：本地推荐与同步

3A/3B/3C已交付本机暂不推荐/撤销/恢复、播放反馈及关闭/清除，实际Media3采样/异常耐久尚未验收。3D.1～3D.8已交付独立身份/因果合并、持久日志、只读Room捕获、本机基线、条件WebDAV/GitHub后台与手动入口、显式冲突及外来UUID适配，见[3D.8](PHASE_3D8_GITHUB_IDENTITY_SYNC.md)与续跑检查点。3D.7远端CI成功，3D.8新2项合成JVM/最终APK/边界通过。

这仍是身份/元数据子集：不改旧同步路径，不上传反馈/凭据/设备路径/播放权，不自动重写Room本机ID、显示字段或完整歌单。真实服务器账号、双设备、旧客户端往返、设备UI和完整Room远端应用未验收，不标记完整Phase3交付。

- 保留平台每日推荐/私人 FM/雷达入口；新增本地内容/规则排序、显式 SKIP/DISLIKE/COMPLETE/REPEAT 事件和去重汇总。
- 每日快照保存 song UUID、栏目、理由、算法版本及来源标签；时间衰减、跳过降权、新颖性与歌手多样性，避免只推已熟悉歌曲。
- 修改 `HomeViewModel.kt` 接入本地栏目，在 UI 标明“网易云提供”或“UniMusic 推荐”；默认不上传播放历史到第三方推荐服务。
- unified dataset 采用独立 schema/capability，复用 GitHub/WebDAV 条件写与重试；保留旧 2.0 数据路径，验证旧客户端回写不会抹掉新字段。
- 同源 UUID 冲突通过 canonical alias 合并；用户拒绝、删除和离线新增使用因果信息，不能仅凭最后时间覆盖。

验证：推荐去重/跳过降权/多样性/来源标签；两设备离线并发、删除冲突、同源重复导入、老协议往返、新旧备份恢复、凭据白名单排除。各选紧凑关键集，不每轮运行整个上游套件。

## Phase 4：双标签 UI、端到端与性能

4G已保留数据装2AD并实际检查来源计时与一次失败源重试；单次网易云0.5秒/B站0.9秒/酷我0.6秒/QQ2.2秒，YT6.1秒超时且不阻塞已返回结果，见[4G](PHASE_4G_DEVICE_SEARCH_AND_RETRY.md)。非稳定性能或音频验收；2AD远端CI成功。

4F补查询匹配/网易云解析关键CI集与子包路径，非迁移不额外assemble设备测试包，保留connected迁移；YAML/路由静态验证通过，见[4F](PHASE_4F_SEARCH_CI_COVERAGE.md)。产品仍是2AA包，未重复本机构建，实际CI结果待确认。

4E已在Mate60保留数据安装最终2T APK，完成推荐偏好、GitHub/WebDAV标识入口和0冲突页检查；本机捕获21条标识/修订，未上传/改偏好，见[4E](PHASE_4E_DEVICE_FEATURE_CHECK.md)。真实云同步、外来UUID音频和Media3反馈采样仍未验收。2T及2S远端CI成功。

4D已按保守白名单路由身份定向CI、按结构变化执行Android迁移；本地边界/真实Git范围/未知回退及YAML检查通过，见[4D](PHASE_4D_CHANGED_SCOPE_CI.md)。新的Actions执行和实际耗时待确认，未重复构建产品。

4C已完成显示名称、Debug覆盖、原创启动图标、源码/上游入口与构建修订；见 [4C](PHASE_4C_PROJECT_IDENTITY.md) 和 [包名签名说明](PACKAGE_AND_SIGNING.md)。实际包标识与签名配置沿用，Release/包名迁移和全Launcher验收未做。

4B已交付本地歌单/艺人后台筛选、取消与索引复用，见 [4B报告](PHASE_4B_BACKGROUND_LIBRARY_SEARCH.md)，不等同已测量大库性能。

4A 的双标签与关键入口已交付，见 [4A报告](PHASE_4A_DISCOVER_AND_MY_LIBRARY.md)；这是导航子阶段，完整端到端、平板/多主题与性能验收尚未完成。

- 在现有 Compose navigation/scaffold 中合并为“发现/我的”；我的包含收藏/所有本地或已导歌单/历史/下载，平台信息放详情和来源菜单，设置保持可访问。
- 复用 Mini Player 和全屏播放器、浅深主题/大屏/无障碍；UniMusic 名称、原创资源、源码入口和签名/包名迁移说明一起交付。
- 根据 Performance Protocol 测量后才优化索引、首批结果、导入、解析/首声、恢复和后台耗电；保留前后原始样本与网络/设备条件，不承诺未测过的目标。
- 少量端到端链路：登录→导入→库→搜索/版本选择→播放→收藏→两设备同步；真机锁屏/耳机/蓝牙/后台切换单独验收。
- 核心闭环稳定后运行 Debug assemble，确认真实 APK 文件、哈希和安装结果再提供路径。Release 需要签名材料及分发条件就绪，构建失败时不生成虚假下载链接。

## 下一步顺序与未决条件

1. 工具链与 1A 迁移验证已完成；无需重复下载或重做 Phase 0。
2. 用户要求音源优先，本地 APK 静态参考已完成：继续目录结果与播放能力的明确分离、正常音频接口核实、持久匹配/确认/拒绝和统一 UUID 解析；1C 操作入口已在 2E 交付。不能以元数据搜索/导入成功代替真实在线播放验收，不移植会员破解、内置凭据或 DRM 绕过。
3. `unimusic_smoke.yml` 已覆盖 main 的定向 JVM/Android 迁移和音源检查，保留原有完整 CI；远端 Actions 执行结果需单独确认，本地检查结果不冒充远端结果。
4. 实际账号/平台授权仅用于线上验收，不阻塞 Fake provider 开发；任何平台暂不可接入时明确标记其 capability 与降级方式。

Phase 1 的工具链与 Room 数据保留前置条件已满足。
