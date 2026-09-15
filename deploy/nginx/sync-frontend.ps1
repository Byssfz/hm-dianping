param(
    [string]$TargetDirectory = ""
)

$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$sourceDirectory = Join-Path $projectRoot "frontend\hmdp"

if ([string]::IsNullOrWhiteSpace($TargetDirectory)) {
    $TargetDirectory = Join-Path $projectRoot "nginx-1.18.0-hmdp\nginx-1.18.0\html\hmdp"
}

$targetFullPath = [System.IO.Path]::GetFullPath($TargetDirectory)
New-Item -ItemType Directory -Path $targetFullPath -Force | Out-Null

Get-ChildItem -LiteralPath $sourceDirectory -Recurse -File | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDirectory.Length).TrimStart('\', '/')
    $targetFile = Join-Path $targetFullPath $relativePath
    $targetParent = Split-Path -Parent $targetFile
    New-Item -ItemType Directory -Path $targetParent -Force | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $targetFile -Force
}

Write-Host "Frontend synchronized to: $targetFullPath"