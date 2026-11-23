package br.com.launcher.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val black = Color.rgb(0, 0, 0)
    private val card = Color.rgb(17, 17, 19)
    private val blue = Color.rgb(42, 183, 255)
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weatherIcon: TextView
    private lateinit var weatherInfo: TextView
    private lateinit var grid: GridLayout
    private lateinit var root: LinearLayout
    private val apps = mutableListOf<android.content.pm.ResolveInfo>()
    private val prefs by lazy { getSharedPreferences("launcher_order", MODE_PRIVATE) }
    private var reorderMode = false
    private var movingIndex = -1
    private var showingAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        apps.addAll(loadApps())
        applySavedOrder()
        buildScreen()
        updateClock()
        loadWeather()
        clock.postDelayed(object : Runnable {
            override fun run() { updateClock(); clock.postDelayed(this, 30000) }
        }, 30000)
    }

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(22), dp(48), dp(20))
            setBackgroundColor(black)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(112)))
        grid = GridLayout(this).apply {
            columnCount = 5
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val gridFrame = FrameLayout(this)
        gridFrame.addView(grid, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(gridFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildFooter(), LinearLayout.LayoutParams(-1, dp(32)))
        setContentView(root)
        rebuildGrid()
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val timeBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            textSize = 43f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        date = TextView(this).apply { textSize = 15f; setTextColor(Color.rgb(220,220,220)) }
        timeBlock.addView(clock); timeBlock.addView(date); row.addView(timeBlock)
        row.addView(separator(), LinearLayout.LayoutParams(dp(1), dp(68)).apply { setMargins(dp(22), 0, dp(22), 0) })

        val weather = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        weatherIcon = TextView(this).apply { text = "☀"; textSize = 43f; setTextColor(Color.rgb(255,190,28)) }
        weather.addView(weatherIcon)
        val climateText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14),0,0,0) }
        climateText.addView(TextView(this).apply {
            text = "São Bernardo do Campo"; textSize = 20f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        weatherInfo = TextView(this).apply { text = "Buscando previsão do tempo…"; textSize = 14f; setTextColor(Color.LTGRAY) }
        climateText.addView(weatherInfo)
        weather.addView(climateText)
        row.addView(weather, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(headerAction("🎙", "Pesquisa por voz") { launchVoiceSearch() })
        row.addView(headerAction("⌁", "Configurações de Wi-Fi") {
            openSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
        })
        row.addView(headerAction("⚙", "Configurações") {
            openSettings(Intent(Settings.ACTION_SETTINGS))
        })
        return row
    }

    private fun launchVoiceSearch() {
        val candidates = listOf(
            Intent("android.intent.action.VOICE_ASSIST"),
            Intent("android.intent.action.VOICE_COMMAND"),
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        )
        for (intent in candidates) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) { }
        }
        Toast.makeText(this, "Pesquisa por voz não disponível neste dispositivo", Toast.LENGTH_LONG).show()
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {
                Toast.makeText(this, "Configuração indisponível", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadWeather() {
        Thread {
            var result = "Não foi possível carregar o clima"
            var icon = "☁"
            try {
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=-23.69&longitude=-46.56&current=temperature_2m,weather_code,is_day&timezone=America%2FSao_Paulo")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 7000; requestMethod = "GET"
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val current = JSONObject(body).getJSONObject("current")
                val temp = current.getDouble("temperature_2m").toInt()
                val code = current.getInt("weather_code")
                val day = current.optInt("is_day", 1) == 1
                val description = when (code) {
                    0 -> "Céu limpo"
                    1 -> "Predominantemente limpo"
                    2 -> "Parcialmente nublado"
                    3 -> "Nublado"
                    45, 48 -> "Neblina"
                    51, 53, 55, 56, 57 -> "Garoa"
                    61, 63, 65, 66, 67 -> "Chuva"
                    71, 73, 75, 77 -> "Neve"
                    80, 81, 82 -> "Pancadas de chuva"
                    85, 86 -> "Neve"
                    95, 96, 99 -> "Trovoadas"
                    else -> "Condição atual"
                }
                icon = when {
                    code >= 95 -> "⛈"
                    code in 51..67 || code in 80..82 -> "🌧"
                    code in 45..48 -> "🌫"
                    code in 2..3 -> "☁"
                    code == 0 && day -> "☀"
                    code == 0 -> "☾"
                    else -> "🌤"
                }
                result = "$temp°C  •  $description"
            } catch (_: Exception) { }
            val finalResult = result
            val finalIcon = icon
            runOnUiThread {
                weatherInfo.text = finalResult
                weatherIcon.text = finalIcon
            }
        }.start()
    }

    private fun headerAction(symbol: String, description: String, action: () -> Unit): View {
        return TextView(this).apply {
            text = symbol; contentDescription = description; textSize = 31f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(13), dp(8), dp(13), dp(8))
            setOnClickListener { action() }
            setOnFocusChangeListener { v, focused ->
                v.background = if (focused) rounded(Color.rgb(30,45,58), 12, blue) else null
            }
        }
    }

    private fun separator() = View(this).apply { setBackgroundColor(Color.rgb(100,100,100)) }

    private fun buildFooter(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "●"; textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(8),0,dp(8),0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "●"; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(dp(8),0,dp(8),0)
            })
        }
    }

    private fun rebuildGrid(focusIndex: Int = 0) {
        grid.removeAllViews()
        val display = if (showingAll) apps.toList() else apps.take(8)
        val width = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 96) / 5
        val tileWidth = width - 12
        if (showingAll) {
            grid.columnCount = 5
            display.forEachIndexed { i, app -> addAppTile(app, i, tileWidth, dp(126), i / 5, i % 5) }
        } else {
            grid.columnCount = 5
            display.take(5).forEachIndexed { i, app -> addAppTile(app, i, tileWidth, dp(126), 0, i) }
            val spacerParams = GridLayout.LayoutParams(GridLayout.spec(1), GridLayout.spec(0))
            spacerParams.width = tileWidth; spacerParams.height = dp(140)
            grid.addView(View(this), spacerParams)
            display.drop(5).take(3).forEachIndexed { i, app -> addAppTile(app, i + 5, tileWidth, dp(126), 1, i + 1) }
            addAppsTile(tileWidth, 1, 4)
        }
        if (grid.childCount > 0) grid.getChildAt(focusIndex.coerceIn(0, grid.childCount - 1))?.requestFocus()
    }

    private fun addAppTile(app: android.content.pm.ResolveInfo, index: Int, width: Int, height: Int, row: Int, col: Int) {
        val tile = makeTile(index)
        val icon = ImageView(this).apply {
            setImageDrawable(app.loadIcon(packageManager))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        tile.addView(icon, LinearLayout.LayoutParams(dp(68), dp(68)))
        tile.addView(TextView(this).apply {
            text = app.loadLabel(packageManager).toString()
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            maxLines = 2; setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        tile.contentDescription = app.loadLabel(packageManager).toString()
        tile.setOnClickListener { launchApp(app) }
        tile.setOnLongClickListener { if (!showingAll) { beginReorder(index); true } else false }
        val lp = GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col))
        lp.width = width; lp.height = height
        lp.setMargins(dp(7), dp(7), dp(7), dp(7))
        grid.addView(tile, lp)
    }

    private fun launchApp(app: android.content.pm.ResolveInfo) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            intent.setClassName(app.activityInfo.packageName, app.activityInfo.name)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            try { startActivity(packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)) }
            catch (_: Exception) { Toast.makeText(this, "Não foi possível abrir este aplicativo", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun addAppsTile(width: Int, row: Int, col: Int) {
        val tile = makeTile(8)
        tile.addView(TextView(this).apply {
            text = "▦"; textSize = 58f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(68), dp(68)))
        tile.addView(TextView(this).apply {
            text = "Todos os apps"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        tile.contentDescription = "Todos os aplicativos"
        tile.setOnClickListener { showingAll = true; rebuildGrid() }
        val lp = GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col))
        lp.width = width; lp.height = dp(126)
        lp.setMargins(dp(7), dp(7), dp(7), dp(7))
        grid.addView(tile, lp)
    }

    private fun makeTile(index: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(8))
            background = rounded(card, 18, Color.rgb(43,43,45))
            isFocusable = true; isFocusableInTouchMode = true
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) Color.rgb(8,22,32) else card, 18,
                    if (focused) blue else Color.rgb(43,43,45))
                v.elevation = if (focused) dp(8).toFloat() else 0f
            }
            setOnKeyListener { _, key, event ->
                if (reorderMode && event.action == KeyEvent.ACTION_DOWN) {
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { moveItem(-1); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { moveItem(1); true }
                        KeyEvent.KEYCODE_DPAD_UP -> { moveItem(-5); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { moveItem(5); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { finishReorder(); true }
                        KeyEvent.KEYCODE_BACK -> { cancelReorder(); true }
                        else -> false
                    }
                } else false
            }
        }
    }

    private fun beginReorder(index: Int) {
        reorderMode = true; movingIndex = index
        Toast.makeText(this, "Organizar: use as setas e OK para salvar", Toast.LENGTH_LONG).show()
    }

    private fun moveItem(offset: Int) {
        if (!reorderMode || movingIndex !in apps.indices) return
        val target = (movingIndex + offset).coerceIn(0, apps.lastIndex)
        if (target == movingIndex) return
        val item = apps.removeAt(movingIndex); apps.add(target, item); movingIndex = target
        rebuildGrid(target)
    }

    private fun finishReorder() {
        if (!reorderMode) return
        reorderMode = false; movingIndex = -1
        prefs.edit().putString("packages", apps.joinToString("|") { it.activityInfo.packageName }).apply()
        Toast.makeText(this, "Ordem salva", Toast.LENGTH_SHORT).show()
        rebuildGrid()
    }

    private fun cancelReorder() {
        reorderMode = false; movingIndex = -1; applySavedOrder(); rebuildGrid()
    }

    override fun onBackPressed() {
        if (showingAll) { showingAll = false; rebuildGrid(); return }
        super.onBackPressed()
    }

    private fun loadApps(): List<android.content.pm.ResolveInfo> {
        val found = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        ).forEach { intent ->
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { info ->
                val pkg = info.activityInfo?.packageName ?: return@forEach
                if (pkg != packageName) found[pkg] = info
            }
        }
        return found.values.sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
    }

    private fun applySavedOrder() {
        val saved = prefs.getString("packages", null)?.split("|") ?: return
        val byPackage = apps.associateBy { it.activityInfo.packageName }
        val ordered = saved.mapNotNull { byPackage[it] }.toMutableList()
        apps.filter { it.activityInfo.packageName !in saved }.forEach { ordered.add(it) }
        apps.clear(); apps.addAll(ordered)
    }

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR")).format(now)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
