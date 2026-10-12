package com.fujiimport.app.domain

import org.junit.Assert.*
import org.junit.Test

class MetadataTest {
    @Test fun recognizesSupportedFormatsCaseInsensitively() {
        assertEquals(PhotoFormat.JPEG, PhotoFormat.fromFileName("DSCF0001.jPeG"))
        assertEquals(PhotoFormat.RAF, PhotoFormat.fromFileName("DSCF0001.RAF"))
        for (extension in listOf("HIF", "HEIC", "HEIF")) assertEquals(PhotoFormat.HEIF, PhotoFormat.fromFileName("photo.$extension"))
    }
    @Test fun excludesVideosAndUnrelatedFiles() {
        for (name in listOf("video.MOV", "video.MP4", "DCIM", ".DS_Store", "photo.jpg.tmp")) assertNull(PhotoFormat.fromFileName(name))
    }
    @Test fun jpegAndRawWithSameStemHaveDifferentKeys() {
        assertNotEquals(testPhoto(name = "DSCF0001.JPG").sourceKey, testPhoto(name = "DSCF0001.RAF").sourceKey)
    }
    @Test fun handlesAreNotReusedAcrossConnections() {
        val photo = testPhoto()
        val reconnect = photo.copy(sessionId = "session-b", handle = 20, parentHandle = 80)
        assertNotEquals(photo.id, reconnect.id)
        assertNotEquals(photo.cacheKey, reconnect.cacheKey)
        assertEquals(photo.sourceKey, reconnect.sourceKey)
    }
    @Test fun differentStorageOrMetadataIsDifferentSource() {
        val photo = testPhoto()
        assertNotEquals(photo.sourceKey, photo.copy(storageId = 2).sourceKey)
        assertNotEquals(photo.sourceKey, photo.copy(capturedAt = photo.capturedAt!! + 1).sourceKey)
        assertNotEquals(photo.sourceKey, photo.copy(size = 101).sourceKey)
    }
    @Test fun differentDirectoryWithSameFileMetadataIsNotDuplicate() {
        val photo = testPhoto()
        val otherFolder = photo.copy(directoryKey = "other-directory")
        assertNotEquals(photo.sourceKey, otherFolder.sourceKey)
        assertFalse(DuplicatePolicy.confirmed(otherFolder, testRecord(photo), 100))
    }
    @Test fun unresolvedDirectoryNeverConfirmsDuplicate() {
        val photo = testPhoto().copy(directoryKey = null)
        assertFalse(DuplicatePolicy.confirmed(photo, testRecord(photo), 100))
        assertNotEquals(photo.sourceKey, photo.copy(sessionId = "session-b").sourceKey)
    }
    @Test fun duplicateRequiresVerifiedLocalFileAndSourceMetadata() {
        val photo = testPhoto()
        assertTrue(DuplicatePolicy.confirmed(photo, testRecord(photo), 100))
        assertFalse(DuplicatePolicy.confirmed(photo, testRecord(photo), null))
        assertFalse(DuplicatePolicy.confirmed(photo, testRecord(photo), 99))
        assertFalse(DuplicatePolicy.confirmed(photo, testRecord(photo).copy(result = ImportResult.FAILED), 100))
        assertFalse(DuplicatePolicy.confirmed(photo, testRecord(photo).copy(originalName = "other.JPG"), 100))
    }
    @Test fun sameFileNameAloneIsInsufficient() {
        val photo = testPhoto()
        assertFalse(DuplicatePolicy.confirmed(photo, testRecord(photo).copy(capturedAt = photo.capturedAt!! + 1), 100))
        assertFalse(DuplicatePolicy.confirmed(photo, testRecord(photo).copy(sourceKey = "other-camera"), 100))
    }
    @Test fun missingCaptureTimeOrCameraIdentityNeverConfirmsDuplicate() {
        val missingTime = testPhoto().copy(capturedAt = null)
        assertFalse(DuplicatePolicy.confirmed(missingTime, testRecord(missingTime), 100))
        val missingCamera = testPhoto().copy(cameraKey = null)
        assertFalse(DuplicatePolicy.confirmed(missingCamera, testRecord(missingCamera), 100))
    }
    @Test fun missingDestinationUriNeverConfirmsDuplicate() {
        val photo = testPhoto()
        assertFalse(DuplicatePolicy.confirmed(photo, testRecord(photo).copy(localUri = null), 100))
    }
    @Test fun localNamesCannotEscapeDestinationDirectory() {
        assertEquals("DSCF0001.JPG", FileNames.safe("../../DCIM/DSCF0001.JPG"))
        assertEquals("photo.JPG", FileNames.safe("C:\\DCIM\\photo.JPG"))
        assertFalse(FileNames.safe("bad\u0000name:photo.JPG").contains('\u0000'))
        assertEquals("照片", FileNames.safe(".."))
    }
    @Test fun conflictsRenameSafelyAndKeepExtension() {
        assertEquals("DSCF0001 (3).RAF", FileNames.available("DSCF0001.RAF", setOf("dscf0001.raf", "DSCF0001 (1).RAF", "DSCF0001 (2).RAF")))
        assertEquals("DSCF0001.RAF", FileNames.available("DSCF0001.RAF", setOf("DSCF0001.JPG")))
    }
    @Test fun boundsLongNamesWithoutLosingFormat() {
        val name = FileNames.safe("A".repeat(300) + ".RAF")
        assertTrue(name.length <= 200)
        assertTrue(name.endsWith(".RAF"))
    }
}
