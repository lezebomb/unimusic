# Phase 3D.2：持久身份日志

日期：2026-10-28。已实现可调用后台，尚未接生产Room应用或上传。

`UnifiedIdentityJournal`将随机设备actor、全局单调counter和独立身份dataset放在同一原子文件中。每次写操作重新读盘，在同进程共享文件Mutex内校验、分配计数并提交；写入失败不发布新的内存counter。多个实例/重启不会重用已持久化dot。没有内容变化的upsert/合并不重写文件。

远端文档先严格解码、因果合并，再原子保存；只有携带完整白名单的portable dataset可以导出，私有计数器wrapper不导出。未知schema、损坏文档、计数回退和写入失败保留旧文件，不用空库掩盖错误。序列化与磁盘工作离开主线程。

普通upsert不能复活远端删除；恢复必须调用独立restore方法，并观察当前别名组的所有因果前沿。delete只修改这一独立日志，未清理用户音乐库或历史；生产UI/Room投影将在后续阶段接入。

## 实际验证

- 新增3项持久日志JVM检查：重开/多实例counter与无变化不写、远端删除/显式恢复、写入失败/未知schema/回退counter旧文件不变。
- 因codec抽出共用wire校验，同时复跑直接受影响的3D.1四项，合计7项通过。
- `:sync:testDebugUnitTest --tests '*UnifiedIdentitySyncTest' --tests '*UnifiedIdentityJournalTest' :app:assembleDebug --offline`成功；模块边界通过，最终APK哈希和XML见[证据](evidence/2026-10-28/phase3d2/validation.json)。CI补这3项，不跑完整上游测试。
- 没有设备安装/实际断电文件系统测试、跨进程并发写、真实云上传/Room应用、两台设备、账号/音频或性能验收。当前锁只保证同一应用进程；生产若启多进程须另补文件锁或单写者服务。Room仍v23，无迁移。

下一步接只读Room身份快照与批量捕获，避免每首歌重写整份日志；自动捕获不得复活删除，也不能将未改变的本地元数据重复覆盖远端编辑。继续保留设备安装限制与暂停YouTube排查。
