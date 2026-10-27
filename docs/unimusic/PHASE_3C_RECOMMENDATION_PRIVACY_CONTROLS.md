# Phase 3C：本地播放反馈控制

日期：2026-10-28（Asia/Shanghai）。

发现页本地推荐增加“推荐偏好”。用户可以关闭播放反馈学习，或独立清除已学习的跳过、听完、重播信号。关闭会清除这些信号并停止写入；再次启用从新的采样周期开始。播放历史、统计、歌单、账号和手动隐藏名单保持原样。开关仅本机生效，不上传或同步。

## 实现

- 在原有私有JSON中加入可选enabled字段，旧文件缺失字段按原行为启用。仍用原子写入，未知schema与写入失败不覆盖旧文件。
- 关闭、启用和清除持久化时间水位，旧排队事件不得回写。关闭时信号读取为空、后台记录直接丢弃；操作修订触发当日推荐重新计算。
- 采样tracker复用已有清除水位机制重置周期，仅读取已创建实例的内存水位，不在播放线程读磁盘或额外初始化私有目录。
- 独立清除反馈不会调用历史/统计的clearAll；手动隐藏/撤销仍走3A独立文件。Room仍v23，无迁移。

## 实际验证

- `:local:testDebugUnitTest --tests '*LocalPlaybackFeedbackStoreTest' :app:assembleDebug --offline`成功，存储3项测试全部通过，其中新增1项覆盖关闭重启、拒绝旧事件、重新启用和独立清除。
- `tools_pub/quality/module_boundaries.py`通过。最终构建日志为本机已忽略的`build/unimusic-toolchain/phase3c-feedback-controls.log`；最终APK哈希与XML见[证据](evidence/2026-10-28/phase3c/validation.json)。
- Mate60仍连接，但本阶段3次保留数据覆盖安装均返回`INSTALL_FAILED_ABORTED: User rejected permissions`。这不是本工具的自动审批拒绝；系统流程未完成，未声称新APK已安装。已停止重复尝试，原版本仍可打开，没有清数据或卸载。新推荐偏好界面、开关的真机流程未验收。
- 没有实际Media3跳过/听完/重播采样、真实账号或新增音频、Release、性能或极端时钟/并发验收。

## 后续

在可完成系统安装确认后检查推荐偏好UI与开关，随后继续UUID数据集同步等剩余工作；YouTube取流排查仍暂停。不要重复登录或重新导入用户歌单。
