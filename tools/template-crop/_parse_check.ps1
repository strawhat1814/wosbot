$e = $null
[void][System.Management.Automation.Language.Parser]::ParseFile(
    "$PSScriptRoot\TemplateCrop.ps1",
    [ref]$null,
    [ref]$e)
if ($e) {
    $e | ForEach-Object { Write-Output ("ERR " + $_.Extent.StartLineNumber + ": " + $_.Message) }
    exit 1
}
Write-Output "PARSE OK"
