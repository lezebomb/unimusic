param(
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT })
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $SdkRoot) { throw 'Set ANDROID_HOME or pass -SdkRoot for an existing Android SDK.' }
$SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
$sdkManager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) { throw "Missing SDK command-line tools: $sdkManager" }

$downloadRoot = Join-Path $projectRoot 'build\unimusic-toolchain'
$javaRoot = Join-Path $projectRoot '.gradle-home\jdks'
New-Item -ItemType Directory -Force $downloadRoot, $javaRoot | Out-Null

# Fetch the package and digest from the official vendor API; do not execute an installer.
$assets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
$asset = @($assets)[0]
$package = $asset.binary.package
if ([IO.Path]::GetFileName($package.name) -ne $package.name) { throw 'Invalid JDK archive name' }
$archive = Join-Path $downloadRoot $package.name
$releaseName = $asset.release_name
$javaHome = Join-Path $javaRoot $releaseName
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\javac.exe'))) {
    if (-not (Test-Path -LiteralPath $archive)) {
        Invoke-WebRequest -Uri $package.link -OutFile $archive
    }
    $digest = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($digest -ne $package.checksum) { throw 'JDK archive SHA-256 does not match official metadata' }
    Expand-Archive -LiteralPath $archive -DestinationPath $javaRoot -Force
}
if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\javac.exe'))) {
    throw "JDK extraction did not produce the expected home: $javaHome"
}

$packages = @('platforms;android-37.0', 'build-tools;37.0.0', 'ndk;27.0.12077973', 'cmake;3.31.6')
$missing = @($packages | Where-Object {
    -not (Test-Path -LiteralPath (Join-Path $SdkRoot ($_.Replace(';', '\') + '\package.xml')))
})
if ($missing.Count -gt 0) {
    & $sdkManager "--sdk_root=$SdkRoot" --install @missing
    if ($LASTEXITCODE -ne 0) { throw "SDK installation failed: $LASTEXITCODE" }
}

$record = [ordered]@{
    installedAtUtc = [DateTime]::UtcNow.ToString('o')
    java17Home = $javaHome
    java17Version = $asset.version.openjdk_version
    packageUrl = $package.link
    sha256 = $package.checksum
    androidSdk = $SdkRoot
    androidPackages = $packages
}
$record | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $downloadRoot 'installed-toolchain.json')
Write-Output "JDK 17: $javaHome"
Write-Output "Android SDK: $SdkRoot"
Write-Output 'Run: python tools_pub/unimusic/baseline.py --android'
# JAVA_HOME/PATH and machine-wide SDK settings are deliberately not rewritten.
