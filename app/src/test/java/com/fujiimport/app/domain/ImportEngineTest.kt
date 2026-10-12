package com.fujiimport.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImportEngineTest {
    private class Store : ImportStore {
        val created = mutableListOf<Int>()
        val published = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val records = mutableListOf<Pair<Int, ImportResult>>()
        var duplicates = emptySet<Int>()
        var copyFailure: Pair<Int, Exception>? = null
        var publishFailure: Int? = null
        var blockCopy: Int? = null
        var createGate: CompletableDeferred<Unit>? = null
        var publishGate: CompletableDeferred<Unit>? = null
        var failRecords = false
        override suspend fun findDuplicate(photo: Photo): String? = if (photo.handle in duplicates) "existing:${photo.handle}" else null
        override suspend fun create(photo: Photo): PendingImport {
            created.add(photo.handle); createGate?.await()
            return PendingImport("pending:${photo.handle}", photo.name)
        }
        override suspend fun copy(photo: Photo, target: PendingImport, onBytes: (Long) -> Unit) {
            onBytes(photo.size / 2)
            if (photo.handle == blockCopy) awaitCancellation()
            copyFailure?.takeIf { it.first == photo.handle }?.let { throw it.second }
            onBytes(photo.size)
        }
        override suspend fun publish(photo: Photo, target: PendingImport) {
            publishGate?.await()
            if (photo.handle == publishFailure) throw IOException("size_mismatch")
            published.add(target.uri)
        }
        override suspend fun discard(target: PendingImport) { discarded.add(target.uri) }
        override suspend fun record(photo: Photo, result: ImportResult, uri: String?, message: String?) {
            if (failRecords) throw IOException("history")
            records.add(photo.handle to result)
        }
    }

    @Test fun successfulFilesPublishOnceAndAreRecorded() = runTest {
        val store = Store()
        val result = ImportEngine(store, {}).execute(listOf(testPhoto(1), testPhoto(2)))
        assertEquals(2, result.succeeded); assertEquals(2, result.processed)
        assertEquals(ImportPhase.FINISHED, result.phase)
        assertEquals(listOf("pending:1", "pending:2"), store.published)
        assertTrue(store.discarded.isEmpty())
        assertTrue(store.records.all { it.second == ImportResult.SUCCESS })
    }
    @Test fun corruptFileIsCleanedAndDoesNotStopRemainingFiles() = runTest {
        val store = Store().apply { copyFailure = 1 to IOException("corrupt") }
        val result = ImportEngine(store, {}).execute(listOf(testPhoto(1), testPhoto(2), testPhoto(3)))
        assertEquals(1, result.failed); assertEquals(2, result.succeeded); assertEquals(3, result.processed)
        assertEquals(listOf("pending:1"), store.discarded)
        assertEquals(listOf("pending:2", "pending:3"), store.published)
    }
    @Test fun confirmedDuplicatesAreSkippedWithoutCreatingFiles() = runTest {
        val store = Store().apply { duplicates = setOf(1) }
        val result = ImportEngine(store, {}).execute(listOf(testPhoto(1), testPhoto(2)))
        assertEquals(1, result.skipped); assertEquals(1, result.succeeded)
        assertEquals(listOf(2), store.created)
        assertTrue(store.records.contains(1 to ImportResult.SKIPPED))
    }
    @Test fun sizeMismatchNeverPublishesAndIsCleaned() = runTest {
        val store = Store().apply { publishFailure = 1 }
        val result = ImportEngine(store, {}).execute(listOf(testPhoto(1), testPhoto(2)))
        assertEquals(1, result.failed); assertEquals(1, result.succeeded)
        assertEquals(listOf("pending:1"), store.discarded)
        assertFalse(store.published.contains("pending:1"))
    }
    @Test fun spaceFailureStopsNewWritesAndReportsRemainingFailures() = runTest {
        val store = Store().apply { copyFailure = 1 to StorageFullException() }
        val result = ImportEngine(store, {}).execute(listOf(testPhoto(1), testPhoto(2)))
        assertEquals(2, result.failed)
        assertEquals(listOf(1), store.created)
        assertEquals(listOf("pending:1"), store.discarded)
    }
    @Test fun disconnectedSessionStopsNewWrites() = runTest {
        val store = Store().apply { copyFailure = 1 to CameraUnavailableException() }
        val result = ImportEngine(store, {}).execute(listOf(testPhoto(1), testPhoto(2)))
        assertEquals(2, result.failed); assertEquals(listOf(1), store.created)
        assertTrue(store.published.isEmpty())
    }
    @Test fun cancellationDiscardsCurrentFileAndKeepsSuccessfulFiles() = runTest {
        val store = Store().apply { blockCopy = 2 }
        var latest: ImportProgress? = null
        val task = launch { ImportEngine(store, { latest = it }).execute(listOf(testPhoto(1), testPhoto(2), testPhoto(3))) }
        runCurrent(); task.cancelAndJoin()
        assertEquals(listOf("pending:1"), store.published)
        assertEquals(listOf("pending:2"), store.discarded)
        assertEquals(1, latest!!.succeeded); assertEquals(1, latest!!.processed); assertEquals(2, latest!!.cancelled)
        assertEquals(ImportPhase.CANCELLED, latest!!.phase)
        assertTrue(store.records.contains(2 to ImportResult.CANCELLED))
    }
    @Test fun cancellationDuringCreationStillCleansCreatedRow() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = Store().apply { createGate = gate }
        val task = launch { ImportEngine(store, {}).execute(listOf(testPhoto())) }
        runCurrent(); task.cancel(); gate.complete(Unit); task.join()
        assertEquals(listOf("pending:1"), store.discarded)
        assertTrue(store.published.isEmpty())
    }
    @Test fun cancellationDuringFinalCommitPreservesAndCountsCommittedFile() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = Store().apply { publishGate = gate }
        var latest: ImportProgress? = null
        val task = launch { ImportEngine(store, { latest = it }).execute(listOf(testPhoto(1), testPhoto(2))) }
        runCurrent(); task.cancel(); gate.complete(Unit); task.join()
        assertEquals(listOf("pending:1"), store.published)
        assertTrue(store.discarded.isEmpty())
        assertEquals(1, latest!!.succeeded); assertEquals(1, latest!!.processed); assertEquals(1, latest!!.cancelled)
    }
    @Test fun historyFailureDoesNotDeleteOrMisreportSuccessfulFile() = runTest {
        val store = Store().apply { failRecords = true }
        var warnings = 0
        val result = ImportEngine(store, {}, { warnings++ }).execute(listOf(testPhoto()))
        assertEquals(1, result.succeeded); assertEquals(1, warnings)
        assertTrue(store.discarded.isEmpty())
        assertEquals(listOf("pending:1"), store.published)
    }
    @Test fun emptyQueueCompletesWithoutWriting() = runTest {
        val store = Store()
        val result = ImportEngine(store, {}).execute(emptyList())
        assertEquals(ImportPhase.FINISHED, result.phase); assertEquals(1f, result.fraction)
        assertTrue(store.created.isEmpty())
    }
    @Test fun progressIncludesBytesWhileCurrentFileIsInFlight() = runTest {
        val updates = mutableListOf<ImportProgress>()
        ImportEngine(Store(), { updates.add(it) }).execute(listOf(testPhoto()))
        assertTrue(updates.any { it.fileBytes == 50L && it.fraction == .5f })
        assertEquals(1f, updates.last().fraction)
    }
}
