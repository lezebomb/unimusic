# Phase 3D.4：独立条件同步与WebDAV后台

日期：2026-10-28。后台可调用，不自动开启同步、不读取用户账号或向真实服务上传。旧同步路径、调度和云设置保持不变。

`UnifiedIdentitySyncCoordinator`读取远端独立文档并先将证据合并落盘，再尝试条件写。发生版本冲突重新读/合并，最多3次、总体45秒，每次HTTP最多15秒；不会将条件失败变为无条件覆盖。上传期间新增的本地修改保留并返回pending标记，下一次可接着同步。

`UnifiedIdentityWebDavTransport`只访问旧同步文件同目录的`unimusic-identities-v1.json`。缺文件用If-None-Match:*创建；已有文件必须有强ETag，用If-Match更新，412才作为版本冲突重试。弱/缺ETag明确失败，不用Last-Modified秒级精度替代。不会自动建目录、重定向或覆盖旧文件。读取正文限8MiB，HTTP请求可取消；错误仅含状态，不读出/记录服务端私有错误正文，端点禁止内嵌用户名、密码、query或fragment。

AppContainer对应后台已有显式synchronize方法，先捕获只读Room身份后再运行该协调器。尚无设置页入口、周期执行或GitHub身份传输。远端合并只进私有身份日志，不改Room歌单/历史或授予播放权限。

## 实际验证

- 4个直接相关JVM方法通过：条件竞争时重新读取并保留第三方修改、再次同步不上传；上传期间本地变化/pending与持续竞争有界终止；创建/更新条件头和独立路径；弱ETag、敏感URL及401错误正文不泄漏/不触发无条件写。
- HTTP使用合成interceptor响应，不是实际WebDAV账号/服务器。没有真实请求、真实重定向服务或双设备验收。
- `:sync:testDebugUnitTest --tests '*UnifiedIdentitySyncCoordinatorTest' --tests '*UnifiedIdentityWebDavTransportTest' :app:assembleDebug --offline`成功；模块边界通过。最终XML和APK哈希见[证据](evidence/2026-10-28/phase3d4/validation.json)。
- 没有设备安装/UI、真实云上传、GitHub身份传输、Room远端应用、账号/音频或性能验收。不得将这套后台称为完整同步已上线。

下一步让远端UUID能只读映射到既有本地歌曲，不重写原ID/关系/歌单，再接用户可见的显式冲突与同步入口。
