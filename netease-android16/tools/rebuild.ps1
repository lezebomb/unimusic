# 从 dist APK 完整重建（网易云音乐 1.5.30 + UNM 模块 + 自制 hook）
#
# 用法:
#   pwsh netease-android16/tools/rebuild.ps1 -DistApk "netease-android16/dist/网易云音乐-1.5.30-安卓16可启动-UNM已生效.apk" -OutDir D:\out
#
# 依赖:
#   - JDK 21  (tools/lspatch492.jar 是 class file 65，必须 21；JBR 21 即可)
#   - JDK 17  (javac，仅编译 hook)
#   - Android build-tools: d8 / zipalign / apksigner
#   - python 3.9+
#
param(
  [Parameter(Mandatory=$true)][string]$DistApk,
  [string]$OutDir = ".\out",
  [string]$Java21 = "C:\Program Files\JetBrains\PyCharm 2025.2.2\jbr\bin\java.exe",
  [string]$JavaHome17 = "",
  [string]$BuildTools = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0",
  [string]$AndroidJar = "",
  [switch]$SkipLspatch
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)   # netease-android16
$tools = Join-Path $root "tools"
$hookSrc = Join-Path $root "hook\src"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path $OutDir).Path

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }

# ---------------------------------------------------------------- 1. 拆 dist
Step "从 dist APK 取出 origin.apk / 内嵌模块 / 两个 so"
$work = Join-Path $OutDir "unpack"; New-Item -ItemType Directory -Force -Path $work | Out-Null
python - "$DistApk" "$work" <<'PY'
import sys, zipfile, os
src, out = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(src)
for name, dst in [
    ("assets/lspatch/origin.apk", "origin.apk"),
    ("assets/lspatch/modules/com.raincat.dolby_beta.apk", "module.apk"),
]:
    with z.open(name) as f, open(os.path.join(out, dst), "wb") as o:
        o.write(f.read())
    print("extracted", name)
m = zipfile.ZipFile(os.path.join(out, "module.apk"))
for name, dst in [
    ("lib/arm64-v8a/libnode.so", "libnode.so"),
    ("lib/arm64-v8a/libdexkit.so", "libdexkit.so"),
    ("assets/xposed_init", "xposed_init.txt"),
]:
    with m.open(name) as f, open(os.path.join(out, dst), "wb") as o:
        o.write(f.read())
    print("extracted(from module)", name)
PY

# ---------------------------------------------------------------- 2. origin2
Step "给 origin 补 libnode.so / libdexkit.so → origin2.apk"
python (Join-Path $tools "zipadd.py") (Join-Path $work "origin.apk") (Join-Path $work "origin2.apk") `
  "lib/arm64-v8a/libnode.so=$(Join-Path $work 'libnode.so')" `
  "lib/arm64-v8a/libdexkit.so=$(Join-Path $work 'libdexkit.so')"

# ---------------------------------------------------------------- 3. 编译 hook
Step "编译 hook（javac 17 → d8）"
if (-not (Test-Path (Join-Path $hookSrc "hooker\Creds.java"))) {
  Copy-Item (Join-Path $hookSrc "hooker\Creds.java.example") (Join-Path $hookSrc "hooker\Creds.java")
  Write-Host "  Creds.java 不存在，已用示例（不内置凭证，装完需手动登录一次）" -ForegroundColor Yellow
}
if (-not $AndroidJar) {
  $cand = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\platforms" -Directory -ErrorAction SilentlyContinue |
          Sort-Object Name -Descending | Select-Object -First 1
  if ($cand) { $AndroidJar = Join-Path $cand.FullName "android.jar" }
}
if (-not (Test-Path $AndroidJar)) { throw "找不到 android.jar，用 -AndroidJar 指定" }
$env:JAVA_HOME = if ($JavaHome17) { $JavaHome17 } else { (Get-Command javac).Source | Split-Path | Split-Path }

$cls = Join-Path $OutDir "classes"; $dex = Join-Path $OutDir "dex"
New-Item -ItemType Directory -Force -Path $cls, $dex | Out-Null
javac -source 8 -target 8 -nowarn -encoding UTF-8 -d $cls -cp "$cls;$AndroidJar" `
  (Join-Path $hookSrc "hooker\HookMain.java") (Join-Path $hookSrc "hooker\Creds.java")
& (Join-Path $BuildTools "d8.bat") --release --lib $AndroidJar --min-api 28 --output $dex (Join-Path $cls "hooker\*.class")
Copy-Item (Join-Path $dex "classes.dex") (Join-Path $work "classes2.dex") -Force

# ---------------------------------------------------------------- 4. 模块
Step "把 hook 塞进 dolby 模块 + xposed_init"
$xi = Join-Path $work "xposed_init.txt"
$init = Get-Content $xi -Raw
if ($init -notmatch "hooker\.HookMain") { Set-Content -Path $xi -Value ($init.TrimEnd() + "`nhooker.HookMain") -NoNewline }
python (Join-Path $tools "zipadd.py") (Join-Path $work "module.apk") (Join-Path $work "module_fix.apk") `
  "classes2.dex=$(Join-Path $work 'classes2.dex')" "assets/xposed_init=$xi"

# ---------------------------------------------------------------- 5. lspatch
if ($SkipLspatch) { Step "跳过 lspatch（已生成 module_fix.apk / origin2.apk）"; return }
Step "LSPatch 1.2 重打包（-l 0）"
$out = Join-Path $OutDir "lspatch"
& $Java21 -jar (Join-Path $tools "lspatch492.jar") (Join-Path $work "origin2.apk") `
  -m (Join-Path $work "module_fix.apk") -l 0 -o $out -f
$apk = Get-ChildItem $out -Filter *.apk | Select-Object -First 1
Step "完成: $($apk.FullName)  ($([math]::Round($apk.Length/1MB,1)) MB)"
