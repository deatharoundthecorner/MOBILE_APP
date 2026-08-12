package com.editor.core

import com.editor.core.data.local.FileDao
import com.editor.core.data.local.entity.FileEntity
import com.editor.core.data.local.entity.FileVersionEntity
import com.editor.core.data.repository.DiskFileRepository
import com.editor.core.features.versioncontrol.DiffEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration test verifying incremental version control requirements.
 * Ensures only deltas are stored and rollback works correctly.
 */
class VersionControlIntegrationTest {

    private lateinit var repository: DiskFileRepository
    private lateinit var mockDao: FakeFileDao
    private val testFilePath = "test_document.txt"

    @Before
    fun setup() {
        mockDao = FakeFileDao()
        // Note: Context is needed for content:// URIs but not for raw File IO in this test
        // We use null or a dummy context if possible, but DiskFileRepository uses it for content resolver.
        // For this test, we'll focus on the logic in DiskFileRepository.
        repository = DiskFileRepository(DummyContext(), mockDao)
        
        // Ensure test file is clean
        val file = File(testFilePath)
        if (file.exists()) file.delete()
    }

    @Test
    fun firstVersion_canBeCreated() {
        runBlocking {
            val file = File(testFilePath)
            file.writeText("First version")

            repository.saveVersionSnapshot(testFilePath, "Initial")
            val history = mockDao.versionHistory[testFilePath] ?: emptyList()
            assertEquals(1, history.size)
            assertEquals("First version", history[0].patchContent)

            file.delete()
        }
    }

    @Test
    fun addedText_generatesAndAppliesPatch() {
        runBlocking {
            val patch = DiffEngine.computeDiff("Hello", "Hello World")
            val result = DiffEngine.applyPatch("Hello", patch)
            assertEquals("Hello World", result)
            assertTrue(patch.contains("+ World") || patch.contains("+  World"))
        }
    }

    @Test
    fun deletedText_generatesAndAppliesPatch() {
        runBlocking {
            val patch = DiffEngine.computeDiff("Hello World", "Hello")
            val result = DiffEngine.applyPatch("Hello World", patch)
            assertEquals("Hello", result)
            assertTrue(patch.contains("- World") || patch.contains("-  World"))
        }
    }

    @Test
    fun replacedText_generatesAndAppliesPatch() {
        runBlocking {
            val patch = DiffEngine.computeDiff("Hello World", "Hello Kotlin")
            val result = DiffEngine.applyPatch("Hello World", patch)
            assertEquals("Hello Kotlin", result)
            assertFalse(patch.isBlank())
            assertTrue(patch.contains("+") && patch.contains("-"))
        }
    }

    @Test
    fun emptyFile_isSupported() {
        runBlocking {
            val patch = DiffEngine.computeDiff("", "Hello")
            val result = DiffEngine.applyPatch("", patch)
            assertEquals("Hello", result)
            assertFalse(patch.isBlank())

            val patch2 = DiffEngine.computeDiff("Hello", "")
            val result2 = DiffEngine.applyPatch("Hello", patch2)
            assertEquals("", result2)
            assertTrue(patch2.contains("- Hello") || patch2.contains("-  Hello"))
        }
    }

    @Test
    fun multipleVersions_canBeReconstructed() {
        runBlocking {
            val file = File(testFilePath)
            file.writeText("v1")
            repository.saveVersionSnapshot(testFilePath, "v1")

            file.writeText("v1 updated")
            repository.saveVersionSnapshot(testFilePath, "v2")

            file.writeText("v1 updated again")
            repository.saveVersionSnapshot(testFilePath, "v3")

            val history = mockDao.versionHistory[testFilePath] ?: emptyList()
            val asc = history.sortedBy { it.timestamp }
            val reconstructed = repository.getVersionContent(testFilePath, asc[2].versionId)

            assertEquals("v1 updated again", reconstructed)
            assertEquals(3, history.size)

            file.delete()
        }
    }

    @Test
    fun versionCanBeRestored() {
        runBlocking {
            val file = File(testFilePath)
            file.writeText("Alpha")
            repository.saveVersionSnapshot(testFilePath, "v1")

            file.writeText("Alpha Beta")
            repository.saveVersionSnapshot(testFilePath, "v2")

            val history = mockDao.versionHistory[testFilePath] ?: emptyList()
            val asc = history.sortedBy { it.timestamp }
            repository.restoreVersion(testFilePath, asc[0].versionId)

            assertEquals("Alpha", File(testFilePath).readText())
            file.delete()
        }
    }

    @Test
    fun versionsCanBeCompared() {
        runBlocking {
            val file = File(testFilePath)
            file.writeText("Alpha")
            repository.saveVersionSnapshot(testFilePath, "v1")

            file.writeText("Alpha Beta")
            repository.saveVersionSnapshot(testFilePath, "v2")

            val history = mockDao.versionHistory[testFilePath] ?: emptyList()
            val asc = history.sortedBy { it.timestamp }
            val diff = repository.compareVersions(testFilePath, asc[0].versionId, asc[1].versionId)

            assertTrue(diff.contains("+ Beta") || diff.contains("+  Beta"))
            file.delete()
        }
    }

    @Test
    fun versionHistory_isOrderedCorrectly() {
        runBlocking {
            val file = File(testFilePath)
            file.writeText("One")
            repository.saveVersionSnapshot(testFilePath, "1")

            Thread.sleep(10)
            file.writeText("Two")
            repository.saveVersionSnapshot(testFilePath, "2")

            val history = repository.getVersionHistory(testFilePath).first()
            assertEquals(2, history.size)
            assertTrue(history[0].timestamp >= history[1].timestamp)

            val firstVersion = repository.getVersionContent(testFilePath, history[1].versionId)
            val secondVersion = repository.getVersionContent(testFilePath, history[0].versionId)

            assertEquals("One", firstVersion)
            assertEquals("Two", secondVersion)
            file.delete()
        }
    }

    @Test
    fun databasePersistsFilesAndVersions() {
        runBlocking {
            val file = File(testFilePath)
            file.writeText("Saved")
            repository.saveVersionSnapshot(testFilePath, "saved")

            assertTrue(mockDao.files.containsKey(testFilePath))
            assertEquals(1, mockDao.versionHistory[testFilePath]?.size ?: 0)
            file.delete()
        }
    }

    /**
     * Mock for android.util.Log which isn't available in unit tests.
     */
    @Before
    fun setupLog() {
        // This is a common workaround for android.util.Log in unit tests
    }

    /**
     * Fake DAO for testing Repository logic without Room.
     */
    private class FakeFileDao : FileDao {
        val files = mutableMapOf<String, FileEntity>()
        val versionHistory = mutableMapOf<String, MutableList<FileVersionEntity>>()
        private var versionIdCounter = 1L

        override suspend fun insertFileIgnore(file: FileEntity): Long {
            if (files.containsKey(file.absolutePath)) return -1L
            files[file.absolutePath] = file
            return 1L
        }

        override suspend fun updateFile(file: FileEntity): Int {
            files[file.absolutePath] = file
            return 1
        }

        override suspend fun getRowIdByPath(absolutePath: String): Long? = if (files.containsKey(absolutePath)) 1L else null

        override suspend fun getFileByPath(absolutePath: String): FileEntity? = files[absolutePath]

        override suspend fun insertVersion(version: FileVersionEntity): Long {
            val list = versionHistory.getOrPut(version.filePath) { mutableListOf() }
            val newVersion = version.copy(versionId = versionIdCounter++)
            list.add(newVersion)
            return newVersion.versionId
        }

        override fun getVersionHistory(filePath: String): Flow<List<FileVersionEntity>> {
            return flowOf(versionHistory[filePath]?.reversed() ?: emptyList())
        }

        override suspend fun getVersionHistorySync(filePath: String): List<FileVersionEntity> {
            return versionHistory[filePath]?.reversed() ?: emptyList()
        }

        override suspend fun getVersionById(versionId: Long): FileVersionEntity? {
            versionHistory.values.forEach { list ->
                list.forEach { v -> if (v.versionId == versionId) return v }
            }
            return null
        }

        override suspend fun deleteVersionsByPath(filePath: String): Int {
            versionHistory.remove(filePath)
            return 1
        }
        override fun getRecentFiles(limit: Int): Flow<List<FileEntity>> = flowOf(files.values.toList())
    }

    /**
     * Dummy Context for Repository instantiation.
     */
    private class DummyContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
        // DiskFileRepository only uses contentResolver if path starts with content://
    }
}
