# Phase 3G：推荐计算响应取消

推荐已在后台运行，但候选收集、摘要过滤、去重、评分/多样性选择与缓存恢复原先是同步循环，collectLatest取消后仍可能继续计算。现在这些循环接收并检查当前协程取消状态；旧刷新能尽早让出后台资源，排序权重、身份及理由不变。普通同步调用仍可使用默认回调。

UUID查询后、发布内存快照前和执行原子缓存写入前再次复核取消，已经取消的UUID捕获不会替换正常快照。原子写一旦进入执行仍按文件工具的事务语义完成，不宣称可以撤销任意时刻已经开始的同步IO。保留用户统计/偏好和Room v23、算法v4、3F上下文兼容。

新增2项/共3项相关JVM通过：2000候选的健康顺序/理由不变，取消计算提前退出；UUID捕获取消后内存与磁盘原快照仍可恢复；既有合唱偏好和多样性通过。首次构建24s在编译时发现协程上下文局部名遮蔽Android Context，已更名修复；最终同组测试和Debug51s通过。模块边界通过。

APK `build/outputs/unimusic/UniMusic-20261030-3G-debug.apk`，版本74ea0b24bc0b.dirty.10300050，SHA-256 `0e7689d016af6cb38e01b532fd5b5d9903c42bba75f42a1c34f38c4fc0192251`。没有真机安装、实际大库耗时/帧率、账号或新音频验收；测试候选规模不是性能测量。XML及实际结果见[evidence](evidence/2026-10-30/phase3g/validation.json)。

2BB远端40913939981被后续提交替代取消，不能标记成功；3F的40914837908最后观察仍在运行，它的普通行为集包含歌手意图测试。后续[CI40917256200](https://github.com/lezebomb/unimusic/actions/runs/40917256200)整体成功，下载XML确认LocalRecommendationRankerTest 7项、DailyRecommendationStoreTest 8项，均0失败，含本阶段用例。不重复本机已通过检查。
