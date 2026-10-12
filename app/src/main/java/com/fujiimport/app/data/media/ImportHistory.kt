package com.fujiimport.app.data.media

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fujiimport.app.domain.ImportRecord
import com.fujiimport.app.domain.ImportResult
import com.fujiimport.app.domain.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class ImportHistory(context: Context) : SQLiteOpenHelper(context, "import-history.db", null, 1) {
    private val revision = MutableStateFlow(0)
    val changes = revision.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE imports (
            id INTEGER PRIMARY KEY AUTOINCREMENT, source_key TEXT NOT NULL,
            original_name TEXT NOT NULL, size INTEGER NOT NULL, captured_at INTEGER,
            imported_at INTEGER NOT NULL, result TEXT NOT NULL, local_uri TEXT, message TEXT
        )""")
        db.execSQL("CREATE INDEX imports_source ON imports(source_key, result, id)")
        db.execSQL("CREATE TABLE pending_files (uri TEXT PRIMARY KEY NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    suspend fun add(photo: Photo, result: ImportResult, uri: String?, message: String?) = withContext(Dispatchers.IO) {
        writableDatabase.insertOrThrow("imports", null, ContentValues().apply {
            put("source_key", photo.sourceKey); put("original_name", photo.name); put("size", photo.size)
            photo.capturedAt?.let { put("captured_at", it) }
            put("imported_at", System.currentTimeMillis()); put("result", result.name)
            put("local_uri", uri); put("message", message)
        })
        revision.update { it + 1 }
    }

    suspend fun successful(sourceKey: String): ImportRecord? = withContext(Dispatchers.IO) {
        readableDatabase.query("imports", null, "source_key=? AND result IN ('SUCCESS','SKIPPED')",
            arrayOf(sourceKey), null, null, "id DESC", "1").use { cursor ->
            if (cursor.moveToFirst()) cursor.record() else null
        }
    }

    suspend fun latest(): List<ImportRecord> = withContext(Dispatchers.IO) {
        readableDatabase.query("imports", null, null, null, null, null, "id DESC", "200").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.record()) }
        }
    }

    suspend fun successfulRecords(): List<ImportRecord> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT * FROM imports WHERE id IN (SELECT MAX(id) FROM imports WHERE result IN ('SUCCESS','SKIPPED') GROUP BY source_key)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.record()) }
        }
    }

    suspend fun addPending(uri: String) = withContext(Dispatchers.IO) {
        writableDatabase.insertOrThrow("pending_files", null, ContentValues().apply { put("uri", uri) })
    }

    suspend fun removePending(uri: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("pending_files", "uri=?", arrayOf(uri))
    }

    suspend fun pending(): List<String> = withContext(Dispatchers.IO) {
        readableDatabase.query("pending_files", arrayOf("uri"), null, null, null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    private fun Cursor.record(): ImportRecord = ImportRecord(
        sourceKey = getString(getColumnIndexOrThrow("source_key")),
        originalName = getString(getColumnIndexOrThrow("original_name")),
        size = getLong(getColumnIndexOrThrow("size")),
        capturedAt = getColumnIndexOrThrow("captured_at").let { if (isNull(it)) null else getLong(it) },
        importedAt = getLong(getColumnIndexOrThrow("imported_at")),
        result = ImportResult.valueOf(getString(getColumnIndexOrThrow("result"))),
        localUri = getString(getColumnIndexOrThrow("local_uri")),
        message = getString(getColumnIndexOrThrow("message")),
    )
}
