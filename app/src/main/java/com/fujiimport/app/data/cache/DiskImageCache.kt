package com.fujiimport.app.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class DiskImageCache(private val directory: File, val limit: Long) {
    private val lock = Mutex()

    suspend fun get(key: String): File? = withContext(Dispatchers.IO) {
        lock.withLock {
            File(directory, key).takeIf { it.isFile && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }
        }
    }

    suspend fun temporary(): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        File.createTempFile("image-", ".part", directory)
    }

    suspend fun put(key: String, bytes: ByteArray): File {
        val temp = withContext(NonCancellable) { temporary() }
        try {
            withContext(Dispatchers.IO) { temp.outputStream().use { it.write(bytes) } }
            return commit(key, temp)
        } finally { withContext(NonCancellable + Dispatchers.IO) { temp.delete() } }
    }

    suspend fun commit(key: String, temp: File): File = withContext(Dispatchers.IO) {
        lock.withLock {
            val target = File(directory, key)
            if (temp.length() <= 0 || temp.length() > limit) throw IOException("cache_size")
            if (target.exists() && !target.delete()) throw IOException("cache_replace")
            if (!temp.renameTo(target)) throw IOException("cache_commit")
            target.setLastModified(System.currentTimeMillis())
            val entries = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") }
            var total = entries.sumOf { it.length() }
            for (entry in entries.sortedBy { it.lastModified() }) {
                if (total <= limit) break
                val size = entry.length()
                if (entry != target && entry.delete()) total -= size
            }
            target
        }
    }

    suspend fun cleanTemporary() = withContext(Dispatchers.IO) {
        lock.withLock { directory.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() } }
    }

    suspend fun size(): Long = withContext(Dispatchers.IO) { lock.withLock { directory.listFiles().orEmpty().sumOf { it.length() } } }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock { directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() } }
    }
}
