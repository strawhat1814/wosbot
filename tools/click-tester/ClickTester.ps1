#Requires -Version 5.1
<#
.SYNOPSIS
  Pick an emulator, set X/Y, tap via ADB (720x1280 game coords).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\click-tester\ClickTester.ps1
  powershell -ExecutionPolicy Bypass -File tools\click-tester\ClickTester.ps1 -Serial 127.0.0.1:5563
#>
param(
    [string]$Serial = "",
    [string]$Adb = "",
    [int]$X = 2,
    [int]$Y = 582
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$SnapDir = Join-Path $RepoRoot ".garbage\emulator-snapshot"
New-Item -ItemType Directory -Force -Path $SnapDir | Out-Null

$Known = [ordered]@{
    "Main A  (127.0.0.1:5563)" = "127.0.0.1:5563"
    "Main B  (127.0.0.1:5565)" = "127.0.0.1:5565"
    "Main C  (127.0.0.1:5567)" = "127.0.0.1:5567"
}

function Resolve-AdbPath([string]$Hint) {
    if ($Hint -and (Test-Path -LiteralPath $Hint)) { return $Hint }
    foreach ($c in @(
        (Join-Path $RepoRoot "tools\adb\adb.exe"),
        "C:\LDPlayer\LDPlayer9\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    )) {
        if (Test-Path -LiteralPath $c) { return $c }
    }
    $where = & where.exe adb 2>$null | Select-Object -First 1
    if ($where) { return $where }
    throw "adb.exe not found"
}

function Invoke-Adb([string]$AdbPath, [string]$Device, [string[]]$AdbArgs) {
    if (-not $AdbArgs -or $AdbArgs.Count -eq 0) {
        throw "Internal error: adb invoked with no command"
    }
    $all = New-Object System.Collections.Generic.List[string]
    if ($Device) {
        $all.Add("-s") | Out-Null
        $all.Add($Device) | Out-Null
    }
    foreach ($a in $AdbArgs) { $all.Add([string]$a) | Out-Null }
    & $AdbPath $all.ToArray() 2>&1
}

function Get-Devices([string]$AdbPath) {
    Invoke-Adb -AdbPath $AdbPath -Device "" -AdbArgs @("devices") |
        Where-Object { $_ -match '^\S+\s+device$' } |
        ForEach-Object { ($_ -split '\s+')[0] }
}

function Ensure-Connected([string]$AdbPath, [string]$Device) {
    if ($Device -match '^\d+\.\d+\.\d+\.\d+:\d+$') {
        $null = Invoke-Adb -AdbPath $AdbPath -Device "" -AdbArgs @("connect", $Device)
    }
}

function Send-Tap([string]$AdbPath, [string]$Device, [int]$TapX, [int]$TapY) {
    $out = Invoke-Adb -AdbPath $AdbPath -Device $Device -AdbArgs @(
        "shell", "input", "tap", "$TapX", "$TapY"
    )
    $text = ($out | Out-String).Trim()
    if ($text -match '(?i)error:|device offline|no devices/emulators|not found|Installed as .*adb\.exe') {
        throw $text
    }
}

function Capture-Frame([string]$AdbPath, [string]$Device, [string]$OutPng) {
    $remote = "/sdcard/frostguard-click-tester.png"
    $null = Invoke-Adb -AdbPath $AdbPath -Device $Device -AdbArgs @("shell", "screencap", "-p", $remote)
    $pull = Invoke-Adb -AdbPath $AdbPath -Device $Device -AdbArgs @("pull", $remote, $OutPng)
    $null = Invoke-Adb -AdbPath $AdbPath -Device $Device -AdbArgs @("shell", "rm", $remote)
    if (-not (Test-Path -LiteralPath $OutPng)) {
        throw "screencap pull failed: $($pull | Out-String)"
    }
}

function Show-Overlay([string]$Png, [int]$TapX, [int]$TapY, [string]$OutPng) {
    $img = [System.Drawing.Image]::FromFile($Png)
    try {
        $g = [System.Drawing.Graphics]::FromImage($img)
        $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::Red), 3
        $g.DrawEllipse($pen, $TapX - 10, $TapY - 10, 20, 20)
        $g.DrawLine($pen, $TapX - 18, $TapY, $TapX + 18, $TapY)
        $g.DrawLine($pen, $TapX, $TapY - 18, $TapX, $TapY + 18)
        $font = New-Object System.Drawing.Font @("Segoe UI", 14.0)
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::Yellow)
        $g.DrawString("($TapX,$TapY)", $font, $brush, [float]($TapX + 14), [float]($TapY - 10))
        $img.Save($OutPng, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $pen.Dispose(); $font.Dispose(); $brush.Dispose()
    } finally {
        $img.Dispose()
    }
    Start-Process $OutPng
}

$adbPath = Resolve-AdbPath $Adb

$form = New-Object System.Windows.Forms.Form
$form.Text = "Frostguard Click Tester"
$form.Size = New-Object System.Drawing.Size(460, 360)
$form.StartPosition = "CenterScreen"
$form.FormBorderStyle = "FixedDialog"
$form.MaximizeBox = $false

function L([string]$t, [int]$x, [int]$y, [int]$w = 80) {
    $c = New-Object System.Windows.Forms.Label
    $c.Text = $t; $c.Location = New-Object System.Drawing.Point($x, $y); $c.Size = New-Object System.Drawing.Size($w, 22)
    $form.Controls.Add($c) | Out-Null
}

L "Emulator" 16 18 70
$emuBox = New-Object System.Windows.Forms.ComboBox
$emuBox.DropDownStyle = "DropDown"
$emuBox.Location = New-Object System.Drawing.Point(90, 16)
$emuBox.Size = New-Object System.Drawing.Size(240, 24)
$emuBox.Font = New-Object System.Drawing.Font @("Segoe UI", 10.0)
$form.Controls.Add($emuBox)

$refreshBtn = New-Object System.Windows.Forms.Button
$refreshBtn.Text = "Refresh"
$refreshBtn.Location = New-Object System.Drawing.Point(340, 14)
$refreshBtn.Size = New-Object System.Drawing.Size(90, 28)
$form.Controls.Add($refreshBtn)

L "X" 16 64 30
$xBox = New-Object System.Windows.Forms.NumericUpDown
$xBox.Minimum = 0; $xBox.Maximum = 719; $xBox.Value = [Math]::Max(0, [Math]::Min(719, $X))
$xBox.Location = New-Object System.Drawing.Point(90, 62)
$xBox.Size = New-Object System.Drawing.Size(100, 28)
$xBox.Font = New-Object System.Drawing.Font @("Segoe UI", 14.0)
$form.Controls.Add($xBox)

L "Y" 210 64 30
$yBox = New-Object System.Windows.Forms.NumericUpDown
$yBox.Minimum = 0; $yBox.Maximum = 1279; $yBox.Value = [Math]::Max(0, [Math]::Min(1279, $Y))
$yBox.Location = New-Object System.Drawing.Point(250, 62)
$yBox.Size = New-Object System.Drawing.Size(100, 28)
$yBox.Font = New-Object System.Drawing.Font @("Segoe UI", 14.0)
$form.Controls.Add($yBox)

# Quick fill buttons for yesterday's bands (center)
$quick = New-Object System.Windows.Forms.FlowLayoutPanel
$quick.Location = New-Object System.Drawing.Point(90, 100)
$quick.Size = New-Object System.Drawing.Size(340, 36)
$quick.WrapContents = $false
$form.Controls.Add($quick)

function Add-Quick([string]$label, [int]$qx, [int]$qy) {
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $label
    $b.AutoSize = $true
    $b.Tag = @{ X = $qx; Y = $qy; Label = $label }
    $b.Add_Click({
        param($sender, $e)
        $t = $sender.Tag
        $xBox.Value = [decimal]$t.X
        $yBox.Value = [decimal]$t.Y
        Write-UiLog ("set ({0},{1}) - {2}" -f $t.X, $t.Y, $t.Label)
    })
    $quick.Controls.Add($b) | Out-Null
}
Add-Quick "(1,483) working" 1 483
Add-Quick "(8,617) working" 8 617
Add-Quick "band center (4,550)" 4 550

$log = New-Object System.Windows.Forms.TextBox
$log.Multiline = $true; $log.ScrollBars = "Vertical"; $log.ReadOnly = $true
$log.Location = New-Object System.Drawing.Point(16, 146)
$log.Size = New-Object System.Drawing.Size(414, 110)
$form.Controls.Add($log)

function Write-UiLog([string]$msg) {
    $log.AppendText(("[{0}] {1}`r`n" -f (Get-Date -Format "HH:mm:ss"), $msg))
}

function Current-Serial {
    $t = $emuBox.Text.Trim()
    if (-not $t) { throw "Select an emulator." }
    if ($Known.Contains($t)) { return [string]$Known[$t] }
    # "label (serial)" or raw serial
    if ($t -match '\(([^)]+)\)\s*$') { return $Matches[1].Trim() }
    return $t
}

$refreshBtn.Add_Click({
    try {
        $devs = @(Get-Devices -AdbPath $adbPath)
        $prev = $emuBox.Text
        $emuBox.Items.Clear()
        foreach ($label in $Known.Keys) {
            $ser = $Known[$label]
            $mark = if ($devs -contains $ser) { "" } else { "  [not connected]" }
            [void]$emuBox.Items.Add($label + $mark)
        }
        foreach ($d in $devs) {
            if (-not ($Known.Values -contains $d)) {
                [void]$emuBox.Items.Add($d)
            }
        }
        if ($Serial) {
            $match = $Known.GetEnumerator() | Where-Object { $_.Value -eq $Serial } | Select-Object -First 1
            if ($match) { $emuBox.Text = $match.Key }
            else { $emuBox.Text = $Serial }
        } elseif ($prev) {
            $emuBox.Text = $prev -replace '\s+\[not connected\]$', ''
        } elseif ($emuBox.Items.Count -gt 0) {
            $emuBox.SelectedIndex = 0
        }
        Write-UiLog ("online: " + ($(if ($devs.Count) { $devs -join ", " } else { "none" })))
    } catch {
        Write-UiLog "refresh failed: $($_.Exception.Message)"
    }
})

$tapBtn = New-Object System.Windows.Forms.Button
$tapBtn.Text = "TAP"
$tapBtn.Font = New-Object System.Drawing.Font @("Segoe UI", 12.0, [System.Drawing.FontStyle]::Bold)
$tapBtn.Location = New-Object System.Drawing.Point(16, 270)
$tapBtn.Size = New-Object System.Drawing.Size(200, 40)
$form.Controls.Add($tapBtn)

$capBtn = New-Object System.Windows.Forms.Button
$capBtn.Text = "Capture mark"
$capBtn.Location = New-Object System.Drawing.Point(230, 270)
$capBtn.Size = New-Object System.Drawing.Size(200, 40)
$form.Controls.Add($capBtn)

$tapBtn.Add_Click({
    try {
        $ser = Current-Serial
        $tx = [int]$xBox.Value
        $ty = [int]$yBox.Value
        Ensure-Connected -AdbPath $adbPath -Device $ser
        Send-Tap -AdbPath $adbPath -Device $ser -TapX $tx -TapY $ty
        Write-UiLog "TAP ($tx,$ty) -> $ser"
    } catch {
        Write-UiLog "FAILED: $($_.Exception.Message)"
    }
})

$capBtn.Add_Click({
    try {
        $ser = Current-Serial
        $tx = [int]$xBox.Value
        $ty = [int]$yBox.Value
        Ensure-Connected -AdbPath $adbPath -Device $ser
        $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'")
        $safe = ($ser -replace '[^\d]', '-')
        $raw = Join-Path $SnapDir "$stamp-$safe-click-raw.png"
        $over = Join-Path $SnapDir "$stamp-$safe-click-mark.png"
        Capture-Frame -AdbPath $adbPath -Device $ser -OutPng $raw
        Show-Overlay -Png $raw -TapX $tx -TapY $ty -OutPng $over
        Write-UiLog "marked ($tx,$ty) -> $over"
    } catch {
        Write-UiLog "FAILED: $($_.Exception.Message)"
    }
})

# Enter key taps
$form.AcceptButton = $tapBtn

$refreshBtn.PerformClick()
Write-UiLog "Set X/Y, pick emulator, press TAP (or Enter). Coords are 720x1280 device pixels."

[void]$form.ShowDialog()
