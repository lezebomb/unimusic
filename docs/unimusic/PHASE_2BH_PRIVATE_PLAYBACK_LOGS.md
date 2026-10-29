# Phase 2BH：播放诊断日志隐私

代码检查确认seek日志会拼接当前媒体URL，NPLogger正常入口会把格式化消息和原Throwable直接发往logcat/可选文件队列。现统一在进入这两条通道前脱敏标签、正文及异常文本；异常转为保留类型/堆栈信息的脱敏字符串，不把原Throwable留在异步日志队列。文件格式化器也对内部直接构造条目做防御性脱敏。

覆盖HTTP/file/content地址、常见认证/密码/会话字段、Cookie/Authorization整段载荷、Bearer/JWT、联系方式与验证码形式。遇编码/结构化敏感载荷保守丢弃后续内容；输入限制32768字符、引号字段使用线性扫描，避免大日志回溯。普通状态200/数量/耗时继续保留；某些疑似验证码的长数字code也会隐藏，保护优先于完整认证诊断。seek日志另外改为仅记录是否有媒体URL。

这些变化只处理日志副本，不修改真实请求、Cookie、播放器URL或用户数据。所有检查都用虚构值，未读取/输出真实密码、验证码、Cookie、邮箱或手机号。属于正常NPLogger入口的前向保护；没有读取/清除历史日志，也不宣称能识别任意无标签秘密或替第三方直接Log调用自动兜底。

新增3项/共5项定向JVM通过：认证/签名URL/联系信息与编码载荷脱敏、超长/截断字段、logcat与文件异常消息两端、正常消息类型及Release日志级别回归。Debug53s、模块边界通过。CI加入logging路径触发与logger选择器，按既有保守规则再验证一次工作流/Android迁移，不新增数据库迁移。

APK `build/outputs/unimusic/UniMusic-20261030-2BH-debug.apk`，版本f436541bc901.dirty.10300605，SHA-256 `01f679bfacdbd2f3a010634031f8c9498fec42b3014d33f6d52f846acbeff875`。未装机、检查真实设备日志或新增音频验收，Room v23/账号/歌单/书签均不变。关键XML与边界见[evidence](evidence/2026-10-30/phase2bh/validation.json)。

此前2BE的[CI40960266466](https://github.com/lezebomb/unimusic/actions/runs/40960266466)已整体成功，Android迁移步骤成功；XML确认SourceResolver17、preview进度2、增量迁移1及升级保护3项均0失败。现在可把已完成2BF/2BG/2BH独立提交一起推送，避免无意义取消/重复排队。
