param(
  [string]$Target = "10.148.189.137:37551",
  [string]$OutFile = "$env:TEMP\ui.txt"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $adb -s $Target shell "uiautomator dump /sdcard/__ui.xml" 2>&1 | Out-Null
$xml = (& $adb -s $Target shell "cat /sdcard/__ui.xml" 2>&1 | Out-String)
& $adb -s $Target shell "rm -f /sdcard/__ui.xml" 2>&1 | Out-Null

$lines = New-Object System.Collections.ArrayList
[void]$lines.Add("raw length = $($xml.Length)")

# Pull out every node that has text, content-desc, or is clickable/editable
$pattern = '<node[^>]*>'
$matches_ = [regex]::Matches($xml, $pattern)
[void]$lines.Add("nodes = $($matches_.Count)")
[void]$lines.Add("--- interesting nodes ---")
foreach ($m in $matches_) {
  $n = $m.Value
  $text = if ($n -match 'text="([^"]*)"') { $Matches[1] } else { "" }
  $desc = if ($n -match 'content-desc="([^"]*)"') { $Matches[1] } else { "" }
  $cls = if ($n -match 'class="([^"]*)"') { $Matches[1] } else { "" }
  $bounds = if ($n -match 'bounds="([^"]*)"') { $Matches[1] } else { "" }
  $click = if ($n -match 'clickable="true"') { "CLICK" } else { "" }
  $edit = if ($n -match 'class="android.widget.EditText"') { "EDIT" } else { "" }
  $focus = if ($n -match 'focused="true"') { "FOCUSED" } else { "" }

  if ($text -or $desc -or $click -or $edit) {
    $short = $cls -replace '^android\.widget\.', '' -replace '^android\.view\.', ''
    [void]$lines.Add(("{0,-14} {1,-26} text='{2}' desc='{3}' {4} {5} {6}" -f $short, $bounds, $text, $desc, $click, $edit, $focus).TrimEnd())
  }
}

$lines | Set-Content -Path $OutFile -Encoding UTF8
Write-Host "written: $OutFile"
