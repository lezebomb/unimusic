# UniMusic 续跑检查点

更新：2026-10-26（Asia/Shanghai）。用户要求主代理负责音源/搜索后端、子智能体分析其提供的 APK。已完成 2B、2C 与 REA 6.8 静态审计，暂缓 1C UI。旧单次定时任务 `3-unimusic` 已删除，不要重新创建。

## 已完成和推送

- Phase 0：`b60d74d1`，源码审计与固定上游已归档，不需重做。
- 工具链：`f2c53ea1`，JDK 17 + API 37.0 / Build Tools 37 / NDK 27.0.12077973 / CMake 3.31.6 均可用。
- Phase 1A：`230d6b10`，持久歌曲 UUID、精确来源键、6 张增量表和 v20→v21、可恢复后台旧库投影，已提交推送。
- Phase 1B.1：`547f08e7`，只读 Provider、共享 Fake 与 main 精简 CI 已推送。
- CI 修复：`094c56c5`，SDK 路径修复后的 [Actions](https://github.com/lezebomb/unimusic/actions/runs/39737919228) 已成功。
- Phase 1B.2：`14da4d19`，实现 v21→v22 的任务/条目/凭证、原库兼容事务、Coordinator/Worker、取消/重试/恢复和显式关联刷新，AppContainer 已装配后台入口。[对应 Actions](https://github.com/lezebomb/unimusic/actions/runs/40169373912) 已成功。
- Phase 2A：`bea4d48e`，原网易云/本地/B 站路径的版本/元数据门槛、分 P 歧义拒绝、可取消且有预算的地址解析、首个成功即返回、失败冷却。[对应 CI](https://github.com/lezebomb/unimusic/actions/runs/40270887580) 已成功。
- Phase 2B：`7022b9b6`，四个已有来源的聚合搜索、独立分页/预算/状态、版本排序及可取消网易云预热。[对应 CI](https://github.com/lezebomb/unimusic/actions/runs/40274273540) 已成功。
- Phase 2C：本检查点所在开发提交新增酷我 metadata-only 目录后台、实际 B 站自动候选预筛选与排序、精确平台依赖登记及静态审计报告。Room 仍为 v22；用 git log/status 确认提交推送与新 CI，不用 2B 旧绿色结果替代验收。

## 实际验证

- Phase 2C：9 个定向测试通过，新增 6 个方法；酷我 3 个检查在实体夹具/超时修正后重跑。模块边界、平台依赖边界及最终 APK 构建结果见 `evidence/2026-10-26/phase2c/`。本机普通酷我 HTTPS 搜索实际返回 HTTP 200 和 5 条元数据，没有请求音频；Android Provider 未经真实设备/账号联网验收。
- Phase 2B：42 个定向 JVM 测试通过，新增 6 个方法；Debug APK 与模块边界检查通过。原始 XML/命令/哈希见 `evidence/2026-10-26/phase2b/`，实现见 [2B](PHASE_2B_SEARCH.md)。无新平台、外部脚本兼容、真实网络播放或设备交互验证；当前四个来源仍是原有本地/网易云/B 站/YouTube。
- 2026-10-26：音源相关 40 个定向 JVM 测试通过（其中新增 13 个方法）；修正冷却时钟后仅重跑解析器 4 个测试并构建 Debug APK，均成功；模块边界检查通过。新拆出的 URL 文件已参与最终编译。详情见 [Phase 2A](PHASE_2A_SOURCES.md) 与 `evidence/2026-10-26/`。
- 本轮无手机/模拟器、未安装新 APK，未做真实账号联网播放、实际切源或覆盖率/性能测量。当前只有保守元数据门槛，没有经标注校准的同录音概率，也没有独立来源权利注册表、持久匹配覆盖或 `SourceResolver(unifiedSongId)`；不承诺商业曲库全部免费。
- 2026-10-25：7 个新导入集成测试 + 1 个冻结 v21→v22 Room 迁移测试通过；数据层、Debug APK、database 测试 APK 构建通过，模块边界检查通过。旧 store / outbox 解码 / Provider 也做了定向检查，没有运行完整上游测试。
- 2026-10-25 的迁移测试使用 Robolectric/本机 SQLite；当轮没有设备执行。详情见 [Phase 1B.2](PHASE_1B_BACKEND.md) 与 `evidence/2026-10-25/`。不能把已导入元数据标成已可播放。

以下为 2026-10-21 的历史 v21 验证：

- 模块边界通过；JVM 17 个测试全部通过（数据库 9、统一库 store 5、Provider 3）。
- API 35 x86_64 独立模拟器上 4 个迁移测试通过：冻结 v20 的 8 张原表逐字段保留，所有历史版本到 v21 的 Room schema 验证、旧下载恢复数据和字节水位/索引保留。
- 两个 Android 测试类分别执行并核对 XML。首次逗号合并筛选仅实际执行 1 个测试，已另跑历史升级类 3 个测试；后续不要只看 Gradle 退出码。
- `:app:assembleDebug` 成功；APK `app/build/outputs/apk/debug/app-debug.apk`，`moe.ouom.neriplayer.debug`，在全新模拟器安装和 MainActivity 启动成功，UnifiedLibraryBackfillWorker 返回 SUCCESS；未接入账号。
- SQLite JVM 长路径问题已修复：Robolectric 的长方法名产生 259 字符路径，WAL 超出 Windows 限制；缩短方法名后原断言全部通过，未修改生产 journal mode。
- [Phase 1A 报告](PHASE_1A.md)、[Provider 报告](PHASE_1B_PROVIDER.md)、`evidence/2026-10-21/phase1a-validation.json` 与保存的 XML 为依据。性能和真实账号/真机验收未执行。

## 下一步：继续音源能力与持久匹配

1. 阅读 IMPLEMENTATION_PLAN.md、DATA_MODEL_AND_MIGRATION.md 和上述报告；先查 git status 和已有 Gradle 进程，避免重复构建。
2. 当前数据库为 v22，20→21 与 21→22 均不可重写；新结构使用后续独立迁移，不可 destructive migration。
3. 提供 APK 的静态审计已完成，见 APK_SOURCE_AUDIT.md。最新 REA 6.8 CLI 已实际复核；旧 MCP 连接仍自报 6.5，未混淆版本。样本为 com.hihonor.cloudmusic 1.5.30 + LSPatch/附加模块/Node 来源层，不凭文件名认作官方网易云 9.1。原始样本已忽略，保留本机，不执行/分发；自动审批拒绝完整破解 Hook 方法体，未间接绕过或移植会员/DRM逻辑。
   酷我 `AppContainer.kuwoCatalogProvider` 只提供目录，未接 UI/播放。后续使用独立目录结果类型和官方跳转，不把酷我ID交给网易云播放器；正常音频能力需另外核实，不以 PAY/COPYRIGHT 字段推断授权。
4. 当前 LINKED 支持显式刷新，尚无定期自动刷新；ONE_SHOT 不关联，普通 refresh 不会重新关联已解除来源。重新 enqueue 是明确重新导入/关联动作。
5. FakeMusicProvider 与 PlaylistImportCoordinatorTest 已覆盖磁盘重开续跑、批次回滚、取消换号、部分失败、用户修改和同步恢复。后续只跑改动相关的小集合。
6. UI/播放器/同步仍使用旧兼容路径，新导入后台会原子写入这些旧表。当前解析器仍基于 SongItem；不要误标为完成 UUID SourceResolver。后续 1C 复用 AppContainer.playlistImportBackend 的现有接口，不要再写独立导入器。
   聚合源由 `FederatedSearchEngine` 驱动；更多源通过原生 Provider 接口扩展。后续补来源能力、持久匹配/用户确认与拒绝，再迁统一 UUID 解析；不要把来源开关或可访问 URL 当成版权/平台许可证明。
7. 每个完成子阶段自行 commit + push 到 https://github.com/lezebomb/unimusic；保留用户数据，准确区分本地验证与远端 Actions、模拟器与真机结果。

## 复用环境与日志

- launcher JDK 21：`D:/Collect/.tooling/jdk/current`；buildSrc JDK 17：`D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1`。
- SDK：`D:/Collect/.tooling/android-sdk`，包均已安装，无需重复下载。
- Gradle 参数：`-Dorg.gradle.java.installations.paths=D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1`；通常 `--max-workers=2 --console=plain`。
- 独立 AVD：`.gradle-home/avd/UniMusicMigrationAPI35.avd`，设置当前进程 ANDROID_AVD_HOME 后复用，WHPX 已验证；本轮结束正常关闭以节省资源。
- 本轮日志：`build/unimusic-toolchain/phase2c-tests.log`、`phase2c-complete.log`、`phase2c-boundaries.log`；9 个检查有 XML。首次 `phase2c-final.log` 的平台边界缺登记已逐项修复，勿把其整体失败冒称通过；后续 complete 是最终验收。2B 的 42 个结果仍保存在独立 evidence 子目录。
- 运行 Python 检查时 Windows 使用 `python -X utf8`，避免默认 GBK 读取 UTF-8 源文件失败。
- 本机普通沙箱执行器此前初始化失败，必要 shell 使用 require_escalated 入口。Git 单次加 `-c safe.directory=D:/unimusic`，网络再加 `-c http.sslBackend=openssl`，不改全局安全设置。
