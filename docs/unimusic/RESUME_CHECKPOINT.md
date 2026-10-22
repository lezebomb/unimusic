# UniMusic 续跑检查点

更新：2026-10-22 下午（Asia/Shanghai）。10 月 21 日 15:00 的单次 heartbeat 已执行；10 月 22 日收到重复触发后，已删除自动任务 `3-unimusic`，不要重新创建。

## 已完成和推送

- Phase 0：`b60d74d1`，源码审计与固定上游已归档，不需重做。
- 工具链：`f2c53ea1`，JDK 17 + API 37.0 / Build Tools 37 / NDK 27.0.12077973 / CMake 3.31.6 均可用。
- Phase 1A：`230d6b10`，持久歌曲 UUID、精确来源键、6 张增量表和 v20→v21、可恢复后台旧库投影，已提交推送。
- Phase 1B.1：本检查点所在提交包含只读 PlaylistImportProvider / Netease adapter、共享 FakeMusicProvider、3 个离线测试，以及 main 的精简 CI。用 git log 确认提交和推送状态，不凭文档假设远端已更新。
- 10 月 22 日 CI 修复：首次远端运行在安装 SDK 包时因 PATH 中没有 sdkmanager 失败（尚未执行测试）；已改用 SDK 根目录内的明确路径，修复提交触发新的 Actions 验证。后续先查看其实际结果。

## 实际验证

- 模块边界通过；JVM 17 个测试全部通过（数据库 9、统一库 store 5、Provider 3）。
- API 35 x86_64 独立模拟器上 4 个迁移测试通过：冻结 v20 的 8 张原表逐字段保留，所有历史版本到 v21 的 Room schema 验证、旧下载恢复数据和字节水位/索引保留。
- 两个 Android 测试类分别执行并核对 XML。首次逗号合并筛选仅实际执行 1 个测试，已另跑历史升级类 3 个测试；后续不要只看 Gradle 退出码。
- `:app:assembleDebug` 成功；APK `app/build/outputs/apk/debug/app-debug.apk`，`moe.ouom.neriplayer.debug`，在全新模拟器安装和 MainActivity 启动成功，UnifiedLibraryBackfillWorker 返回 SUCCESS；未接入账号。
- SQLite JVM 长路径问题已修复：Robolectric 的长方法名产生 259 字符路径，WAL 超出 Windows 限制；缩短方法名后原断言全部通过，未修改生产 journal mode。
- [Phase 1A 报告](PHASE_1A.md)、[Provider 报告](PHASE_1B_PROVIDER.md)、`evidence/2026-10-21/phase1a-validation.json` 与保存的 XML 为依据。性能和真实账号/真机验收未执行。

## 下一步：Phase 1B.2，再到 1C

1. 阅读 IMPLEMENTATION_PLAN.md、DATA_MODEL_AND_MIGRATION.md 和上述报告；先查 git status 和已有 Gradle 进程，避免重复构建。
2. 现数据库 v21 已提交，新增导入任务/失败/有序快照条目使用 **21→22 独立迁移**，不可重写 20→21，不可 destructive migration。
3. 实现按账号/来源唯一的导入任务、持久 snapshot 与页级 checkpoint；同一事务写旧兼容歌单/成员、新 UUID 关系、进度及既有同步 mutation/outbox。完成页才推进水位。
4. 加入取消、重试、恢复、部分失败明细、重复导入/关联刷新、跨页重复与无进展检测；账号变更暂停，空/不完整/失败远端响应不删除本地数据，用户名称/封面/排序/删除状态优先。
5. FakeMusicProvider 已在 local testFixtures，可用请求边界 hook 制造慢响应、取消、授权失效与 snapshot 变化；只写少量覆盖关键不变量的测试。
6. 连接 AppContainer / LibraryViewModel、导入选择/进度/重试/取消/解除关联界面。当前统一库是旧库兼容投影，UI/播放器/同步仍走旧路径；旧库回填 Worker 不等于完整远端导入 Worker。
7. 每个完成子阶段自行 commit + push 到 https://github.com/lezebomb/unimusic；保留用户数据，准确区分本地验证与远端 Actions、模拟器与真机结果。

## 复用环境与日志

- launcher JDK 21：`D:/Collect/.tooling/jdk/current`；buildSrc JDK 17：`D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1`。
- SDK：`D:/Collect/.tooling/android-sdk`，包均已安装，无需重复下载。
- Gradle 参数：`-Dorg.gradle.java.installations.paths=D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1`；通常 `--max-workers=2 --console=plain`。
- 独立 AVD：`.gradle-home/avd/UniMusicMigrationAPI35.avd`，设置当前进程 ANDROID_AVD_HOME 后复用，WHPX 已验证；本轮结束正常关闭以节省资源。
- 日志：`build/unimusic-toolchain/phase1-focused-final.log`、`phase1-app-final.log`、`phase1a-android-migration.log`、`phase1a-android-history.log`、`app-worker-smoke.log`。
- 运行 Python 检查时 Windows 使用 `python -X utf8`，避免默认 GBK 读取 UTF-8 源文件失败。
- 本机普通沙箱执行器此前初始化失败，必要 shell 使用 require_escalated 入口。Git 单次加 `-c safe.directory=D:/unimusic`，网络再加 `-c http.sslBackend=openssl`，不改全局安全设置。
