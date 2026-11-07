# Xray Android kutuphanesini (AndroidLibXrayLite / libv2ray.aar) indirir, SHA256 dogrular -> app\libs\libv2ray.aar
param(
    [string]$Version = "26.10.31",
    [string]$Sha256  = "2efdf731b1ed624eb1cb0d5a4d0aa95c0672e89f9702226d1ba2dc90308630b6"
)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $root "app\libs"
$dest = Join-Path $libs "libv2ray.aar"
$url  = "https://github.com/2dust/AndroidLibXrayLite/releases/download/v$Version/libv2ray.aar"

New-Item -ItemType Directory -Force -Path $libs | Out-Null
$tmp = "$dest.download"
Write-Host "Indiriliyor: $url"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

$hash = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
if ($hash -ne $Sha256.ToLower()) {
    Remove-Item $tmp -Force
    throw "SHA256 uyusmuyor! Beklenen $Sha256, gelen $hash"
}
Move-Item $tmp $dest -Force
Write-Host "Tamam: $dest"
