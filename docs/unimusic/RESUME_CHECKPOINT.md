# UniMusic 续跑检查点

更新：2026-10-30（Asia/Shanghai），05:30单次续跑已删除；上轮因自动审批额度错误受阻，09:34用户“继续”后正常审批已恢复，接续开发。历史记录已完整保留在[历史检查点](RESUME_HISTORY_THROUGH_2026_10_30_2BF.md)，避免把旧待办/旧设备状态当作当前指令。

## 当前状态与优先级

- 完善网易云、酷我、B站、本地来源及搜索/推荐；QQ/YouTube在线搜索/播放/切源入口已停用，保留历史身份、原登录和本地文件；GD为显式用户来源。
- 保留网易云/Google授权和既有音乐库，不重发验证码/重登录/重导全部歌单。Room v23，禁止destructive migration、清数据或卸载。
- 用户授权每完成阶段自行commit并push，持续处理可恢复错误；只跑直接相关少量关键检查。不为耗额度制造工作，不使用reset/付费额度或绕过平台限制。

## 05:30本次新增阶段

| 阶段 | 实际结果 | 提交/推送 |
| --- | --- | --- |
| [2BD主源账号上下文](PHASE_2BD_PRIMARY_ACCOUNT_CONTEXT.md) | 2新/共3 JVM、边界、Debug3m2s；原账号变化后不返回旧试听/错误 | a52fdf29已推；CI被后续2BE替代 |
| [2BE试听逻辑时长](PHASE_2BE_PREVIEW_RECORDING_DURATION.md) | 2新/共3 JVM、边界、Debug48s；试听不回填未知整曲元数据 | 643b8a79已推，CI40960266466含Android迁移SUCCESS |
| [2BF独立歌手证据](PHASE_2BF_INDEPENDENT_ARTIST_EVIDENCE.md) | 2新/共3 JVM、边界、Debug1m42s；重复/外源/坏ID不充当多首共识 | ae82d472已push |
| [2BG试听书签/恢复](PHASE_2BG_PREVIEW_BOOKMARK_ISOLATION.md) | 2新/共3 JVM、边界、Debug44s；保留整曲书签及片段内有效位置 | f436541b已push |
| [2BH日志隐私](PHASE_2BH_PRIVATE_PLAYBACK_LOGS.md) | 3新/共5 JVM、边界、Debug53s；日志/异常两通道前脱敏 | f5b6d4f7已push，CI40964014292含Android SUCCESS |
| [2BI酷我实际来源](PHASE_2BI_KUWO_PLAYBACK_PROVENANCE.md) | 2新/共3 JVM、边界、Debug1m22s；真实来源徽标及无效音质操作保护 | 8cc7990b已push，CI40965533755含Android SUCCESS |
| [2BJ未知署名](PHASE_2BJ_UNKNOWN_ARTIST_EVIDENCE.md) | 3新/共5 JVM、边界、Debug1m38s；跨源/卡片/推荐不利用占位署名，算法v5 | e72358fc已push |
| [2BK名称边界](PHASE_2BK_ARTIST_NAME_BOUNDARIES.md) | 1新/共3 JVM、边界、Debug44s；名称有序边界、明确别名及作品共识 | e2ac4ec7已push |
| [3J正向歌手亲和度](PHASE_3J_POSITIVE_ARTIST_AFFINITY.md) | 1新/共4 JVM、边界、Debug51s；累计试听统计不再间接加分喜好，算法v6 | e5cca5d2已push，CI40967228048含Android SUCCESS |
| [2BL跨语言版本](PHASE_2BL_PERFORMANCE_VERSION_LABELS.md) | 2新/共3 JVM、边界、Debug49s；日文/繁体演绎、Off Vocal版本证据 | 47d54c19已push |
| [2BM流凭据边界](PHASE_2BM_BILI_STREAM_CREDENTIAL_BOUNDARIES.md) | 4不同JVM最终均通过（含1次测试桩修复）、边界、Debug成功；HTTPS/严格域名与重定向去凭据 | 分层恢复96415c15已push，CI40972945541整体SUCCESS |
| [3K稳定跳过采样](PHASE_3K_STABLE_SKIP_EVIDENCE.md) | 1新/共3 JVM、边界、Debug39s；暂停/恢复2秒内不推断不喜欢，含累计包设备观察 | 78f785e9已push，CI40972945541含新用例SUCCESS |
| [2BN浅克隆CI](PHASE_2BN_CI_SHALLOW_BASE.md) | 3 Python/4.077s与YAML解析通过；精确补base，失败保守回退；无重复APK构建 | 281d0b29已push，CI40972945541含Android SUCCESS |
| [2BO继承请求头](PHASE_2BO_INHERITED_STREAM_CREDENTIALS.md) | 1新/共2 JVM、边界/YAML、Debug1m37s；分段继承B站凭据也按实际目标过滤，补CI路径 | 7561fcdf已push，CI40974329323含Android SUCCESS |
| [2BP正常API鉴权](PHASE_2BP_BILI_API_AUTH_BOUNDARY.md) | 1新/共2 JVM、边界、Debug45s；自身HTTPS API正常鉴权与外部去凭据兼容 | eaac3fa7已push，CI40976201462 SUCCESS |
| [2BQ歌手短期缓存](PHASE_2BQ_ARTIST_METADATA_CACHE.md) | 1新/共3 JVM、边界、Debug53s；2分钟小索引/10秒歌曲页、上下文失效、失败重试 | 56d6aaef已push，CI40976201462 SUCCESS |
| [2BR旧日志脱敏](PHASE_2BR_LEGACY_DIAGNOSTIC_EXPORT.md) | 3新/共5 JVM、边界、Debug1m6s；查看/复制/流式导出保护、原文件保留 | bcb44f6c已push，CI40987129828含Android SUCCESS |
| [2BS异常检查上下文](PHASE_2BS_EXCEPTIONAL_CHECK_CONTEXT.md) | 2新/共4 JVM、边界、Debug55s；旧账号/决定异常不更新当前检查，复核不延预算 | b0af53ad已push，CI40989128063 SUCCESS |
| [3L同曲新周期](PHASE_3L_EXPLICIT_FEEDBACK_CYCLES.md) | 1新/共2 JVM、边界、runtime编译47s；同曲重新装载不累加旧偏好片段 | 2b088bc9已push，CI40989128063 SUCCESS |
| [4I首页库快照](PHASE_4I_HOME_LIBRARY_SNAPSHOT.md) | 无新增JVM；边界/累计Debug1m40s，回来直接读ViewModel快照，减少清空重建 | 68eb61de已push、CI成功、一趟装机往返通过 |
| [4J搜索取消](PHASE_4J_PLAYLIST_SEARCH_WORK.md) | 仅1取消回归/边界/app编译1m29s；旧索引/结果不混用 | 本检查点所在提交，待相邻优化统一push |

最新CI40989128063（68eb61de，含2BS/3L/4I）整体/依赖SUCCESS；实际XML见ci-40989128063.json，均0失败/跳过。报告下载EOF只重取附件，没重跑CI。纯源码不重复迁移，前必要40987129828已成功。4J只1核心检查+app编译，尚无其新APK。

最新构建：`build/outputs/unimusic/UniMusic-20261030-4I-debug.apk`，版本`2b088bc9e468.dirty.10300957`，SHA-256 `b6a7738affdfd33d74014fc1a38097a2a4460cd9bc2b6b8a8743e04420b61fb1`。边界/Debug1m40s通过，包含3L，无本地Gradle。4I包已保留数据安装，adb/系统/版本匹配，一趟首页→我的→首页位置保持；未测延迟。4J仅编译，待相邻优化合并一次打包。

## 此前已完成，不重做

- 2BB搜索歌曲/歌手索引并行，3F～3I推荐缓存、取消、空栏目和故障恢复，2BC新酷我/B站备用并行均已commit/push。
- 2BC源码8cec801f的CI40918996595整体SUCCESS；实际XML SourceResolver15、预算3、NetEaseAuto3、NetEaseLocal14全0失败。
- 9b87ef00的CI40917256200整体SUCCESS；ArtistIntent7、DailyStore8、PlaybackFeedbackStore4、LocalRanker7全0失败。摘要在evidence/2026-10-30。
- 当前规则见[SEARCH_BEHAVIOR.md](SEARCH_BEHAVIOR.md)；原唱/版本/署名/时长和当前权限门槛不放宽，不移植会员/DRM破解。

## 设备与验收边界

- 只操作授权Mate60 BRA-AL00，ADB按空白split，不输出标识。最初离线，后续重新在线，实际安装累计3K包`67338ee336f5.dirty.10300656`，adb Success/系统成功/版本匹配，保留数据；充电常亮已7，未改。
- 新包查询“哭泣少女乐队”顶部两关联乐队和原生曲仍正确。单次界面批次显示网易云0.5s、酷我0.4s、B站0.6s；包括排队/元数据，不等于切源/首声或全查询性能，未独立标定缓存。
- Take Me Hand显示50秒试听，未独立抓其进度/结束。どうして为3:01/320kbps且无试听标记，自身进度12.232→72.006秒；稳定UI播放/暂停2→3→2，最终state2/75992ms。未验证整曲终点、完整文件或人工听音。
- 推荐快照schema1/算法6；旧schema反馈enabled缺省兼容true，事件0→0。没有新正向完成/重播对照、隔离早期暂停非试听切歌、Kuwo/Bili新取流/跳转或切源计时验收；未清记录/重登录。
- 新分层修复APK没有再次安装；以上设备结果只对应3K包，见phase3k/device-validation.json。此前2BC的20秒试听/手动下一首证据保留在历史检查点及phase2bc，不冒充当前状态。

## 工具链与安全

- 本地JDK17 `.gradle-home/jdks/jdk-17.0.20.1+1`、SDK `.android-sdk` API37.0/BuildTools37、NDK27.0.12077973/CMake3.31.6和现有Gradle缓存可复用，勿重下载。
- 使用`tools_pub/unimusic/build_android.ps1 -GradleArgs @(...,'--offline')`；最多2 worker，不能同时运行本地Gradle或在构建时改源/输入文件。
- 本机没有runtime单测固定要求的JDK21，别重复尝试该套件或下载；纯logic/app关键JVM和APK可用17，runtime选择器由CI运行。
- Windows默认sandbox初始化偶发失败，可按相同require_escalated审批流程正常执行；若自动审批拒绝，不换工具绕过。已完成检查不重跑。
- 凭据、密码、验证码、Cookie、签名URL及联系方式不得写日志/文档/Git。只按既有授权本地使用，不扫描Codex历史或私有存储找秘密，不重问已授权的安装确认。

## 接下来

1. 2BS/3L/4I已CI通过、4I装机往返通过；4J已1核心回归+编译。接着保留首页推荐快照，合并一次APK/一次push，不重复设备循环和历史套件。
2. 下载路径已查到明确试听拒绝及现有回归，不重做。2BJ已修复未知署名、2BK已补名称边界、3J已隔离统计与亲和度；2BL已补跨语言版本；2BM已补流凭据边界；2BN浅克隆CI已修复且远端通过，勿重做；2BR旧日志查看/复制/导出已处理，勿导出真实秘密验证。
3. 设备重新在线后仅做必要、保留数据的本地验收，明确试听与完整版差别；不为测试修改真实账号/清除用户书签或偏好。
4. 继续高价值来源/匹配/推荐后台，按实际结果更新此短检查点，每阶段commit/push。

`unimusic-10-30-05-30`已在正确日期触发并删除，无剩余UniMusic定时计划；不新建每日循环。

2BM分层恢复/3K/2BN均已独立commit/push并远端通过。2BO/2BP/2BQ均commit/push并CI通过。2BR保存本阶段变更后继续来源/搜索/推荐开发，后续以Git/Actions为准。

用户09:xx最新要求：严格减少无用测试，功能收尾后优先加载、首页/我的切换、搜索速度；简洁沟通。3L只2 JVM+runtime编译，4I首页快照优化累计构建一次；继续按必要性做少量实机检查，保留滚动/交互和用户数据。
