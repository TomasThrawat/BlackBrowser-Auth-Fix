package com.tomasthrawat.blackbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(val title: String, val url: String, val timestamp: Long)

/**
 * Persists browsing history (title, url, timestamp) as a JSON array in SharedPreferences.
 * Newest entries returned first; capped so it never grows unbounded.
 */
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
