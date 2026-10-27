# Phase 2P：Google 登录与账号取流验证

日期：2026-10-27。用户明确授权 Google 登录，并在 Mate60 上亲自完成密码/验证；代理没有读取或记录输入内容。此前待登录的交接状态已结束，勿重新打开会重置网页会话的登录流程。

## 已确认的登录结果

通过 App 设置中的 YouTube 浏览器登录进入官方 Google 页面。完成后已返回设置，YouTube 卡片显示“重新登录／退出登录”。授权使用 App 原有的手机加密存储，之后新的 instrumentation 进程也读取到了登录 Cookie，进一步调用正常账号资料接口得到成功结果。本轮仅记录 server account profile verified=true，没有输出账号资料或凭证。

Google 登录和服务端账号识别已确认；没有连接 Gmail，也没有由代理再次请求短信。授权保留在手机中。

## 播放结果与定位

生产 PlayerManager 的账号样本仍未成功：没有观察到播放地址或解码进度，本次约 5819ms 收到普通播放错误。观察末 pending=false，共享客户端相关 YouTube HTTP Call 为 0。不能把账号登录成功称为音源验收成功。

第一次协议诊断只窥视 512KiB，某条大型响应未能作为完整 JSON 解析，输出了 NONE / 0 个格式。已修正为使用项目原有的 8MiB 上限，并兼容 playerResponse 包装；旧记录仍保留，但不能拿 NONE 当平台没有返回候选。

补齐后的真实账号结果为：账号接口确认成功；WEB_REMIX（client 67）HTTP 200、playability=OK，返回 16 条 adaptive formats，16 条带 signatureCipher；TVHTML5（client 7）HTTP 200、UNPLAYABLE、0 条格式。仍未解析出最终播放地址。

这些证据将问题缩小到账号获准的 player 响应之后的地址解析/签名处理/流访问链路。尚未取得 EJS、PoToken 或候选校验的精确失败子阶段，不能仅凭 signatureCipher 字段就断言某个具体解签器故障。16 是格式条数，不是 16 首歌曲，也不是 16 个独立音源。

## 测试与产物

只增加可选的真实账号前置检查和诊断完整性：`unimusicRequireYoutubeAccount=true` 等待已保存授权，避免把匿名请求当作账号验证。YouTube 播放观察窗口留 35 秒供 20 秒主解析与播放器启动使用，生产预算没有变更。

测试包两次构建通过，分别 2m43s 和 42s；保留数据覆盖安装的是测试包，主 App 仍为 2O 已安装 APK。本轮真机两个方法共执行 3 次，音频验证均失败；没有运行完整单测。实际输出、命令开关和哈希见 [validation.json](evidence/2026-10-27/phase2p/validation.json)。[2O CI](https://github.com/lezebomb/unimusic/actions/runs/40472299289) 已确认成功。

账号、网易云既有登录与歌单未退出或清除，主 App 已重新打开并暂停播放。没有新迁移，Room 仍 v23。报告不含邮箱、手机号、Google 账号标识、密码、验证码、Cookie、锁屏 PIN 或签名音频 URL；没有保存登录页面截图或 XML。

下一步直接接续已登录账号，定位正常 OK 响应后的签名/流访问失败；不要再次向用户索要账号或重复登录，不清空授权。QQ 正向账号取流等其他未验收项保持原状态。
