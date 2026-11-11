# Yeni Android surumu yayinlar: profilsiz APK derler, dogrular, GitHub Release olusturur.
# Kullanim:  .\release.ps1 -Version 1.2.0 [-Notes "Degisiklikler..."]
# Yayinlanan her surum, uygulamayi kullanan herkese otomatik guncelleme olarak gider.
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = ""
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
[Environment]::CurrentDirectory = $PSScriptRoot

if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Surum X.Y.Z biciminde olmali (or. 1.2.0)." }
$tag = "v$Version"
$repo = "ToprakEsen/SchafVPN-Android"

function Invoke-Native([scriptblock]$cmd) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { & $cmd 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $prev }
}

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = "$env:ProgramFiles\GitHub CLI\gh.exe" }
if (-not (Test-Path $gh)) { throw "GitHub CLI (gh) bulunamadi." }

# ---- on kontroller
$ksProps = Join-Path $env:USERPROFILE ".schafvpn\keystore.properties"
if (-not (Test-Path $ksProps)) { throw "Kalici imza anahtari yok ($ksProps). Onsuz yayinlanan APK'lar birbirini guncelleyemez." }
if (git status --porcelain) { throw "Commit edilmemis degisiklikler var. Once commit et (yayin, depodaki kodla ayni olmali)." }
Invoke-Native { & $gh release view $tag --repo $repo } | Out-Null
if ($LASTEXITCODE -eq 0) { throw "$tag zaten yayinlanmis." }
$gp = [IO.File]::ReadAllText("$PSScriptRoot\gradle.properties", [Text.Encoding]::UTF8)
$current = [regex]::Match($gp, 'appVersion=(\S+)').Groups[1].Value
if ([Version]$Version -le [Version]$current) { throw "Yeni surum ($Version) mevcut surumden ($current) buyuk olmali." }

# ---- arac/ortam hazirligi build.ps1 ile ayni (JDK, SDK, libv2ray); derlemeyi burada yapiyoruz
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { (Get-ChildItem "$env:ProgramFiles\Microsoft" -Directory -Filter "jdk-*" | Sort-Object Name -Descending | Select-Object -First 1).FullName }
if (-not (Test-Path "app\libs\libv2ray.aar")) { & "$PSScriptRoot\scripts\get-libv2ray.ps1" }
if (-not (Test-Path "local.properties")) { throw "local.properties yok: once build.ps1'i bir kez calistir." }

# ---- profilsiz derleme
Remove-Item -Recurse -Force "app\build" -ErrorAction SilentlyContinue
Invoke-Native { & "$PSScriptRoot\gradlew.bat" -p "$PSScriptRoot" assembleRelease "-PappVersion=$Version" "-PembedProfile=false" --console=plain --no-daemon } | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { throw "Derleme basarisiz." }
$apkSrc = "app\build\outputs\apk\release\app-release.apk"

# ---- dogrulama: profil yok, dogru surum, kalici anahtarla imzali
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $apkSrc))
try {
    if ($zip.Entries | Where-Object { $_.FullName -like "*profile.txt" }) { throw "GUVENLIK: APK'da profile.txt var! Yayin durduruldu." }
    $local = "app\src\main\assets\profile.txt"
    if (Test-Path $local) {
        $link = Get-Content $local | Where-Object { $_.Trim() -like "vless://*" } | Select-Object -First 1
        if ($link) {
            $u = [Uri]$link.Trim(); $secrets = @([Uri]::UnescapeDataString($u.UserInfo), $u.Host) | Where-Object { $_.Length -ge 4 }
            foreach ($e in $zip.Entries | Where-Object { $_.FullName -match '\.(dex|html|txt|json|xml)$|^assets/' }) {
                $ms = New-Object IO.MemoryStream; $s = $e.Open(); $s.CopyTo($ms); $s.Close()
                $t = [Text.Encoding]::GetEncoding(28591).GetString($ms.ToArray())
                foreach ($sec in $secrets) { if ($t.Contains($sec)) { throw "GUVENLIK: APK'da ($($e.FullName)) kisisel profil bilgisi var! Yayin durduruldu." } }
            }
        }
    }
} finally { $zip.Dispose() }

$sdk = (Get-Content local.properties | Where-Object { $_ -like "sdk.dir=*" }) -replace '^sdk.dir=', '' -replace '\\\\', '\'
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$badging = Invoke-Native { & "$($bt.FullName)\aapt2.exe" dump badging $apkSrc } | Select-Object -First 1
if ($badging -notmatch "versionName='$([regex]::Escape($Version))'") { throw "APK surumu beklenen ($Version) degil: $badging" }
$cert = Invoke-Native { & cmd /c "`"$($bt.FullName)\apksigner.bat`" verify --print-certs `"$apkSrc`"" } | Where-Object { $_ -match 'certificate DN' } | Select-Object -First 1
if ($cert -notmatch 'CN=SchafVPN') { throw "APK kalici anahtarla imzalanmamis: $cert" }

New-Item -ItemType Directory -Force dist-release | Out-Null
Copy-Item $apkSrc "dist-release\SchafVPN.apk" -Force
$sha = (Get-FileHash "dist-release\SchafVPN.apk" -Algorithm SHA256).Hash.ToLower()
[IO.File]::WriteAllText("$PSScriptRoot\dist-release\SchafVPN.apk.sha256", "$sha  SchafVPN.apk`n")
Write-Host "Dogrulandi: profil yok, surum $Version, imza CN=SchafVPN, SHA256 $sha"

# ---- surum numarasini kaydet ve yayinla
$gp = $gp -replace 'appVersion=\S+', "appVersion=$Version"
[IO.File]::WriteAllText("$PSScriptRoot\gradle.properties", $gp, (New-Object Text.UTF8Encoding $false))
Invoke-Native { git add gradle.properties } | Out-Null
Invoke-Native { git commit -q -m "Surum $Version" } | Out-Null
if ($LASTEXITCODE -ne 0) { throw "git commit basarisiz." }
Invoke-Native { git push -q origin HEAD }
if ($LASTEXITCODE -ne 0) { throw "git push basarisiz." }

if (-not $Notes) { $Notes = "SchafVPN Android $Version" }
$Notes += "`n`n**Kurulum:** ``SchafVPN.apk`` dosyasini telefona indirip kur. Ilk acilista VLESS linkini gir.`nOnceki surumu kullananlar bu guncellemeyi uygulama icinden alir."
$branch = git rev-parse --abbrev-ref HEAD
Invoke-Native { & $gh release create $tag "dist-release\SchafVPN.apk" "dist-release\SchafVPN.apk.sha256" --repo $repo --title "SchafVPN Android $Version" --notes $Notes --target $branch }
if ($LASTEXITCODE -ne 0) { throw "GitHub Release olusturulamadi." }
Write-Host ""
Write-Host "Yayinlandi: $tag"
