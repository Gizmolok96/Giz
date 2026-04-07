package com.footballanalyzer.app.data.local

import android.content.Context
import com.footballanalyzer.app.data.model.HistoryEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistoryStorage(context: Context) {

    private val prefs = context.getSharedPreferences("history_storage", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val KEY = "entries"

    fun getAllEntries(): List<HistoryEntry> {
        return try {
            val raw = prefs.getString(KEY, null) ?: return emptyList()
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson(raw, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveEntry(entry: HistoryEntry) {
        val list = getAllEntries().toMutableList()
        list.removeAll { it.matchId == entry.matchId }
        list.add(0, entry)
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun updateEntry(entry: HistoryEntry) {
        val list = getAllEntries().toMutableList()
        val idx = list.indexOfFirst { it.matchId == entry.matchId }
        if (idx >= 0) list[idx] = entry
        else list.add(0, entry)
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun deleteEntry(matchId: String) {
        val list = getAllEntries().toMutableList()
        list.removeAll { it.matchId == matchId }
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    /**
     * Bulk delete — reads the list once, removes all matching IDs, writes once.
     * Use instead of calling deleteEntry() in a loop to avoid TransactionTooLargeException
     * and ANR crashes on large history sets.
     */
    fun deleteEntries(matchIds: Set<String>) {
        if (matchIds.isEmpty()) return
        val list = getAllEntries().toMutableList()
        list.removeAll { it.matchId in matchIds }
        prefs.edit().putString(KEY, gson.toJson(list)).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY).apply()
    }
}
