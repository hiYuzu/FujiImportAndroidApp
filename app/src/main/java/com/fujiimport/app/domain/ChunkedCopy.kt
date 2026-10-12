package com.fujiimport.app.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.OutputStream

class PartialReadException(cause: Throwable? = null) : IOException("partial_read", cause)

/** Bounded memory, including short reads. The camera adapter keeps its I/O lock for the whole call. */
suspend fun copyExactBytes(
    size: Long,
    output: OutputStream,
    read: suspend (offset: Long, requested: Int, buffer: ByteArray) -> Long,
    onProgress: (Long) -> Unit,
) {
    require(size > 0)
    val buffer = ByteArray(256 * 1024)
    var offset = 0L
    while (offset < size) {
        currentCoroutineContext().ensureActive()
        val requested = minOf(buffer.size.toLong(), size - offset).toInt()
        val received = read(offset, requested, buffer)
        currentCoroutineContext().ensureActive()
        if (received <= 0 || received > requested) throw PartialReadException()
        output.write(buffer, 0, received.toInt())
        offset += received
        onProgress(offset)
    }
    currentCoroutineContext().ensureActive()
    output.flush()
}
