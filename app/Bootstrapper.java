import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Self-contained Java bootstrapper for Android Gradle projects.
 * Downloads Gradle wrapper files and initializes the build environment.
 * 
 * This class is designed to run on a native JDK machine without requiring
 * a global Gradle installation. It programmatically downloads and installs
 * Gradle 8.2.0 wrapper components exactly where Android projects expect them.
 * 
 * Usage: java Bootstrapper
 * 
 * The bootstrapper will:
 * 1. Create gradle/wrapper/ directory structure
 * 2. Download gradle-wrapper.jar (official Gradle distribution)
 * 3. Download gradle-wrapper.properties (wrapper configuration)
 * 4. Generate gradlew.bat (Windows batch script for Gradle execution)
 * 5. Provide clear feedback on success/failure
 * 
 * @author TextEditor Gradle Bootstrap System
 * @version 1.0
 */
public class Bootstrapper {

    // Gradle 8.2.0 official repository URLs
    private static final String GRADLE_VERSION = "8.2.0";
    private static final String JAR_URL = 
        "https://raw.githubusercontent.com/gradle/gradle/v" + GRADLE_VERSION + 
        "/gradle/wrapper/gradle-wrapper.jar";
    private static final String PROPERTIES_URL = 
        "https://raw.githubusercontent.com/gradle/gradle/v" + GRADLE_VERSION + 
        "/gradle/wrapper/gradle-wrapper.properties";

    // Gradle wrapper properties configuration
    private static final String WRAPPER_PROPERTIES_CONTENT =
        "distributionBase=GRADLE_USER_HOME\n" +
        "distributionPath=wrapper/dists\n" +
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.2.0-bin.zip\n" +
        "zipStoreBase=GRADLE_USER_HOME\n" +
        "zipStorePath=wrapper/dists\n";

    // Windows gradlew.bat script content
    private static final String GRADLEW_BAT_CONTENT =
        "@rem\n" +
        "@rem Copyright 2015 the original author or authors.\n" +
        "@rem\n" +
        "@rem Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
        "@rem you may not use this file except in compliance with the License.\n" +
        "@rem You may obtain a copy of the License at\n" +
        "@rem\n" +
        "@rem      https://www.apache.org/licenses/LICENSE-2.0\n" +
        "@rem\n" +
        "@rem Unless required by applicable law or agreed to in writing, software\n" +
        "@rem distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
        "@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
        "@rem See the License for the specific language governing permissions and\n" +
        "@rem limitations under the License.\n" +
        "@rem\n" +
        "\n" +
        "@if \"%DEBUG%\" == \"\" @echo off\n" +
        "@rem ##########################################################################\n" +
        "@rem\n" +
        "@rem  Gradle startup script for Windows\n" +
        "@rem\n" +
        "@rem ##########################################################################\n" +
        "\n" +
        "@rem Set local scope for the variables with windows NT shell\n" +
        "if \"%OS%\"==\"Windows_NT\" setlocal\n" +
        "\n" +
        "set DIRNAME=%~dp0\n" +
        "if \"%DIRNAME%\" == \"\" set DIRNAME=.\n" +
        "set APP_HOME=%DIRNAME%\n" +
        "\n" +
        "@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.\n" +
        "set DEFAULT_JVM_OPTS=-Xmx64m -Xms64m\n" +
        "\n" +
        "@rem Find java.exe\n" +
        "if defined JAVA_HOME goto findJavaFromJavaHome\n" +
        "\n" +
        "set JAVA_EXE=java.exe\n" +
        "%JAVA_EXE% -version >nul 2>&1\n" +
        "if \"%ERRORLEVEL%\" == \"0\" goto execute\n" +
        "\n" +
        "echo.\n" +
        "echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.\n" +
        "echo.\n" +
        "echo Please set the JAVA_HOME variable in your environment to match the\n" +
        "echo location of your Java installation.\n" +
        "\n" +
        "goto fail\n" +
        "\n" +
        ":findJavaFromJavaHome\n" +
        "set JAVA_HOME=%JAVA_HOME:\"=%\n" +
        "set JAVA_EXE=%JAVA_HOME%/bin/java.exe\n" +
        "\n" +
        "if exist \"%JAVA_EXE%\" goto execute\n" +
        "\n" +
        "echo.\n" +
        "echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%\n" +
        "echo.\n" +
        "echo Please set the JAVA_HOME variable in your environment to match the\n" +
        "echo location of your Java installation.\n" +
        "\n" +
        "goto fail\n" +
        "\n" +
        ":execute\n" +
        "@rem Setup the command line\n" +
        "\n" +
        "set CLASSPATH=%APP_HOME%\\gradle\\wrapper\\gradle-wrapper.jar\n" +
        "\n" +
        "@rem Execute Gradle\n" +
        "\"%JAVA_EXE%\" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% \"-Dorg.gradle.appname=%APP_BASE_NAME%\" -classpath \"%CLASSPATH%\" org.gradle.wrapper.GradleWrapperMain %*\n" +
        "\n" +
        ":fail\n" +
        "rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of\n" +
        "rem the _script_ return code of this script.\n" +
        "if  not \"\" == \"%GRADLE_EXIT_CONSOLE%\" exit 1\n" +
        "exit /b 1\n";

    /**
     * Main entry point for the bootstrapper.
     * Orchestrates the setup of Gradle wrapper components.
     * 
     * @param args Command line arguments (unused)
     */
    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("Gradle Bootstrapper v" + GRADLE_VERSION);
        System.out.println("Initializing Android Gradle project environment...");
        System.out.println("=".repeat(70));

        try {
            // Step 1: Create gradle/wrapper/ directory structure
            System.out.println("\n[1/4] Creating gradle/wrapper/ directory...");
            Path wrapperDir = createWrapperDirectory();
            System.out.println("      ✓ Directory created at: " + wrapperDir.toAbsolutePath());

            // Step 2: Download gradle-wrapper.jar
            System.out.println("\n[2/4] Downloading gradle-wrapper.jar...");
            Path jarPath = wrapperDir.resolve("gradle-wrapper.jar");
            downloadFile(JAR_URL, jarPath);
            System.out.println("      ✓ gradle-wrapper.jar downloaded (" + 
                             getReadableFileSize(Files.size(jarPath)) + ")");

            // Step 3: Download gradle-wrapper.properties
            System.out.println("\n[3/4] Setting up gradle-wrapper.properties...");
            Path propertiesPath = wrapperDir.resolve("gradle-wrapper.properties");
            writePropertiesFile(propertiesPath);
            System.out.println("      ✓ gradle-wrapper.properties created");

            // Step 4: Generate gradlew.bat
            System.out.println("\n[4/4] Generating gradlew.bat script...");
            Path projectRoot = Paths.get(".").toAbsolutePath().getParent();
            Path gradlewBat = projectRoot.resolve("gradlew.bat");
            writeGradlewBat(gradlewBat);
            System.out.println("      ✓ gradlew.bat created at: " + gradlewBat.toAbsolutePath());

            // Success summary
            System.out.println("\n" + "=".repeat(70));
            System.out.println("✓ Bootstrap complete! Gradle environment is ready.");
            System.out.println("=".repeat(70));
            System.out.println("\nYou can now use: gradlew build");
            System.out.println("                 gradlew assembleDebug");
            System.out.println("                 gradlew assembleRelease");
            System.out.println("\nNext steps:");
            System.out.println("1. Ensure Java 11+ is installed: java -version");
            System.out.println("2. Run: gradlew.bat build");
            System.out.println("3. Import project into Android Studio\n");

        } catch (Exception exception) {
            System.err.println("\n" + "=".repeat(70));
            System.err.println("✗ Bootstrap failed with error:");
            System.err.println("=".repeat(70));
            System.err.println("\nException: " + exception.getClass().getSimpleName());
            System.err.println("Message: " + exception.getMessage());
            System.err.println("\nStack trace:");
            exception.printStackTrace(System.err);
            System.err.println("\n" + "=".repeat(70));
            System.err.println("Troubleshooting steps:");
            System.err.println("1. Check internet connection (GitHub downloads required)");
            System.err.println("2. Ensure Java 11+ is installed: java -version");
            System.err.println("3. Verify firewall allows GitHub HTTPS downloads");
            System.err.println("4. Check available disk space (at least 500MB)");
            System.err.println("5. Try running with elevated privileges");
            System.err.println("=".repeat(70) + "\n");
            System.exit(1);
        }
    }

    /**
     * Creates the gradle/wrapper/ directory structure.
     * Returns immediately if directory already exists.
     * 
     * @return Path to the created gradle/wrapper directory
     * @throws IOException if directory creation fails
     */
    private static Path createWrapperDirectory() throws IOException {
        Path wrapperDir = Paths.get("gradle", "wrapper");
        
        if (Files.exists(wrapperDir)) {
            System.out.println("      (directory already exists)");
            return wrapperDir;
        }

        return Files.createDirectories(wrapperDir);
    }

    /**
     * Downloads a file from the given URL and writes it to the specified path.
     * Uses HTTP connection with proper timeout and buffer handling.
     * 
     * @param urlString The remote URL to download from
     * @param targetPath The local file path to write to
     * @throws IOException if download or write fails
     */
    private static void downloadFile(String urlString, Path targetPath) throws IOException {
        // Skip if file already exists
        if (Files.exists(targetPath)) {
            System.out.println("      (file already exists, skipping download)");
            return;
        }

        URL url = new URL(urlString);
        URLConnection connection = url.openConnection();

        // Set reasonable timeouts
        connection.setConnectTimeout(30000);  // 30 seconds
        connection.setReadTimeout(30000);     // 30 seconds

        // Set user agent to avoid potential blocking
        connection.setRequestProperty("User-Agent", 
            "TextEditor-Bootstrapper/1.0 (Android Gradle Setup)");

        // Download with progress feedback
        try (InputStream inputStream = connection.getInputStream()) {
            byte[] buffer = new byte[8192];
            long totalBytes = 0;
            long contentLength = connection.getContentLength();

            try (FileOutputStream outputStream = new FileOutputStream(targetPath.toFile())) {
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    // Progress feedback for large files
                    if (contentLength > 0) {
                        int percent = (int) ((totalBytes * 100) / contentLength);
                        if (percent % 10 == 0) {
                            System.out.print(".");
                        }
                    }
                }
            }

            if (contentLength > 0) {
                System.out.println();  // Newline after progress dots
            }
        }

        if (!Files.exists(targetPath)) {
            throw new IOException("File download failed: " + urlString);
        }
    }

    /**
     * Writes the gradle-wrapper.properties configuration file.
     * Contains settings for Gradle distribution URL and wrapper behavior.
     * 
     * @param propertiesPath Path to write properties file
     * @throws IOException if write operation fails
     */
    private static void writePropertiesFile(Path propertiesPath) throws IOException {
        Files.write(propertiesPath, WRAPPER_PROPERTIES_CONTENT.getBytes());
    }

    /**
     * Writes the gradlew.bat Windows batch script.
     * This script allows running Gradle tasks without a global Gradle installation.
     * 
     * @param gradlewBatPath Path to write gradlew.bat file
     * @throws IOException if write operation fails
     */
    private static void writeGradlewBat(Path gradlewBatPath) throws IOException {
        Files.write(gradlewBatPath, GRADLEW_BAT_CONTENT.getBytes());

        // Make file executable on Windows
        File batFile = gradlewBatPath.toFile();
        batFile.setExecutable(true, false);
    }

    /**
     * Converts file size in bytes to human-readable format.
     * 
     * @param bytes File size in bytes
     * @return Formatted string (e.g., "5.2 MB")
     */
    private static String getReadableFileSize(long bytes) {
        if (bytes <= 0) return "0 B";

        final String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = (int) (Math.log10(bytes) / Math.log10(1024));
        double size = bytes / Math.pow(1024, unitIndex);

        return String.format("%.1f %s", size, units[unitIndex]);
    }
}
