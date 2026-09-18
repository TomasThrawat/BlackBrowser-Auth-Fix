package com.tomasthrawat.blackbrowser

/**
 * History API kept for the existing UI contract.
 *
 * Browsing history is intentionally not persisted.
 * No SharedPreferences, files, logs, tracing, or telemetry are used here.
 */
data class HistoryEntry(
    val title: String,
    val url: String,
    val timestamp: Long
)

object HistoryStore {
    fun getAll(context: android.content.Context): List<HistoryEntry> = emptyList()

    fun add(
        context: android.content.Context,
        title: String,
        url: String
    ) {
        // Intentionally no-op: browsing history is not persisted.
    }

    fun clear(context: android.content.Context) {
        // Intentionally no-op: there is no persisted history to clear.
    }
}
