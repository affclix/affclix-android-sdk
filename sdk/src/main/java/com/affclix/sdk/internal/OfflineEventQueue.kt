package com.affclix.sdk.internal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/**
 * SQLite-backed persistent event queue.
 *
 * Events and installs are written here before their first send, and other
 * requests when delivery fails (network down, timeout).
 * [EventQueueWorker] (WorkManager, CONNECTED constraint) drains the queue
 * when connectivity is restored.
 *
 * Design:
 *   - Complete request URL is stored so the worker is self-contained and never
 *     needs the SDK to be initialized or its config to be available.
 *   - POST body (JSON) stored alongside URL for /m/ev events.
 *   - URL and body are encrypted at rest ([QueueCipher]); a row that can no
 *     longer be decrypted is dropped.
 *   - Capped at MAX_QUEUE entries; oldest entry dropped when full.
 *   - Entries with MAX_RETRIES failures are pruned to prevent stale accumulation.
 */
internal class OfflineEventQueue(context: Context) {

    companion object {
        private const val DB_NAME    = "affclix_event_queue.db"
        private const val DB_VERSION = 1
        private const val TABLE      = "event_queue"

        private const val COL_ID      = "id"
        private const val COL_URL     = "url"
        private const val COL_BODY    = "body"
        private const val COL_METHOD  = "method"
        private const val COL_RETRIES = "retries"
        private const val COL_TS      = "created_at"

        const val MAX_RETRIES = 3
        const val MAX_QUEUE   = 500
    }

    data class QueuedEvent(
        val id:      Long,
        val url:     String,
        val body:    String?,
        val method:  String,
        val retries: Int
    )

    private val db: SQLiteDatabase = DbHelper(context).writableDatabase

    fun enqueue(url: String, body: String? = null, method: String = "GET"): Boolean =
        enqueueForId(url, body, method) != null

    /** Saves a request; its row ID, or null when the write failed. */
    fun enqueueForId(url: String, body: String? = null, method: String = "GET"): Long? {
        trimIfFull()
        val cv = ContentValues().apply {
            put(COL_URL,     QueueCipher.encrypt(url))
            put(COL_BODY,    body?.let { QueueCipher.encrypt(it) })
            put(COL_METHOD,  method)
            put(COL_RETRIES, 0)
            put(COL_TS,      System.currentTimeMillis())
        }
        return db.insert(TABLE, null, cv).takeIf { it != -1L }
    }

    fun peek(limit: Int = 50): List<QueuedEvent> {
        val result = mutableListOf<QueuedEvent>()
        val undecryptable = mutableListOf<Long>()
        db.rawQuery(
            "SELECT $COL_ID,$COL_URL,$COL_BODY,$COL_METHOD,$COL_RETRIES " +
            "FROM $TABLE ORDER BY $COL_ID ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id   = cursor.getLong(0)
                val url  = QueueCipher.decrypt(cursor.getString(1))
                val rawBody = cursor.getString(2)
                val body    = rawBody?.let { QueueCipher.decrypt(it) }
                if (url == null || (rawBody != null && body == null)) {
                    undecryptable += id
                    continue
                }
                result += QueuedEvent(
                    id      = id,
                    url     = url,
                    body    = body,
                    method  = cursor.getString(3),
                    retries = cursor.getInt(4)
                )
            }
        }
        undecryptable.forEach { remove(it) }
        return result
    }

    fun remove(id: Long) {
        db.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun incrementRetry(id: Long) {
        db.execSQL(
            "UPDATE $TABLE SET $COL_RETRIES = $COL_RETRIES + 1 WHERE $COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun pruneExpired() {
        db.delete(TABLE, "$COL_RETRIES >= ?", arrayOf(MAX_RETRIES.toString()))
    }

    /**
     * Drops saved events and installs, for a data deletion: sent afterwards, they
     * would bring back what the user asked to erase. Other requests stay.
     */
    fun removeEvents(eventPath: String) {
        peek(MAX_QUEUE).filter { Uri.parse(it.url).path == eventPath }.forEach { remove(it.id) }
    }

    fun size(): Int = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun trimIfFull() {
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (count >= MAX_QUEUE) {
            db.execSQL("DELETE FROM $TABLE WHERE $COL_ID = (SELECT MIN($COL_ID) FROM $TABLE)")
        }
    }

    private class DbHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE (
                    $COL_ID      INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_URL     TEXT    NOT NULL,
                    $COL_BODY    TEXT,
                    $COL_METHOD  TEXT    NOT NULL DEFAULT 'GET',
                    $COL_RETRIES INTEGER NOT NULL DEFAULT 0,
                    $COL_TS      INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_queue_retries ON $TABLE ($COL_RETRIES)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
