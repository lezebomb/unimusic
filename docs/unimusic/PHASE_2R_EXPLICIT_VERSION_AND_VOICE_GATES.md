# Phase 2R：显式版本与合成人声门槛

日期：2026-10-28。改进自动原唱匹配的保守元数据规则，不增加会员/DRM绕过或重开YouTube取流。

- 识别显式AI生成/音色/换声、声音克隆等中英文标签，合成人声也不能被普通人声翻唱的COVER标记掩盖。只读标题/版本上下文，不用歌手名“AI”或“Aimer”推断合成；完整NFKC处理全角标签。
- 当原歌名自身含Live/Cover/Remix等词时，额外比较括号/后缀/结构化版本证据，避免原词掩盖新增“现场/翻唱/混音”标签。原有同版本Live括号/裸后缀及普通Official Audio装饰仍兼容。
- 标题、歌手和时长依旧是独立必要条件；无标签/错误标签无法由该规则证明声纹身份，不承诺全部原唱识别准确率，也没有声纹模型或实际听辨结果。
- 持久自动来源证据升级v3，v2不能直接复用，下一次取得合格地址时重新确认；旧来源行、手动确认/拒绝和修订保留，Room仍v23，无数据迁移/清空。

## 实际验证

新增3个匹配方法覆盖显式合成人声、真人翻唱区分、原歌名词掩盖、AI/Aimer负例及装饰兼容；新增1个存储方法覆盖旧v2失效、新证据重确认、手动选择保持。最终只跑匹配6项、存储新/原过期重开2项，共8项通过。初版匹配6项已通过，补AI Cover标签与缓存版本后以最终8项为准。

`:playback:logic:testDebugUnitTest --tests '*RecordingMatchPolicyTest' :local:testDebugUnitTest --tests '*SourceMatchRoomStoreTest.olderAutomaticEvidenceNeedsRecheckWhileManualChoicesRemain' --tests '*SourceMatchRoomStoreTest.automaticCacheReopensAndExpires' :app:assembleDebug --offline`与模块边界通过，XML/最终APK哈希见[证据](evidence/2026-10-28/phase2r/validation.json)。

没有设备安装、真实曲目原唱标注/听辨、实际平台账号/播放、音频指纹或性能验收。后续优化发现排名的重复元数据计算及搜索键隔离，继续保留实际未执行项。
