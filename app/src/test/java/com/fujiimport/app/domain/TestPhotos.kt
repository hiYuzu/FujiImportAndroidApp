package com.fujiimport.app.domain

fun testPhoto(handle: Int = 1, name: String = "DSCF${handle.toString().padStart(4, '0')}.JPG") = Photo(
    sessionId = "session-a", cameraKey = "camera-hash", cameraModel = "X-T5", storageId = 1,
    handle = handle, parentHandle = 4, directoryKey = "directory-hash", name = name, format = PhotoFormat.fromFileName(name)!!,
    size = 100, capturedAt = 1_700_000_000_000, modifiedAt = 1_700_000_000_000,
    width = 7728, height = 5152, thumbnailSize = 8000,
)

fun testRecord(photo: Photo) = ImportRecord(photo.sourceKey, photo.name, photo.size, photo.capturedAt,
    1_700_000_100_000, ImportResult.SUCCESS, "content://media/external/images/media/1", null)
