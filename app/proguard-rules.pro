# Rules for ProGuard to keep essential classes and methods
# Preserve all classes in the app's package
-keep class com.editor.** { *; }

# Preserve Room database classes
-keep class androidx.room.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# Preserve ViewModel classes
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

# Preserve Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Preserve Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Preserve enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Preserve annotation processors
-keep interface * extends java.lang.annotation.Annotation { *; }

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
