# UniMusic 源码来源

- 记录日期：2026-10-21（Asia/Shanghai）。
- 本仓库：<https://github.com/lezebomb/unimusic>。
- 上游：<https://github.com/cwuom/NeriPlayer>，分支 `master`。
- Phase 0 基线：`b9b6caf39bc65ca252f1c6a632ec24320cec68bb`。
- 导入方式：保留上游 Git 提交历史，在其提交上继续开发；不是根据 README 重建播放器。
- 本地 `origin` 指向 UniMusic，`upstream` 指向 NeriPlayer。

固定子模块：

| 子模块 | 提交 |
| --- | --- |
| `np-submodule/NeriPlayer-LTW` | `31d55c60cc7417f5e0592803550eb751345d17b1` |
| `np-submodule/accompanist-lyrics-core` | `825661a10101b6b17cdcf8f39e0d5a12ff8d21fd` |
| `np-submodule/accompanist-lyrics-ui` | `d844e105bfb49104e1eabd03f700f26f492140e2` |
| `np-submodule/miuix` | `8650597b0c848c8fc94342b5af8219979fe7e831` |

## 许可与分发

保留根目录 [GPL-3.0 许可证](../../LICENSE)、源文件版权声明、子模块和第三方许可。新增 UniMusic 修改继续按本项目 GPL-3.0 许可提供。发行二进制时需要提供对应的完整源码、构建说明和修改说明，并整理 FFmpeg、libusb、歌词组件等实际随包依赖的许可材料；不能把开源代码许可当作平台音乐的播放或再分发许可。

Phase 0 未替换原项目图标、商标、包名或签名。UniMusic 品牌、源码入口、原创图标和应用标识调整安排在 Phase 4。包名或签名变化会影响覆盖安装及读取原应用私有数据库，须先提供用户主动导出/导入的迁移路径，不能假称换包名即可读取旧应用数据。

## 获取与复现

```powershell
git clone --recurse-submodules https://github.com/lezebomb/unimusic.git
cd unimusic
python tools_pub/unimusic/baseline.py
```

完整 Android 基线条件和命令见 [BASELINE.md](BASELINE.md)。后续更新上游应先检查数据库版本、同步字段编号、子模块和二进制依赖差异，避免把上游版本号直接覆盖 UniMusic 已发布的迁移链。
