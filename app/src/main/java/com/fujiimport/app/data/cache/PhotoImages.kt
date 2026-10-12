package com.fujiimport.app.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.fujiimport.app.data.mtp.MtpCamera
import com.fujiimport.app.domain.Photo
import com.fujiimport.app.domain.PhotoFormat
import com.fujiimport.app.util.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class PreviewData(val bitmap: Bitmap?, val note: String?, val metadata: Map<String, String> = emptyMap(), val retryAllowed: Boolean = false)

class PhotoImages(
    private val context: Context,
    private val camera: MtpCamera,
    private val diagnostics: Diagnostics,
    private val photos: () -> List<Photo>,
    private val beforeRead: suspend () -> Unit,
) {
    private val thumbnails = DiskImageCache(File(context.cacheDir, "thumbnails"), 96L * 1024 * 1024)
    private val previews = DiskImageCache(File(context.cacheDir, "previews"), 384L * 1024 * 1024)
    private val thumbnailRequests = Semaphore(2)
    private val previewLock = Mutex()
    private val memory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun initialize() { thumbnails.cleanTemporary(); previews.cleanTemporary() }

    suspend fun thumbnail(photo: Photo): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(photo.cacheKey)?.let { return@withContext it }
        thumbnailRequests.withPermit {
            memory.get(photo.cacheKey)?.let { return@withPermit it }
            val key = "${photo.cacheKey}.thumb"
            thumbnails.get(key)?.let { decode(it, 400) }?.let { bitmap ->
                memory.put(photo.cacheKey, bitmap)
                return@withPermit bitmap
            }
            beforeRead()
            val bytes = try {
                camera.thumbnail(photo) ?: companion(photo)?.let { camera.thumbnail(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.event("thumbnail_failed", error)
                null
            }
            if (bytes == null || bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return@withPermit null
            val file = thumbnails.put(key, bytes)
            decode(file, 400)?.also { memory.put(photo.cacheKey, it) }
        }
    }

    suspend fun preview(photo: Photo): PreviewData = withContext(Dispatchers.IO) {
        previewLock.withLock {
            if (photo.format == PhotoFormat.RAF) {
                val jpeg = companion(photo)
                if (jpeg != null) {
                    try {
                        val result = fullImage(jpeg)
                        return@withLock result.copy(note = "RAW 使用同目录 JPEG 伴生文件预览", metadata = emptyMap())
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { diagnostics.event("raw_companion_failed", error) }
                }
                val bitmap = thumbnail(photo)
                return@withLock PreviewData(bitmap, if (bitmap == null) "RAW 预览不可用，仍可导入原文件" else "RAW 使用相机缩略图预览；原文件仍可导入", retryAllowed = bitmap == null)
            }
            try { fullImage(photo) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                diagnostics.event("preview_failed", error)
                PreviewData(thumbnail(photo), "高清预览暂不可用，可重试或直接导入原文件", retryAllowed = true)
            }
        }
    }

    private suspend fun fullImage(photo: Photo): PreviewData {
        val key = "${photo.cacheKey}.image"
        var file = previews.get(key)
        if (file == null) {
            if (photo.size <= 0 || photo.size > previews.limit || StatFs(context.cacheDir.path).availableBytes < photo.size + 16L * 1024 * 1024) {
                throw IOException("preview_space_or_size")
            }
            beforeRead()
            val temp = withContext(NonCancellable) { previews.temporary() }
            try {
                ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use { descriptor ->
                    camera.transfer(photo, descriptor) {}
                    if (descriptor.statSize != photo.size) throw IOException("preview_size")
                }
                currentCoroutineContext().ensureActive()
                file = previews.commit(key, temp)
            } finally { withContext(NonCancellable + Dispatchers.IO) { temp.delete() } }
        }
        val bitmap = decode(file, 2560) ?: run { withContext(Dispatchers.IO) { file.delete() }; throw IOException("preview_decode") }
        val metadata = try { readExif(file) } catch (error: Exception) { diagnostics.event("exif_failed", error); emptyMap() }
        return PreviewData(bitmap, null, metadata)
    }

    suspend fun clear() = previewLock.withLock {
        thumbnails.clear(); previews.clear(); memory.evictAll()
    }

    suspend fun diskSize(): Long = thumbnails.size() + previews.size()

    private fun companion(photo: Photo): Photo? = if (photo.format != PhotoFormat.RAF) null else photos().firstOrNull {
        it.format == PhotoFormat.JPEG && it.sessionId == photo.sessionId && it.storageId == photo.storageId &&
            it.parentHandle == photo.parentHandle && it.name.substringBeforeLast('.').equals(photo.name.substringBeforeLast('.'), ignoreCase = true)
    }

    private fun decode(file: File, longestSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > longestSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = try { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        return if (transform.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
    }

    private fun readExif(file: File): Map<String, String> {
        val exif = ExifInterface(file)
        return buildMap {
            for ((label, tag) in listOf(
                "相机型号" to ExifInterface.TAG_MODEL, "拍摄日期（EXIF）" to ExifInterface.TAG_DATETIME_ORIGINAL,
                "焦距（mm）" to ExifInterface.TAG_FOCAL_LENGTH, "光圈" to ExifInterface.TAG_F_NUMBER,
                "曝光时间（秒）" to ExifInterface.TAG_EXPOSURE_TIME, "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            )) exif.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { put(label, it) }
        }
    }
}
