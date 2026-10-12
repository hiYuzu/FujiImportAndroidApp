package com.fujiimport.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChunkedCopyTest {
    @Test fun shortReadsProduceExactOriginalBytes() = runTest {
        val source = ByteArray(850_003) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        copyExactBytes(source.size.toLong(), output, { offset, requested, buffer ->
            val count = minOf(requested, 7_991)
            source.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
            count.toLong()
        }, { progress.add(it) })
        assertArrayEquals(source, output.toByteArray())
        assertEquals(source.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> before < after })
    }
    @Test fun largeTransferUsesOneBoundedBuffer() = runTest {
        val expected = 31L * 1024 * 1024 + 101
        var written = 0L
        var firstBuffer: ByteArray? = null
        val output = object : OutputStream() {
            override fun write(value: Int) { written++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { written += length }
        }
        copyExactBytes(expected, output, { _, requested, buffer ->
            assertTrue(buffer.size <= 256 * 1024)
            if (firstBuffer == null) firstBuffer = buffer else assertSame(firstBuffer, buffer)
            requested.toLong()
        }, {})
        assertEquals(expected, written)
    }
    @Test fun zeroByteReadFailsInsteadOfLoopingOrPublishingTruncatedData() = runTest {
        try { copyExactBytes(100, ByteArrayOutputStream(), { _, _, _ -> 0 }, {}); fail("expected failure") }
        catch (_: IOException) { }
    }
    @Test fun oversizedReadIsRejectedBeforeWrite() = runTest {
        val output = ByteArrayOutputStream()
        try { copyExactBytes(100, output, { _, requested, _ -> requested + 1L }, {}); fail("expected failure") }
        catch (_: IOException) { }
        assertEquals(0, output.size())
    }
    @Test fun cancellationStopsBeforeNextChunk() = runTest {
        var reads = 0
        val output = ByteArrayOutputStream()
        val task = launch {
            try { copyExactBytes(20_000_000, output, { _, requested, _ -> reads++; if (reads == 2) awaitCancellation(); requested.toLong() }, {}) }
            catch (cancelled: CancellationException) { throw cancelled }
        }
        runCurrent()
        assertTrue(task.isActive)
        task.cancelAndJoin()
        val stoppedAt = reads
        runCurrent()
        assertEquals(stoppedAt, reads)
        assertEquals(2, reads)
        assertEquals(256 * 1024, output.size())
    }
}
