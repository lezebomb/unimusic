# 网易云音乐 1.5.30 → Android 16（一加 Ace 6T / PLR110）修复工程

让「带 UnblockNeteaseMusic 模块的网易云音乐荣耀渠道包」在 Android 16 上能正常启动、正常播放，
并保持包内 `com.raincat.dolby_beta`（杜比大喇叭 = UnblockNeteaseMusic）模块完整可用。

- 目标设备：OnePlus Ace 6T（`PLR110`），Android 16 / API 36，arm64-v8a，4 KB page size，无 root
- 目标包：`com.hihonor.cloudmusic` versionCode `1005030` / versionName `1.5.30`（荣耀渠道）
- 产物：`dist/网易云音乐-1.5.30-安卓16可启动-UNM已生效.apk`

---

## 1. 现象

安装即闪退：`Unable to instantiate application com.netease.cloudmusic.CloudMusicApplication ...
java.lang.ClassNotFoundException`，或者进程起来后 1~2 秒 native `SIGABRT` 无限重启。

## 2. 根因（三层，全部实证）

### 2.1 原包本身是 LSPatch 0.6 的二次打包

原 APK 结构：

```
assets/lspatch/origin.apk                    # 真正的网易云主体（97MB）
assets/lspatch/loader.dex                    # LSPatch loader（1165 类）
assets/lspatch/modules/com.raincat.dolby_beta.apk   # UNM 模块（12.5MB，必须保留）
assets/lspatch/so/arm64-v8a/liblspatch.so
classes.dex                                  # 只有 10KB 的 metaloader stub
assets/lspatch/config.json                   # lspConfig.VERSION_CODE = 398（LSPatch 0.6）
```

LSPatch 0.6 的 `LSPApplication.createLoadedApkWithContext()` 反射取
`android.app.ActivityThread$AppBindData#compatInfo`，该字段在 Android 16 已移除：

```
E/LSPatch: java.lang.NoSuchFieldError: android.app.ActivityThread$AppBindData#compatInfo
    at de.robv.android.xposed.XposedHelpers.findField
    at org.lsposed.lspatch.loader.LSPApplication.createLoadedApkWithContext(LSPApplication.java:108)
```

→ origin 的 dex 永远没被加进 classpath → `ClassNotFoundException` → 闪退。
同时 `E/LSPosed: Failed to init lsplant`，0.6 的 native 注入在 16 上也是坏的。

### 2.2 换成 LSPatch 1.2 之后：网易加固的原生签名校验

用 LSPatch 1.2（canary-492, core canary-3106）重打包后 Java 层通了，但 `libpoison.so` dlopen 后
1~2 ms 内主线程自杀：

```
D/nativeloader: Load .../lib/arm64/libpoison.so ... : ok
D/POISON      : APK Signature:4a4bdd3e83eb39b9a7e3fb624616dc6e     # 我们自己的证书 MD5
F/libc        : FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x76819fc478)
F/libc        : Fatal signal 6 (SIGABRT), code -1 (SI_QUEUE) in tid <pid>
```

`0x76819fc478` 这个地址在同进程 `/proc/<pid>/maps` 里**没有任何映射**（0x7681000000 之后是空洞），
即指针是伪造的 —— `FORTIFY` 只是它选的自杀手法，不是真 bug；tombstone 也拿不到 backtrace
（`crash socket received short read of length 0`）。

已排除的路线：

| 尝试 | 结果 |
|---|---|
| `-l 1`（只 hook PackageManager 签名） | 打印的是原始 MD5，但照样 abort |
| `-l 2`（pm + openat，把 base.apk 读取重定向到内嵌 origin.apk） | 照样 abort |
| `-l 3`（pm + openat + svc，改 native） | 照样 abort |
| 删掉 `libpoison.so` | 不再 abort，但 `Could not find 'libpoison.so'` → Tinker 起不来；它的 `serialdata`/`deserialdata`/`ncaeDecrypt` 是网络层硬依赖 |
| 改写 `libpoison.so` 的 `pthread_mutex_lock/abort` 导入名 | 照样 abort（自杀点不在这些导入调用） |
| capstone 静态定位 `POISON` / `APK Signature:%s` 字符串 xref | 0 命中，字符串运行时解密（VM 保护） |

### 2.3 关键旁证：改包名会触发「假阳性」崩溃

把包名等长替换成 `com.hihonor.cloudmusix`（为了在同一台机器的第二个用户里做对照实验）后，
**任何** `-l` 级别、任何模块组合都会 abort；而真包名 + `-l 0` 能一路进到主界面。
→ 加固对包名/APK 路径敏感，之前沙盒里的很多崩溃结论是假的。

## 3. 修法

思路：**不做签名绕过（`-l 0`），让 `libpoison.so` 正常加载**，然后用一个自写 Xposed 模块把
重打包/模块加载引入的两个新问题补掉。

### 3.1 `-l 0` + LSPatch 1.2

```
java -jar lspatch492.jar origin.apk -m module_v15.apk -l 0 -o outv15 -f
```

签名校验路径完全不动，poison 读到的是自己的原始证书，`D/POISON` 打印后不再 abort。

### 3.2 自制 hook 模块（`hook/src/hooker/HookMain.java`）

打进 dolby 模块的 `classes2.dex`（`assets/xposed_init` 追加一行 `hooker.HookMain`）：

1. **修 DexKit**：模块在 `attachBaseContext` 阶段要
   `System.load("<模块apk>!/lib/arm64-v8a/libdexkit.so")`，LSPatch 下 dlopen 必失败
   （`library ... not found`）。hook 里把它解压到 `files/nativelibs/`，改用
   `Runtime.load0(模块的 ClassLoader, path)` 预加载 —— 这样 `JNI_OnLoad` 用模块 classloader
   找得到 `org.luckypray.dexkit.DexKitBridge`，native 才能 `RegisterNatives` 成功。
2. **修命名空间**：hook `System.load/loadLibrary` 后，Xposed 调原件时调用方退化成 boot
   classloader，App 自己的 so 会落进 `com_android_art` 命名空间并报
   `library "libstdc++.so" not found`（`libpoison.so`、`libxcrash.so` 全挂），启动卡死在 splash。
   → 除 libdexkit/libnode 外的**所有** `System.load*` 一律改成
   `Runtime.load0(com.netease.cloudmusic.CloudMusicApplication, path)`，走 App 自己的命名空间。
3. **守卫模块的 `CLASS_*` 空值**：模块 `O000O0O00OO0OO0OOO0()` 用
   `XposedHelpers.findClassIfExists(Prefs.get("CLASS_CDN",""), cl)`，首次运行为空串会抛
   `IllegalArgumentException: Class name is empty`，把 `MainHook → 注册链` 整条打断，**后续 hook
   （包括 UNM）全部不装**。hook 在模块初始化前给这些键写占位值。
4. **登录态自恢复**：`attachBaseContext` 阶段若 prefs 里没有 `MUSIC_U`，就把内置的
   cookie（`hook/src/hooker/Creds.java`，由 `tools/gen_creds.py` 生成，**不要提交真实值**）
   写回 `shared_prefs/cm_cookie_storage.xml` 与 `files/mmkv/cm_cookie_storage`。

### 3.3 `origin2.apk`：给 App 补 libnode

模块是拿 `context.getApplicationInfo().nativeLibraryDir` 里的 `libnode.so` **当可执行文件**跑
JS 服务端的（`sh -c 'libnode.so app.js -a 127.0.0.1 -o pyncmd kuwo'`）。
LSPatch 只打包 origin 自带的 so，所以往 `origin.apk` 的 `lib/arm64-v8a/` 里补进
`libnode.so`（30MB）和 `libdexkit.so`，得到 `origin2.apk`，UNM 服务才能起来。

## 4. 复现

```powershell
# 依赖：JDK 21（lspatch CLI）、JDK 17（javac）、Android build-tools（d8/zipalign/apksigner）
pwsh netease-android16/tools/rebuild.ps1 -DistApk netease-android16/dist/<apk> -OutDir D:\out
```

脚本会：从 dist APK 里取出 `assets/lspatch/origin.apk` 与内嵌模块 → 抽出 `libnode.so` /
`libdexkit.so` 补进 origin → `javac` + `d8` 编译 hook → `zipadd` 塞进模块 → `lspatch -l 0` 重打包。

单独的手工步骤见 `tools/` 里每个脚本头注释；`hook/patch15.py` 是 v12→v15 的补丁脚本，留作记录。

## 5. 验证

```bash
# 1) UNM 服务在跑
adb shell "ps -A | grep libnode"        # u0_a519 ... S libnode.so

# 2) 走模块内置代理问一首 VIP 曲（186016 = 周杰伦《晴天》）
adb shell "curl -s -x http://127.0.0.1:8080 \
  'http://music.163.com/api/song/enhance/player/url?id=186016&ids=%5B186016%5D&br=320000'"
# → {"data":[{"id":186016,"url":"https://music.163.com/package/aHR0cDovL2t3LWJqLmt1d28uY24v...","fee":0,...}]}
#   base64 解开是 http://kw-bj.kuwo.cn/...  → 第三方音源，fee=0

# 3) 模块的 DexKit 类名表已填满（否则 UNM 不工作）
adb shell "cat /sdcard/Android/data/com.hihonor.cloudmusic/files/creds/shared_prefs/CLASS.xml"
```

实测：`ラストシーン.`（03:17）从 00:00 播到结束；`君をなぞるだけの夜`（03:36）在旧包上是
「VIP听整首」试听，本包 00:42 → 02:47 → 播完。

## 6. 目录

```
netease-android16/
├── dist/     # 产物 APK（Git LFS）
├── docs/     # 中文修复说明（含时间线）
├── hook/     # 自制 Xposed 模块源码（HookMain.java + 编译用 Xposed API 桩 + patch15.py）
└── tools/    # 重打包/签名/逆向辅助脚本 + lspatch492.jar + rebuild.ps1
```

## 7. 注意

- 包名与官方一致但签名是本机 testkey，**不能与任何官方/旧版本共存**，安装前需
  `adb uninstall com.hihonor.cloudmusic`（数据会清空，登录态本包会自动补回）。
- `hook/src/hooker/Creds.java` 含账号凭证，公开仓库只保留 `Creds.java.example`。
- 仅用于本人设备的兼容性修复与学习研究；音乐版权归各版权方，请支持正版。
