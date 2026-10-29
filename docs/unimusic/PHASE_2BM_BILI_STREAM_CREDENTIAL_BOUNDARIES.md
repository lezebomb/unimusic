# Phase 2BM：播放/下载的B站凭据边界

日期：2026-10-30（Asia/Shanghai）。

## 发现与修复

isBiliStreamHost原用contains("bilivideo.")，相似主机可能被识别为B站并被注入仓库Cookie；下载又按逻辑歌曲平台直接构建Cookie。未观察到真实秘密泄露，验证没有使用任何真实账号。

现在按项目已有bilivideo.com、bilivideo.cn及mountaintoys子域的边界识别，拒绝仿冒前后缀、userinfo和非HTTP(S) URL。播放和下载共用基于实际目标地址的请求头生成；普通HTTP CDN仍可匿名请求，但不携带保存的Cookie，旧Cookie/Authorization不被混入。真实HTTPS CDN保留所需UA/Referer和当前Cookie，调用者其他头保持原行为。

共享OkHttp客户端加入每次网络跳转的凭据复核：从受信B站流开始的请求若跳到其他主机或HTTP，就移除Cookie/Cookie2/Authorization，保留Range等传输头。所有继承该客户端的生产播放/下载路径适用；不改变其他平台显式调用者认证、登录存储、用户数据或已停用来源。没有将地址获取成功冒充完整音频。

## 实际验证

- 4个不同的定向JVM：首轮3通过，1因新Mockito测试桩空匹配器失败；修正测试桩后仅补跑该1项并通过。初次失败和最终XML均保存在phase2bm，未隐藏失败，也未重跑整组。
- 覆盖仿冒域名、协议/userinfo、真实CDN优先级、实际目标/HTTPS限定、重定向去凭据、无关调用保留行为。所有Cookie与认证头均为虚构数据，未发外部请求。
- module_boundaries通过，最终`BUILD SUCCESSFUL in 1m 31s`；本机没有runtime单测要求的JDK21，既有header集成2项交CI跑，未下载JDK或临时覆盖要求。
- APK `build/outputs/unimusic/UniMusic-20261030-2BM-debug.apk`，版本`47d54c197707.dirty.10300651`，SHA-256 `b79f79f678b1069870bad4f67edaf777d7134b1d0acb1aa3369dcb2f6c833d5e`。
- 本次CI选择器加入上述platform类与已有bilibili stream runtime集成项，因此工作流配置的必要Android迁移CI仍需完成后再推下一源码阶段。
- 未执行设备安装、真实B站鉴权/网络跳转、完整歌曲、速度或全局隐私审计；不声称覆盖自建外部客户端或所有第三方库的日志/认证。

## 远端依赖检查失败与恢复

CI40969263368失败在platform:verifyDomainDependencies（4处新依赖），必要Android迁移因此跳过。实际取得XML仅BiliStreamUrls3、CredentialPolicy2和RecordingMatch11，均0失败；未取得本轮app/runtime对应XML，不能宣称这些项或整轮已通过，见ci-40969263368.json。

已把跳转拦截器移到应用网络层，平台仅保留域名/请求头协议，并使用已有列表/字符串操作，不放宽白名单。补跑3项JVM、同一平台依赖检查（17域0违规）和Debug2m31s均成功，新累计CI仍需通过。

修复APK：`build/outputs/unimusic/UniMusic-20261030-2BM-network-layer-debug.apk`，版本`67338ee336f5.dirty.10300715`，SHA-256 `62ef789e2c6bd2b85f296bbd7c6f031435fa126bdf74d764ebb41646cf939b10`。它包含已在本机验证的3K改动，但没有再次安装；实机证据对应此前3K包。
