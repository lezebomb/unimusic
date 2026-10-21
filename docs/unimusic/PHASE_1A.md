# Phase 1A：统一 UUID、来源桥接与增量回填

日期：2026-10-21。Phase 1A 已完成实现、定向验证和模拟器启动检查。

## 实际行为

- 新增统一歌曲、精确来源、旧身份桥接、统一歌单、成员和来源关联 6 张表。Room 数据库 v20→v21 仅增加表和索引，不修改旧表，不使用 destructive migration；v21 schema 由 KSP 实际生成。
- 歌曲首次注册生成持久随机 UUID，重复精确来源复用 UUID。同名、跨平台、不同 B 站 cid 和录音上下文默认分开；标题相似度不作为歌曲身份。
- 来源注册、旧 identity 绑定和歌单投影在事务内提交。绑定冲突回滚新增记录。来源默认 METADATA_ONLY / UNKNOWN，导元数据不授予播放权利。
- 回填每批最多 500 条，以缺少的映射/成员关系作为 checkpoint，避免 OFFSET 或最后 key 在旧库并发增补时跳过内容。已投影的删除成员不复活，旧歌曲、成员顺序、addedAt、payload 与因果 tokens 保留。
- 普通启动在首屏交互门槛后调度唯一 WorkManager 任务；每次最多 32 批、默认每批 200 条，未完成则退避续跑，取消和进程退出保留已提交批次。新版/不可读数据库保护沿用上游逻辑。
- 解除来源仅更新关联状态，保留本地歌单和成员。

## 已执行验证

| 范围 | 结果 |
| --- | --- |
| 模块归属/依赖结构 | 通过，`python -X utf8 tools_pub/quality/module_boundaries.py` |
| 数据库迁移登记和版本保护 | 9 个 JVM 测试通过 |
| 统一库 Room store | 5 个 JVM 测试通过，包含并发同源注册、冲突回滚、重启/增量回填、顺序/tokens/删除状态和空歌单 |
| Android 冻结 v20 数据夹具及历史升级链 | 4 个测试通过；逐字段数据保留、v1…v20 升至 v21、v15 下载恢复数据、v17 字节水位/索引 |
| 完整应用 | `:app:assembleDebug` 成功，Debug APK 在全新模拟器安装和启动成功，回填 Worker 返回 SUCCESS |

Windows 首次版本保护测试失败发生于打开 SQLite/WAL：Robolectric 将长方法名带入临时目录，数据库路径达到 259 字符。缩短方法名后全部原断言通过，没有改变生产数据库的 journal mode 或削弱版本保护。

日志在 `build/unimusic-toolchain/`：`phase1a-database-unit.log`、`phase1-focused-final.log`、`phase1-app-final.log`。首次完整构建含原生库耗时 9m35s，后续最终增量构建 30s；这些是构建耗时，不是应用性能。

Android 测试使用独立 `UniMusicMigrationAPI35`（API 35、x86_64、WHPX），没有原有音乐库或账号。两个迁移测试类分别执行并保留两份 XML：首次合并 class 筛选实际仅执行 1 个测试，因此单独补跑历史升级类的 3 个测试；不以 Gradle 成功退出替代实际计数。迁移日志为 `phase1a-android-migration.log`、`phase1a-android-history.log`。

可追溯计数、日志哈希和 APK 哈希记录在 [phase1a-validation.json](evidence/2026-10-21/phase1a-validation.json)。APK 为 `app/build/outputs/apk/debug/app-debug.apk`，包名 `moe.ouom.neriplayer.debug`，保留上游界面及 Debug 签名；尚未完成 UniMusic 品牌/UI 和完整导入入口。

## 保留的边界

- 当前 UI、播放器、收藏/历史/同步仍使用原有读写路径；统一库是兼容投影，没有把旧库替换为空的新库。
- 后台任务在启动时补齐当前旧库；尚未将每一个旧 repository mutation 都接成实时双写。完成新导入事务及一致性检查后再切换统一库读取。
- 跨设备 UUID alias、统一数据集同步、用户匹配 override 和性能测量属于后续阶段；本轮没有这类验收结果。
- 未执行真实账号导入、在线播放、真机后台/蓝牙/功耗或 Release 签名验证。独立测试模拟器不代表真机结果。
- 原 v20 应用不能直接打开升级后的 v21 数据库；功能回滚使用保留 v21 schema 的修复构建，不能删除新增表来强制降级。
