# UnblockNeteaseMusic 匹配桥（UniMusic 配套服务）

UniMusic 的在线音源全部依赖客户端直连 CDN。网易云对部分曲目（如 `トゲナシトゲアリ` 的《雑踏、僕らの街》）
只返回 `PREVIEW_CLIP`，客户端拿不到完整音频。本目录提供一个**只读匹配桥**：把
[UnblockNeteaseMusic/server](https://github.com/UnblockNeteaseMusic/server) 的匹配能力
（`src/provider/match.js`）暴露成两个 HTTP 接口，让已配置该服务的设备可取得同一首歌的完整音轨地址。

上游 `src/app.js` 启动的是 MITM 代理，只能改写网易云 `/api/song/enhance/player/url` 的响应，
没有独立的匹配路由，因此需要这层薄封装。

## 接口

```
GET /health
  -> {"ok":true,"version":"0.28.0","sources":[...],"tiers":[["pyncmd","bodian"],["kuwo"]]}

GET /match?id=<neteaseId>[&sources=a,b]
  -> 200 {"ok":true,"id":"2142857204","source":"bodian",
          "url":"http://bd-er.kuwo.cn/.../M800000ONJFa3jViPd.mp3?from=bodian",
          "size":7373020,"br":320000,"md5":null}
  -> 200 {"ok":false,"reason":"not_found"}
  -> 400 {"ok":false,"reason":"bad_id"}
  -> 401 {"ok":false,"reason":"unauthorized"}   // 仅当设置了 UNM_BRIDGE_TOKEN
```

`source` 只会是 `pyncmd` / `bodian` / `kuwo`。`bilibili` 与 `bilivideo` 被显式排除：前者是另一套
id 空间，后者返回的是视频音轨片段，实测 184 秒曲目只有约 1.05 MB，不能当作完整原唱。

`size` 与 `br` 是上游 `check()` 从响应头与首帧解析出的值，也是客户端唯一可用的**时长证据**：
客户端用 `size * 8 / (br / 1000)` 反推时长，并与曲库时长比对后才允许播放。`/match` 不返回标题、
歌手或 MIME，因为上游本身没有这些字段；录音身份由「按网易云 id 匹配」这一事实保证。

## 启动

```powershell
# 首次：准备上游源码（任选目录，勿放进版本控制的源码树）
git clone --depth 1 --branch enhanced https://github.com/UnblockNeteaseMusic/server.git D:\unm\server

# 启动（依赖缺失时会自动 corepack yarn install --immutable）
pwsh tools_pub/unm-bridge/run.ps1 -UnmServerDir D:\unm\server -Port 8900
```

`run.ps1` 会把 `bridge.js` 复制进上游目录再运行——上游使用 Yarn PnP，
`--require ./.pnp.cjs` 只在项目目录内生效。

参数与环境变量：

| 参数 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `-Port` | `UNM_BRIDGE_PORT` | `8900` | 监听端口 |
| `-Bind` | `UNM_BRIDGE_BIND` | `0.0.0.0` | 监听地址，`127.0.0.1` 仅本机 |
| `-Tiers` | `UNM_BRIDGE_TIERS` | `pyncmd,bodian\|kuwo` | 用 `\|` 分层；层内并发竞速，层间按顺序降级 |
| `-Token` | `UNM_BRIDGE_TOKEN` | 空 | 设置后要求 `x-unm-token` 头 |
| `-TimeoutMs` | `UNM_BRIDGE_TIMEOUT_MS` | `15000` | 单层匹配超时 |

分层是必要的：上游默认用 `Promise.any` 竞速，最快的 `kuwo`（128 kb/s）总是先返回，会盖掉
`pyncmd`/`bodian` 的 320 kb/s。分层后第一层命中约 0.2–0.5 s 且为 320 kb/s，`kuwo` 仅作兜底。

## 客户端配置

App 内「设置 → 播放 → 播放音源」中的*解锁灰歌（自建匹配服务）*：开启开关并填写
`http://<本机局域网 IP>:8900`。桥只做只读匹配，不接收也不转发任何网易云 Cookie。

## 固定版本与许可

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| UnblockNeteaseMusic/server | `enhanced` 分支 `1d64281`，`0.28.0` | Node ≥ 12（实测 v24.13.0），Yarn 3.8.7 + PnP |
| 本桥 | `bridge.js` | 仅用 Node 内置模块，无额外依赖 |

上游为 **LGPL-3.0-only**。本桥不修改、不打包上游代码，仅在运行时 `require` 其模块，
因此上游按其自身许可分发；`bridge.js` 本身遵守本仓库许可。升级上游前请重新验证四条代表曲的
`source`/`size`/`br`，因为提供方随时可能失效。
