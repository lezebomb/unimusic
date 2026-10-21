# 统一音乐库：模型与迁移设计

状态：Phase 1A 已实现并验证 Room v21 的前 6 张统一关系表、精确来源 UUID 桥接与分批后台回填。既有 SongItem、SongIdentity、系统收藏歌单和同步协议继续工作；本文其余导入、匹配和同步部分仍是后续设计，实际验收见 [PHASE_1A.md](PHASE_1A.md)。

## 1. 录音身份与来源身份分离

`UnifiedSong.id` 使用持久 UUID，描述一份具体录音/版本。它不等于平台歌曲 ID，也不通过“标准化标题+歌手”自动判定同曲。新来源首次导入时，在事务内按精确来源键查找现有映射；未映射则创建 UUID 并持久保存，重复导入复用此 UUID。

不同平台的来源默认对应不同歌曲，只有显式可信映射或人工确认才能指向同一 UnifiedSong。录音室/Live/翻唱/Remix 等版本不同，必须保持不同 UnifiedSong；重制版、再录音、同名不同曲和不同专辑版本保守分开。

来源唯一键为 `(provider, resourceId, subResourceId, recordingContext)`。`subResourceId` 非空默认值用于避免 SQLite 的 NULL 唯一索引语义产生重复；B 站 cid 必须进入键。playlistContext 是播放上下文，单独保存，不因同一视频出现在多个歌单而创建多个来源。不要通过简单分隔符拼接未经转义的字段生成键；使用复合唯一索引或带长度的规范编码。

来源会话/账号、当前地区与检测时间影响可用性，不能永久写死“这个平台源合法可播”。Provider 准入策略和当前账号 entitlement 都满足后，才允许独立音频播放。可公开查看的视频或一个返回的 URL，不自动等于授权音频来源。

## 2. 逻辑实体

| 实体 / 拟用 Room 表 | 关键内容和约束 |
| --- | --- |
| UnifiedSong / `unified_song` | UUID PK；原始/展示标题、歌手、专辑、时长、录音版本、ISRC/MBID 可空、创建/修改修订；不保存平台凭据 |
| SongSource / `song_source` | UUID PK，FK song UUID；provider/resourceId/subResourceId/context 唯一；原始元数据、可信渠道证据、媒体类型、质量信息、允许的播放模式、可用性原因、观察时间/过期时间 |
| LegacySongLink / `legacy_song_link` | 旧 identity_key → source UUID/song UUID；保留旧稳定键；支持本地下载对远端身份的已有关系 |
| UnifiedPlaylist / `unified_playlist` | UUID PK，旧 local_playlist ID 唯一桥接；名称、封面、用户拥有、创建/修改修订、删除 tombstone |
| PlaylistMembership / `unified_playlist_member` | playlist/song UUID 复合键；顺序、addedAt、revision、删除状态/因果信息；来源成员引用另存以保留导入顺序和可追溯性 |
| PlaylistOrigin / `playlist_origin` | UUID；provider+非凭据 accountKey+remotePlaylistId 唯一；本地 playlist UUID、原始名称/封面、导入模式、关联状态、已提交 snapshot/水位和刷新时间 |
| ImportJob / `playlist_import_job` | accountKey、origin、运行/取消/暂停/失败状态、snapshot 标识、下一批位置、已提交数量、重试次数；不存 cookies、token 或整段 HTTP 响应 |
| ImportFailure / `playlist_import_failure` | job/source 唯一；失败原因类型、次数、可重试性、下一次允许重试时间；保留原远端位置 |
| SongMatch / `song_match` | source pair、版本/算法版本、证据摘要、校准分数、验证状态和时间；检索相关分不作为校准概率 |
| UserMatchOverride / `user_match_override` | source pair、ACCEPT/REJECT、设备/修订/时间；用户 REJECT 优先于旧自动映射，ACCEPT 仍不能绕过播放权限或自动取消版本冲突 |
| PlayEvent / `play_event` | UUID eventId、song UUID、PLAY/SKIP/COMPLETE/REPEAT/DISLIKE、有效收听量/时间和设备；事件去重、保留策略和聚合水位 |
| RecommendationSnapshot / `recommendation_snapshot` | snapshot UUID、日期/算法版本/栏目、song UUID 有序条目、解释原因、PLATFORM/UNIMUSIC 来源标记；必要反馈单独存储 |

v21 实现前 6 项的最小字段。Source 尚未增加独立原始元数据/匹配证据/质量信息，成员因果 tokens 仍由旧表保存；ImportJob/ImportFailure 将在 Phase 1B 用新的独立迁移实现。匹配实体、PlayEvent/RecommendationSnapshot 分属 Phase 2/3，不提前堆叠不可验证 schema。每次新增表均独立增量迁移，不能重写已提交的 20→21。

## 3. Room v20 → v21 与回填

1. v21 只新增前 6 张统一库/来源关系表、索引、外键；不重建、清空或改变旧 `track/local_playlist/playlist_member`、收藏、历史、队列、统计、同步 outbox 的含义。
2. Room 的 `@Database` 和 `allMigrations()` 同时更新，导出并提交 v21 schema。迁移 SQL 使用固定完整语句，不引用将来会改变的 model 常量。禁止 destructive migration。
3. 增量关系回填由可恢复 Kotlin/WorkManager 任务执行，不在开库迁移中一次加载整个歌曲库。查询缺少映射的歌曲和成员作为 checkpoint，每批事务保存 UUID 关系；不依赖会漏过前插记录的 OFFSET/最后 key，唯一约束保证重复执行不生成新 UUID。
4. 保留旧 durable payload、自定义封面/名称/歌词、membership addedAt/顺序、因果 tokens 及所有删除记录。未知 provider 允许导入元数据，但默认 `eligibility=UNKNOWN`，不自动解析播放。
5. 新读路径在回填/一致性检查完成后开启。中断时旧 repository 仍可读写；新增旧数据通过 bridge 的增量投影补齐。UI 不把“迁移未完成”等同空音乐库。
6. 后续导入新歌单时将通过 local repository 新增的统一事务入口，同时写旧兼容表、新统一表、导入水位与既有同步 mutation/outbox；提交后再发布 StateFlow。目前这个入站导入事务尚未接入，禁止用先建空歌单再异步填充的方式代替。

首次切换前生成用户可恢复的数据备份，排除凭据、签名 URL 和音频。WAL 数据库备份使用一致性快照/既有逻辑导出，不能在数据库写入时只复制主 `.db` 文件。

### 必需的迁移验证

复用 MigrationTestHelper 和已有 1→20 schema，加入 20→21 与 1→21 数据夹具：旧歌曲（网易云/B 站 cid/YouTube/本地文件）、多个歌单、收藏、历史、自定义字段、顺序、会员试听元数据、删除 tokens/outbox。迁移前后逐项核对原表数据；新 schema 由 Room 验证。

追加幂等回填、批次中断重启、旧 repository 写入后桥接、来源唯一键并发创建和损坏/新版数据库保护测试。用少量明确夹具验证关键不变量，不复制生产实现写“自证”测试。

### 回滚的实际边界

- 关闭 UniMusic feature gate，使用旧 repository/旧 UI 读取保留表；提供**保留 schema v21 的修复构建**作为应用回滚版本。
- 直接安装 v20 二进制会被现有版本检查拒绝打开 v21 数据库，不能承诺二进制降级兼容；不自动创建向下删除迁移。
- 需要回到原 NeriPlayer 时，使用兼容导出导入或迁移前一致性备份；还原覆盖数据必须由用户明确选择，保留现有数据库副本。
- 新 UUID 和人工映射在回滚旧功能时保留；后续恢复新读路径能够继续使用，不能重分配身份。

## 4. 网易云只读 Provider 与可靠导入

接口划分：

```kotlin
interface PlaylistImportProvider {
    val providerId: String
    suspend fun listPlaylists(accountKey: String, cursor: String?, limit: Int): ImportPlaylistPage
    suspend fun readSnapshot(accountKey: String, remotePlaylistId: String): ImportPlaylistSnapshot
    suspend fun readSongs(accountKey: String, resourceIds: List<String>): ImportSongBatch
}
```

这些类型和 Netease adapter / FakeMusicProvider 已在 Phase 1B.1 实现；持久任务与本地事务将在 local 继续接入。Provider 接口只传公开账号标识和元数据，不传 credential；通过已注入的安全会话请求。FakeMusicProvider 支持分页、snapshot 变化、部分失败、授权失效和可取消延迟，测试不需要真实账号。

- `listPlaylists` 复用 `getUserPlaylists(offset, limit)` 逐页读取创建/收藏歌单；识别用户喜欢的歌曲源。必须验证响应 code、页码/游标前进和重复页，避免风控返回内容导致死循环。
- 当前上游歌单详情接口提供 trackIds 快照，不能假设它已有 tracks 分页。先获取有权读取的源快照/顺序，再分批请求详情；缺失 ID 保留为失败条目或占位，**不能用 mapNotNull 静默减少成功总数**。
- 每个账号/来源使用唯一 WorkManager work 名，限制并发；优先 1 个歌单顺序推进、少量详情请求，实际批大小/限流遵守平台可用接口并可配置。只导元数据，不批量解析音频 URL。
- 每页数据、来源关系及下一水位同事务提交。取消合作式中断，保留已提交页；进程重启按持久 checkpoint 恢复。snapshot 变化时重读并按唯一键幂等合并，不能拿旧页位置跳过新内容。
- 分开“已导入”“失败”“权限不可读”计数；失败项指数退避重试，权限过期暂停等待既有登录恢复，不伪造授权。任务恢复先验证账号上下文，防止另一账号会话继续前一账号任务。
- 同一 origin 重复导入保持同一个歌单/UUID。刷新默认为增量添加和来源元数据更新，不因失败、远端空列表或不完整快照删除本地曲目。第一版不传播远端删除，后续若支持须显式选择并具备完整快照/删除证据。
- 手工加入、改名、封面、排序和删除 tombstone 高于来源默认值；刷新不复活用户明确删掉的成员。原始名称/封面/来源顺序保存在 origin 关系中可检查，第一次导入采用原顺序。
- ONE_SHOT 导入后保留来源追溯但不调度刷新；LINKED 可刷新。解除来源关联时取消其任务并更新 origin 状态，**不删除本地歌单、歌曲、收藏或用户修改**。

## 5. 同步兼容与 UUID 冲突

Phase 1 先保留现有 2.0 数据同步，不让新字段意外混入旧序列化；未启用统一映射跨设备同步时在 UI/报告明确此限制。Phase 3 使用额外 unified dataset，manifest 中包含 capability/schema；旧客户端可继续维护旧歌单，不能覆盖新 dataset。

同一来源被两台离线设备首次导入时可能产生不同 UUID；合并需按来源唯一键建立 UUID alias 并选择确定的 canonical UUID，重定向成员、匹配和事件引用，保留旧 UUID 可解析。没有共享精确来源或可信映射的同名歌曲不能因此合并。用户 REJECT 和删除 tombstone 不可被旧自动结果覆盖。

只同步统一身份、元数据、来源 ID/版本、人工映射、因果成员、推荐反馈/聚合统计和必要设置；不传账号 cookie/token/password、私有文件路径、签名 URL、未授权音频或导入任务的设备执行锁。测试旧客户端读写往返、离线编辑、删除冲突、重复导入及恢复备份后再启用。
