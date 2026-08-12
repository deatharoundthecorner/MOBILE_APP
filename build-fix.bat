@echo off
setlocal enabledelayedexpansion

REM Set JAVA_HOME to JDK (not JRE)
set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Verify Java tools
echo ===== JAVA VERIFICATION =====
java -version
javac -version
echo.

REM Stop Gradle
echo ===== STOPPING GRADLE =====
call gradlew.bat --stop
echo.

REM Clear .gradle cache
echo ===== CLEARING GRADLE CACHE =====
if exist .gradle (
    rmdir /s /q .gradle
    echo .gradle directory removed
)
echo.

REM Clear app/build
echo ===== CLEARING APP BUILD =====
if exist app\build (
    rmdir /s /q app\build
    echo app\build directory removed
)
echo.

REM Clean build
echo ===== STARTING CLEAN BUILD =====
call gradlew.bat clean assembleDebug

echo.
echo ===== BUILD COMPLETE =====
if exist app\build\outputs\apk\debug\app-debug.apk (
    echo SUCCESS: APK created at app\build\outputs\apk\debug\app-debug.apk
) else (
    echo FAILED: APK was not created
)

pause
