# APK 音源与搜索静态审计

日期：2026-10-26。只做本地静态分析；没有安装或运行 APK、执行样本 JavaScript、连接账号，或调用样本中的音乐/代理服务。

## 结论

**观察：** 输入并不是能够仅凭文件名认定的“官方网易云 9.1”。实际 APK 声明 `com.hihonor.cloudmusic`、`versionName=1.5.30`、`versionCode=1005030`。其归档含 LSPatch loader、原始宿主 APK，以及 `com.raincat.dolby_beta` 附加模块。附加模块实际声明“杜比大喇叭β”4.0.0，内含 `UnblockNeteaseMusic.zip`；其中 package.json 自报 `@unblockneteasemusic/server` 0.27.7。

**推断：** 样本更接近“宿主音乐应用 + LSPatch 附加模块 + Node JavaScript 来源适配层”，而不是两个对等音源的独立聚合搜索客户端。已看到网易云歌曲 ID → 歌曲元数据 → 歌名/歌手关键词 → 酷我搜索候选的静态代码关系。JNI 到 Node 的实际启动、运行时选项和请求路由没有执行验证。

**未知：** 任何端点当前是否可用、能否播放、是否需要用户授权、账号权益、地区限制和具体音质。搜索结果与域名字符串均不能证明可播放。

## 输入身份与材料链

| 材料 | SHA-256 |
|---|---|
| 网易云音乐_9.1_全网最新.zip | `d99303c1c27b925259e474848ebca06a0d72bb01185183983703f64bf554e404` |
| 内部 网易云音乐_1.5.30.apk，123075778 bytes | `674286d6e9522d95e1d168f47b98336b44d40819b41010a605cf95df0b2f3fef` |
| assets/lspatch/origin.apk | `c65429580506aac89c6b61ff181ac9c18dfdfdb0977fa176e53cc510d05200a5` |
| assets/lspatch/modules/com.raincat.dolby_beta.apk | `89ea85ef90c6530c935d765dd554ba532e1fbc3dbc257219092bfe0408a9ae55` |
| 模块内 assets/UnblockNeteaseMusic.zip | `8d5744ae2efa185b067b3f66e0388bc02ad16a10151bb2ad812571093c1bfd0e` |
| 解出 JavaScript 目录，REA inventory digest | `c359e018c5e09fc63dc2d1f4ed9d6137eba639b0e36ebc19755a21eec82981b9` |

外层 Manifest 的 `appComponentFactory` 为 `org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub`。模块 `assets/xposed_init` 指向 `com.raincat.dolby_beta.MainHook`。该类声明 `handleLoadPackage(XC_LoadPackage$LoadPackageParam)` 与 `initZygote(StartupParam)`。仅凭这些声明不声称已证明特定 Hook 行为。

外层归档同时有 React Native/Hermes 资源（例如 basics.android.hbc、搜索页面资源）和原生库（例如 libreactnativejni.so、libnode.so）。这是打包层面的运行时线索，不等同于动态调用证据。未验证签名，不能认证其官方出处。

## 普通搜索与元数据事实

以下只记载样本中的静态请求构造与映射，没有对服务发送请求。

### 网易云歌曲元数据

- 定位：`src/provider/find.js:24-79`，`getFormatData(data)`、`find(id,data)`、默认导出。
- 当调用方已传入 data 时，直接做字段规整；否则构造对 `https://music.163.com/api/song/detail` 的 GET，参数 `ids=[<song id>]`，读取 `songs[0]`。
- 保留 `id/name/alias/duration`，专辑保留 `id/name`，歌手保留 `id/name`。
- 关键词由歌名与歌手组成；会清理 cover/翻自括号说明，并有可选的专辑关键词。
- 以歌曲 ID 做缓存。这个适配文件没有实现网易云关键词搜索；不能把它当成完整网易云搜索 API 的证据。

### 酷我普通搜索

- 定位：`src/provider/kuwo.js:7-60`，`format(song)`、`search(info)`。
- 活动代码使用 GET `http://search.kuwo.cn/r.s`。普通查询字段为 `correct=1`、`stype=comprehensive`、`encoding=utf8`、`rformat=json`、`mobi=1`、`searchapi=6`、`all=<URI编码关键词>`。
- 样本额外含 `show_copyright_off=1` 的元数据筛选选项；未将它解释为授权或播放能力。独立只读 Provider 不依赖它获得播放权限。
- 关键词把 ` - ` 改为空格后编码。响应定位 `content[1].musicpage.abslist`；样本在该列表为空时拒绝结果。
- 文件前半段还包含一段已经注释掉的 Web 搜索实现。活动代码与注释不混为一谈；没有提取或复用其中的会话机制。

| 统一字段 | 酷我样本字段/转换 |
|---|---|
| source | 独立实现应明确标为 kuwo |
| providerSongId | `MUSICRID` 最后一个下划线后的部分 |
| title | `SONGNAME` |
| durationMs | `DURATION * 1000` |
| albumId / albumName | `ALBUMID` / `ALBUM` |
| artists | `ARTIST` 按 `&` 分割 |
| artistId | 首位歌手取 `ARTISTID`；其他歌手 ID 为 null |
| artwork | 该 format/search 没有映射封面，保持未知/空值 |

`src/provider/select.js:1-10` 只在前五条中挑首个时长差小于 5 秒的候选，无匹配则退回第一条。这种宽松规则存在错配风险，不适合直接作为 UniMusic 的自动播放决策。

`src/provider/match.js:58-63` 接收歌曲 ID、来源选择及可选元数据，先调用 find。来源调用和播放解析属于后续阶段；本报告不提供其解锁、签名、权限改写、音频地址解析或代理复现步骤。

## UniMusic 可独立参考的实现

1. 把搜索/目录元数据与播放能力分开：catalog Provider 返回 `provider + providerSongId + title + artists + album + durationMs + artwork?`，另设明确的播放状态。静态目录命中不能产生“可播放”结论。
2. 酷我先做只读 metadata-only Provider，保留来源与官方跳转；播放解析须另行验证可用性与用户权限。
3. 使用歌名、歌手、版本标记、专辑和时长共同排序；低置信度候选让用户选择。避免样本的“无匹配就第一条”。
4. 为字段缺失、字符串/数字 ID、空数组、响应结构变化、超时和限流做容错；缓存键包含来源、查询和分页，记录来源级错误，不把一个源的失败当成全部搜索失败。
5. 封面缺失时使用占位图；不要猜测接口。可借鉴字段与职责划分，独立编写实现；复制代码需另行核实许可证与项目分发条件。

## 工具版本、Evidence 与覆盖

用户更新 REA 后，已实际验证新 CLI `--version=6.8.0` 且安装包 version=6.8.0，并使用它重新完成所选 JavaScript 静态分析与 Android 包/类声明分析。已连接 MCP 进程当时仍自报 6.5.0、started_at=2026-10-26T05:24:33.790Z；因此新分析明确走 6.8.0 CLI，没有声称旧 MCP 已重连。

Android provider 为官方固定版本 jadx-headless-mcp 0.7.1；JAR 官方 SHA-256 与本地均为 `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`。只在单次 CLI 进程设置 REA_JADX_MCP_JAR/JAVA_HOME，未改全局 MCP 配置。JAR 来源：https://github.com/1013503897/jadx-headless-mcp/releases/tag/v0.7.1 。

| Evidence ID | 内容 |
|---|---|
| `ev_bbfb0eaabbc1065f280aec9d7822fc5497a8ca8ace50790d4d738e507a381d14` | REA 从输入 ZIP 安全提取 APK，记录内层 SHA-256 |
| `ev_c463b8f8dbc39cdd3292e4bc2068ac912723f7171ffe5bce8baf45af3e9c52aa` | 模块 APK 完整归档 inventory/inspection；不是运行证据 |
| `ev_525b8ea48ab9c4130a1ded4ecf1cea636542a485c2aca875d867286e4d2771ab` | REA 安全提取 JavaScript ZIP，文件逐项哈希 |
| `ev_d480ec29c7f7318a7aad87d2728da5c6775b86ec78edd6b45f18bd0609726034` | 更新后 REA 6.8.0 所选 JavaScript 静态分析，主要结论依据 |
| `ev_2e64cdd9807b85076e2bbc369dd5555314b3b7579e1945e06ac8452be08153d5` | 更新后外层 APK 身份；487 类；包名/版本/minSdk=28/targetSdk=33；签名未验证 |
| `ev_4e52fcd21717c91cdb024c46846e245a4fcbfd0b81719397f9841820ae23f983` | 更新后模块身份；1037 类；包名/版本/minSdk=23/targetSdk=33；签名未验证 |
| `ev_c5209efaa1ec079e68ef57e6077466a86db5c1a97d004184a8aa73c23c75d5bc` | 更新后 MainHook 类声明与精确 DEX 方法描述符 |
| `ev_f6b26645fc92e2a10d79a13c7e6af08d9fa3631683e68e2f044b0ffe52a5ff4b` | 更新前 6.5.0 相同所选 JavaScript 的原始 parent Evidence，保留用于交叉复核 |

所选文件为 package.json、src/provider/find.js、select.js、match.js、kuwo.js、src/cache.js、src/request.js。解析六个 JavaScript 文件，24865 字节，无 parse failure；48 个其他相关文件未分析。应用图与语义图明确为 partial，不能宣称全量功能或运行时可达性覆盖。源码定位均相对于内嵌 JavaScript ZIP。

本地材料：`rea-6.8-js-evidence.json` 为完整可复核 Evidence bundle（SHA-256 `b1d32e257caf17a9fc3a11dad9921d163cf237aca6842f765b1b8d4166f2dcf9`）；`rea-6.8-js-summary.json` 为摘要。原始源码/归档与大文件仅在 ignored build/ 或 REA 临时目录，没有加入 Git 索引。

## 限制与未完成项

- 初次 MCP Android 检查因缺 JADX provider 返回 capability_unavailable；已通过项目 build/ 内的固定 provider 与最新 CLI 解决。
- REA 6.5.0 对外层 APK 的 archive inspection 报 `ZIP archive is malformed: Ambiguous archive`。保留该失败，不推断样本损坏或恶意；受控单成员读取、aapt2 和更新后的 JADX 包身份检查成功。未把不同解析器的接受行为当作完整归档有效性证明。
- 动态/反射调用、JNI 到 Node 的启动边、预编译 JS 与源码等价、宿主原始搜索实现、当前端点可用性与播放权限均未验证。
- 自动审批拒绝了“完整反编译并输出 MainHook.handleLoadPackage 方法体”的操作，理由是可能暴露可操作的会员/DRM 绕过实现，超出结构性报告范围。本次没有重试、间接绕过或输出该方法体；包/类声明、普通搜索与元数据证据不受影响。
