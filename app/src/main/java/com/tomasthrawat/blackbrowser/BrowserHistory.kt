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

private object NavigationTrace {
    private const val TAG = "BlackBrowserTrace"
    private const val FILE_NAME = "BlackBrowser-WebView-Trace.log"
    private const val MIME = "text/plain"
    private val lock = Any()
    private var sequence = 0L
    fun record(context: Context, title: String, url: String) {
        val safeUrl = try {
            val uri = android.net.Uri.parse(url)
            val base = buildString {
                if (!uri.scheme.isNullOrBlank()) append(uri.scheme).append("://")
                if (!uri.host.isNullOrBlank()) append(uri.host)
                if (uri.port != -1) append(":").append(uri.port)
                uri.path?.let { append(it) }
            }
            if (!uri.query.isNullOrBlank()) base + "?[query-redacted]" else base
        } catch (_: Exception) { "<invalid-url>" }
        val line = synchronized(lock) {
            sequence += 1
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
            "#" + sequence + " " + stamp + " [PAGE_FINISHED] url=" + safeUrl + " title=" + title.take(120).replace("\n", " ") + "\n"
        }
        Log.d(TAG, line.trimEnd())
        synchronized(lock) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    var uri: android.net.Uri? = null
                    resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                        MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.MIME_TYPE + "=?",
                        arrayOf(FILE_NAME, MIME), null)?.use { c ->
                        if (c.moveToFirst()) uri = android.net.Uri.withAppendedPath(collection, c.getLong(0).toString())
                    }
                    if (uri == null) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                            put(MediaStore.Downloads.MIME_TYPE, MIME)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BlackBrowser")
                        }
                        uri = resolver.insert(collection, values)
                    }
                    uri?.let { resolver.openOutputStream(it, "wa")?.use { stream -> stream.write(line.toByteArray()) } }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    java.io.File(dir, FILE_NAME).appendText(line)
                }
            } catch (e: Exception) {
                Log.e(TAG, "trace-write-failed:" + e.javaClass.simpleName)
            }
        }
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
