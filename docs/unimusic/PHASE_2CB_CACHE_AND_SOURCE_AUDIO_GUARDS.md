# Phase 2CB：旧缓存与原生入口音轨检查

2026-10-30（Asia/Shanghai）。2BZ删除新视频回退后，旧完整B站视频缓存仍可绕过重新取流。现在读取旧描述符时跳过试听或已知视频类型的B站缓存，继续解析当前独立音轨；不在该分支清缓存，不变更本地文件处理或v2格式。

另一个缺口是试听/DRM此前仅由跨源解析检查，普通B站取流没有共用。现在`getAllAudioStreams`和`toAudioStreamInfos`均拒绝明确受限响应，直接取流时不重试、不回退视频；保持正常音轨和原有匹配门槛。

实际验证：新增1项平台JVM检查覆盖转换/取流、显式试听与DRM标记，0失败；runtime/app先编译38秒、缓存修复中间APK43秒（未安装），补齐原生入口后最终Debug及该JVM累计1分4秒。边界与空白检查通过。扩展既有`preview cache metadata stays distinct while old complete v2 metadata still opens`测试，覆盖旧视频描述符/正常音轨/本地文件；本机缺JDK21，未运行或下载，交由现有CI选择器执行。CI41047701749成功，已下载`unimusic-focused-reports`并核对该回归XML：1项通过，无failure/error/skipped。

最终包`build/outputs/unimusic/UniMusic-20261030-2CB-debug.apk`，版本`6864690a823c.dirty.10301915`，SHA-256 `404f886d5ea899ad1b0569fa33ff22287229757084cb7537062ba0a1f5a66034`，已累计2CA。保留数据覆盖安装adb Success、版本匹配，启动后发现/我的导航可见。没有重新登录、导入、下载或清空用户数据；未重复2BZ的MV首尾解码，不把旧验证称作最终包整曲播放或自动原唱成功。

2CA的CI41045025687已成功。2CB提交a1418957首次推送遇TLS EOF，正常重试成功，没有关闭证书校验。发行账号信任仍未扩展，代表曲原唱自动切源尚未全部接通。
