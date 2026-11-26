package br.com.launcher.tv.data

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/** Persists Saímo TV favorite channel names independently of the screen UI. */
class FavoriteChannelsRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("launcher_v2", Context.MODE_PRIVATE)

    fun load(): MutableList<String> {
        val json = preferences.getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            MutableList(array.length()) { index -> array.optString(index).trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(channels: List<String>) {
        val array = JSONArray()
        channels.distinct().forEach { channel -> array.put(channel) }
        preferences.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "saimo_favorite_channels"
    }
}
