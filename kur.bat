@echo off
setlocal
cd /d "%~dp0"
title SchafVPN Android - Kurulum

echo.
echo   ======================================================
echo     SchafVPN Android - kendi VLESS profilinle APK olustur
echo   ======================================================
echo.
echo   Gerekli araclar (JDK, Android SDK) yoksa otomatik indirilir.
echo   Ilk derleme internet hizina gore 5-15 dakika surebilir.

rem --- VLESS linkini al -> app\src\main\assets\profile.txt ---
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\set-profile.ps1"
if errorlevel 1 goto :fail

rem --- Araclari hazirla ve derle ---
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
if errorlevel 1 goto :fail

echo.
echo   Hazir: %~dp0dist\SchafVPN.apk
echo   APK'yi telefona at ve kur ("Bilinmeyen kaynaklar" iznini ver).
echo   Bu APK icinde senin VLESS linkin var, baskasiyla paylasma.
echo.
explorer "%~dp0dist"
pause
exit /b 0

:fail
echo.
echo   Bir hata olustu, yukaridaki mesaji kontrol et.
echo.
pause
exit /b 1
