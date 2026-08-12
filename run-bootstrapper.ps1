# Gradle Bootstrapper - PowerShell Version
# For users who prefer PowerShell over Command Prompt
# Usage: .\run-bootstrapper.ps1

Write-Host ""
Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host "Gradle Bootstrapper Launcher (PowerShell)" -ForegroundColor Cyan
Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host ""

# Function to display colored output
function Write-Status {
    param([string]$Message, [string]$Status = "INFO")
    
    $color = @{
        "INFO" = "Cyan"
        "SUCCESS" = "Green"
        "ERROR" = "Red"
        "WARNING" = "Yellow"
    }
    
    $symbol = @{
        "INFO" = "●"
        "SUCCESS" = "✓"
        "ERROR" = "✗"
        "WARNING" = "⚠"
    }
    
    Write-Host $symbol[$Status] $Message -ForegroundColor $color[$Status]
}

# Check if Java is available
Write-Status "Checking Java installation..." "INFO"
try {
    $javaVersion = java -version 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Status "Java detected:" "SUCCESS"
        $javaVersion | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }
    } else {
        throw "Java not available"
    }
} catch {
    Write-Status "Java is not installed or not in PATH." "ERROR"
    Write-Host ""
    Write-Host "Please install Java 11 or higher from:" -ForegroundColor Yellow
    Write-Host "https://www.oracle.com/java/technologies/downloads/" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Then add it to your system PATH and restart PowerShell." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""

# Check if Bootstrapper.java exists
Write-Status "Checking for Bootstrapper.java..." "INFO"
if (-not (Test-Path "app\Bootstrapper.java")) {
    Write-Status "Bootstrapper.java not found at app\Bootstrapper.java" "ERROR"
    Write-Host ""
    Write-Host "Make sure you are running this script from the project root directory." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"
    exit 1
}
Write-Status "Found Bootstrapper.java" "SUCCESS"

Write-Host ""

# Step 1: Compile Bootstrapper.java
Write-Status "[1/3] Compiling Bootstrapper.java..." "INFO"
try {
    javac app\Bootstrapper.java 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Status "Compilation successful" "SUCCESS"
    } else {
        throw "Compilation failed"
    }
} catch {
    Write-Status "Failed to compile Bootstrapper.java" "ERROR"
    Write-Host "Error: $_" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""

# Step 2: Run Bootstrapper
Write-Status "[2/3] Running Bootstrapper (this may take a minute)..." "INFO"
try {
    Push-Location "app"
    java Bootstrapper
    if ($LASTEXITCODE -ne 0) {
        throw "Bootstrapper execution failed"
    }
    Pop-Location
} catch {
    Write-Status "Bootstrapper execution failed" "ERROR"
    Write-Host "Error: $_" -ForegroundColor Red
    Pop-Location
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""

# Step 3: Verify installation
Write-Status "[3/3] Verifying installation..." "INFO"
Write-Host ""

$allFilesExist = $true

$files = @(
    @{ Path = "gradle\wrapper\gradle-wrapper.jar"; Name = "gradle-wrapper.jar" },
    @{ Path = "gradle\wrapper\gradle-wrapper.properties"; Name = "gradle-wrapper.properties" },
    @{ Path = "gradlew.bat"; Name = "gradlew.bat" }
)

foreach ($file in $files) {
    if (Test-Path $file.Path) {
        Write-Status "$($file.Name) exists" "SUCCESS"
    } else {
        Write-Status "$($file.Name) NOT found" "ERROR"
        $allFilesExist = $false
    }
}

Write-Host ""

if ($allFilesExist) {
    Write-Host "========================================================================" -ForegroundColor Green
    Write-Host "SUCCESS! Gradle environment is ready." -ForegroundColor Green
    Write-Host "========================================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "You can now run:" -ForegroundColor Cyan
    Write-Host "  .\gradlew.bat build" -ForegroundColor Gray
    Write-Host "  .\gradlew.bat assembleDebug" -ForegroundColor Gray
    Write-Host "  .\gradlew.bat test" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Next steps:" -ForegroundColor Cyan
    Write-Host "  1. Ensure Java 11+ is installed" -ForegroundColor Gray
    Write-Host "  2. Run: .\gradlew.bat build" -ForegroundColor Gray
    Write-Host "  3. Import project into Android Studio" -ForegroundColor Gray
    Write-Host ""
} else {
    Write-Host "========================================================================" -ForegroundColor Red
    Write-Host "ERROR! Some files are missing." -ForegroundColor Red
    Write-Host "========================================================================" -ForegroundColor Red
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""
Read-Host "Press Enter to exit"
