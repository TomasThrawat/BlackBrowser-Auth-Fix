package com.tomasthrawat.blackbrowser

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val title: String, val url: String, val timestamp: Long)

/**
 * Persists browsing history (title, url, timestamp) as a JSON array in SharedPreferences.
 * Newest entries returned first; capped so it never grows unbounded.
 */

object NavigationTrace {
    private const val TAG = "BlackBrowserTrace"
    private const val FILE_NAME = "BlackBrowser-WebView-Diagnostics.log"
    private const val MIME = "text/plain"
    private val RELATIVE_PATH = Environment.DIRECTORY_DOWNLOADS + "/BlackBrowser"
    private const val PREFS = "blackbrowser_diagnostics"
    private const val KEY_URI = "diagnostics_uri"
    private val lock = Any()
    private var sequence = 0L

    fun record(context: Context, title: String, url: String) {
        recordDetails(context, title, url, null)
    }

    fun recordDetails(context: Context, title: String, url: String, details: String?) {
        val safeUrl = try {
            val uri = android.net.Uri.parse(url)
            val base = buildString {
                if (!uri.scheme.isNullOrBlank()) append(uri.scheme).append("://")
                if (!uri.host.isNullOrBlank()) append(uri.host)
                if (uri.port != -1) append(":").append(uri.port)
                uri.path?.let { append(it) }
            }
            if (!uri.query.isNullOrBlank()) base + "?[query-redacted]" else base
        } catch (_: Exception) {
            "<invalid-url>"
        }

        synchronized(lock) {
            sequence += 1
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
            val event = title.ifBlank { "EVENT" }.replace("\n", " ").replace("\r", " ")
            val detailText = details?.replace("\n", " ")?.replace("\r", " ")?.trim()?.takeIf { it.isNotEmpty() }
            val line = buildString {
                append("#$sequence $stamp [$event] url=$safeUrl")
                if (detailText != null) append(" details=").append(detailText)
                append("\n")
            }
            Log.d(TAG, line.trimEnd())
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = getCanonicalUri(context)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri, "wa")?.use {
                            it.write(line.toByteArray(Charsets.UTF_8))
                        }
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS + "/BlackBrowser"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    java.io.File(dir, FILE_NAME).appendText(line, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                Log.e(TAG, "trace-write-failed:" + e.javaClass.simpleName)
            }
        }
    }

    @android.annotation.SuppressLint("Range")
    private fun getCanonicalUri(context: Context): android.net.Uri? {
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val saved = prefs.getString(KEY_URI, null)
        if (!saved.isNullOrBlank()) {
            val savedUri = android.net.Uri.parse(saved)
            try {
                resolver.openOutputStream(savedUri, "wa")?.use { return savedUri }
            } catch (_: Exception) {
                prefs.edit().remove(KEY_URI).apply()
            }
        }

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val matches = mutableListOf<android.net.Uri>()

        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            MediaStore.Downloads.RELATIVE_PATH + "=? AND " +
                MediaStore.Downloads.DISPLAY_NAME + "=?",
            arrayOf(RELATIVE_PATH + "/", FILE_NAME),
            MediaStore.Downloads._ID + " ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Downloads._ID)
            while (cursor.moveToNext()) {
                matches += android.net.Uri.withAppendedPath(
                    collection,
                    cursor.getLong(idColumn).toString()
                )
            }
        }

        val uri = if (matches.isNotEmpty()) {
            val keep = matches.first()
            matches.drop(1).forEach { extra ->
                runCatching { resolver.delete(extra, null, null) }
            }
            keep
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.insert(collection, values)
        }

        uri?.let {
            prefs.edit().putString(KEY_URI, it.toString()).apply()
        }
        return uri
    }
}

object HistoryStore {
    private const val PREFS_NAME = "history_prefs"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    fun getAll(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(o.optString("title"), o.optString("url"), o.optLong("ts"))
            }.asReversed()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, title: String, url: String) {
        NavigationTrace.record(context, title, url)
        if (url.isBlank() || url == "about:blank") return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, null)
        val arr = try {
            if (raw != null) JSONArray(raw) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }

        if (arr.length() > 0) {
            val last = arr.getJSONObject(arr.length() - 1)
            if (last.optString("url") == url) return
        }

        val entry = JSONObject().apply {
            put("title", title)
            put("url", url)
            put("ts", System.currentTimeMillis())
        }
        arr.put(entry)

        val trimmed = if (arr.length() > MAX_ENTRIES) {
            val newArr = JSONArray()
            for (i in (arr.length() - MAX_ENTRIES) until arr.length()) newArr.put(arr.get(i))
            newArr
        } else arr

        prefs.edit().putString(KEY_ENTRIES, trimmed.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ENTRIES).apply()
    }
}
