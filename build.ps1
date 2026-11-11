# SchafVPN Android APK'sini derler -> dist\SchafVPN.apk
# JDK veya Android SDK yoksa yonetici izni gerektirmeden .tools\ klasorune indirir.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$tools = Join-Path $PSScriptRoot ".tools"
$sdkPackages = @("platforms/android-36", "build-tools/36.1.0", "platform-tools")

function Get-Verified([string]$url, [string]$out, [string]$algo, [string]$expected) {
    Write-Host "  Indiriliyor: $url"
    Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
    $h = (Get-FileHash $out -Algorithm $algo).Hash.ToLower()
    if ($h -ne $expected.ToLower()) { Remove-Item $out -Force; throw "$algo uyusmuyor: $url" }
}

# ---------------------------------------------------------------- JDK (17+)
function Find-Jdk {
    $c = @()
    if ($env:JAVA_HOME) { $c += $env:JAVA_HOME }
    $c += Get-ChildItem "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java" `
            -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName
    $c += Get-ChildItem "$tools" -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue | ForEach-Object FullName
    foreach ($d in $c) {
        $java = Join-Path $d "bin\java.exe"
        if (-not (Test-Path $java)) { continue }
        $v = (& cmd /c "`"$java`" -version 2>&1" | Select-Object -First 1)
        if ($v -match '"(\d+)') { if ([int]$Matches[1] -ge 17) { return $d } }
    }
    return $null
}

$jdk = Find-Jdk
if (-not $jdk) {
    Write-Host "JDK bulunamadi, Microsoft OpenJDK 21 indiriliyor (.tools)..."
    New-Item -ItemType Directory -Force $tools | Out-Null
    $zip = Join-Path $tools "jdk.zip"
    $shaTxt = (Invoke-WebRequest "https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip.sha256sum.txt" -UseBasicParsing).Content
    if ($shaTxt -is [byte[]]) { $shaTxt = [Text.Encoding]::ASCII.GetString($shaTxt) }
    $sha = ($shaTxt.Trim() -split '\s+')[0]
    Get-Verified "https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip" $zip "SHA256" $sha
    Expand-Archive $zip $tools; Remove-Item $zip
    $jdk = Find-Jdk
    if (-not $jdk) { throw "JDK kurulamadi." }
}
$env:JAVA_HOME = $jdk
Write-Host "JDK: $jdk"

# ---------------------------------------------------------------- Android SDK
$sdk = $null
foreach ($d in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk", (Join-Path $tools "android-sdk"))) {
    if ($d -and (Test-Path (Join-Path $d "cmdline-tools\latest\bin"))) { $sdk = $d; break }
}
if (-not $sdk) {
    Write-Host "Android SDK bulunamadi, komut satiri araclari indiriliyor (.tools)..."
    $sdk = Join-Path $tools "android-sdk"
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
    $zip = Join-Path $tools "cmdtools.zip"
    Get-Verified "https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip" $zip "SHA1" "57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d"
    Expand-Archive $zip "$tools\ct"; Remove-Item $zip
    Move-Item "$tools\ct\cmdline-tools" "$sdk\cmdline-tools\latest"; Remove-Item -Recurse "$tools\ct"
}
$env:ANDROID_HOME = $sdk
$missing = @()
if (-not (Test-Path "$sdk\platforms\android-36")) { $missing += "platforms/android-36" }
if (-not (Test-Path "$sdk\build-tools\36.1.0"))   { $missing += "build-tools/36.1.0" }
if (-not (Test-Path "$sdk\platform-tools"))       { $missing += "platform-tools" }
if ($missing) {
    Write-Host "Android SDK paketleri kuruluyor: $($missing -join ', ')"
    $cli = Join-Path $sdk "cmdline-tools\latest\bin\android.exe"
    & cmd /c "`"$cli`" --sdk=`"$sdk`" sdk install $($missing -join ' ') 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "Android SDK paketleri kurulamadi." }
}
Write-Host "Android SDK: $sdk"
[IO.File]::WriteAllText((Join-Path $PSScriptRoot "local.properties"), "sdk.dir=" + ($sdk -replace '\\', '\\') + "`n")

# ---------------------------------------------------------------- Xray kutuphanesi + profil
if (-not (Test-Path "app\libs\libv2ray.aar")) { & "$PSScriptRoot\scripts\get-libv2ray.ps1" }
if (-not (Test-Path "app\src\main\assets\profile.txt")) {
    Write-Host "Not: profil gomulmuyor; uygulama ilk acilista VLESS linkini soracak."
}

# ---------------------------------------------------------------- derleme
[Environment]::CurrentDirectory = $PSScriptRoot
& "$PSScriptRoot\gradlew.bat" -p "$PSScriptRoot" assembleRelease --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Derleme basarisiz." }

New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item "app\build\outputs\apk\release\app-release.apk" "dist\SchafVPN.apk" -Force
Write-Host ""
Write-Host "Hazir: $PSScriptRoot\dist\SchafVPN.apk"
