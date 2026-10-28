# Phase 2AV：服务拒绝与网络错误分开，其他来源实际播放

2026-10-28，接续2AT/2AU的手机联网恢复和播放修复。

QQ/酷我目录HTTP拒绝、QQ非成功业务响应或缺少歌曲列表、酷我响应页码/结构拒绝，改为类型化UNAVAILABLE；保留真正传输IOException的NETWORK及SocketTimeoutException的TIMEOUT。有效空列表仍是正常空结果，不能把失败返回空列表当搜索成功。不重试、不改音频权限，不改变已返回的其他平台曲目。

新1项组合JVM检查覆盖两个真实Provider与聚合引擎，分别模拟HTTP500、无效数据、有效空结果、网络错误、超时，核对健康平台结果保留和可重试状态。初次构建40s因误删JSONArray导入失败，已恢复并接续；最终JVM1/0失败、平台领域0违规、当前模块边界通过、Debug1m20s。

手机另有真实音频证据（运行于已安装2AU）：B站样本发现1732ms，2697ms观察到2211ms播放进度；GD样本发现1924ms，3434ms观察到2099ms进度，两者均无播放器错误，结束暂停且恢复音量。与2AT已验证的网易云、酷我合计四路样本可播放；非原唱准确率、首声、统计延迟或全曲耐久验收。

QQ匿名接口调查仅使用公开元数据、无账号凭据：旧c.y.qq.com搜索桌面单次HTTP500/5203ms；[当前QQ官方公共JS](https://y.qq.com/ryqq/js/common.chunk.5b378f933e2203b7da48.js?max_age=2592000)使用SearchCgiService的DoSearchForQQMusicDesktop。普通RPC单次264ms/HTTP200、code0，但0条/总数0；补普通分页字段后仍为空。没有足够正向证据，不替换生产搜索，也不声称QQ已恢复。手机最终仍显示QQ网络错误，不能以桌面500解释其具体原因。

最终包保留数据安装成功，版本`df89c94738aa.dirty.10282208`。实际聚合搜索Canon返回网易云30、B站20、酷我20，YouTube超时、QQ网络错误，健康结果保留；此次UI没有合成HTTP500故障注入。APK：`build/outputs/unimusic/UniMusic-20261028-2AV-debug.apk`，哈希、JVM/XML、脱敏设备结果见[evidence](evidence/2026-10-28/phase2av/validation.json)。

2AT远端40700241615和2AU远端40701853652均成功。本阶段远端以提交后的实际状态为准。QQ账号正向音频、YouTube取流仍未验收；Google/网易云登录、既有歌单和Room v23保留，未改DNS/代理/VPN/凭据，未发送分享或消息。
