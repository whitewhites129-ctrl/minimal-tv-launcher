package br.com.launcher.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Busca logos de emissoras brasileiras em uma base pública de metadados.
 * Os dados privados do Saímo não são acessíveis entre aplicativos; por isso
 * a associação é feita automaticamente pelo nome do favorito.
 */
object ChannelLogos {
    private const val PREFS = "launcher_v2"
    private const val URLS_KEY = "channel_logo_urls"
    private const val SYNC_KEY = "channel_logo_sync"
    private const val CHANNELS_URL = "https://iptv-org.github.io/api/channels.json"
    private const val LOGOS_URL = "https://iptv-org.github.io/api/logos.json"
    private const val MAX_CACHE_AGE = 3L * 24L * 60L * 60L * 1000L

    private val worker = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun normalize(value: String): String {
        val plain = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("\\b(hd|fhd|uhd|4k|sd)\\b", RegexOption.IGNORE_CASE), "")
        return plain.replace(Regex("[^a-z0-9]"), "")
    }

    private fun savedUrls(context: Context): JSONObject = try {
        JSONObject(prefs(context).getString(URLS_KEY, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun fetchText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LauncherLite/0.4 AndroidTV")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun logoFile(context: Context, key: String): File {
        val directory = File(context.applicationContext.filesDir, "channel_logos")
        if (!directory.exists()) directory.mkdirs()
        return File(directory, "${key.hashCode().toUInt().toString(16)}.png")
    }

    private fun manualId(key: String): String? = when (key) {
        "sbt", "sbtbrasil", "sbt hd" -> "SBTNacional.br"
        "globo", "tvglobo", "redeglobo" -> "RedeGlobo.br"
        "recordtv", "tvrecord", "rederecord" -> "Record.br"
        "redetv", "redetv!" -> "RedeTV.br"
        "redevida" -> "RedeVida.br"
        else -> null
    }

    /** Atualiza o índice em segundo plano e chama onUpdated quando a associação mudar. */
    fun ensure(context: Context, channelNames: List<String>, onUpdated: () -> Unit) {
        if (channelNames.isEmpty()) return
        val app = context.applicationContext
        val current = savedUrls(app)
        val now = System.currentTimeMillis()
        val hasKnownUrls = channelNames.all { current.optString(normalize(it)).startsWith("https://") }
        val lastSync = prefs(app).getLong(SYNC_KEY, 0L)
        if (hasKnownUrls && now - lastSync < MAX_CACHE_AGE) return
        if (now - lastSync < MAX_CACHE_AGE && channelNames.none { current.optString(normalize(it)).isBlank() }) return
        if (!syncing.compareAndSet(false, true)) return

        worker.execute {
            var changed = false
            try {
                val channels = JSONArray(fetchText(CHANNELS_URL))
                val logos = JSONArray(fetchText(LOGOS_URL))

                val idByName = HashMap<String, String>()
                for (i in 0 until channels.length()) {
                    val item = channels.optJSONObject(i) ?: continue
                    if (item.optString("country") != "BR") continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    val keys = ArrayList<String>()
                    keys.add(item.optString("name"))
                    keys.add(id.substringBefore('.'))
                    val aliases = item.optJSONArray("alt_names")
                    if (aliases != null) {
                        for (j in 0 until aliases.length()) keys.add(aliases.optString(j))
                    }
                    for (candidate in keys) {
                        val key = normalize(candidate)
                        if (key.isNotBlank()) idByName.putIfAbsent(key, id)
                    }
                }

                val logoById = HashMap<String, String>()
                val candidates = HashMap<String, MutableList<JSONObject>>()
                for (i in 0 until logos.length()) {
                    val item = logos.optJSONObject(i) ?: continue
                    if (!item.optBoolean("in_use", false)) continue
                    val id = item.optString("channel")
                    val url = item.optString("url")
                    val format = item.optString("format").uppercase()
                    if (id.isBlank() || !url.startsWith("https://")) continue
                    if (format !in setOf("PNG", "JPEG", "JPG", "WEBP")) continue
                    candidates.getOrPut(id) { mutableListOf() }.add(item)
                }
                candidates.forEach { (id, entries) ->
                    val best = entries.sortedWith(
                        compareBy<JSONObject> { if (it.isNull("feed") || it.optString("feed").isBlank()) 0 else 1 }
                            .thenBy { when (it.optString("format").uppercase()) { "PNG" -> 0; "JPEG", "JPG" -> 1; else -> 2 } }
                            .thenByDescending { it.optInt("width", 0) * it.optInt("height", 0) }
                    ).firstOrNull()
                    val url = best?.optString("url").orEmpty()
                    if (url.isNotBlank()) logoById[id] = url
                }

                val updated = savedUrls(app)
                for (name in channelNames) {
                    val key = normalize(name)
                    var id = idByName[key] ?: manualId(key)
                    if (id == null && key.isNotBlank()) {
                        // Segunda tentativa remove sufixos comuns de qualidade/canal.
                        val compact = key.removeSuffix("hd").removeSuffix("fhd").removeSuffix("uhd")
                        id = idByName[compact] ?: manualId(compact)
                    }
                    val url = id?.let { logoById[it] }
                    if (!url.isNullOrBlank() && updated.optString(key) != url) {
                        updated.put(key, url)
                        changed = true
                    }
                }
                prefs(app).edit()
                    .putString(URLS_KEY, updated.toString())
                    .putLong(SYNC_KEY, System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {
                // A launcher continua utilizável mesmo sem internet ou sem a base de logos.
            } finally {
                syncing.set(false)
                if (changed) main.post(onUpdated)
            }
        }
    }

    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Carrega o arquivo local ou baixa a imagem sem bloquear a interface da TV. */
    fun load(context: Context, channelName: String, target: ImageView, onLoaded: (Boolean) -> Unit) {
        val app = context.applicationContext
        val key = normalize(channelName)
        val address = savedUrls(app).optString(key)
        if (!address.startsWith("https://")) {
            onLoaded(false)
            return
        }
        val file = logoFile(app, key)
        worker.execute {
            var bitmap: Bitmap? = runCatching { if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null }.getOrNull()
            if (bitmap == null && downloading.add(key)) {
                try {
                    val connection = (URL(address).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12000
                        readTimeout = 18000
                        setRequestProperty("User-Agent", "LauncherLite/0.4 AndroidTV")
                    }
                    try {
                        if (connection.responseCode in 200..299) {
                            connection.inputStream.use { input ->
                                bitmap = BitmapFactory.decodeStream(input)
                            }
                            bitmap?.let { image ->
                                runCatching { file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (_: Exception) {
                    bitmap = null
                } finally {
                    downloading.remove(key)
                }
            }
            val result = bitmap
            main.post {
                if (result != null) target.setImageBitmap(result)
                onLoaded(result != null)
            }
        }
    }
}
