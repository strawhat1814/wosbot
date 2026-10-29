#Requires -Version 5.1
<#
.SYNOPSIS
  Capture an emulator frame, compare it to an existing template, then crop a replacement.

.DESCRIPTION
  Pick an emulator and Capture ADB for a live frame. Pick a TemplatesEnum / PNG from the
  catalog to show the previous template beside the live frame so you can decide whether
  it needs replacing. Drag a green rectangle on the live frame to crop; Save Crop writes
  over the selected template path (with confirm) or a custom/mercenary preset.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\template-crop\TemplateCrop.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\template-crop\TemplateCrop.ps1 -Serial 127.0.0.1:5567
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [string]$Adb = "",
    [string]$Image = "",
    [string]$TemplateKey = "",
    [string]$RepoRoot = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}
$GarbageDir = Join-Path $RepoRoot ".garbage\emulator-snapshot"
$TemplatesRoot = Join-Path $RepoRoot "modules\vision\src\main\resources"
$TemplatesProps = Join-Path $RepoRoot "modules\api\src\main\resources\config\templates.properties"
$MercDir = Join-Path $TemplatesRoot "templates\mercenary"
New-Item -ItemType Directory -Force -Path $GarbageDir | Out-Null
New-Item -ItemType Directory -Force -Path $MercDir | Out-Null

# Last-12h Main A/B/C: only templates that missed on the right screen with a
# routine failure (found=0 alone is not enough). See game-ui-update-tracking.md.
$TrackingKeys = @(
    "GAME_HOME_FURNACE",
    "GAME_HOME_WORLD",
    "ALLIANCE_SHOP_ADVANCED_TELEPORT",
    "POLAR_TERROR_TAB_SPECIAL_REWARDS",
    "GAME_HOME_SHORTCUTS_FARM_COAL"
)

function Find-Adb {
    param([string]$Preferred)
    if ($Preferred -and (Test-Path $Preferred)) { return $Preferred }
    $candidates = @(
        (Join-Path $RepoRoot "tools\adb\adb.exe"),
        "C:\LDPlayer\LDPlayer9\adb.exe",
        "C:\leidian\LDPlayer9\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "C:\Program Files\Netease\MuMuPlayer\nx_main\adb.exe",
        "C:\Program Files\Netease\MuMuPlayerGlobal\nx_main\adb.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    $where = & where.exe adb 2>$null | Select-Object -First 1
    if ($where) { return $where }
    return $null
}

function Get-LdIndexFromSerial {
    param([string]$DeviceSerial)
    if ($DeviceSerial -match '127\.0\.0\.1:(\d+)$') {
        $port = [int]$Matches[1]
        if (($port - 5555) % 2 -eq 0) { return [int](($port - 5555) / 2) }
    }
    if ($DeviceSerial -match '^emulator-(\d+)$') {
        $port = [int]$Matches[1]
        if (($port - 5554) % 2 -eq 0) { return [int](($port - 5554) / 2) }
    }
    return $null
}

function Get-AdbDeviceSerials {
    param([string]$AdbPath)
    if (-not $AdbPath) { return @() }
    $lines = & $AdbPath devices | Where-Object { $_ -match "\sdevice$" -and $_ -notmatch "^List" }
    $serials = @()
    foreach ($line in $lines) {
        $serial = ($line -split "\s+")[0]
        if ($serial) { $serials += $serial }
    }
    $tcp = @($serials | Where-Object { $_ -like "127.0.0.1:*" } | Sort-Object)
    if ($tcp.Count -gt 0) { return $tcp }
    return @($serials | Sort-Object)
}

function Format-DeviceLabel {
    param([string]$DeviceSerial)
    $idx = Get-LdIndexFromSerial -DeviceSerial $DeviceSerial
    if ($null -ne $idx) {
        return "$DeviceSerial  (LD index $idx)"
    }
    return $DeviceSerial
}

function Capture-Frame {
    param([string]$AdbPath, [string]$DeviceSerial)
    $stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMdd'T'HHmmss'Z'")
    $safe = ($DeviceSerial -replace '[:.]', '-')
    $remote = "/sdcard/frostguard-adb-capture.png"
    $tmp = Join-Path $env:TEMP "frostguard-template-crop.png"
    $dest = Join-Path $GarbageDir "$stamp-$safe-live-adb.png"
    & $AdbPath -s $DeviceSerial shell screencap -p $remote
    if ($LASTEXITCODE -ne 0) { throw "screencap failed on $DeviceSerial" }
    & $AdbPath -s $DeviceSerial pull $remote $tmp | Out-Null
    & $AdbPath -s $DeviceSerial shell rm $remote | Out-Null
    Copy-Item $tmp $dest -Force
    return $dest
}

function Get-TemplateCatalog {
    $byKey = @{}
    if (Test-Path $TemplatesProps) {
        foreach ($line in Get-Content -LiteralPath $TemplatesProps) {
            $trim = $line.Trim()
            if (-not $trim -or $trim.StartsWith("#") -or $trim -notmatch "=") { continue }
            $parts = $trim -split "=", 2
            $key = $parts[0].Trim()
            $rel = $parts[1].Trim().TrimStart("/")
            if (-not $key -or -not $rel) { continue }
            $full = Join-Path $TemplatesRoot ($rel -replace "/", [IO.Path]::DirectorySeparatorChar)
            $byKey[$key] = [pscustomobject]@{
                Key      = $key
                RelPath  = $rel
                FullPath = $full
                Exists   = (Test-Path -LiteralPath $full)
                Label    = if (Test-Path -LiteralPath $full) { "$key  ($rel)" } else { "$key  ($rel) [MISSING FILE]" }
            }
        }
    }
    return $byKey
}

$script:AdbPath = Find-Adb -Preferred $Adb
$script:DeviceSerial = $Serial
$script:DeviceLabels = @{}
$script:TemplateCatalog = Get-TemplateCatalog
$script:SelectedTemplate = $null

$script:SourcePath = $null
$script:SourceBitmap = $null
$script:RefBitmap = $null
$script:DragStart = $null
$script:SelRect = [System.Drawing.Rectangle]::Empty
$script:SquareMode = $false

$form = New-Object System.Windows.Forms.Form
$form.Text = "Frostguard Template Capture Assist"
$form.Width = 1280
$form.Height = 920
$form.StartPosition = "CenterScreen"
$form.TopMost = $true
$form.KeyPreview = $true
$form.Add_Shown({
    $form.Activate()
    $form.BringToFront()
})

$top = New-Object System.Windows.Forms.FlowLayoutPanel
$top.Dock = "Top"
$top.Height = 120
$top.Padding = New-Object System.Windows.Forms.Padding(6)
$form.Controls.Add($top)

function New-Btn([string]$text) {
    $b = New-Object System.Windows.Forms.Button
    $b.Text = $text
    $b.AutoSize = $true
    $b.Margin = New-Object System.Windows.Forms.Padding(4)
    return $b
}

$lblEmu = New-Object System.Windows.Forms.Label
$lblEmu.Text = "Emulator:"
$lblEmu.AutoSize = $true
$lblEmu.Margin = New-Object System.Windows.Forms.Padding(4, 12, 0, 4)

$deviceCombo = New-Object System.Windows.Forms.ComboBox
$deviceCombo.DropDownStyle = "DropDownList"
$deviceCombo.Width = 300
$deviceCombo.Margin = New-Object System.Windows.Forms.Padding(4)

$btnRefreshDevices = New-Btn "Refresh devices"
$btnCapture = New-Btn "Capture ADB"
$btnOpen = New-Btn "Open PNG"
$btnSave = New-Btn "Save Crop"
$btnClear = New-Btn "Clear"
$chkSquare = New-Object System.Windows.Forms.CheckBox
$chkSquare.Text = "Force square"
$chkSquare.AutoSize = $true
$chkSquare.Margin = New-Object System.Windows.Forms.Padding(8, 10, 4, 4)

$lblTpl = New-Object System.Windows.Forms.Label
$lblTpl.Text = "Previous template:"
$lblTpl.AutoSize = $true
$lblTpl.Margin = New-Object System.Windows.Forms.Padding(12, 12, 0, 4)

$templateCombo = New-Object System.Windows.Forms.ComboBox
$templateCombo.DropDownStyle = "DropDown"
$templateCombo.Width = 520
$templateCombo.Margin = New-Object System.Windows.Forms.Padding(4)
$templateCombo.AutoCompleteMode = "SuggestAppend"
$templateCombo.AutoCompleteSource = "ListItems"

$btnReveal = New-Btn "Reveal file"
$btnTrackingOnly = New-Btn "Tracking list"
$btnCharOcr = New-Btn "Char name/ID OCR"
$btnSidebar = New-Btn "Sidebar regions"

$lblSaveMode = New-Object System.Windows.Forms.Label
$lblSaveMode.Text = "Save mode:"
$lblSaveMode.AutoSize = $true
$lblSaveMode.Margin = New-Object System.Windows.Forms.Padding(12, 12, 0, 4)

$preset = New-Object System.Windows.Forms.ComboBox
$preset.DropDownStyle = "DropDownList"
$preset.Width = 400
$preset.Margin = New-Object System.Windows.Forms.Padding(4)
@(
    "overwrite-selected-template",
    "mercenaryWorldSummon.png  (mercenary folder)",
    "mercenaryWorldSummon2.png  (mercenary folder)",
    "mercenaryWorldSummon3.png  (mercenary folder)",
    "mercenaryEventAskHelp.png  (mercenary folder)",
    "sidebar-left-menu-trigger  (region only)",
    "sidebar-left-menu-fallback  (region only)",
    "sidebar-left-menu-close  (region only)",
    "ocr-character-name  (region only)",
    "ocr-character-id  (region only)",
    "ocr-pre-scout  (region only)",
    "ocr-pre-attack  (region only)",
    "custom-name.png"
) | ForEach-Object { [void]$preset.Items.Add($_) }
$preset.SelectedIndex = 0

# Current CommonGameAreas seeds (x1,y1,x2,y2) - green box after Capture = "previous" coords.
$script:OcrRegionSeeds = @{
    "ocr-character-name" = @(281, 831, 610, 884)
    "ocr-character-id"   = @(275, 901, 462, 932)
    "sidebar-left-menu-trigger"  = @(1, 483, 8, 617)
    "sidebar-left-menu-fallback" = @(1, 483, 8, 617)
    "sidebar-left-menu-close"    = @(450, 510, 482, 594)
}
$script:RegionCropNames = @{
    "ocr-character-name" = "crop-characterNameOcr.png"
    "ocr-character-id"   = "crop-characterIdOcr.png"
    "sidebar-left-menu-trigger"  = "crop-sidebarLeftMenuTrigger.png"
    "sidebar-left-menu-fallback" = "crop-sidebarLeftMenuFallback.png"
    "sidebar-left-menu-close"    = "crop-sidebarLeftMenuClose.png"
}
$script:RegionJavaNames = @{
    "ocr-character-name" = "CHARACTER_NAME_OCR_AREA"
    "ocr-character-id"   = "CHARACTER_ID_OCR_AREA"
    "sidebar-left-menu-trigger"  = "LEFT_MENU_TRIGGER"
    "sidebar-left-menu-fallback" = "LEFT_MENU_TRIGGER_FALLBACK"
    "sidebar-left-menu-close"    = "LEFT_MENU_CLOSE"
}

$top.Controls.AddRange(@(
    $lblEmu, $deviceCombo, $btnRefreshDevices,
    $btnCapture, $btnOpen, $btnSave, $btnClear, $chkSquare
))
$top.SetFlowBreak($chkSquare, $true)
$top.Controls.AddRange(@(
    $lblTpl, $templateCombo, $btnTrackingOnly, $btnCharOcr, $btnSidebar, $btnReveal
))
$top.SetFlowBreak($btnReveal, $true)
$top.Controls.AddRange(@(
    $lblSaveMode, $preset
))

$split = New-Object System.Windows.Forms.SplitContainer
$split.Dock = "Fill"
$split.Orientation = "Vertical"
$split.SplitterDistance = 360
$form.Controls.Add($split)
$split.BringToFront()

$refPanel = New-Object System.Windows.Forms.Panel
$refPanel.Dock = "Fill"
$split.Panel1.Controls.Add($refPanel)

$refTitle = New-Object System.Windows.Forms.Label
$refTitle.Dock = "Top"
$refTitle.Height = 28
$refTitle.TextAlign = "MiddleLeft"
$refTitle.Padding = New-Object System.Windows.Forms.Padding(8, 0, 0, 0)
$refTitle.Text = "Previous template (pick above before capturing)"
$refPanel.Controls.Add($refTitle)

$refPicture = New-Object System.Windows.Forms.PictureBox
$refPicture.Dock = "Fill"
$refPicture.SizeMode = "Zoom"
$refPicture.BackColor = [System.Drawing.Color]::FromArgb(40, 40, 40)
$refPanel.Controls.Add($refPicture)
$refPicture.BringToFront()

$livePanel = New-Object System.Windows.Forms.Panel
$livePanel.Dock = "Fill"
$split.Panel2.Controls.Add($livePanel)

$liveTitle = New-Object System.Windows.Forms.Label
$liveTitle.Dock = "Top"
$liveTitle.Height = 28
$liveTitle.TextAlign = "MiddleLeft"
$liveTitle.Padding = New-Object System.Windows.Forms.Padding(8, 0, 0, 0)
$liveTitle.Text = "Live emulator / crop target  -  drag green box, then Save Crop"
$livePanel.Controls.Add($liveTitle)

$picture = New-Object System.Windows.Forms.PictureBox
$picture.Dock = "Fill"
$picture.SizeMode = "Zoom"
$picture.BackColor = [System.Drawing.Color]::DimGray
$livePanel.Controls.Add($picture)
$picture.BringToFront()

$status = New-Object System.Windows.Forms.Label
$status.Dock = "Bottom"
$status.Height = 56
$status.Padding = New-Object System.Windows.Forms.Padding(8)
$status.Text = "1) Pick previous template  2) Pick emulator  3) Capture ADB  4) Compare  5) Crop + Save if needed"
$form.Controls.Add($status)

function Populate-TemplateCombo {
    param([bool]$TrackingOnly)
    $templateCombo.Items.Clear()
    $keys = @()
    if ($TrackingOnly) {
        foreach ($k in $TrackingKeys) {
            if ($script:TemplateCatalog.ContainsKey($k)) { $keys += $k }
        }
    } else {
        # Tracking keys first, then the rest A-Z
        $seen = @{}
        foreach ($k in $TrackingKeys) {
            if ($script:TemplateCatalog.ContainsKey($k)) {
                $keys += $k
                $seen[$k] = $true
            }
        }
        foreach ($k in ($script:TemplateCatalog.Keys | Sort-Object)) {
            if (-not $seen.ContainsKey($k)) { $keys += $k }
        }
    }
    foreach ($k in $keys) {
        [void]$templateCombo.Items.Add($script:TemplateCatalog[$k].Label)
    }
}

function Resolve-TemplateFromCombo {
    $text = [string]$templateCombo.Text
    if (-not $text) { return $null }
    $key = ($text -split "\s+")[0]
    if ($script:TemplateCatalog.ContainsKey($key)) {
        return $script:TemplateCatalog[$key]
    }
    return $null
}

function Load-ReferenceTemplate {
    $entry = Resolve-TemplateFromCombo
    $script:SelectedTemplate = $entry
    if ($script:RefBitmap) {
        $refPicture.Image = $null
        $script:RefBitmap.Dispose()
        $script:RefBitmap = $null
    }
    if (-not $entry) {
        $refTitle.Text = "Previous template  -  none selected"
        Update-Status
        return
    }
    if (-not $entry.Exists) {
        $refTitle.Text = "Previous template MISSING FILE: $($entry.RelPath)"
        Update-Status
        return
    }
    $loaded = [System.Drawing.Image]::FromFile($entry.FullPath)
    $script:RefBitmap = New-Object System.Drawing.Bitmap $loaded
    $loaded.Dispose()
    $refPicture.Image = $script:RefBitmap
    $refTitle.Text = "Previous: $($entry.Key)  $($script:RefBitmap.Width)x$($script:RefBitmap.Height)  $($entry.RelPath)"
    Update-Status
}

function Sync-DeviceSerialFromCombo {
    $label = [string]$deviceCombo.SelectedItem
    if (-not $label) {
        $script:DeviceSerial = $null
        return
    }
    if ($script:DeviceLabels.ContainsKey($label)) {
        $script:DeviceSerial = $script:DeviceLabels[$label]
    } else {
        $script:DeviceSerial = ($label -split "\s+")[0]
    }
    Update-Status
}

function Refresh-DeviceList {
    $deviceCombo.Items.Clear()
    $script:DeviceLabels = @{}
    if (-not $script:AdbPath) {
        Update-Status
        return
    }
    $serials = Get-AdbDeviceSerials -AdbPath $script:AdbPath
    $preferred = $script:DeviceSerial
    $selectIndex = 0
    for ($i = 0; $i -lt $serials.Count; $i++) {
        $serial = $serials[$i]
        $label = Format-DeviceLabel -DeviceSerial $serial
        $script:DeviceLabels[$label] = $serial
        [void]$deviceCombo.Items.Add($label)
        if ($preferred -and $serial -eq $preferred) {
            $selectIndex = $i
        }
    }
    if ($deviceCombo.Items.Count -gt 0) {
        $deviceCombo.SelectedIndex = $selectIndex
        Sync-DeviceSerialFromCombo
    } else {
        $script:DeviceSerial = $null
        Update-Status
    }
}

function Update-Status {
    $serialText = if ($script:DeviceSerial) { $script:DeviceSerial } else { "(no device)" }
    $adbText = if ($script:AdbPath) { Split-Path $script:AdbPath -Leaf } else { "adb missing" }
    $tplText = if ($script:SelectedTemplate) { $script:SelectedTemplate.Key } else { "(no template)" }
    $base = "adb=$adbText  serial=$serialText  template=$tplText"
    if ($script:SourcePath) {
        $base += "  live=$(Split-Path $script:SourcePath -Leaf)"
    }
    if ($script:SelRect.Width -gt 0 -and $script:SelRect.Height -gt 0) {
        $r = $script:SelRect
        $x2 = $r.X + $r.Width
        $y2 = $r.Y + $r.Height
        $base += "  |  region($($r.X), $($r.Y), $x2, $y2)  size=$($r.Width)x$($r.Height)"
    }
    $status.Text = $base
}

function Get-OcrPresetKey {
    $choice = [string]$preset.SelectedItem
    if ($choice -like "ocr-character-name*") { return "ocr-character-name" }
    if ($choice -like "ocr-character-id*") { return "ocr-character-id" }
    if ($choice -like "sidebar-left-menu-trigger*") { return "sidebar-left-menu-trigger" }
    if ($choice -like "sidebar-left-menu-fallback*") { return "sidebar-left-menu-fallback" }
    if ($choice -like "sidebar-left-menu-close*") { return "sidebar-left-menu-close" }
    return $null
}

function Load-RegionReferenceCrop([string]$key) {
    if (-not $key -or -not $script:RegionCropNames.ContainsKey($key)) { return }
    $cropPath = Join-Path $GarbageDir $script:RegionCropNames[$key]
    $prevPath = Join-Path $GarbageDir ("prev-" + $script:RegionCropNames[$key])
    $path = if (Test-Path $prevPath) { $prevPath } elseif (Test-Path $cropPath) { $cropPath } else { $null }
    if (-not $path) {
        $refTitle.Text = "Previous region crop missing - Capture then Save once to create it"
        if ($refPicture.Image) { $refPicture.Image.Dispose(); $refPicture.Image = $null }
        return
    }
    if ($refPicture.Image) { $refPicture.Image.Dispose(); $refPicture.Image = $null }
    $loaded = [System.Drawing.Image]::FromFile($path)
    $bmp = New-Object System.Drawing.Bitmap $loaded
    $loaded.Dispose()
    $refPicture.Image = $bmp
    $seed = $script:OcrRegionSeeds[$key]
    $refTitle.Text = "Previous ($key): $(Split-Path $path -Leaf)  seed=region($($seed[0]), $($seed[1]), $($seed[2]), $($seed[3]))"
}

function Apply-OcrRegionSeed {
    $key = Get-OcrPresetKey
    if (-not $key -or -not $script:SourceBitmap) { return $false }
    if (-not $script:OcrRegionSeeds.ContainsKey($key)) { return $false }
    Load-RegionReferenceCrop $key
    $c = $script:OcrRegionSeeds[$key]
    $x1 = [int]$c[0]; $y1 = [int]$c[1]; $x2 = [int]$c[2]; $y2 = [int]$c[3]
    $w = [Math]::Max(1, $x2 - $x1)
    $h = [Math]::Max(1, $y2 - $y1)
    if ($x1 -ge $script:SourceBitmap.Width -or $y1 -ge $script:SourceBitmap.Height) { return $false }
    $w = [Math]::Min($w, $script:SourceBitmap.Width - $x1)
    $h = [Math]::Min($h, $script:SourceBitmap.Height - $y1)
    $script:SelRect = New-Object System.Drawing.Rectangle $x1, $y1, $w, $h
    $liveTitle.Text = "Live seed ($key): region($x1, $y1, $x2, $y2)  -  drag to adjust, then Save Crop"
    Update-Status
    $picture.Invalidate()
    return $true
}

function Save-RegionPresetCrop([string]$key, [string]$regionLine) {
    $javaName = $script:RegionJavaNames[$key]
    $cropName = $script:RegionCropNames[$key]
    $dest = Join-Path $GarbageDir $cropName
    $prev = Join-Path $GarbageDir ("prev-" + $cropName)
    if ((Test-Path $dest) -and -not (Test-Path $prev)) {
        Copy-Item -LiteralPath $dest -Destination $prev -Force
    } elseif (Test-Path $dest) {
        Copy-Item -LiteralPath $dest -Destination $prev -Force
    }
    $crop = $script:SourceBitmap.Clone($script:SelRect, $script:SourceBitmap.PixelFormat)
    $crop.Save($dest, [System.Drawing.Imaging.ImageFormat]::Png)
    $crop.Dispose()
    $snippet = "public static final AreaData $javaName = $regionLine;"
    Set-Clipboard $snippet
    Write-Host ("{0} = {1}" -f $javaName, $regionLine)
    Load-RegionReferenceCrop $key
    [System.Windows.Forms.MessageBox]::Show(
        "Copied CommonGameAreas snippet:`n$snippet`n`nSaved crop:`n$dest`nPrevious kept as:`n$prev",
        "Region saved") | Out-Null
}

function Load-Image([string]$path) {
    if ($script:SourceBitmap) {
        $picture.Image = $null
        $script:SourceBitmap.Dispose()
        $script:SourceBitmap = $null
    }
    $script:SourcePath = $path
    $loaded = [System.Drawing.Image]::FromFile($path)
    $script:SourceBitmap = New-Object System.Drawing.Bitmap $loaded
    $loaded.Dispose()
    $picture.Image = $script:SourceBitmap
    $script:SelRect = [System.Drawing.Rectangle]::Empty
    $liveTitle.Text = "Live: $(Split-Path $path -Leaf)  $($script:SourceBitmap.Width)x$($script:SourceBitmap.Height)  -  drag green box to crop"
    if (-not (Apply-OcrRegionSeed)) {
        Update-Status
        $picture.Invalidate()
    }
}

function Get-ImageRectOnControl {
    if (-not $script:SourceBitmap) { return [System.Drawing.Rectangle]::Empty }
    $cw = [double]$picture.ClientSize.Width
    $ch = [double]$picture.ClientSize.Height
    $iw = [double]$script:SourceBitmap.Width
    $ih = [double]$script:SourceBitmap.Height
    if ($cw -le 0 -or $ch -le 0) { return [System.Drawing.Rectangle]::Empty }
    $scale = [Math]::Min($cw / $iw, $ch / $ih)
    $dw = $iw * $scale
    $dh = $ih * $scale
    $ox = ($cw - $dw) / 2.0
    $oy = ($ch - $dh) / 2.0
    return New-Object System.Drawing.Rectangle ([int]$ox), ([int]$oy), ([int]$dw), ([int]$dh)
}

function ControlToImage([System.Drawing.Point]$pt) {
    $disp = Get-ImageRectOnControl
    if ($disp.Width -le 0 -or -not $script:SourceBitmap) {
        return [System.Drawing.Point]::Empty
    }
    $nx = ($pt.X - $disp.X) / [double]$disp.Width
    $ny = ($pt.Y - $disp.Y) / [double]$disp.Height
    $nx = [Math]::Max(0.0, [Math]::Min(1.0, $nx))
    $ny = [Math]::Max(0.0, [Math]::Min(1.0, $ny))
    $x = [int][Math]::Floor($nx * $script:SourceBitmap.Width)
    $y = [int][Math]::Floor($ny * $script:SourceBitmap.Height)
    if ($x -ge $script:SourceBitmap.Width) { $x = $script:SourceBitmap.Width - 1 }
    if ($y -ge $script:SourceBitmap.Height) { $y = $script:SourceBitmap.Height - 1 }
    return New-Object System.Drawing.Point $x, $y
}

function Normalize-Rect([System.Drawing.Point]$a, [System.Drawing.Point]$b, [bool]$square) {
    $x1 = [Math]::Min($a.X, $b.X)
    $y1 = [Math]::Min($a.Y, $b.Y)
    $x2 = [Math]::Max($a.X, $b.X)
    $y2 = [Math]::Max($a.Y, $b.Y)
    $w = [Math]::Max(1, $x2 - $x1)
    $h = [Math]::Max(1, $y2 - $y1)
    if ($square) {
        $side = [Math]::Max($w, $h)
        if ($b.X -ge $a.X) { $x2 = $x1 + $side } else { $x1 = $x2 - $side }
        if ($b.Y -ge $a.Y) { $y2 = $y1 + $side } else { $y1 = $y2 - $side }
        if ($x1 -lt 0) { $x2 -= $x1; $x1 = 0 }
        if ($y1 -lt 0) { $y2 -= $y1; $y1 = 0 }
        if ($script:SourceBitmap) {
            if ($x2 -gt $script:SourceBitmap.Width) {
                $x1 -= ($x2 - $script:SourceBitmap.Width)
                $x2 = $script:SourceBitmap.Width
            }
            if ($y2 -gt $script:SourceBitmap.Height) {
                $y1 -= ($y2 - $script:SourceBitmap.Height)
                $y2 = $script:SourceBitmap.Height
            }
            if ($x1 -lt 0) { $x1 = 0 }
            if ($y1 -lt 0) { $y1 = 0 }
        }
        $w = [Math]::Max(1, $x2 - $x1)
        $h = [Math]::Max(1, $y2 - $y1)
    }
    return New-Object System.Drawing.Rectangle $x1, $y1, $w, $h
}

$picture.Add_Paint({
    param($sender, $e)
    if ($script:SelRect.Width -le 0 -or -not $script:SourceBitmap) { return }
    $disp = Get-ImageRectOnControl
    if ($disp.Width -le 0) { return }
    $sx = $disp.Width / [double]$script:SourceBitmap.Width
    $sy = $disp.Height / [double]$script:SourceBitmap.Height
    $rx = $disp.X + [int]($script:SelRect.X * $sx)
    $ry = $disp.Y + [int]($script:SelRect.Y * $sy)
    $rw = [Math]::Max(1, [int]($script:SelRect.Width * $sx))
    $rh = [Math]::Max(1, [int]($script:SelRect.Height * $sy))
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::Lime), 2
    $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(60, 0, 255, 0))
    $e.Graphics.FillRectangle($brush, $rx, $ry, $rw, $rh)
    $e.Graphics.DrawRectangle($pen, $rx, $ry, $rw, $rh)
    $pen.Dispose()
    $brush.Dispose()
})

$picture.Add_MouseDown({
    param($sender, $e)
    if (-not $script:SourceBitmap -or $e.Button -ne [System.Windows.Forms.MouseButtons]::Left) { return }
    $script:SquareMode = $chkSquare.Checked -or ([System.Windows.Forms.Control]::ModifierKeys -band [System.Windows.Forms.Keys]::Shift)
    $script:DragStart = ControlToImage $e.Location
    $script:SelRect = New-Object System.Drawing.Rectangle $script:DragStart.X, $script:DragStart.Y, 1, 1
    Update-Status
    $picture.Invalidate()
})

$picture.Add_MouseMove({
    param($sender, $e)
    if ($null -eq $script:DragStart -or $e.Button -ne [System.Windows.Forms.MouseButtons]::Left) { return }
    $cur = ControlToImage $e.Location
    $script:SquareMode = $chkSquare.Checked -or ([System.Windows.Forms.Control]::ModifierKeys -band [System.Windows.Forms.Keys]::Shift)
    $script:SelRect = Normalize-Rect $script:DragStart $cur $script:SquareMode
    Update-Status
    $picture.Invalidate()
})

$picture.Add_MouseUp({
    param($sender, $e)
    if ($null -eq $script:DragStart) { return }
    $cur = ControlToImage $e.Location
    $script:SelRect = Normalize-Rect $script:DragStart $cur ($chkSquare.Checked -or $script:SquareMode)
    $script:DragStart = $null
    Update-Status
    $picture.Invalidate()
    if ($script:SelRect.Width -gt 0) {
        $r = $script:SelRect
        $line = "region($($r.X), $($r.Y), $($r.X + $r.Width), $($r.Y + $r.Height))"
        Write-Host $line
        try { Set-Clipboard $line } catch { }
    }
})

$deviceCombo.Add_SelectedIndexChanged({ Sync-DeviceSerialFromCombo })
$templateCombo.Add_SelectedIndexChanged({ Load-ReferenceTemplate })
$preset.Add_SelectedIndexChanged({
    $key = Get-OcrPresetKey
    if ($key) {
        [void](Apply-OcrRegionSeed)
        if ($key -like "sidebar-*") {
            $status.Text = "Sidebar: World map with 5+ marches, Capture ADB, adjust green box (old seed), Save Crop"
        } else {
            $status.Text = "OCR mode: open avatar/profile sheet on emu, Capture ADB, adjust green box, Save Crop"
        }
    }
})
$templateCombo.Add_Leave({ Load-ReferenceTemplate })

$btnRefreshDevices.Add_Click({
    try {
        Refresh-DeviceList
    } catch {
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, "Refresh failed") | Out-Null
    }
})

$btnTrackingOnly.Add_Click({
    Populate-TemplateCombo -TrackingOnly $true
    if ($templateCombo.Items.Count -gt 0) {
        $templateCombo.SelectedIndex = 0
        Load-ReferenceTemplate
    }
})

$btnCharOcr.Add_Click({
    for ($i = 0; $i -lt $preset.Items.Count; $i++) {
        if ([string]$preset.Items[$i] -like "ocr-character-name*") {
            $preset.SelectedIndex = $i
            break
        }
    }
    [void](Apply-OcrRegionSeed)
    [System.Windows.Forms.MessageBox]::Show(
        "Save mode is now 'ocr-character-name'.`n`n1) Open avatar/profile sheet on the emulator`n2) Capture ADB`n3) Adjust the green box over the NAME only`n4) Save Crop (copies region snippet)`n`nThen switch Save mode to 'ocr-character-id' for the ID line.",
        "Character OCR regions") | Out-Null
})

$btnSidebar.Add_Click({
    for ($i = 0; $i -lt $preset.Items.Count; $i++) {
        if ([string]$preset.Items[$i] -like "sidebar-left-menu-close*") {
            $preset.SelectedIndex = $i
            break
        }
    }
    Load-RegionReferenceCrop "sidebar-left-menu-close"
    [void](Apply-OcrRegionSeed)
    [System.Windows.Forms.MessageBox]::Show(
        "Save mode: sidebar-left-menu-close (LEFT_MENU_CLOSE)`n`nLeft = previous crop if any`nGreen box = CURRENT code region (450,510)-(482,594)`n`n1) Open the LEFT SIDEBAR on the emulator (so the close arrow is visible)`n2) Capture ADB`n3) Drag green box over the white close arrow/triangle on the panel's right edge`n4) Save Crop (copies CommonGameAreas snippet)`n`nAlso recapture templates GAME_HOME_FURNACE (city.png) and GAME_HOME_WORLD (world.png): pick them in the template list, Capture on City/World, crop, Save.",
        "Sidebar close + City/World anchors") | Out-Null
})

$btnReveal.Add_Click({
    $entry = Resolve-TemplateFromCombo
    if (-not $entry -or -not $entry.Exists) {
        [System.Windows.Forms.MessageBox]::Show("Select a template that exists on disk.", "Reveal") | Out-Null
        return
    }
    Start-Process explorer.exe -ArgumentList "/select,`"$($entry.FullPath)`""
})

$btnCapture.Add_Click({
    try {
        if (-not $script:AdbPath) { throw "adb.exe not found. Pass -Adb path." }
        Sync-DeviceSerialFromCombo
        if (-not $script:DeviceSerial) { Refresh-DeviceList }
        if (-not $script:DeviceSerial) { throw "No emulator selected. Click Refresh devices." }
        $path = Capture-Frame -AdbPath $script:AdbPath -DeviceSerial $script:DeviceSerial
        Load-Image $path
        Write-Host "Captured $($script:DeviceSerial) -> $path"
    } catch {
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, "Capture failed") | Out-Null
    }
})

$btnOpen.Add_Click({
    $dlg = New-Object System.Windows.Forms.OpenFileDialog
    $dlg.Filter = "PNG|*.png|All|*.*"
    $dlg.InitialDirectory = $GarbageDir
    if ($dlg.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        Load-Image $dlg.FileName
    }
})

$btnClear.Add_Click({
    $script:SelRect = [System.Drawing.Rectangle]::Empty
    Update-Status
    $picture.Invalidate()
})

$btnSave.Add_Click({
    try {
        if (-not $script:SourceBitmap) { throw "No live image loaded. Capture ADB first." }
        if ($script:SelRect.Width -le 0 -or $script:SelRect.Height -le 0) { throw "Draw a selection on the live frame first." }
        $choice = [string]$preset.SelectedItem
        $r = $script:SelRect
        $x2 = $r.X + $r.Width
        $y2 = $r.Y + $r.Height
        $regionLine = "region($($r.X), $($r.Y), $x2, $y2)"

        $regionKey = Get-OcrPresetKey
        if ($regionKey -and $script:RegionJavaNames.ContainsKey($regionKey)) {
            Save-RegionPresetCrop -key $regionKey -regionLine $regionLine
            return
        }
        if ($choice -like "ocr-pre-scout*") {
            Write-Host "PRE_SCOUT OCR -> $regionLine"
            Set-Clipboard "MERCENARY_LEVEL_PRE_SCOUT_OCR_AREA = $regionLine"
            [System.Windows.Forms.MessageBox]::Show(
                "Copied CommonGameAreas snippet:`nMERCENARY_LEVEL_PRE_SCOUT_OCR_AREA = $regionLine",
                "OCR region") | Out-Null
            return
        }
        if ($choice -like "ocr-pre-attack*") {
            Write-Host "PRE_ATTACK OCR -> $regionLine"
            Set-Clipboard "MERCENARY_LEVEL_PRE_ATTACK_OCR_AREA = $regionLine"
            [System.Windows.Forms.MessageBox]::Show(
                "Copied CommonGameAreas snippet:`nMERCENARY_LEVEL_PRE_ATTACK_OCR_AREA = $regionLine",
                "OCR region") | Out-Null
            return
        }

        $dest = $null
        if ($choice -eq "overwrite-selected-template") {
            $entry = Resolve-TemplateFromCombo
            if (-not $entry) { throw "Select a Previous template first (or choose custom/mercenary preset)." }
            $dest = $entry.FullPath
            $msg = "Overwrite existing template?`n`n$($entry.Key)`n$dest`n`nCrop $($r.Width)x$($r.Height) from live frame."
            $ans = [System.Windows.Forms.MessageBox]::Show($msg, "Confirm overwrite",
                [System.Windows.Forms.MessageBoxButtons]::YesNo,
                [System.Windows.Forms.MessageBoxIcon]::Warning)
            if ($ans -ne [System.Windows.Forms.DialogResult]::Yes) { return }
            $dir = Split-Path $dest -Parent
            if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        } elseif ($choice -like "custom*") {
            $input = New-Object System.Windows.Forms.Form
            $input.Text = "Template file name"
            $input.Width = 420
            $input.Height = 140
            $input.StartPosition = "CenterParent"
            $tb = New-Object System.Windows.Forms.TextBox
            $tb.Text = "customTemplate.png"
            $tb.Left = 12
            $tb.Top = 16
            $tb.Width = 370
            $ok = New-Object System.Windows.Forms.Button
            $ok.Text = "OK"
            $ok.DialogResult = [System.Windows.Forms.DialogResult]::OK
            $ok.Left = 220
            $ok.Top = 55
            $input.Controls.AddRange(@($tb, $ok))
            $input.AcceptButton = $ok
            if ($input.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return }
            $name = $tb.Text.Trim()
            if (-not $name.EndsWith(".png")) { $name = "$name.png" }
            $dest = Join-Path $MercDir $name
        } else {
            $name = ($choice -split "\s+")[0]
            if (-not $name.EndsWith(".png")) { $name = "$name.png" }
            $dest = Join-Path $MercDir $name
        }

        $crop = $script:SourceBitmap.Clone($script:SelRect, $script:SourceBitmap.PixelFormat)
        $crop.Save($dest, [System.Drawing.Imaging.ImageFormat]::Png)
        $also = Join-Path $GarbageDir ("crop-" + [IO.Path]::GetFileNameWithoutExtension($dest) + ".png")
        $crop.Save($also, [System.Drawing.Imaging.ImageFormat]::Png)
        $crop.Dispose()
        Write-Host "Saved $dest"
        Write-Host $regionLine
        Load-ReferenceTemplate
        [System.Windows.Forms.MessageBox]::Show("Saved:`n$dest`n`n$regionLine", "Crop saved") | Out-Null
    } catch {
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, "Save failed") | Out-Null
    }
})

$form.Add_Shown({
    Populate-TemplateCombo -TrackingOnly $false
    if ($TemplateKey -and $script:TemplateCatalog.ContainsKey($TemplateKey)) {
        $templateCombo.Text = $script:TemplateCatalog[$TemplateKey].Label
        Load-ReferenceTemplate
    } elseif ($templateCombo.Items.Count -gt 0) {
        # Select first tracking key if present
        $firstTracking = $TrackingKeys | Where-Object { $script:TemplateCatalog.ContainsKey($_) } | Select-Object -First 1
        if ($firstTracking) {
            $templateCombo.Text = $script:TemplateCatalog[$firstTracking].Label
            Load-ReferenceTemplate
        }
    }
    Refresh-DeviceList
    Update-Status
    if ($Image -and (Test-Path $Image)) {
        Load-Image (Resolve-Path $Image).Path
    }
})

[void]$form.ShowDialog()
if ($script:SourceBitmap) {
    $picture.Image = $null
    $script:SourceBitmap.Dispose()
}
if ($script:RefBitmap) {
    $refPicture.Image = $null
    $script:RefBitmap.Dispose()
}
