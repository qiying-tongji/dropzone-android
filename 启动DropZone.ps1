$ErrorActionPreference = 'Stop'
$sdk = 'C:/Users/qy/Documents/ChatGPT/Dropzone/tools/android-sdk'
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$devices = & $adb devices
if (-not ($devices -match 'emulator-\d+\s+device')) {
 Start-Process -FilePath (Join-Path $sdk 'emulator/emulator.exe') -ArgumentList '-avd','DropZone_Pixel'
 & $adb wait-for-device
 for ($i=0; $i -lt 120; $i++) {
  if ((& $adb shell getprop sys.boot_completed) -match '1') { break }
  Start-Sleep -Seconds 2
 }
}
& $adb install -r (Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk')
& $adb shell am start -n com.bitocean.dropzone/.MainActivity
