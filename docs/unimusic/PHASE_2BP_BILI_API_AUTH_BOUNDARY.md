# Phase 2BP：正常API鉴权与媒体防护共存

日期：2026-10-30（Asia/Shanghai）。

2BO新增继承凭据判断会作用在共享客户端上，B站自身API也带相同Cookie；仅允许媒体CDN会误移除正常API鉴权。已把B站自己的HTTPS web/API域加入同一严格边界，并把自身API初始请求纳入来源判断，未知Cookie名的启动/登录请求若跳到外部也会去掉凭据。

允许bilibili.com及真正子域的HTTPS（不含userinfo），不允许伪后缀或HTTP。受信媒体CDN保持原规则；继承B站头的外部子请求继续匿名，数据存储/登录未改，不放宽第三方地址的权限。

本阶段新增正常API正向/外部跳转反向用例，和原继承子请求回归共2 JVM通过；边界通过，Debug45s成功。使用虚构请求/凭据，无真实账号、外部网络或新手机操作。原真实手机仍是3K包，未受尚未安装的2BO/2BP改动影响。

APK `build/outputs/unimusic/UniMusic-20261030-2BP-debug.apk`，版本`7561fcdf112d.dirty.10300741`，SHA-256 `4e59a43c6525efa0e9d79e1f47c3007d4956669e6f7ac73d142cd01876de17ab`。现有CI整类选择器包含新用例；先本地commit，待2BO必要工作流40974329323实际完成后再push，不能取消其Android安全检查。未宣称真实B站登录/音频/全部源已完成验收。
