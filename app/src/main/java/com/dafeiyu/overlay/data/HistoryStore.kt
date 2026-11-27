package com.dafeiyu.overlay.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val mode: String,
    val modelId: String,
    val latencyMs: Long,
    val summary: String,
    val success: Boolean = true
)

class HistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(loadEntries())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun record(
        mode: String,
        modelId: String,
        latencyMs: Long,
        summary: String,
        success: Boolean = true
    ) {
        val next = (listOf(
            HistoryEntry(
                mode = mode,
                modelId = modelId,
                latencyMs = latencyMs,
                summary = summary.take(240),
                success = success
            )
        ) + _entries.value).take(MAX_ENTRIES)
        _entries.value = next
        saveEntries(next)
    }

    fun clear() {
        _entries.value = emptyList()
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun loadEntries(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        HistoryEntry(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            timestampMs = obj.optLong("ts", System.currentTimeMillis()),
                            mode = obj.optString("mode", "未知"),
                            modelId = obj.optString("model", ""),
                            latencyMs = obj.optLong("latency", 0L),
                            summary = obj.optString("summary", ""),
                            success = obj.optBoolean("ok", true)
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveEntries(list: List<HistoryEntry>) {
        val arr = JSONArray().apply {
            list.forEach { e ->
                put(
                    JSONObject().apply {
                        put("id", e.id)
                        put("ts", e.timestampMs)
                        put("mode", e.mode)
                        put("model", e.modelId)
                        put("latency", e.latencyMs)
                        put("summary", e.summary)
                        put("ok", e.success)
                    }
                )
            }
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "dafeiyu_history_prefs"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 30
    }
}
