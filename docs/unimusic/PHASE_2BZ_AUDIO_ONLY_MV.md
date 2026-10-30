# Phase 2BZ：MV仅使用独立音轨

2026-10-30（Asia/Shanghai）。用户要求MV来源最终仍按音乐播放。

`BiliClient.getAllAudioStreams`此前在DASH没有音轨时，会请求HTML5并返回含视频的MP4。现在删除这一回退，保留已有有界DASH重试；没有独立音轨就返回空结果，交由既有来源失败处理。共用`toAudioStreamInfos`同时拒绝视频MIME或非零画面尺寸，普通、杜比和无损分支一致生效。没有扩大音频权限，也没有放松原唱、版本和时长检查。

新增可选`PublishedBiliAudioDeviceTest.inspectPublicationAndDecodeTail`，仅显式传入`unimusicPublishedBiliProbe=true`时执行。它读取正常客户端的公开发布账号/认证、原生歌曲元数据，以静音独立播放器检查音轨、时长和尾段解码；不写入用户队列、历史或收听偏好。报告不包含Cookie、签名地址、设备标识或个人信息。此诊断始终标记`automaticOriginalMatchVerified=false`，音轨可用不能替代原唱匹配验收。

实际验证：

- 三项直接相关JVM检查（视频回退、渐进式视频、错误音轨元数据及正常音轨对照）0失败；Debug与AndroidTest APK累计构建成功，3分36秒。
- 模块边界和空白检查通过；没有执行全量测试。
- 产品包`build/outputs/unimusic/UniMusic-20261030-2BZ-debug.apk`，版本`25f09cf49140.dirty.10301833`，SHA-256 `51bec75eff593ac74147119b1ecdf8b3c28951294c92b43f648b32796307014a`。
- 初次安装/诊断遭遇权限审核连接错误和设备桥接错误，没有有效报告。用户确认后仍是旧版本；按用户新授权覆盖安装，产品和测试包均捕获adb Success，实际产品版本匹配。保留用户数据，没有卸载或清空。
- 最终仅运行上述一项设备诊断，1项通过，总耗时6146ms。BV1HVQHYNEBg（ダレモ）返回非试听、非DRM的audio/mp4；播放器发现音轨、没有视频轨，解码时长189012ms，尾段位置186044ms，渲染音频缓冲60个。网易云原生曲186566ms、歌手ID57020536，公开发布者MID3546852200811118。
- 发布者认证接口HTTP200但业务码-352，未取得认证，不视为成功或绕过风控。候选传输/纯音轨已验证，自动原唱匹配仍未放行；未整曲解码、未人工听音、未证明两首既有代表曲或整个乐队可自动播放。6146ms不是自动切源/首声性能结论。

证据见`evidence/2026-10-30/phase2bz/results.json`；详细构建与安装过程仅保留在忽略的本地构建目录。
