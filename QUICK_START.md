# Quick Start Guide

## Getting Started with TextEditor Project

### Prerequisites
- Android Studio (latest)
- Android SDK 34 (target)
- Java 11 JDK
- 2GB+ available disk space

### Step 1: Import Project

1. Open Android Studio
2. **File → Open** → Select the `TextEditor` folder
3. Wait for Gradle to sync (may take 2-3 minutes on first import)
4. If sync fails, try **File → Invalidate Caches → Restart**

### Step 2: Configure SDK

Ensure you have the correct SDKs:
```bash
# In Android Studio SDK Manager (Tools → SDK Manager)
# Install if missing:
- Android SDK 34
- Android SDK Platform Tools
- Android Emulator (optional, for testing)
```

### Step 3: Build Project

```bash
# Clean build
./gradlew clean build

# Quick build (debug)
./gradlew assembleDebug

# With tests
./gradlew build test
```

### Step 4: Run on Emulator

1. **Run → Select Device** (or create new emulator)
2. Choose Android 14 (API 34) or compatible
3. Click **Run** (Shift+F10)
4. App launches on device/emulator

### Step 5: Test File Operations

```kotlin
// Create a test file at:
// /storage/emulated/0/Documents/test.txt

// Open in app:
// Intent extras: "file_path" → "/storage/emulated/0/Documents/test.txt"
// Or use FileProvider to access files safely
```

---

## Project Overview

### Main Components

1. **Domain Layer** - Pure business logic (no Android)
   - TextFile.kt - File data model
   - FileRepository.kt - Interface for file operations

2. **Data Layer** - Persistence & I/O
   - Room Database (FileEntity, FileVersionEntity)
   - DiskFileRepository - File system operations
   - DiffEngine - Text diff computation

3. **Presentation Layer** - MVVM Architecture
   - EditorActivity - Material 3 UI
   - EditorViewModel - State management
   - EditorUiState - UI state machine

### Key Files to Know

| File | Purpose | Location |
|------|---------|----------|
| EditorActivity.kt | Main UI Activity | `features/editor/ui/` |
| EditorViewModel.kt | ViewModel with StateFlow | `features/editor/ui/` |
| activity_editor.xml | Layout (Material 3) | `res/layout/` |
| DiffEngine.kt | Text diff/patch | `features/versioncontrol/` |
| AppDatabase.kt | Room Singleton | `data/local/` |

---

## Common Tasks

### Open a File

```kotlin
// In EditorActivity or any place with ViewModel access
viewModel.openFile("/path/to/file.txt")

// UI automatically updates via StateFlow
// Shows Loading → Success with content
```

### Save Current File

```kotlin
// Automatically called when user taps Save button
viewModel.saveFile(
    name = "document.txt",
    path = "/storage/emulated/0/Documents/document.txt",
    content = editText.text.toString()
)
```

### Get Version History

```kotlin
viewModel.repository.getVersionHistory(filePath).collect { versions ->
    // List of FileVersion sorted by timestamp DESC
    versions.forEach { version ->
        println("Version: ${version.commitMessage} at ${version.timestamp}")
    }
}
```

### Rollback to Previous Version

```kotlin
viewModel.rollBackToVersion(
    path = "/storage/emulated/0/Documents/document.txt",
    targetSnapshotText = previousVersionContent,
    commitMsg = "Rolled back due to user request"
)
```

---

## Modifying the UI

### Change Toolbar Color

In `res/values/colors.xml`:
```xml
<color name="primary">#YourColor</color>
```

For dark mode, update `res/values-night/colors.xml`

### Change EditText Font

In `res/layout/activity_editor.xml`:
```xml
<EditText
    android:fontFamily="monospace"  <!-- Change this -->
    ...
/>
```

### Add More Menu Items

In `res/menu/menu_editor.xml`:
```xml
<item
    android:id="@+id/action_undo"
    android:title="Undo"
    android:icon="@drawable/ic_undo"
    app:showAsAction="ifRoom" />
```

Then handle in `EditorActivity.onOptionsItemSelected()`:
```kotlin
R.id.action_undo -> {
    // Handle undo action
    true
}
```

---

## Adding Features

### Add a New Domain Model

1. Create in `features/YOURFEATURE/domain/YourModel.kt`
2. Add corresponding Room entity in `data/local/entity/`
3. Add DAO methods in `data/local/FileDao.kt`
4. Update `AppDatabase.kt` entities list
5. Add Repository interface method in `features/YOURFEATURE/domain/`
6. Implement in `data/repository/DiskFileRepository.kt`

### Add a New ViewModel

1. Create in `features/YOURFEATURE/ui/YourViewModel.kt`
   - Extend `androidx.lifecycle.ViewModel`
   - Use `StateFlow` for reactive updates
2. Create factory in `YourViewModelFactory.kt`
3. Create UI State sealed class in `YourUiState.kt`
4. Create Activity/Fragment in same `ui/` package

### Add Database Migration

For future schema changes:
1. Update entity classes
2. Increment `version` in `@Database` annotation
3. Create migration:
   ```kotlin
   val MIGRATION_1_2 = object : Migration(1, 2) {
       override fun migrate(database: SupportSQLiteDatabase) {
           // SQL migration queries
       }
   }
   ```
4. Add to `Room.databaseBuilder()`:
   ```kotlin
   .addMigrations(MIGRATION_1_2)
   ```

---

## Testing

### Run Unit Tests

```bash
./gradlew test
```

Tests located in: `app/src/test/kotlin/`

### Run Instrumented Tests

```bash
./gradlew connectedAndroidTest
```

Tests located in: `app/src/androidTest/kotlin/`

### Test DiffEngine

```bash
./gradlew testDebugUnitTest --tests "*DiffEngineTest*"
```

---

## Performance Tips

### Avoid Main Thread Blocking

All I/O already uses `Dispatchers.IO`:
```kotlin
// Good - already handled in DiskFileRepository
viewModel.openFile(path)  // Safe to call from UI

// Don't do this:
val content = File(path).readText()  // ❌ Blocks UI
```

### Reduce Database Queries

Version history is already indexed:
```sql
CREATE INDEX idx_file_path ON file_versions(file_path);
CREATE INDEX idx_timestamp ON file_versions(timestamp);
```

### Memory Management

Room DB singleton is automatically managed:
```kotlin
// Good - singleton ensures single instance
val db = AppDatabase.getInstance(context)

// Don't do this:
AppDatabase.getInstance(context)  // Multiple calls still return same instance ✓
```

---

## Debugging

### Enable Logging

In `EditorActivity.kt`, logging is already included:
```kotlin
android.util.Log.e("EditorActivity", "UI Error", state.throwable)
```

View in Logcat:
- **Android Studio** → **Logcat** tab
- Filter: `EditorActivity`
- Log level: Verbose, Debug, Info

### Inspect Database

1. Device File Explorer (Android Studio):
   - Connect device/emulator
   - Navigate: `/data/data/com.editor.core/databases/`
   - Download `editor_database.db`

2. Open with SQLite Browser:
   - View tables: `files`, `file_versions`
   - Run queries directly

### Simulate File Operations

Create test file:
```bash
adb shell
touch /storage/emulated/0/Documents/test.txt
echo "Hello World" > /storage/emulated/0/Documents/test.txt
exit
```

Then open in app with file path.

---

## Building for Release

### Generate Signing Key

```bash
# One-time, creates keystore
./gradlew bundleRelease
# Follow prompts to create keystore
```

### Build Release APK

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Upload to Google Play

1. Create Google Play Developer account ($25 one-time fee)
2. In Android Studio: **Build → Generate Signed Bundle/APK**
3. Select `app`, choose `.aab` (Bundle format)
4. Sign with your keystore
5. Upload to Google Play Console

---

## Troubleshooting

### Gradle Sync Fails

```bash
./gradlew clean
./gradlew sync
```

Or in Android Studio: **File → Invalidate Caches → Restart**

### APK Won't Install

Check minimum SDK: API 26 (Android 8.0)
```bash
adb shell getprop ro.build.version.sdk
# Must be ≥ 26
```

### Database Corrupted

Remove and rebuild:
```bash
adb shell rm -rf /data/data/com.editor.core/databases/
# App will recreate database on next launch
```

### File Not Found Errors

Check permissions: `res/AndroidManifest.xml`
- Android 11+: Scoped storage limitations apply
- Use `getExternalFilesDir()` for app-specific directory
- Request `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`

### Version Control Issues

If diff engine produces unexpected results:
1. Check `DiffEngineTest.kt` for known limitations
2. Algorithm: Longest Common Subsequence (line-based)
3. For binary files: Not supported, use text files only

---

## Architecture Deep Dive

### StateFlow vs LiveData

We use `StateFlow` because:
- ✅ Coroutine-based (non-blocking)
- ✅ Latest value guaranteed
- ✅ Cold stream behavior (no collection = no computation)
- ❌ LiveData: Legacy, loses lifecycle safety

### Room vs SQLite

We use Room because:
- ✅ Type-safe queries
- ✅ Automatic SQL generation
- ✅ Built-in coroutine support
- ✅ Testing helpers

### Diff Algorithm: LCS

Why Longest Common Subsequence?
- ✅ Pure Kotlin (no dependencies)
- ✅ O(m×n) time, manageable for text files
- ✅ Works offline
- ❌ Not optimal for very large files (>10MB)

For production with large files, consider:
- `java-diff-utils` library
- Myers' diff algorithm
- Delta compression

---

## Next Steps

1. ✅ **Import project** - Done
2. ✅ **Build** - `./gradlew build`
3. ✅ **Run on device** - Shift+F10
4. ✅ **Test features** - Open/Save/History
5. ⏭️ **Customize UI** - Modify colors, layout
6. ⏭️ **Add features** - Extend ViewModel, Repository
7. ⏭️ **Deploy** - Release build for Play Store

---

## Resources

- **Android Docs:** https://developer.android.com
- **Material 3:** https://material.io/design
- **Kotlin:** https://kotlinlang.org
- **Coroutines:** https://kotlinlang.org/docs/coroutines-overview.html
- **Room:** https://developer.android.com/jetpack/androidx/releases/room
- **MVVM:** https://developer.android.com/jetpack/guide

---

## Support

For issues or questions:
1. Check `IMPLEMENTATION_GUIDE.md` for detailed explanations
2. Review `DiffEngineTest.kt` for usage examples
3. Check Android Logcat for error messages
4. Review AndroidManifest.xml for permission issues

---

**Happy coding! 🚀**
