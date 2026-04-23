@echo off
title Screen Recorder

echo ================================================
echo         Screen Recorder - Build and Run
echo ================================================
echo.

where mvn >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Maven not found. Please install Apache Maven first.
    echo Download: https://maven.apache.org/download.cgi
    pause
    exit /b 1
)

where java >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java not found. Please install JDK 17 or later.
    echo Download: https://adoptium.net/
    pause
    exit /b 1
)

if not exist ffmpeg\ffmpeg.exe (
    echo [WARNING] ffmpeg\ffmpeg.exe not found.
    echo           System audio recording will not work.
    echo           See ffmpeg\README.txt for download instructions.
    echo.
)

echo [1/2] Building...
mvn clean package -q
if %errorlevel% neq 0 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)

echo [2/2] Launching...
java -jar target\screen-recorder-1.0.0.jar

pause
