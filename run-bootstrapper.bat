@echo off
REM Gradle Bootstrapper Runner
REM Compiles and executes Bootstrapper.java to initialize the Gradle environment

setlocal enabledelayedexpansion

echo.
echo ========================================================================
echo Gradle Bootstrapper Launcher
echo ========================================================================
echo.

REM Check if Java is available
java -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java is not installed or not in PATH.
    echo.
    echo Please install Java 11 or higher from:
    echo https://www.oracle.com/java/technologies/downloads/
    echo.
    echo Then add it to your system PATH.
    pause
    exit /b 1
)

REM Display Java version
echo Detected Java:
java -version
echo.

REM Check if Bootstrapper.java exists
if not exist "app\Bootstrapper.java" (
    echo ERROR: Bootstrapper.java not found at app\Bootstrapper.java
    echo.
    echo Make sure you are running this script from the project root directory.
    pause
    exit /b 1
)

echo [1/3] Compiling Bootstrapper.java...
javac app\Bootstrapper.java
if errorlevel 1 (
    echo ERROR: Failed to compile Bootstrapper.java
    pause
    exit /b 1
)
echo [DONE] Compilation successful
echo.

echo [2/3] Running Bootstrapper (this may take a minute on first run)...
cd app
java Bootstrapper
if errorlevel 1 (
    echo ERROR: Bootstrapper execution failed
    cd ..
    pause
    exit /b 1
)
cd ..
echo [DONE] Bootstrap process complete
echo.

echo [3/3] Verifying installation...
if exist "gradle\wrapper\gradle-wrapper.jar" (
    echo ✓ gradle-wrapper.jar exists
) else (
    echo ✗ gradle-wrapper.jar NOT found
    pause
    exit /b 1
)

if exist "gradle\wrapper\gradle-wrapper.properties" (
    echo ✓ gradle-wrapper.properties exists
) else (
    echo ✗ gradle-wrapper.properties NOT found
    pause
    exit /b 1
)

if exist "gradlew.bat" (
    echo ✓ gradlew.bat exists
) else (
    echo ✗ gradlew.bat NOT found
    pause
    exit /b 1
)

echo.
echo ========================================================================
echo SUCCESS! Gradle environment is ready.
echo ========================================================================
echo.
echo You can now run:
echo   gradlew.bat build
echo   gradlew.bat assembleDebug
echo   gradlew.bat test
echo.
echo Press any key to continue...
pause

endlocal
exit /b 0
