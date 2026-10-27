# Phase 2K：平台账号入口与 QQ 音乐网页登录

日期：2026-10-27。接续已推送的 `dc40b8a4`（GD 公开在线播放），Room 保持 v23。

用户最新要求：并非只找匿名取流，可分别登录各平台。正常账号登录与播放权限是两个状态，不能把保存 Cookie、拥有账号或存在 API 库当作全部歌曲可播放。

## 各来源的实际状态

| 来源 | 本轮处理后的账号入口 | 播放边界 |
| --- | --- | --- |
| 网易云 | 复用现有二维码/官方网页/验证码入口和安全账号仓库 | 按当前账号及正常播放接口响应处理完整音频、试听和权限失败 |
| Bilibili | 复用现有二维码/官方网页登录及安全 Cookie 仓库 | 复核当前会话、精确 CID、完整性/版本和正常取流权限 |
| YouTube Music | 复用已启用功能时的官方网页登录及账号仓库 | 仍受网络、平台和当前账号限制 |
| QQ音乐 | **新增**官方网页登录、加密保存、身份校验、重新登录、退出，接入首次设置与“设置→账号” | 本阶段补账号链；QQ 独立音频适配器仍未接完，不能据登录入口宣称 QQ 已能播放 |
| GD 网易云公开线路 | 不需要另建 GD 账号，按2J手动选择精确ID | 仅在线播放，地址不进入磁盘音频缓存、下载或音频链接分享 |
| 酷我 | 当前仍是匿名目录，不收集没有用途的账号凭证 | 尚未核实接入正常账号完整取流，不能把目录接口当音频 |

## 新增 QQ 链路

1. 首次设置和设置账号页共用 QQ 账号组件。原 QQ 占位卡在实际接入仓库后启用；安全仓库首次打开在 IO 线程完成，初始化时显示加载状态。
2. 用户点击登录，进入不可导出的 `QQMusicWebLoginActivity`，只展示 QQ 音乐官方网页。QQ/微信的登录交互由官方网页处理，未自行发送验证码或读取密码。官方页面实际提供哪些登录方式，以设备页面为准。
3. 网页位于 `:qq_music_login` 独立进程，复用 Application 对非主进程配置的独立 WebView data-directory suffix。清理网页状态只影响本平台登录进程，不清除网易云、B站或 YouTube 会话。
4. 主页面导航限制为 HTTPS、正常443端口、QQ/静态资源可信域；拒绝任意域、URL内凭据、本地文件、混合内容和自定义外部协议。弹窗仅响应用户手势；未添加 JavaScriptInterface 或账号表单脚本。
5. 用户点击“完成并保存”，只返回 `uin/wxuin/qqmusic_key/qm_keyst/login_type` 五类音乐会话字段。QQ及微信数字ID使用字符串处理，支持长ID；不收集其他 Cookie 或密码。
6. 主进程将凭证交给独立 `QQMusicAuthRepository`，默认使用 EncryptedSharedPreferences 和既有 SecurePreferencesOpener。Keystore不可用时只允许已有统一的内存回退，界面明确提示；没有明文磁盘降级。凭证对象 toString 隐去内容，公开StateFlow只包含账号ID/状态/昵称/时间。
7. 保存后请求 QQ 固定 HTTPS 网关的正常 `GetLoginUserInfo`。只有当前登录身份响应成功且返回账号ID相符，才标记 VERIFIED；普通公开主页、仅code=0或另一账号ID不能作为登录证明。未知响应结构保持 SAVED_UNVERIFIED，网络失败保留凭证，未登录/失效代码标记 EXPIRED。
8. 返回设置页可校验、重新登录或退出。网页回调保存前比较持久 revision；退出后保存递增修订墓碑，进程重建后旧回调也不能覆盖退出状态。在途校验复核捕获对象与revision，旧响应返回CONTEXT_CHANGED，不恢复账号状态。

默认未实现 QQ 凭证自动续期；过期后使用官方页面重新登录。没有新建Room表、改写迁移、修改音乐库/收藏/队列/同步数据。Android系统备份原有规则排除全部sharedpref，音乐库同步/导出没有新增QQ凭证字段。网页会话完成后清理该独立进程的 Cookie、local storage 和缓存；账号仓库仍在主进程保存。

## API 依据及实际验证

参考 [QQMusicApi 登录说明](https://l-1124.github.io/QQMusicApi/tutorial/login/)、[Credential 说明](https://l-1124.github.io/QQMusicApi/tutorial/credential/) 和作者仓库的 [登录业务实现](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/modules/login.py)，用自有 Kotlin 适配普通网页会话/身份接口。没有使用账号占位票据获得权限、会员/DRM改写或第三方服务器代存用户凭证。

一次不含Cookie/票据的匿名协议检查实际返回 HTTP200、外层code=0、account.code=1000，data为空，耗时约0.23秒。它证明端点有响应并拒绝匿名身份查询，不是登录成功或正常账号正向验收；见 [anonymous-account-probe.json](evidence/2026-10-27/phase2k/anonymous-account-probe.json)。

仅运行4个新关键测试：

- 凭证字段白名单、长微信ID、敏感输出隐藏、Cookie注入拒绝和网页登录导航范围。
- 固定QQ网关请求与身份严格匹配；未知/公开资料不能误报已登录。
- 仓库重开保持待校验状态，持久退出墓碑拒绝旧网页结果。
- 退出与在途服务端响应竞争时，旧成功结果不能恢复账号。

仓库测试注入测试用SharedPreferences，只验证数据和竞争语义，未冒充实际Android Keystore验收。初轮4个均通过，持久修订补齐后只重跑受影响的仓库2个。模块边界、QQ客户端/存储的精确依赖范围和最终Debug APK构建通过。命令、实际日志和哈希见 [validation.json](evidence/2026-10-27/phase2k/validation.json)。

## 未执行与后续

没有连接手机/模拟器，未进行真实QQ或微信账号登录、官方弹窗/验证码/页面跳转、正向服务端身份响应、退出长期恢复或实际播放验收。现有3个平台登录本轮未重复账号验收。没有把账号已保存、HTTP200、代码编译或夹具成功写成真实登录/可播放结果。

后续优先在用户正常登录后核实账号身份响应并接入QQ正常音频接口（含权限/试听/账号变化复核），再核实酷我正常账号取流。不要把当前QQ登录仓库误记成已完成QQ音频Provider。
