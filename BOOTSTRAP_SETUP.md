# Gradle Bootstrapper Setup Guide

## Overview

This project includes a **self-contained Java Bootstrapper** (`Bootstrapper.java`) that automatically initializes the Android Gradle build environment on Windows machines that don't have Gradle installed globally.

The bootstrapper:
- Downloads Gradle 8.2.0 wrapper components from official GitHub repository
- Creates the required `gradle/wrapper/` directory structure
- Generates `gradle-wrapper.jar` (Gradle executable)
- Creates `gradle-wrapper.properties` (Gradle configuration)
- Generates `gradlew.bat` (Windows batch script for running Gradle)

**No global Gradle installation needed!** Just Java 11+.

---

## Prerequisites

### Required
- **Java 11 or higher** (JDK, not just JRE)
  - Verify: `java -version`
  - Download: https://www.oracle.com/java/technologies/downloads/

### Optional (for development)
- **Android Studio** (recommended, but not required for CLI builds)
- **Git** (for version control)

---

## Quick Start

### Option 1: Run Bootstrapper Batch Script (Easiest)

1. Open Command Prompt or PowerShell
2. Navigate to the project root:
   ```bash
   cd C:\Users\YourName\Desktop\New kotlin
   ```
3. Run the bootstrapper launcher:
   ```bash
   run-bootstrapper.bat
   ```
4. Wait for completion (downloads ~100MB on first run)
5. When done, you'll see: "SUCCESS! Gradle environment is ready."

### Option 2: Manual Compilation & Execution

If the batch script doesn't work:

```bash
# Navigate to project root
cd C:\Users\YourName\Desktop\New kotlin

# Compile Bootstrapper
javac app\Bootstrapper.java

# Run it
cd app
java Bootstrapper
cd ..
```

### Option 3: Use PowerShell

```powershell
# From project root
Set-Location "C:\Users\YourName\Desktop\New kotlin"

# Compile
javac app/Bootstrapper.java

# Run
cd app
java Bootstrapper
cd ..
```

---

## What Gets Downloaded

The bootstrapper downloads these files from official Gradle repository:

| File | Source | Size | Purpose |
|------|--------|------|---------|
| gradle-wrapper.jar | GitHub (v8.2.0) | ~60 MB | Gradle runtime executable |
| gradle-wrapper.properties | GitHub (v8.2.0) | ~1 KB | Gradle configuration |
| gradlew.bat | Generated locally | ~5 KB | Windows batch launcher |

**Total Download:** ~60 MB (once, then cached)

---

## After Bootstrap Completes

Once the bootstrapper succeeds, you can use Gradle commands:

```bash
# Build the project
gradlew.bat build

# Build debug APK
gradlew.bat assembleDebug

# Build release APK
gradlew.bat assembleRelease

# Run unit tests
gradlew.bat test

# Run specific task
gradlew.bat clean

# View all available tasks
gradlew.bat tasks
```

---

## Directory Structure After Bootstrap

```
TextEditor/
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar          ← Downloaded executable
│       └── gradle-wrapper.properties    ← Downloaded config
├── gradlew.bat                         ← Generated launcher
├── app/
│   ├── Bootstrapper.java               ← This bootstrapper
│   ├── Bootstrapper.class              ← Compiled class
│   └── ...
├── run-bootstrapper.bat                ← Launcher script
└── ...
```

---

## Troubleshooting

### "Java is not installed or not in PATH"
```bash
# Verify Java is installed
java -version

# If not found, add to PATH:
# 1. Find your Java installation
# 2. Control Panel → System → Environment Variables
# 3. Add Java\bin to PATH
# 4. Restart Command Prompt
```

### "Connection timeout" or "Failed to download"
- Check internet connection
- Verify firewall allows GitHub HTTPS
- Try running Command Prompt as Administrator
- Check available disk space (need at least 500 MB)

### "Bootstrapper.java not found"
- Make sure you're in the project root directory
- The file should be at: `app/Bootstrapper.java`

### "Android Studio can't find SDK"
- Bootstrap only downloads Gradle, not Android SDK
- Install Android SDK separately via Android Studio
- Or set ANDROID_HOME environment variable

### Gradle still doesn't work after bootstrap
```bash
# Clean and retry
del gradle\wrapper\gradle-wrapper.jar
del gradle\wrapper\gradle-wrapper.properties
del gradlew.bat

# Re-run bootstrapper
run-bootstrapper.bat
```

---

## How the Bootstrapper Works

### Step-by-Step Process

1. **Directory Creation** (Step 1/4)
   - Creates `gradle/wrapper/` if it doesn't exist
   - Uses `Files.createDirectories()` for atomic creation

2. **Download gradle-wrapper.jar** (Step 2/4)
   - Uses `java.net.URL` to fetch from GitHub
   - Saves to `gradle/wrapper/gradle-wrapper.jar`
   - Shows download progress
   - Skips if file already exists

3. **Create gradle-wrapper.properties** (Step 3/4)
   - Writes standard Gradle wrapper configuration
   - Points to official Gradle 8.2.0 distribution
   - Includes wrapper behavior settings

4. **Generate gradlew.bat** (Step 4/4)
   - Creates Windows batch script in project root
   - Script launches `gradle-wrapper.jar` with JVM options
   - Handles Java discovery (JAVA_HOME or PATH)

### Technical Details

**No External Dependencies**
- Uses only `java.net.URL` (built-in)
- Uses only `java.nio.file.Files` (built-in)
- Pure Java 11 standard library
- Runs on any JDK without Maven/Gradle

**Error Handling**
- Validates all file operations
- Catches IOExceptions with context
- Provides detailed error messages
- Exits with status code 1 on failure

**Progress Feedback**
- Shows which step is running (1/4, 2/4, etc.)
- Displays download progress with dots
- Shows file sizes in human-readable format
- Provides success/failure summary

---

## Source Code

The complete bootstrapper source is in:
- **Location:** `app/Bootstrapper.java`
- **Lines:** 250+ (fully documented)
- **Language:** Java 11
- **Dependencies:** None (pure stdlib)

### Key Methods

| Method | Purpose |
|--------|---------|
| `main()` | Orchestrates entire bootstrap process |
| `createWrapperDirectory()` | Creates gradle/wrapper/ |
| `downloadFile()` | Downloads from URL with timeout |
| `writePropertiesFile()` | Creates gradle-wrapper.properties |
| `writeGradlewBat()` | Generates gradlew.bat script |
| `getReadableFileSize()` | Formats bytes as human-readable |

---

## Security Notes

The bootstrapper:
- ✅ Downloads only from official Gradle GitHub repository
- ✅ Uses HTTPS for all connections
- ✅ Validates downloads by checking file existence
- ✅ Sets reasonable timeouts (30 seconds)
- ✅ No execution of downloaded code (jar is for Gradle, not run directly)
- ✅ Source code is visible in this project

### Verify Downloads

After bootstrap completes, you can verify file integrity:

```bash
# Check file sizes
dir gradle\wrapper\gradle-wrapper.jar
REM Should be ~60 MB

# Check gradle-wrapper.properties exists
type gradle\wrapper\gradle-wrapper.properties

# Test gradlew.bat
gradlew.bat --version
REM Should show: Gradle 8.2
```

---

## Advanced Usage

### Proxy Configuration

If behind a corporate proxy:

```bash
# Set proxy before running
set HTTP_PROXY=http://proxy.company.com:8080
set HTTPS_PROXY=https://proxy.company.com:8080

# Then run bootstrapper
run-bootstrapper.bat
```

### Offline Mode

If you have Gradle files from another machine:

1. Copy `gradle/wrapper/` directory from another project
2. Copy `gradlew.bat` to project root
3. Skip running the bootstrapper

### Using Different Gradle Version

To modify the bootstrapper for a different version:

1. Edit `app/Bootstrapper.java`
2. Change `GRADLE_VERSION = "8.2.0"` to desired version
3. Recompile and run:
   ```bash
   javac app\Bootstrapper.java
   cd app
   java Bootstrapper
   ```

---

## FAQ

**Q: Why do I need this bootstrapper?**
A: Android projects use Gradle to build. Without a bootstrapper, you'd need to:
- Install Gradle globally
- Download Android SDK manually
- Configure environment variables
This bootstrapper automates the Gradle setup.

**Q: Does this work offline?**
A: No, the bootstrapper needs to download files from GitHub. However, once downloaded, you can work offline with `gradlew.bat`.

**Q: Can I delete Bootstrapper.java after running it?**
A: Yes, once bootstrap completes, Bootstrapper.java is not needed. But keeping it allows you to re-run bootstrap if needed.

**Q: What if I move the project?**
A: The gradle/wrapper/ files are part of the project now. They move with it. You don't need to re-run the bootstrapper.

**Q: Can I use this on Mac/Linux?**
A: The bootstrapper generates `gradlew.bat` (Windows only). For Mac/Linux:
- Use official Gradle installation
- Or generate `gradlew` (bash script) separately

**Q: Why not use Maven instead of Gradle?**
A: Android Studio and modern Android projects standardize on Gradle. Maven support is deprecated.

---

## Next Steps After Bootstrap

1. **Verify Installation**
   ```bash
   gradlew.bat --version
   ```
   Should show: `Gradle 8.2`

2. **Build the Project**
   ```bash
   gradlew.bat build
   ```

3. **Open in Android Studio**
   - File → Open → Select project root
   - Android Studio detects gradle structure
   - Click "Trust Project"

4. **Run on Device/Emulator**
   - In Android Studio: Run → Run 'app'
   - Or via Gradle: `gradlew.bat installDebug`

---

## Support

For issues with the bootstrapper:

1. Check **Troubleshooting** section above
2. Review error message in console
3. Verify Java is installed: `java -version`
4. Check internet connectivity
5. Try running as Administrator
6. Check available disk space

---

## Technical Reference

### Java APIs Used

```java
java.net.URL                  // Network URLs
java.net.URLConnection        // HTTP connection
java.nio.file.Files           // File operations
java.nio.file.Path            // Path handling
java.nio.file.Paths           // Path creation
java.io.InputStream           // Read from network
java.io.FileOutputStream      // Write to disk
```

### Exception Handling

All exceptions are caught and reported with context:
- IOException (file/network errors)
- FileNotFoundException (missing files)
- MalformedURLException (bad URLs)
- InterruptedIOException (timeout)

---

## Version History

- **v1.0** (2026-07-08)
  - Initial release
  - Gradle 8.2.0
  - Windows batch script generation
  - GitHub repository downloads
  - Full documentation

---

**Generated:** 2026-07-08  
**Gradle Version:** 8.2.0  
**Minimum Java:** 11  
**Platform:** Windows (batch scripts)
