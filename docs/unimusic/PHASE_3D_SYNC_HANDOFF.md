# Phase 3D：统一库同步接续设计（未实现）

记录：2026-10-28。仅为源码接入梳理，没有新协议、网络上传、备份恢复或两设备结果。

## 当前源码边界

- `modules/sync/.../runtime/dataset/SyncPlaybackDataset.kt`中的SyncDataset当前只将播放统计轨道/日桶拆成磁盘分页source；构造器要求旧SyncData的对应列表为空。不能误把现有dataset称为统一UUID库协议。
- `modules/sync/.../merge/dataset/SyncDatasetMerger.kt`先合并旧core，再按清除水位归并统计；no-op分支还保留capturedPlaybackRevision和资源所有权。不能直接增加一个内存歌曲列表，绕过条件写、快照一致性和close生命周期。
- `modules/local/.../sync/host/AndroidSyncSnapshotBuilder.kt`目前只装配旧歌单、收藏、历史、删除、统计与歌词数据；未导出unified模型、来源匹配或推荐反馈。
- 3A/3B/3C摘要文件、开关和每日推荐缓存仍本机私有。不能将它们直接附加到旧SyncData或备份目录，尤其不能上传本地路径、完整音频URL、账号Cookie、令牌和本机设备URI。

## 建议接续顺序

1. 先读同步archive的manifest/wire budget与GitHub/WebDAV条件写入口，再设计独立且可探测的统一库schema/capability；旧2.0客户端回写不得删除独立dataset。
2. 为精确来源键建立UUID canonical alias规则；区分同源重复投影和无证据的跨平台同录音。不同分P、版本、用户拒绝不能自动合并。
3. 独立保存有因果信息的删除/拒绝和离线修改，不能只用本机时间戳最后写入获胜；明确未知版本拒绝写入及限额。
4. 完成小型纯模型/合并契约后，再接分页快照和数据库事务。未通过白名单/预算/旧协议往返检查前不接生产上传。
5. 选择最少关键验证：两设备同源不同UUID、离线添加/删除冲突、旧客户端回写、未知schema不破坏原文件、凭证和本地URI排除。真实双设备、账号与备份恢复另列，不以本机合成替代。

本设计不将播放反馈开关变成云同步开关，不改变用户当前云同步状态；YouTube排查继续暂停。
