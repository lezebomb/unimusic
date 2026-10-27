# UniMusic 续跑检查点

> 2026-10-27 的02:20和07:25两次单次续跑已执行，对应任务均已删除；没有新建重复计划。10月26日暂停交接仅为历史记录，按本文最新检查点接续。

更新：2026-10-27（Asia/Shanghai）。最新 2M 已完成网易云真实短信网页登录、两份歌单共 5 首导入、顺序/UUID/重复导入无重复和正常网易云完整音频真机播放验证；B站/GD 也实际播放通过，YouTube 搜索成功但播放等待 20 秒失败，QQ 正向账号取流仍未验收。6 个定向 JVM 检查通过，设备 5 个检查中 4 通过 / 1 YouTube 失败，最新 APK/测试包构建和模块边界通过。见 [2M报告](PHASE_2M_ACCOUNT_IMPORT_AND_DEVICE_AUDIO.md) 及 phase2m 证据。已保存账号与导入库，勿重复发送验证码、清空数据或再次导入所有歌单。未连接 Gmail；凭证与联系方式不要写入日志/提交。2L 远端 CI 失败的旧能力断言已在 2M 修复，后续 CI 以实际状态为准。

此前阶段：2026-10-27（Asia/Shanghai）。2L已接QQ正常标准MP3与酷我官网免费音频、并发备用源匹配、可播性排序与精确持久映射；26个定向JVM检查和Mate60上2个真机检查通过，Debug APK / AndroidTest APK、模块及平台边界通过。酷我样本已由生产播放器实际解码并观察到进度，QQ匿名拒绝被正确保留；真实QQ登录正向取流和完整歌曲/多网络耐久未验收。Room仍v23，无新迁移。手机已开启充电常亮，初次设置中已按用户要求开启播放源兜底；本地音乐权限及云同步未启用。手机密码仅在本机系统验证时输入，未保存到文件/日志/提交。

原D:/Collect/.tooling开发包被用户清理，Android包已按需恢复到项目内已忽略的D:/unimusic/.android-sdk；复用项目内JDK17与C:/Users/24981/.gradle，local.properties指向新SDK。原生模块显式固定NDK27/CMake3.31.6。后续不要继续引用已删除的JDK21/current或旧SDK。可直接使用 `tools_pub/unimusic/build_android.ps1`，它只为当前进程设置项目JDK/SDK；`-GradleArgs`可传任务和`--offline`。

历史报告中“无设备”“酷我仅目录”“QQ未接音频”只描述对应旧阶段；当前以最新 [2M报告](PHASE_2M_ACCOUNT_IMPORT_AND_DEVICE_AUDIO.md) 及对应阶段证据为准。

## 已完成和推送

- Phase 2M：本检查点所在提交完成真实账号/导入/原生音频与其他来源设备验证，修复登录页隐私及视口和旧 CI 断言；以 git log/status 确认提交推送，详见 [2M](PHASE_2M_ACCOUNT_IMPORT_AND_DEVICE_AUDIO.md)。

- Phase 2L：`64c7d699` 接QQ/酷我正常音频、自动匹配与可播性排序，实际范围见 [2L](PHASE_2L_NATIVE_AUDIO_AND_PLAYABILITY.md)；以git log/status核对提交推送。

- Phase 0：`b60d74d1`，源码审计与固定上游已归档，不需重做。
- 工具链：`f2c53ea1`，JDK 17 + API 37.0 / Build Tools 37 / NDK 27.0.12077973 / CMake 3.31.6 均可用。
- Phase 1A：`230d6b10`，持久歌曲 UUID、精确来源键、6 张增量表和 v20→v21、可恢复后台旧库投影，已提交推送。
- Phase 1B.1：`547f08e7`，只读 Provider、共享 Fake 与 main 精简 CI 已推送。
- CI 修复：`094c56c5`，SDK 路径修复后的 [Actions](https://github.com/lezebomb/unimusic/actions/runs/39737919228) 已成功。
- Phase 1B.2：`14da4d19`，实现 v21→v22 的任务/条目/凭证、原库兼容事务、Coordinator/Worker、取消/重试/恢复和显式关联刷新，AppContainer 已装配后台入口。[对应 Actions](https://github.com/lezebomb/unimusic/actions/runs/40169373912) 已成功。
- Phase 2A：`bea4d48e`，原网易云/本地/B 站路径的版本/元数据门槛、分 P 歧义拒绝、可取消且有预算的地址解析、首个成功即返回、失败冷却。[对应 CI](https://github.com/lezebomb/unimusic/actions/runs/40270887580) 已成功。
- Phase 2B：`7022b9b6`，四个已有来源的聚合搜索、独立分页/预算/状态、版本排序及可取消网易云预热。[对应 CI](https://github.com/lezebomb/unimusic/actions/runs/40274273540) 已成功。
- Phase 2C：`b6a67864`，酷我 metadata-only 目录后台、实际 B 站自动候选预筛选与排序、精确平台依赖登记及静态审计。[对应 CI](https://github.com/lezebomb/unimusic/actions/runs/40276481535) 已成功。
- Phase 2D：`3b6afc00` 完成混合 Catalog 聚合入口、目录/队列隔离、官方页面校验、全角版本修复和后台缓存排名。该阶段 Room 为 v22，[对应 CI](https://github.com/lezebomb/unimusic/actions/runs/40279029813) 已成功。
- Phase 2E：`6ab3934f` 完成持久 SongMatch/Override 和 22→23、来源菜单/下次播放适配、网易云导入界面、本地推荐初版；报告见 [2E](PHASE_2E_SOURCE_CHOICES_AND_LIBRARY.md)。[对应CI](https://github.com/lezebomb/unimusic/actions/runs/40287265802) 已成功。
- Phase 2F：`b5e73b3f` 完成 UUID反查/临播适配、四源元数据菜单、最新版本/会话复核、预取交接保护、北京时间每日快照。见 [2F](PHASE_2F_UUID_RESOLUTION_AND_DAILY_CACHE.md) 和 `evidence/2026-10-27/phase2f/`，[对应CI](https://github.com/lezebomb/unimusic/actions/runs/40379408254) 已成功。
- Phase 2G：`5976e9fb` 完成 MusicSourceRegistry、单来源显式校验和类型化结果、精确决策戳复核、校验菜单。见 [2G](PHASE_2G_SOURCE_AVAILABILITY.md)，此前[对应CI](https://github.com/lezebomb/unimusic/actions/runs/40421809825)已确认成功。
- Phase 2H：`0c4df844` 完成共享解析预算、聚合4路并发、B站两个查询并发及更保守原唱匹配。见 [2H](PHASE_2H_LATENCY_AND_ORIGINAL_MATCHING.md)，[对应CI](https://github.com/lezebomb/unimusic/actions/runs/40428919546)已成功。
- Phase 2I：`c9a08ba1` 完成自动成功来源持久映射和预算/失效/修订复核，见 [2I](PHASE_2I_PERSISTENT_SOURCE_FALLBACK.md) 和 `evidence/2026-10-27/phase2i/`。用户原始笔记保存在已忽略的 `.codex-remote-attachments/`，不要提交附件。
- Phase 2J：`dc40b8a4` 接入GD公开音频线路，见 [2J](PHASE_2J_PUBLIC_AUDIO_SOURCE.md) 和 `evidence/2026-10-27/phase2j/`，[对应CI](https://github.com/lezebomb/unimusic/actions/runs/40435919202)已成功。
- Phase 2K：本检查点所在提交补QQ正常登录入口/安全存储/身份校验，见 [2K](PHASE_2K_PLATFORM_SIGN_IN.md) 和 `evidence/2026-10-27/phase2k/`，以git log/status确认commit/push及新CI。

## 实际验证

- Phase 2L：26个直接相关JVM检查（含真实Kotlin客户端匿名联网1个），Mate60上生产播放器解码/进度与QQ匿名拒绝2个检查通过。酷我取址最终包单次700ms，播放请求到观察到2178ms进度耗时4105ms；这些不是首声分位数或完整歌曲结论。APK/测试包及模块/平台边界通过，证据见phase2l。

- Phase 2K：4个新关键测试通过，持久退出修订补齐后只重跑受影响仓库2个；最终Debug APK及模块/QQ协议-存储依赖边界通过。匿名GetLoginUserInfo实测HTTP200/account.code1000，明确没有提供凭证、没有正向账号验证。无设备、官方网页交互、实际Keystore/账号/播放验收。

- Phase 2J：8个检查通过，含手动启用的真实GdStudioClient联网取址1个；两个地址HEAD返回200/audio/mpeg，没有获取音频响应体。最终Debug APK/模块/平台/Listen Together边界通过；证据见phase2j。无设备/账号/实际解码播放验收。

- Phase 2I：15个定向JVM测试通过（新增2个方法并补冷却断言），模块边界和最终Debug APK通过。首次磁盘测试SQLite开库失败，缩短方法名后通过；最后专用查询改动另做同集合/构建确认，实际日志分别保留。没有新迁移、设备/真实账号/播放性能验收，没有验证GD或酷我完整音频可用。

- Phase 2H：18个定向JVM测试通过（新增4个方法），Debug APK及模块边界通过。虚拟时钟验证阶段共享8秒，不作为实际网络/首声性能结论。无设备/账号/原唱人工标注集验收。

- Phase 2G：7个定向JVM测试通过（新增3个方法），Debug APK、模块边界、平台依赖边界通过。只在用户点击单一来源校验后解析地址，未做真实设备/账号校验或实际播放，酷我依然仅目录。证据在 `evidence/2026-10-27/phase2g/`。

- Phase 2F：19个定向JVM测试通过（新增12个方法）、Debug APK与模块/平台依赖边界检查通过；首次网易云详情夹具缺歌手ID，被原有parser拒绝，补齐真实结构后仅重跑受影响集合。无设备/账号/性能验收，详见阶段证据。

- Phase 2E：本地关键集合、模块边界、平台依赖边界、Debug APK 和 database AndroidTest APK 构建，实际结果/命令/哈希见 `evidence/2026-10-26/phase2e/validation.json`。本轮没有设备或真实账号验收。

- Phase 2D：22 个定向 JVM 测试通过，新增 5 个方法并扩展全角版本断言；模块边界与 Debug APK 构建通过，见 [2D](PHASE_2D_CATALOG_SEARCH.md) 和 `evidence/2026-10-26/phase2d/`。没有设备、App 联网搜索/跳转/播放或性能测量，不把已编译 UI 当成设备验收。
- Phase 2C：9 个定向测试通过，新增 6 个方法；酷我 3 个检查在实体夹具/超时修正后重跑。模块边界、平台依赖边界及最终 APK 构建结果见 `evidence/2026-10-26/phase2c/`。本机普通酷我 HTTPS 搜索实际返回 HTTP 200 和 5 条元数据，没有请求音频；Android Provider 未经真实设备/账号联网验收。
- Phase 2B：42 个定向 JVM 测试通过，新增 6 个方法；Debug APK 与模块边界检查通过。原始 XML/命令/哈希见 `evidence/2026-10-26/phase2b/`，实现见 [2B](PHASE_2B_SEARCH.md)。无新平台、外部脚本兼容、真实网络播放或设备交互验证；当前四个来源仍是原有本地/网易云/B 站/YouTube。
- 2026-10-26：音源相关 40 个定向 JVM 测试通过（其中新增 13 个方法）；修正冷却时钟后仅重跑解析器 4 个测试并构建 Debug APK，均成功；模块边界检查通过。新拆出的 URL 文件已参与最终编译。详情见 [Phase 2A](PHASE_2A_SOURCES.md) 与 `evidence/2026-10-26/`。
- 本轮无手机/模拟器、未安装新 APK，未做真实账号联网播放、实际切源或覆盖率/性能测量。2A 时只有保守元数据门槛；2E 已增加持久人工覆盖，仍没有经标注校准的同录音概率、独立来源权利注册表或完整 `SourceResolver(unifiedSongId)`；不承诺商业曲库全部免费。
- 2026-10-25：7 个新导入集成测试 + 1 个冻结 v21→v22 Room 迁移测试通过；数据层、Debug APK、database 测试 APK 构建通过，模块边界检查通过。旧 store / outbox 解码 / Provider 也做了定向检查，没有运行完整上游测试。
- 2026-10-25 的迁移测试使用 Robolectric/本机 SQLite；当轮没有设备执行。详情见 [Phase 1B.2](PHASE_1B_BACKEND.md) 与 `evidence/2026-10-25/`。不能把已导入元数据标成已可播放。

以下为 2026-10-21 的历史 v21 验证：

- 模块边界通过；JVM 17 个测试全部通过（数据库 9、统一库 store 5、Provider 3）。
- API 35 x86_64 独立模拟器上 4 个迁移测试通过：冻结 v20 的 8 张原表逐字段保留，所有历史版本到 v21 的 Room schema 验证、旧下载恢复数据和字节水位/索引保留。
- 两个 Android 测试类分别执行并核对 XML。首次逗号合并筛选仅实际执行 1 个测试，已另跑历史升级类 3 个测试；后续不要只看 Gradle 退出码。
- `:app:assembleDebug` 成功；APK `app/build/outputs/apk/debug/app-debug.apk`，`moe.ouom.neriplayer.debug`，在全新模拟器安装和 MainActivity 启动成功，UnifiedLibraryBackfillWorker 返回 SUCCESS；未接入账号。
- SQLite JVM 长路径问题已修复：Robolectric 的长方法名产生 259 字符路径，WAL 超出 Windows 限制；缩短方法名后原断言全部通过，未修改生产 journal mode。
- [Phase 1A 报告](PHASE_1A.md)、[Provider 报告](PHASE_1B_PROVIDER.md)、`evidence/2026-10-21/phase1a-validation.json` 与保存的 XML 为依据。性能和真实账号/真机验收未执行。

## 下一步：统一 UUID 解析、推荐与同步

1. 阅读 IMPLEMENTATION_PLAN.md、DATA_MODEL_AND_MIGRATION.md 和上述报告；先查 git status 和已有 Gradle 进程，避免重复构建。
2. 当前数据库为 v23，20→21、21→22 与 22→23 均不可重写；新结构使用后续独立迁移，不可 destructive migration。
3. 提供 APK 的静态审计已完成，见 APK_SOURCE_AUDIT.md。最新 REA 6.8 CLI 已实际复核；旧 MCP 连接仍自报 6.5，未混淆版本。样本为 com.hihonor.cloudmusic 1.5.30 + LSPatch/附加模块/Node 来源层，不凭文件名认作官方网易云 9.1。原始样本已忽略，保留本机，不执行/分发；自动审批拒绝完整破解 Hook 方法体，未间接绕过或移植会员/DRM逻辑。
   酷我 `AppContainer.kuwoCatalogProvider` 只提供目录，现以独立 Catalog 结果接入聚合 UI 和官方查看入口，仍未接音频播放/下载/收藏/队列。不要把酷我ID交给网易云播放器；正常音频能力需另外核实，不以 PAY/COPYRIGHT 字段推断授权。
4. 当前 LINKED 支持显式刷新，尚无定期自动刷新；ONE_SHOT 不关联，普通 refresh 不会重新关联已解除来源。重新 enqueue 是明确重新导入/关联动作。
5. FakeMusicProvider 与 PlaylistImportCoordinatorTest 已覆盖磁盘重开续跑、批次回滚、取消换号、部分失败、用户修改和同步恢复。后续只跑改动相关的小集合。
6. UI/队列/同步仍使用旧兼容路径，新导入后台会原子写入旧表。2F已提供 resolveUnifiedSongUrl(UUID) 和原播放入口UUID适配，精确反查当前durable SongItem；不是整库/同步完全切换UUID，也没有独立权利注册表。1C 操作界面已复用 AppContainer.playlistImportBackend；不要再写独立导入器。
   聚合快照现在为 `FederatedSearchSnapshot<ExploreSearchResult>`，Song/Catalog 混合展示，searchSongItems 只提取 Song。ExploreSearchRankingSession 每查询最多缓存 2048 个分数，数据变化失效；在 Default 排名后回 Main 校验 generation。2E 已补本地持久匹配/用户确认与拒绝，2F已提供UUID临播入口；新决策暂不跨设备同步。
7. 2G已补实现能力登记和显式校验状态；这不是播放权利授予或真实账号/设备验收。下一步优先实际网络下的首声/卡顿恢复测量及原唱标注样本；2H未验证所有阻塞式提取器及时取消，也未把播放器启动/运行看门狗等待合并到地址预算。继续细化平台明确返回的失败类型，避免仅凭地址或元数据宣称可播。反馈事件与独立unified dataset同步、双标签UI继续按计划推进，不能缺真机验收时宣称全功能完善。
8. 每个完成子阶段自行 commit + push 到 https://github.com/lezebomb/unimusic；保留用户数据，准确区分本地验证与远端 Actions、模拟器与真机结果。
9. 2I自动缓存复用song_match.algorithm_version=2和observed_at，普通发现/显式探测仍为1，不能当作已播放证据。按目标指纹、24小时TTL、NONE决策筛选最多8条，运行时跳过冷却后最多试2条；共享预算内自动缓存阶段最多1.2秒/每条1秒，失败后进入现有本地/B站发现。缓存命中仍检查最新元数据、歌手名称佐证、正常音频、会话、开关及人工revision。后续算法版本不可无区分接受所有旧证据；没有新表/迁移或新音频平台。GD官方文档当前列netease/joox/bilibili，不含kuwo，限制下载/传播/商用；只读取文档HTTP200，没有代取曲目URL/音频。不要把旧笔记里的固定pyncmd→kuwo顺序或HTTP200文档当作全量可播验证。
10. 上一条的GD未接音频/仅查文档是2I历史范围。2J已实际请求地址并加入`gdstudio_netease` Registry/显式匹配/来源菜单，GD仅在用户选择后使用；其API客户端独立匿名，不接收平台Cookie；只接受预期HTTPS网易云CDN MP3，限50次/5分钟。模型内部fragment标记让数据源绕过CacheDataSource，两媒体预取入口和中央分享URL策略也排除它。实际联网smoke必须显式设置UNIMUSIC_LIVE_GD_NETEASE_ID，默认不联网；不因地址/HEAD通过宣称已播放、全长或原唱验证。下一阶段用户允许分别登录，复用官方网页登录，不能索取聊天中的密码/票据或自动导入其他应用账号。
11. 2K已补`AppContainer.qqMusicAuthRepo`及`:qq_music_login`独立官方WebView，凭证白名单仅5类音乐会话字段，不读密码。主进程默认加密保存，退出修订墓碑持久化；旧网页/在途校验不能恢复退出。状态分SAVED_UNVERIFIED/VERIFIED/EXPIRED/UNAVAILABLE，不以Cookie存在认作已登录。QQ当前只有账号和既有歌词/元数据能力，没有MusicSourceRegistry QQ音频Adapter，不能误记已可播；正常GetLoginUserInfo接口匿名会返回1000。以后取流必须捕获账号会话并复核变更，不能把账号票据发给GD或未知演示服务器。QQ凭证不进同步/备份payload；硬件Keystore及真实账号仍待设备。

## 复用环境与日志

- launcher JDK 21：`D:/Collect/.tooling/jdk/current`；buildSrc JDK 17：`D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1`。
- SDK：`D:/Collect/.tooling/android-sdk`，包均已安装，无需重复下载。
- Gradle 参数：`-Dorg.gradle.java.installations.paths=D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1`；通常 `--max-workers=2 --console=plain`。
- 独立 AVD：`.gradle-home/avd/UniMusicMigrationAPI35.avd`，设置当前进程 ANDROID_AVD_HOME 后复用，WHPX 已验证；本轮结束正常关闭以节省资源。
- 最新日志：`build/unimusic-toolchain/phase2k-final-build-io.log`、`phase2k-final-validation.log`、`phase2k-validation.log`和`phase2k-final-boundaries.log`；4个关键检查XML及匿名协议实测已归档。2J/2I/2H等保留独立证据，避免重跑完整上游套件。
- 运行 Python 检查时 Windows 使用 `python -X utf8`，避免默认 GBK 读取 UTF-8 源文件失败。
- 本机普通沙箱执行器此前初始化失败，必要 shell 使用 require_escalated 入口。Git 单次加 `-c safe.directory=D:/unimusic`，网络再加 `-c http.sslBackend=openssl`，不改全局安全设置。
