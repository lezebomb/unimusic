# 完整音源匹配服务（UnblockNeteaseMusic 桥）接入说明

状态：已实现并真机验证（2026-10-31）。本文档对应提交 `feat(sources): ...`（阶段 A 为 `103faf8c`，阶段 B–D 见同系列提交）。

## 1. 为什么需要它

网易云对本项目的代表曲（`トゲナシトゲアリ` 的 `雑踏、僕らの街`、`誰にもなれない私だから`、`空の箱`、`ダレモ`）返回 `PREVIEW_CLIP`：账号没有该地区/该曲的完整播放权限，客户端只能拿到 30 秒试听片段。

`UnblockNeteaseMusic/server` 在服务端把「网易云歌曲 ID」映射到其他渠道的同曲音频地址。实测四首代表曲在三家渠道都有完整音轨：

| 渠道 | 码率 | 完整性证据 |
| --- | --- | --- |
| `pyncmd`（网易云自家 CDN） | 320 kbps | size/br 推算时长与目录时长差 < 300 ms（4 首中 3 首命中） |
| `bodian`（酷我 320k 镜像） | 320 kbps | 同上，4 首全部命中，`ID3` 头 + `content-length` 与实际字节一致 |
| `kuwo`（酷我 128k） | 128 kbps | 4 首全部命中，但码率低于前两者 |

被显式排除的渠道：`bilibili`、`bilivideo`（只返回被截断的视频音轨，size 仅约 1.0–1.25 MB）、`kugou`/`migu`/`qq`/`joox`/`ytdlp`（当前不可用或不适用）。

## 2. 两个必须自己解决的问题

### 2.1 上游是 MITM 代理，没有独立的匹配 REST 接口

`src/server.js` + `src/hook.js` 是拦截网易云 `/api/song/enhance/player/url` 响应的代理。可复用的匹配入口是 `src/provider/match.js` 导出的 `async match(id, source, data)`，返回：

```js
{ size: number, br: number | null, url: string | null, md5: string | null, source: string }
```

它**不提供**标题、歌手、版本、时长、MIME、有效期。因此身份与完整性证据必须由客户端补齐（见 §3）。

### 2.2 上游默认「最快者胜」，128k 总会抢赢

`match.js` 默认用 `Promise.any`：先返回的先算数。酷我 128k 接口比 320k 镜像快，于是请求几乎总是落回 128k（实测单层调用四首全部返回 `kuwo` / 128000）。

桥（`tools_pub/unm-bridge/bridge.js`）因此实现**分层竞速**：

- `UNM_BRIDGE_TIERS`，默认 `pyncmd,bodian|kuwo`；
- `|` 分隔「层」，层内并发、层间顺序降级；
- 第一层（320k）全部失败才降级到第二层（128k）；
- 请求带 `?sources=` 时视为单层覆盖，由调用方决定。

分层生效后四首全部返回 `bodian` 320k。

## 3. Android 侧契约

### 3.1 桥服务（`tools_pub/unm-bridge/`）

- `bridge.js`：纯 `node:http` 实现，必须先设置 `global.endpoint` / `global.hosts` / `global.cnrelay` 再 `require` 上游 `match.js`；因为上游使用 Yarn PnP，脚本必须复制到上游检出目录内运行。
- `run.ps1`：定位 Node、校验上游目录、按需执行 `yarn install --immutable`、复制并启动桥。
- 路由：
  - `GET /health` → `{ ok, version, sources, tiers }`
  - `GET /match?id=<网易云ID>[&sources=]` → `{ ok: true, id, source, url, size, br, md5 }` 或 `{ ok: false, reason }`
- 失败类型：`bad_id` / `unauthorized` / `not_found` / `upstream_timeout` / `upstream_rejected` / `match_failed`。失败一律 HTTP 200 + `ok:false`，避免客户端把业务失败当网络故障。
- 可选 `UNM_BRIDGE_TOKEN`：设置后要求 `x-unm-token` 头。

### 3.2 客户端（`modules/platform/.../platform/unm/api/UnmBridgeClient.kt`）

`UnmBridgeClient.match(bridgeBaseUrl, neteaseId, expectedDurationMs, sources)` 返回 `UnmBridgeOutcome.Matched(UnmMatchedAudio)` 或 `UnmBridgeOutcome.Failed(reason)`。

服务端字段全部按不可信输入校验：

- `source` 必须属于 `pyncmd` / `bodian` / `kuwo`（`UNM_ALLOWED_SOURCES`）；
- `br` 必须是 128k/192k/320k；
- `size` 必须 ≥ 512 KiB；
- URL 必须无 userinfo/fragment，不得是 `.m3u8`/`.mpd`；`https` 只允许 `*.kuwo.cn`、`*.music.126.net`，`http` 只允许 `*.kuwo.cn`（网易 CDN 只走 https，酷我 CDN 走明文 302，`network_security_config.xml` 已允许明文，因为它本来就要支持一起听）；
- 回显 `id` 必须与请求 ID 一致；
- **时长证据由 `size` 与 `br` 反推**：`durationMs = size * 8000 / br`，与目录时长的容差为 `max(4000ms, expected/32)`；超差即视为截断片段，直接拒绝。这条规则正是挡住 `bilivideo` 那种「有 URL 但只有 1 MB」的情况的机制。

此外客户端自带滑动窗口限流（5 分钟内最多 60 次），超限立即失败而不排队，避免把 UI 卡在等桥上。

### 3.3 播放链路接入点

- 新增逻辑来源 `unm_netease`，登记在 `modules/model/.../unified/MusicSourceCapabilities.kt`（`validKey` 按正整数网易云 ID 校验）。
- `modules/playback/runtime/.../core/player/resolver/unm/PlayerManagerUnmBridgeSource.kt`：`tryResolveUnmBridgeSource(song, sideEffects)`，预算 `sourceStageBudgetMs(3_000, 5_000)`，返回前复核开关、地址、会话是否仍然有效。
- 该任务作为 `NeteasePlaybackUrlResolver.resolveNeteaseAlternates` 里 `raceFreshPlaybackSources` 的**第三个并行候选**，与「原生目录备选」「网易云自动 B 站」并列竞速；不再串联新的超时，服务不可达时只是该候选返回 null，不会拖垮其他来源。
- 结果构造为 `SongUrlResult.Success`：带真实 `durationMs`、`expectedContentLength`、`mimeType = audio/mpeg`、`audioInfo`（`NETEASE`、码率标签）、`cacheKeyOverride = "unm-<source>-<id>-<br>"`、`isPreviewClip = false`，因此能通过 `fullRecordingPlaybackResultOrNull()` 的完整录音门禁。
- `RememberedSourceResolver.resolveSourceCandidate` 也支持 `unm_netease` 候选，便于「来源」菜单显式选择；这里用的是 `resolveUnmBridgeSource`，它返回 `UnmSourceResult.Ready / Unavailable(SourceFailure)`，因此显式检查能拿到真实原因：桥不可达 → `NETWORK`、没有该录音 → `NO_MATCH`、被白名单/时长门禁拒绝 → `UNAVAILABLE`、超出预算 → `TIMEOUT`。竞速路径继续用 `tryResolveUnmBridgeSource`（失败静默返回 `null`）。

### 3.4 来源菜单与发现

- 「来源」面板（`SourceChoiceDiscovery` / `SourceChoiceSheet`）只在**开关打开且地址非空**时才列出一项「完整音源匹配」，且只挑与当前歌曲**同一个网易云 ID** 的身份——桥按 ID 匹配，跨 ID 候选没有意义。
- 面板打开本身不会请求桥；只有点「检查」才走 `checkSourceAvailability` → `resolveSourceCandidate`（`unm_netease` 分支），结果按 §3.3 的原因映射显示在卡片上。
- 失败原因也进入 `SourceAvailabilityChecker` 的统一状态映射，与 GD 音乐台、酷我、QQ 面板行为一致。

## 4. 设置

设置 → 播放 → 播放音源：

- `unm_bridge_enabled`（默认关闭）：使用完整音源匹配服务；
- `unm_bridge_base_url`：服务地址，例如 `http://192.168.1.10:8900`，弹窗内可「检测」连接。

两项都写穿到 `PlaybackPreferenceSnapshot`，`PlayerManager` 冷启动时由 `applyUnmBridgePreferences` 注入、运行时由 `observeUnmBridgeSettings` 跟随设置变化。

默认关闭，且未配置地址时整条链路完全不动，等同于不存在。

## 5. 上游固定版本与许可证

- 上游：`https://github.com/UnblockNeteaseMusic/server`，分支 `enhanced`，提交 `1d64281`，版本 `0.28.0`。
- 许可证：**LGPL-3.0-only**。桥不修改、不打包、不分发上游源码，只在运行时 `require` 本地检出副本，因此本仓库不包含上游代码；分发修改版本时需遵守 LGPL。
- 运行环境：Node ≥ 12（实测 v24.13.0）、Yarn 3.8.7（PnP）。

## 6. 真机验证

设备：`9CN0224C17003798`（Mate60）。链路：`adb reverse tcp:8900 tcp:8900` → 桥监听 `127.0.0.1:8900`。

`app/src/androidTest/java/moe/ouom/neriplayer/sources/UnmBridgeDeviceTest.kt`（门控参数 `-e unimusicLiveUnmBridge true`）：

| 曲目 | 结果 | 实际音源主机 |
| --- | --- | --- |
| 雑踏、僕らの街 | PLAYED（position > 2 s） | `m801.music.126.net` |
| 誰にもなれない私だから | PLAYED | `m801.music.126.net` |
| 空の箱 | PLAYED | `m801.music.126.net` |
| ダレモ | PLAYED | `m701.music.126.net` |

四首全部脱离 `PREVIEW_CLIP`，播放推进超过 2 秒且来自完整音轨主机。测试内固定把媒体音量设为 0，跑完恢复。

`app/src/androidTest/java/moe/ouom/neriplayer/sources/UnmBridgeSourceChoiceDeviceTest.kt`（同一门控参数）覆盖来源面板：

- 打开开关并填地址后，`discoverSourceChoices` 必须为当前曲目给出 `unm_netease` 候选，且 `resourceId` 等于该曲的网易云 ID；
- 关掉开关后，同一调用不得再出现 `unm_netease`。

真机结果：`OK (1 test)`（2026-10-31，app `d65ed1ffd15a`）。同一次构建上 `UnmBridgeDeviceTest` 四首仍全部 `PLAYED`、`source=unm_netease`。

### 6.1 负例（必须保持的行为）

| 输入 | 桥的响应 | App 侧结果 |
| --- | --- | --- |
| 不存在的网易云 ID（`1`、`9999999999`） | `{"ok":false,"reason":"match_failed"}` | `NO_MATCH` → 面板显示「元数据不匹配」，播放不被接管，继续走原有回退 |
| 非法 ID（`0`、`abc`） | `{"ok":false,"reason":"bad_id"}`（HTTP 400） | 请求在客户端就被 `parseUnmBridgeBaseUrl`/ID 校验挡下，不产生 HTTP 调用 |
| 桥不可达（端口关闭） | 连接失败 | `NETWORK` → 面板显示「网络异常」；竞速路径静默跳过，其他来源不受影响 |
| 返回被截断的音轨（`bilivideo` 那类） | `size` 与码率反推时长超出容差 | `REJECTED` → `UNAVAILABLE`，不会拿 1 MB 的片段冒充完整曲 |

## 7. 已知边界

- 桥只做「网易云 ID → 音频 URL」；非网易云来源（酷我原生目录、B 站、本地）不经过它。
- 网易云曲目若在三个渠道都找不到（新曲、冷门曲），链路行为与未接入完全一致（继续走原有回退）。
- 地址变更或开关关闭会立即作废进行中的匹配请求（返回前复核）。
- `pyncmd` 的 URL 带时间戳路径，长期缓存意义有限；缓存键里只放来源与 ID，不放 URL。
