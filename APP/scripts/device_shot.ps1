param(
  [string]$Target = "10.148.189.137:37551",
  [string]$Out = "$env:TEMP\shot.png"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s $Target shell "screencap -p /sdcard/__shot.png" 2>&1 | Out-Null
& $adb -s $Target pull /sdcard/__shot.png $Out 2>&1 | Out-Null
& $adb -s $Target shell "rm -f /sdcard/__shot.png" 2>&1 | Out-Null
if (Test-Path $Out) {
  $f = Get-Item $Out
  Write-Host ("saved: {0} ({1:N0} bytes)" -f $Out, $f.Length)
} else {
  Write-Host "FAILED"
}
