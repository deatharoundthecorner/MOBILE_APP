package com.editor.core

import com.editor.core.data.local.FileDao
import com.editor.core.data.local.entity.FileEntity
import com.editor.core.data.local.entity.FileVersionEntity
import com.editor.core.data.repository.DiskFileRepository
import com.editor.core.features.versioncontrol.DiffEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun testIncrementalVersioningRequirements() {
        runBlocking {
            // Step 1: Create "Hello" and Save (Version 1)
            val fileV1 = File(testFilePath)
            fileV1.writeText("Hello")
            repository.saveVersionSnapshot(testFilePath, "Version 1")

            // Step 2: Change to "Hello World" and Save (Version 2)
            fileV1.writeText("Hello World")
            repository.saveVersionSnapshot(testFilePath, "Version 2")

            // Step 3: Change to "Hello World!!" and Save (Version 3)
            fileV1.writeText("Hello World!!")
            repository.saveVersionSnapshot(testFilePath, "Version 3")

            // Check Version History Storage (Test 2 requirements)
            val history = mockDao.versionHistory[testFilePath] ?: emptyList()
            val historyAsc = history.sortedBy { it.timestamp }

            if (historyAsc.size != 3) {
                throw AssertionError("Should have 3 versions, but found ${historyAsc.size}. Content: ${historyAsc.map { it.patchContent }}")
            }

            // Version 1 stores "Hello"
            if (historyAsc[0].patchContent != "Hello") {
                throw AssertionError("Version 1 should store full text 'Hello', but got '${historyAsc[0].patchContent}'")
            }

            // Version 2 stores delta (char-level since it's a single line)
            val patch2 = historyAsc[1].patchContent
            if (!patch2.contains("+ World") && !patch2.contains("+  World")) {
                throw AssertionError("Version 2 should store delta containing '+ World', but got '$patch2'")
            }
            if (patch2 == "Hello World") {
                throw AssertionError("Version 2 should NOT be the full 'Hello World'")
            }

            // Version 3 stores delta
            val patch3 = historyAsc[2].patchContent
            if (!patch3.contains("+ !!")) {
                throw AssertionError("Version 3 should store delta containing '+ !!', but got '$patch3'")
            }
            if (patch3 == "Hello World!!") {
                throw AssertionError("Version 3 should NOT be the full 'Hello World!!'")
            }

            // Rollback Verification
            val contentV1 = repository.getVersionContent(testFilePath, historyAsc[0].versionId)
            if (contentV1 != "Hello") {
                throw AssertionError("Rollback to Version 1 failed: expected 'Hello', but got '$contentV1'")
            }

            val contentV2 = repository.getVersionContent(testFilePath, historyAsc[1].versionId)
            if (contentV2 != "Hello World") {
                throw AssertionError("Rollback to Version 2 failed: expected 'Hello World', but got '$contentV2'")
            }

            val contentV3 = repository.getVersionContent(testFilePath, historyAsc[2].versionId)
            if (contentV3 != "Hello World!!") {
                throw AssertionError("Rollback to Version 3 failed: expected 'Hello World!!', but got '$contentV3'")
            }

            // Cleanup
            fileV1.delete()
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
