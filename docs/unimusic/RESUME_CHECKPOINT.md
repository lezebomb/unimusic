# UniMusic 续跑检查点

更新：2026-10-30（Asia/Shanghai），05:30单次续跑已开始并持续开发。历史记录已完整保留在[历史检查点](RESUME_HISTORY_THROUGH_2026_10_30_2BF.md)，避免把旧待办/旧设备状态当作当前指令。

## 当前状态与优先级

- 完善网易云、酷我、B站、本地来源及搜索/推荐；QQ/YouTube在线搜索/播放/切源入口已停用，保留历史身份、原登录和本地文件；GD为显式用户来源。
- 保留网易云/Google授权和既有音乐库，不重发验证码/重登录/重导全部歌单。Room v23，禁止destructive migration、清数据或卸载。
- 用户授权每完成阶段自行commit并push，持续处理可恢复错误；只跑直接相关少量关键检查。不为耗额度制造工作，不使用reset/付费额度或绕过平台限制。

## 05:30本次新增阶段

| 阶段 | 实际结果 | 提交/推送 |
| --- | --- | --- |
| [2BD主源账号上下文](PHASE_2BD_PRIMARY_ACCOUNT_CONTEXT.md) | 2新/共3 JVM、边界、Debug3m2s；原账号变化后不返回旧试听/错误 | a52fdf29已推；CI被后续2BE替代 |
| [2BE试听逻辑时长](PHASE_2BE_PREVIEW_RECORDING_DURATION.md) | 2新/共3 JVM、边界、Debug48s；试听不回填未知整曲元数据 | 643b8a79已推；CI40960266466正在验证Android迁移 |
| [2BF独立歌手证据](PHASE_2BF_INDEPENDENT_ARTIST_EVIDENCE.md) | 2新/共3 JVM、边界、Debug1m42s；重复/外源/坏ID不充当多首共识 | ae82d472已本地commit，等待必要CI后push |
| [2BG试听书签/恢复](PHASE_2BG_PREVIEW_BOOKMARK_ISOLATION.md) | 2新/共3 JVM、边界、Debug44s；保留整曲书签及片段内有效位置 | 本检查点所在提交，先本地commit |

**先让2BE的CI40960266466完成再推下一源代码阶段。** 它因工作流新增两项preview选择器，按既有保守路由需要一次Android迁移检查，不能被下次push取消。没有新增Room迁移，勿重复本机迁移套件。2BF/2BG新用例会被后续普通行为集覆盖；最终状态/推送以git/Actions为准。

最新构建：`build/outputs/unimusic/UniMusic-20261030-2BG-debug.apk`，版本`ae82d4722eff.dirty.10300552`，SHA-256 `e41a636550c0afde1d1798f75a6bb3ecf975b153703c17d1b30ea6d2e1770491`。当前无本地Gradle构建运行，CI监视可能仍在运行，先查进程/日志再启动。无本次设备安装或真实账号切换/书签/完整音频/速度验收。

## 此前已完成，不重做

- 2BB搜索歌曲/歌手索引并行，3F～3I推荐缓存、取消、空栏目和故障恢复，2BC新酷我/B站备用并行均已commit/push。
- 2BC源码8cec801f的CI40918996595整体SUCCESS；实际XML SourceResolver15、预算3、NetEaseAuto3、NetEaseLocal14全0失败。
- 9b87ef00的CI40917256200整体SUCCESS；ArtistIntent7、DailyStore8、PlaybackFeedbackStore4、LocalRanker7全0失败。摘要在evidence/2026-10-30。
- 当前规则见[SEARCH_BEHAVIOR.md](SEARCH_BEHAVIOR.md)；原唱/版本/署名/时长和当前权限门槛不放宽，不移植会员/DRM破解。

## 设备与验收边界

- 只操作授权Mate60 BRA-AL00。`adb devices -l`必须按空白split，不能要求制表符；导出只显示数量/型号，不输出设备标识。05:30正确检测未找到该目标，离线继续开发。
- 最后真正装机是累计2BC包`9b87ef001ede.dirty.10300110`，系统安装成功/版本匹配、保留数据。充电常亮为7，当时未改设置。
- 该包实机完整历史词“哭泣少女乐队”顶部两关联乐队及原曲正确；明确20秒试听进度6044→8115ms，反馈开启，手动下一首新增偏好0条。最后自身媒体会话state2/5456ms已暂停；这是历史观察，不当作当前实时状态。
- 这不是新完整备用来源或切源速度验收；未做新正向完整曲反馈对照、全局准确率、推荐清除/重启/下载/导入UI、真实双设备同步。实际证据在phase2bc/device-validation.json。

## 工具链与安全

- 本地JDK17 `.gradle-home/jdks/jdk-17.0.20.1+1`、SDK `.android-sdk` API37.0/BuildTools37、NDK27.0.12077973/CMake3.31.6和现有Gradle缓存可复用，勿重下载。
- 使用`tools_pub/unimusic/build_android.ps1 -GradleArgs @(...,'--offline')`；最多2 worker，不能同时运行本地Gradle或在构建时改源/输入文件。
- 本机没有runtime单测固定要求的JDK21，别重复尝试该套件或下载；纯logic/app关键JVM和APK可用17，runtime选择器由CI运行。
- Windows默认sandbox初始化偶发失败，可按相同require_escalated审批流程正常执行；若自动审批拒绝，不换工具绕过。已完成检查不重跑。
- 凭据、密码、验证码、Cookie、签名URL及联系方式不得写日志/文档/Git。只按既有授权本地使用，不扫描Codex历史或私有存储找秘密，不重问已授权的安装确认。

## 接下来

1. 核对2BE必要CI与XML，保存状态，然后推送已完成本地提交。
2. 已确认NPLogger没有统一脱敏，而seek日志会拼接当前媒体URL；继续补正常日志入口的凭据/联系方式/签名URL保护，覆盖logcat和文件队列，全部使用虚构测试值，不读取或导出真实秘密。
3. 设备重新在线后仅做必要、保留数据的本地验收，明确试听与完整版差别；不为测试修改真实账号/清除用户书签或偏好。
4. 继续高价值来源/匹配/推荐后台，按实际结果更新此短检查点，每阶段commit/push。

`unimusic-10-30-05-30`已在正确日期触发并删除，无剩余UniMusic定时计划；不新建每日循环。
