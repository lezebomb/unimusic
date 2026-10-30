# Phase 2BY — 网易云音频响应绑定请求歌曲

2026-10-30（Asia/Shanghai）。时长接近不能说明返回的是同一首歌。

播放解析新增带expectedSongId的重载，保留旧两参数接口。主播放、一起听候选和下载现在都核对有地址响应的原生ID；缺失、其他ID、非规范小数ID不能作为目标音频。没有URL的权限/登录失败保留原分类，不因缺失ID被误改。继续执行歌手、版本、原曲时长及当前账号/来源决定复核；不从返回顺序或URL路径猜ID。

只执行3项直接回归（2解析、1下载），0失败，与Debug同批1m45s；边界通过。保留数据覆盖安装并核对版本，累计包含2BX下载时长检查。设备代表曲`雑踏、僕らの街`主源/一条网易云备用均PREVIEW_ONLY，说明正常有ID的响应继续进入试听识别；没有新增完整版播放或真实文件下载验收。

APK `build/outputs/unimusic/UniMusic-20261030-2BY-debug.apk`，版本 `d4bf289d5102.dirty.10301655`，SHA-256 `cc04f9e66486e3b3a51cea2fad1027c553f2ba3cccd884aae8e2829284a56af5`。[结果](evidence/2026-10-30/phase2by/results.json)。
