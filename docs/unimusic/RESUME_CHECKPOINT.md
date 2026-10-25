# UniMusic 续跑检查点

更新：2026-10-25（Asia/Shanghai）。用户要求无手机时先开发后台；本轮完成 Phase 1B.2。旧单次定时任务 `3-unimusic` 已删除，不要重新创建。

## 已完成和推送

- Phase 0：`b60d74d1`，源码审计与固定上游已归档，不需重做。
- 工具链：`f2c53ea1`，JDK 17 + API 37.0 / Build Tools 37 / NDK 27.0.12077973 / CMake 3.31.6 均可用。
- Phase 1A：`230d6b10`，持久歌曲 UUID、精确来源键、6 张增量表和 v20→v21、可恢复后台旧库投影，已提交推送。
- Phase 1B.1：`547f08e7`，只读 Provider、共享 Fake 与 main 精简 CI 已推送。
- CI 修复：`094c56c5`，SDK 路径修复后的 [Actions](https://github.com/lezebomb/unimusic/actions/runs/39737919228) 已成功。
- Phase 1B.2：本检查点所在开发提交实现 v21→v22 的任务/条目/凭证、原库兼容事务、Coordinator/Worker、取消/重试/恢复和显式关联刷新，AppContainer 已装配后台入口。用 git log/status 确认提交推送及对应新 CI，勿用旧绿色结果代替新提交验收。

## 实际验证

- 2026-10-25：7 个新导入集成测试 + 1 个冻结 v21→v22 Room 迁移测试通过；数据层、Debug APK、database 测试 APK 构建通过，模块边界检查通过。旧 store / outbox 解码 / Provider 也做了定向检查，没有运行完整上游测试。
- 本轮没有连接手机或启动模拟器，没有安装新 APK；迁移测试使用 Robolectric/本机 SQLite。准确记录为无设备后台验证，详情见 [Phase 1B.2](PHASE_1B_BACKEND.md) 与 `evidence/2026-10-25/`。
- 音源问题尚未完全解决：尚未实现 Phase 2 同版本严格匹配、临播来源解析及失败切源，也没有真实账号在线播放结果。不能把已导入元数据标成已可播放。

以下为 2026-10-21 的历史 v21 验证：

- 模块边界通过；JVM 17 个测试全部通过（数据库 9、统一库 store 5、Provider 3）。
- API 35 x86_64 独立模拟器上 4 个迁移测试通过：冻结 v20 的 8 张原表逐字段保留，所有历史版本到 v21 的 Room schema 验证、旧下载恢复数据和字节水位/索引保留。
- 两个 Android 测试类分别执行并核对 XML。首次逗号合并筛选仅实际执行 1 个测试，已另跑历史升级类 3 个测试；后续不要只看 Gradle 退出码。
- `:app:assembleDebug` 成功；APK `app/build/outputs/apk/debug/app-debug.apk`，`moe.ouom.neriplayer.debug`，在全新模拟器安装和 MainActivity 启动成功，UnifiedLibraryBackfillWorker 返回 SUCCESS；未接入账号。
- SQLite JVM 长路径问题已修复：Robolectric 的长方法名产生 259 字符路径，WAL 超出 Windows 限制；缩短方法名后原断言全部通过，未修改生产 journal mode。
- [Phase 1A 报告](PHASE_1A.md)、[Provider 报告](PHASE_1B_PROVIDER.md)、`evidence/2026-10-21/phase1a-validation.json` 与保存的 XML 为依据。性能和真实账号/真机验收未执行。

## 下一步：Phase 1C，然后音源基座

1. 阅读 IMPLEMENTATION_PLAN.md、DATA_MODEL_AND_MIGRATION.md 和上述报告；先查 git status 和已有 Gradle 进程，避免重复构建。
2. 当前数据库为 v22，20→21 与 21→22 均不可重写；新结构使用后续独立迁移，不可 destructive migration。
3. AppContainer.playlistImportBackend 已提供 listPlaylists / enqueue / observeJobs / progress / failures / cancel / retry / refresh / unlink；将其连接到 LibraryViewModel 与导入选择/进度/失败明细界面，不要再写一套独立导入器。
4. 当前 LINKED 支持显式刷新，尚无定期自动刷新；ONE_SHOT 不关联，普通 refresh 不会重新关联已解除来源。重新 enqueue 是明确重新导入/关联动作。
5. FakeMusicProvider 与 PlaylistImportCoordinatorTest 已覆盖磁盘重开续跑、批次回滚、取消换号、部分失败、用户修改和同步恢复。后续只跑改动相关的小集合。
6. UI/播放器/同步仍使用旧兼容路径，新导入后台会原子写入这些旧表；不要提前切换到未完成统一匹配的播放路径。Phase 2 先做版本冲突拒绝、来源资格、严格匹配、临播解析与队列身份保护。
7. 每个完成子阶段自行 commit + push 到 https://github.com/lezebomb/unimusic；保留用户数据，准确区分本地验证与远端 Actions、模拟器与真机结果。

## 复用环境与日志

- launcher JDK 21：`D:/Collect/.tooling/jdk/current`；buildSrc JDK 17：`D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1`。
- SDK：`D:/Collect/.tooling/android-sdk`，包均已安装，无需重复下载。
- Gradle 参数：`-Dorg.gradle.java.installations.paths=D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1`；通常 `--max-workers=2 --console=plain`。
- 独立 AVD：`.gradle-home/avd/UniMusicMigrationAPI35.avd`，设置当前进程 ANDROID_AVD_HOME 后复用，WHPX 已验证；本轮结束正常关闭以节省资源。
- 本轮日志：`build/unimusic-toolchain/phase1b2-compile.log`、`phase1b2-tests.log`、`phase1b2-final.log`、`phase1b2-backend-final.log`、`phase1b2-boundaries.log`。先看最终报告；首轮两个夹具问题已修正并重跑通过。
- 运行 Python 检查时 Windows 使用 `python -X utf8`，避免默认 GBK 读取 UTF-8 源文件失败。
- 本机普通沙箱执行器此前初始化失败，必要 shell 使用 require_escalated 入口。Git 单次加 `-c safe.directory=D:/unimusic`，网络再加 `-c http.sslBackend=openssl`，不改全局安全设置。
