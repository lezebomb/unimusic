# Phase 0：构建、检查与真实限制

日期：2026-10-21，时区 Asia/Shanghai。被测业务源码为上游 `b9b6caf39bc65ca252f1c6a632ec24320cec68bb`，尚未进行 UniMusic 数据库/播放器功能修改。

## 实际执行结果

| 操作 | 结果 | 说明 |
| --- | --- | --- |
| 上游源码与固定子模块导入 | 成功 | 保留 Git 历史和 GPL；见 SOURCE_PROVENANCE |
| `gradlew.bat --version` | 成功 | Wrapper 下载并运行 Gradle 9.6.1，Launcher 为 JDK 21.0.12.1 |
| Debug assemble + 两组数据库 JVM 测试 | **阻塞/失败退出 1** | 138.48s 后在 buildSrc Java toolchain 解析失败，未进入应用编译/测试 |
| `tools_pub/quality/module_boundaries.py` | **通过** | 运行器使用 `python -X utf8 -B`；55.234s，退出 0 |
| Android Room MigrationTestHelper / UI 仪器测试 | 未执行 | Android 构建工具链未齐备；没有可用已授权设备测试结果 |
| 三平台真实账号和实际在线播放 | 未执行 | 未提供/使用平台凭据，接口线上可用性未验收 |
| 性能、功耗、首次出声、APK 安装 | 未执行 | 未生成本次 Debug APK，没有真实性能数据 |

两组被请求的现有数据库测试为 `RegisteredMigrationChainTest` 与 `NeriUserDataDatabaseVersionTest`。它们在 Gradle 配置阶段之前被阻塞，**不能记为“测试通过”或“测试断言失败”**。

初次直接运行上游 Python 边界脚本受 Windows 默认 GBK 解码影响退出；新增运行器指定 Python UTF-8 模式后复跑通过。没有改写其断言或放宽质量门槛。

## 构建失败原文

```text
Could not resolve all dependencies for configuration ':buildSrc:buildScriptClasspath'.
Failed to calculate the value of task ':buildSrc:compileJava' property 'javaCompiler'.
Cannot find a Java installation ... matching: {languageVersion=17, ...}.
Toolchain download repositories have not been configured.
BUILD FAILED in 2m 18s
```

这是环境工具链失败，不是已经发现业务编译错误；后续补齐环境后仍可能暴露其他依赖/编译问题。本次没有为绕过检查更改原有 SDK、NDK 或 Java 目标版本。

## 已检查环境

| 条件 | 上游要求 | 本机实际观察 |
| --- | --- | --- |
| Gradle | 9.6.1 | 已由 Wrapper 成功运行 |
| JDK | buildSrc/约定 toolchain 17；上游 CI 使用 JDK 21 launcher | 21.0.12.1；Gradle 未找到可用 JDK 17 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 28 | SDK 平台只安装 android-35、android-36，缺 37 |
| Build tools | 由 AGP 配置解析 | 已安装 36.0.0；本次未执行到 AGP 选择，未断言它不兼容 |
| NDK | 27.0.12077973 | 已安装 28.2.13676358，缺指定版本 |
| CMake | 3.28.0+ | 已安装 3.22.1，未满足指定范围 |
| SDK 路径 | ANDROID_HOME / ANDROID_SDK_ROOT 或 local.properties | `D:\Collect\.tooling\android-sdk` |
| 设备 | 仪器测试需已授权设备/模拟器 | 初次无设备，后续一度 authorizing；最终轻量清单记录为 device，但未运行安装/仪器测试 |
| 平台凭据 | 真实账号测试时需要 | 本次未读取或使用 |

版本来源：`gradle/wrapper/gradle-wrapper.properties`、`gradle/libs.versions.toml`、`buildSrc/build.gradle.kts` 与 `build-logic/convention/src/main/kotlin/Version.kt`。环境原始清单在 evidence report 中，仅记录设备状态，不保留设备序列号。

本机默认沙箱执行器当时无法初始化，附件读取和实际命令经具备授权的执行入口运行；这与上述 Gradle 工具链阻塞是两件事，未把工具故障伪记为业务测试结果。

## 复现

默认轻量检查（无需平台账号或 Android 构建）：

```powershell
python tools_pub/unimusic/baseline.py
```

生成 `build/unimusic-baseline/repro/report.json` 与模块边界日志。默认退出 0 **只代表所请求的轻量检查通过**；report.notRun 会标记未执行的 Android 项目。

工具链就绪后：

```powershell
python tools_pub/unimusic/baseline.py --android --java17-home D:/path/to/jdk-17
```

该参数是本机已安装 JDK 17 的位置，需换成真实路径。运行器直接使用仓库的 Gradle Wrapper JAR，执行：

```text
:app:assembleDebug :database:testDebugUnitTest
--tests *RegisteredMigrationChainTest
--tests *NeriUserDataDatabaseVersionTest --continue
```

报告按每个命令分别记录退出码、超时、日志路径和墙钟时长；脚本不下载平台音乐、不采集 credentials。超时不是成功。默认单项超时 1800s 可通过 `--timeout` 调整。Gradle 的完整 Android 模式本次尚未通过，不能从默认模式通过推断其通过。

准备环境时先安装/定位 JDK 17 并让 Gradle toolchain 发现；安装 Android `platforms;android-37`、`ndk;27.0.12077973` 及 SDK manager 实际提供的满足 3.28.0+ 范围的 CMake。保持既有 JDK 21 和旧 SDK，不覆盖其他项目工具链。随后按项目既有平台仓库解析依赖，使用已授权设备/模拟器执行指定迁移测试。

只在生产数据迁移开始时添加并运行相关新迁移测试；没有创建虚假 21.json、空 APK 或未经 Room 校验的“通过”标志。

## 保存的证据

- [gradle-version.log](evidence/2026-10-21/gradle-version.log)：Wrapper/JVM 版本输出。
- [baseline-gradle.log](evidence/2026-10-21/baseline-gradle.log)：首次未修改上游业务代码的完整构建失败输出。
- [baseline-result.json](evidence/2026-10-21/baseline-result.json)：首次 Android 尝试的时间、命令范围和退出码。
- [report.json](evidence/2026-10-21/report.json)：最终轻量运行器结果及工具链清单。
- [module-boundaries.log](evidence/2026-10-21/module-boundaries.log)：实际通过结果。

Phase 1 当前限制：不能验证 Room schema 生成与迁移、Android 集成或安装，因此本次不启用新的生产数据写入。后续首先补齐环境并完成最小构建/迁移门禁，再进入 1A；详细任务见 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)。
