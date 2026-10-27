# Phase 3D：统一库同步接续设计（完整同步未实现）

记录：2026-10-28。已完成[3D.1独立契约](PHASE_3D1_PORTABLE_IDENTITY_CONTRACT.md)，下文为生产接入梳理；尚无实际网络上传、备份恢复或两设备结果。

## 当前源码边界

- `modules/sync/.../runtime/dataset/SyncPlaybackDataset.kt`中的SyncDataset当前只将播放统计轨道/日桶拆成磁盘分页source；构造器要求旧SyncData的对应列表为空。不能误把现有dataset称为统一UUID库协议。
- `modules/sync/.../merge/dataset/SyncDatasetMerger.kt`先合并旧core，再按清除水位归并统计；no-op分支还保留capturedPlaybackRevision和资源所有权。不能直接增加一个内存歌曲列表，绕过条件写、快照一致性和close生命周期。
- `modules/local/.../sync/host/AndroidSyncSnapshotBuilder.kt`目前只装配旧歌单、收藏、历史、删除、统计与歌词数据；未导出unified模型、来源匹配或推荐反馈。
- 3A/3B/3C摘要文件、开关和每日推荐缓存仍本机私有。不能将它们直接附加到旧SyncData或备份目录，尤其不能上传本地路径、完整音频URL、账号Cookie、令牌和本机设备URI。

## 归档与写入约束（源码已核对）

- `SyncArchiveManifestValidation.kt`明确验证内层protocol为3、非负且相容的数量/字节、空root约束及header不得嵌入records；`SyncArchiveManifestWireGuard.kt`对原manifest与v4外层分别走wire检查。实施计划的旧“2.0数据路径”、内层protocol、v4封装并非同一个版本号，下一轮必须分别追踪。
- GitHub发布资产的`GitHubReleaseSyncManifest.kt`另有formatVersion=1，校验assetId/名称/正内容长度及64位小写SHA256。不能将统一库schema数字复用为该manifest格式版本，也不能据此推断服务端支持新dataset。
- WebDAV的`WebDavApiClient.kt`将expectedVersion/createOnly传给`WebDavConditionalHeaders`，没有条件令牌时默认拒绝普通更新；归档删除要求ownedPath与强ETag并发送If-Match。独立dataset也应走这些约束，不能打开allowUnconditionalWrite来掩盖冲突。
- 上述为只读源码梳理，尚未核实GitHub分支/Contents或Release资产替换的完整竞态流程；该部分不能标记已完成。

## 建议接续顺序

1. 先读同步archive的manifest/wire budget与GitHub/WebDAV条件写入口，再设计独立且可探测的统一库schema/capability；旧2.0客户端回写不得删除独立dataset。
2. 为精确来源键建立UUID canonical alias规则；区分同源重复投影和无证据的跨平台同录音。不同分P、版本、用户拒绝不能自动合并。
3. 独立保存有因果信息的删除/拒绝和离线修改，不能只用本机时间戳最后写入获胜；明确未知版本拒绝写入及限额。
4. 完成小型纯模型/合并契约后，再接分页快照和数据库事务。未通过白名单/预算/旧协议往返检查前不接生产上传。
5. 选择最少关键验证：两设备同源不同UUID、离线添加/删除冲突、旧客户端回写、未知schema不破坏原文件、凭证和本地URI排除。真实双设备、账号与备份恢复另列，不以本机合成替代。

本设计不将播放反馈开关变成云同步开关，不改变用户当前云同步状态；YouTube排查继续暂停。
