## Text Editor with Version Control - Kotlin & Material 3

Complete, production-ready Android text editor with incremental version control subsystem.

### Project Structure

```
TextEditor/
├── app/
│   ├── src/main/
│   │   ├── kotlin/com/editor/core/
│   │   │   ├── features/
│   │   │   │   ├── editor/
│   │   │   │   │   ├── domain/
│   │   │   │   │   │   ├── TextFile.kt          # Domain model
│   │   │   │   │   │   └── FileRepository.kt    # Repository interface
│   │   │   │   │   └── ui/
│   │   │   │   │       ├── EditorUiState.kt     # UI state sealed class
│   │   │   │   │       ├── EditorViewModel.kt   # MVVM ViewModel
│   │   │   │   │       ├── EditorViewModelFactory.kt
│   │   │   │   │       └── EditorActivity.kt    # Main activity
│   │   │   │   └── versioncontrol/
│   │   │   │       └── DiffEngine.kt            # Text diff engine
│   │   │   └── data/
│   │   │       ├── local/
│   │   │       │   ├── entity/
│   │   │       │   │   ├── FileEntity.kt        # Room entity
│   │   │       │   │   └── FileVersionEntity.kt # Version entity
│   │   │       │   ├── FileDao.kt               # Room DAO
│   │   │       │   └── AppDatabase.kt           # Room database
│   │   │       └── repository/
│   │   │           └── DiskFileRepository.kt    # Repository impl
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_editor.xml          # Material 3 layout
│   │   │   ├── menu/
│   │   │   │   └── menu_editor.xml              # Toolbar menu
│   │   │   ├── drawable/
│   │   │   │   ├── ic_save.xml                  # Save icon
│   │   │   │   ├── ic_back.xml                  # Back icon
│   │   │   │   └── cursor_drawable.xml          # EditText cursor
│   │   │   ├── values/
│   │   │   │   ├── strings.xml                  # String resources
│   │   │   │   ├── colors.xml                   # Material 3 colors
│   │   │   │   └── themes.xml                   # Theme definitions
│   │   │   ├── values-night/
│   │   │   │   └── colors.xml                   # Dark mode colors
│   │   │   └── xml/
│   │   │       ├── data_extraction_rules.xml
│   │   │       ├── backup_descriptor.xml
│   │   │       └── preferences.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
└── README.md (this file)
```

### Architecture

**SOLID Principles**
- **S**ingle Responsibility: Each class has one reason to change
- **O**pen/Closed: Extension via interfaces, not modification
- **L**iskov Substitution: Repository implementation substitutes interface
- **I**nterface Segregation: Clean, minimal interface boundaries
- **D**ependency Inversion: Depend on abstractions (FileRepository), not concrete implementations

**Clean Architecture Layers**

1. **Domain Layer** (`com.editor.core.features.editor.domain`)
   - Pure business logic
   - `TextFile`: Immutable domain model
   - `FileRepository`: Clean abstraction interface
   - No Android dependencies

2. **Data Layer** (`com.editor.core.data`)
   - Room database: `FileEntity`, `FileVersionEntity`, `FileDao`, `AppDatabase`
   - Repository implementation: `DiskFileRepository`
   - File I/O operations on `Dispatchers.IO`

3. **Feature Layer** (`com.editor.core.features`)
   - Version control: `DiffEngine` (pure Kotlin utility)
   - UI: `EditorActivity`, `EditorViewModel`, `EditorUiState`

4. **Presentation Layer** (MVVM)
   - `EditorUiState`: Sealed class state management
   - `EditorViewModel`: StateFlow-backed reactive state
   - `EditorActivity`: Material 3 UI with lifecycle-aware coroutine collection

### Key Features

**Text Editing**
- Material 3 AppBarLayout with Toolbar
- Monospace font for code-like editing
- Scrollable EditText with 16dp padding
- Real-time UI state binding

**File Operations**
- Read/write with UTF-8 encoding
- Automatic parent directory creation
- Read-only file detection
- Comprehensive error handling via `runCatching`

**Version Control**
- Line-by-line diff computation using LCS algorithm
- Patch application with context line validation
- Atomic version snapshots with timestamps
- Cascading delete for version cleanup (onDelete = CASCADE)

**Database**
- Room singleton with double-checked locking
- Transactional file + version updates
- Indexed queries for fast history retrieval
- Flow-based reactive version history

### Material 3 Design

- **Colors**: Dynamic color scheme from Material 3 spec
- **Typography**: Monospace for editor, body for UI
- **Components**: AppBarLayout, Toolbar, ProgressBar, EditText
- **Dark Mode**: Automatic theming via values-night/colors.xml
- **Accessibility**: Content descriptions, text hierarchy

### Dependencies

```kotlin
// Android Core
androidx.core:core-ktx:1.12.0
androidx.appcompat:appcompat:1.6.1

// Material Design 3
com.google.android.material:material:1.11.0

// Jetpack Components
androidx.lifecycle:lifecycle-runtime-ktx:2.7.0
androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0
androidx.activity:activity-ktx:1.8.0

// Coroutines
kotlinx.coroutines:kotlinx-coroutines-android:1.7.3
kotlinx.coroutines:kotlinx-coroutines-core:1.7.3

// Room Database
androidx.room:room-runtime:2.6.1
androidx.room:room-ktx:2.6.1
androidx.room:room-compiler:2.6.1 (kapt)
```

### Usage

1. **Open File**
   ```kotlin
   viewModel.openFile("/path/to/file.txt")
   ```

2. **Save File**
   ```kotlin
   viewModel.saveFile("document.txt", "/path/to/file.txt", content)
   ```

3. **Rollback to Version**
   ```kotlin
   viewModel.rollBackToVersion(path, snapshotText, "Rolled back to previous version")
   ```

4. **View Version History**
   ```kotlin
   viewModel.repository.getVersionHistory(path).collect { versions ->
       // Reactive updates with historical snapshots
   }
   ```

### Error Handling

All I/O operations use `runCatching` for graceful error handling:
- File read/write failures → `EditorUiState.Error`
- Invalid paths → `IllegalArgumentException`
- Patch mismatches → Validation errors with context
- Database operations → Transaction rollback on failure

### Testing

Database testing dependencies included:
```gradle
androidTestImplementation("androidx.room:room-testing:2.6.1")
```

### Compilation

```bash
./gradlew build       # Full build
./gradlew assembleDebug  # Debug APK
./gradlew assembleRelease # Release APK (minified)
```

### ProGuard Configuration

- Preserves app classes and Room entities
- Keeps ViewModel constructors for reflection
- Strips logging in release builds
- Preserves Serializable/Parcelable classes
