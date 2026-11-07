$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$sdkRoot = Join-Path $projectRoot 'tools/android-sdk'
$env:ANDROID_HOME = $sdkRoot
$jdk = Get-ChildItem (Join-Path $projectRoot 'tools') -Directory -Filter 'jdk-17*' | Select-Object -First 1
if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
if (-not $jdk) {
 $installedJdk = Get-ChildItem 'C:/Program Files/Microsoft' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1
 if ($installedJdk) { $env:JAVA_HOME = $installedJdk.FullName }
}
$env:GRADLE_USER_HOME = Join-Path $projectRoot 'tools/gradle-cache'
if (-not $env:JAVA_HOME) { throw '请先安装 JDK 17，并设置 JAVA_HOME。' }
$gradle = Join-Path $projectRoot 'tools/gradle-8.13/bin/gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { throw '缺少已校验的 Gradle 8.13。' }
"sdk.dir=$($sdkRoot.Replace('\','/'))" | Set-Content -LiteralPath 'local.properties' -Encoding utf8
& $gradle ':app:assembleDebug' '--no-daemon'
if ($LASTEXITCODE -ne 0) { throw 'APK 编译失败，请检查上方日志。' }
$adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
& $adb 'wait-for-device'
& $adb 'install' '-r' 'app/build/outputs/apk/debug/app-debug.apk'
if ($LASTEXITCODE -ne 0) { throw 'APK 安装失败。' }
& $adb 'shell' 'am' 'start' '-n' 'com.bitocean.dropzone/.MainActivity'
