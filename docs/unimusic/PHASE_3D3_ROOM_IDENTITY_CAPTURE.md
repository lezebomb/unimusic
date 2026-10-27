# Phase 3D.3：只读Room身份快照与批量捕获

日期：2026-10-28。已接AppContainer的lazy后台入口，未自动启动、未向网络上传、未将远端数据应用到用户Room。

## 行为

- 一个Room读事务按source_id每页200条读取来源，再按页批量查歌曲，避免逐歌N+1。支持的公开精确身份进入portable观察；deviceOnly、未知/私有来源、未解析B站分P、非法元数据有明确排除数量。歌曲、历史、来源权限不修改。
- 同一backend的捕获串行，从完整读快照生成一批修订并只原子写一次日志。没有变化不写，缺失歌曲不自动当成删除；这是身份快照，不是歌单成员同步。
- 私有本地元数据基线防止旧Room数据反复覆盖远端编辑。确定没有远端冲突的实际本地修改才追加修订；初次本地与远端不一致、已有并发冲突或离线双方都改动时报告冲突，须显式处理，不假装用户已经观察远端修改。
- 远端删除时普通捕获抑制复活；delete/restore仍为显式日志方法。捕获基线、设备actor和counter存入noBackupFilesDir，portable导出不含私有wrapper/基线，避免系统备份克隆actor身份。
- 读取/验证/捕获在后台执行。Room新DAO仅增查询，schema仍v23，不需要迁移；最大扫描20万来源、协议容量仍按3D.1拒绝超限，不静默截断。

## 实际验证

- 新增2项批量捕获JVM，检查一批只写一次、重开不重复写、旧本地不覆盖远端、双方改动报告冲突、删除抑制、真实本地变化和空快照不删除。
- 新增1项Room/Robolectric检查，四类来源仅公开网易云进入导出，设备来源及不支持身份排除；原歌曲、legacy link、播放权限和v23保持不变。
- 直接受影响的journal/codec/merge共7项同时检查，合计10项通过；最终Debug APK、模块边界通过。命令为`:sync:testDebugUnitTest --tests '*UnifiedIdentityCaptureTest' --tests '*UnifiedIdentityJournalTest' --tests '*UnifiedIdentitySyncTest' :local:testDebugUnitTest --tests '*UnifiedIdentityCaptureRoomTest' :app:assembleDebug --offline`。原始XML/哈希见[证据](evidence/2026-10-28/phase3d3/validation.json)。
- 没有真机安装/UI、用户真实库捕获、实际云上传、Room远端应用、双设备、账号/音频或性能验证。现有设备安装限制仍保留，不重复尝试。

下一步接独立远端条件写/冲突重试，保留旧同步文件路径；随后接Room别名只读投影/显式冲突操作。不能把身份后台当成完整歌单、推荐反馈或播放权限同步。
