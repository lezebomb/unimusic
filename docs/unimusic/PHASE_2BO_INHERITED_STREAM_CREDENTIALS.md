# Phase 2BO：分段子请求的B站凭据边界

日期：2026-10-30（Asia/Shanghai）。

分段下载从playlistRequest复制头到每个新segmentRequest（AudioDownloadHlsTransfer），子请求拥有新的初始URL，原先只看首次URL的重定向防护无法识别继承上下文。

应用网络防护现在也识别B站Referer的严格域名及B站专用Cookie名称（只看名称，不把值中的SESSDATA文本误判成字段）。继承这些头的子请求若实际目标是非B站受信HTTPS，就移除Cookie/Cookie2/Authorization；保留Range和正常匿名传输。受信HTTPS CDN仍可使用原凭据；不读取新账号，不把请求真实发往外部地址来试漏。无关自定义Cookie/Referer按既有行为保留。

补上core/network/stream生产与测试文件的CI触发路径，后续只改这一层也能得到验证。scope计数通过带pipefail的tee输出当前CI日志且写入GITHUB_OUTPUT，只执行一次分类器，便于区分未知范围回退，分类器失败仍会让步骤失败。

实际1新增+1既有JVM共2项通过，边界/YAML通过，Debug1m37s成功；没有重跑平台/数据库全套。APK `build/outputs/unimusic/UniMusic-20261030-2BO-debug.apk`，版本`281d0b29afa9.dirty.10300729`，SHA-256 `c01333d29c1b24e241277447c7d3d0c8c855a0f37847c550cf5eaf73e5a0cc64`。

此前CI40972945541整体、平台依赖和必要Android迁移已SUCCESS，包含2BM分层恢复/3K/2BN；实际XML新网络1、平台域名3/请求头1、runtime注入2、ArtistIntent12、FeedbackTracker4、增量迁移1/升级3均0失败。摘要见ci-40972945541.json。新子请求用例与工作流触发修改仍需下一轮累计CI。

没有本阶段再次装机、真实HLS源/账号/网络转跳、完整音频或性能验收。手机仍对应前3K包的已归档播放观察，最终已暂停。此防护针对App保存的B站上下文，不声称审计所有第三方库或所有服务的认证方式。
