Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class WinFgTpl {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);
  public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
}
"@

Get-CimInstance Win32_Process -Filter "Name='powershell.exe' OR Name='pwsh.exe'" | Where-Object {
    $_.CommandLine -match 'TemplateCrop\.ps1'
} | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 1

$p = Start-Process -FilePath powershell.exe -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass',
    '-File', (Join-Path $PSScriptRoot 'TemplateCrop.ps1')
) -PassThru

for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 500
    $proc = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
    if ($proc -and $proc.MainWindowHandle -ne [IntPtr]::Zero) {
        [WinFgTpl]::ShowWindow($proc.MainWindowHandle, 9) | Out-Null
        [WinFgTpl]::SetWindowPos(
            $proc.MainWindowHandle, [WinFgTpl]::HWND_TOPMOST,
            0, 0, 0, 0, 0x0001 -bor 0x0002 -bor 0x0040) | Out-Null
        [WinFgTpl]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
        Write-Output "Foreground PID=$($p.Id) title='$($proc.MainWindowTitle)'"
        exit 0
    }
}
Write-Output "Started PID=$($p.Id) (window handle not ready)"
