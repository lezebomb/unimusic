param(
    [string[]]$GradleArgs = @(':app:assembleDebug')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$projectJdk = Join-Path $projectRoot '.gradle-home/jdks/jdk-17.0.20.1+1'
$projectSdk = Join-Path $projectRoot '.android-sdk'
if (-not (Test-Path -LiteralPath (Join-Path $projectJdk 'bin/java.exe'))) {
    throw "Project JDK 17 is missing: $projectJdk"
}
if (-not (Test-Path -LiteralPath (Join-Path $projectSdk 'platforms/android-37.0/android.jar'))) {
    throw "Project Android SDK is missing: $projectSdk"
}

# Process-local overrides: do not modify other projects or the user's Windows environment.
$env:JAVA_HOME = $projectJdk
$env:ANDROID_HOME = $projectSdk
$env:ANDROID_SDK_ROOT = $projectSdk
$toolchain = '-Dorg.gradle.java.installations.paths=' + $projectJdk.Replace('\', '/')
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') $toolchain @GradleArgs --max-workers=2 --console=plain
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $buildExitCode
