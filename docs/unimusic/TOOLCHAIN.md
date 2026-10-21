# Android 构建环境

2026-10-21 已补齐本机开发组件；安装日志在忽略目录 `build/unimusic-toolchain/`。

| 组件 | 已安装版本/位置 |
| --- | --- |
| Gradle | 项目 Wrapper 9.6.1 |
| Gradle launcher JDK | 保留本机原有 Temurin 21 |
| Java toolchain 17 | Temurin 17.0.20.1+1，项目 `.gradle-home/jdks/jdk-17.0.20.1+1` |
| compileSdk 平台 | `platforms;android-37.0`（官方包名；对应项目 compileSdk=37） |
| Build Tools | `build-tools;37.0.0` |
| NDK | `ndk;27.0.12077973`，保留本机已有其他版本 |
| CMake | `cmake;3.31.6`，满足项目 3.28.0+ 要求 |

JDK 来自 [Adoptium 官方 API](https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse)，下载包按官方 SHA-256 校验后解压，不改机器级 JAVA_HOME/PATH。该次校验值为 `e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0`。

## 复现与构建

Windows 机器先准备官方 Android command-line tools，设置 ANDROID_HOME，然后运行：

```powershell
.\tools_pub\unimusic\setup-android.ps1
python tools_pub/unimusic/baseline.py --android
```

安装器幂等跳过已有的 JDK 和 SDK 包，生成 `build/unimusic-toolchain/installed-toolchain.json`，保存实际版本、校验与路径。JDK 使用官方当前 17 系列版本，具体复现版本以该记录为准；构建使用仓库固定 Gradle/AGP/SDK 目标。

baseline 自动发现项目内的 JDK 17，通过 [Gradle toolchain 路径配置](https://docs.gradle.org/current/userguide/toolchains.html#sec:custom_loc) 提供给构建。也支持 `--java17-home` 手工指定路径；已经运行结构检查时可加 `--skip-structure` 避免重复。

直接运行某个开发任务：

```powershell
.\gradlew.bat '-Dorg.gradle.java.installations.paths=D:/unimusic/.gradle-home/jdks/jdk-17.0.20.1+1' :database:compileDebugKotlin --max-workers=2
```

将路径替换为本机安装记录中的 JDK home。源码仓库不提交开发包、机器级路径配置或凭据。

## 本次验证

- JDK 17 的 buildSrc 编译阻塞已解除。
- SDK 四个包均有安装完成的 package.xml；安装脚本第二次执行正常跳过，无重复下载。
- `:database:compileDebugKotlin` 成功，Room 实际生成 v21 schema；首次含依赖解析的构建耗时 7m34s，这不是应用性能指标。
- 首次插件解析遇到一次 Maven Central TLS 握手中断；重试后该依赖解析成功，没有禁用 TLS 校验或修改协议安全设置。
- 后续统一库测试、迁移验证和 Debug APK 的结果另记在 Phase 1A 报告，不用环境安装成功替代功能验收。

[Phase 0 基线](BASELINE.md) 保留当时真实的失败记录，作为环境修复前的历史证据。
