package br.com.launcher.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
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
    private val accent = Color.rgb(45, 205, 255)
    private val apps = mutableListOf<ResolveInfo>()
    private val prefs by lazy { getSharedPreferences("launcher_v2", MODE_PRIVATE) }
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weatherIcon: TextView
    private lateinit var weatherText: TextView
    private lateinit var appGrid: GridLayout
    private var showingAll = false
    private var organizing = false
    private var moving = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        apps.addAll(findApps())
        restoreOrder()
        createHome()
        refreshClock()
        refreshWeather()
        clock.postDelayed(object : Runnable {
            override fun run() { refreshClock(); clock.postDelayed(this, 30000) }
        }, 30000)
    }

    override fun onResume() {
        super.onResume()
        if (::appGrid.isInitialized) {
            val fresh = findApps()
            if (fresh.map { it.activityInfo.packageName } != apps.map { it.activityInfo.packageName }) {
                apps.clear(); apps.addAll(fresh); restoreOrder(); renderApps()
            }
        }
    }

    private fun createHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(52), dp(28), dp(52), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(5, 15, 36), Color.rgb(8, 39, 83), Color.rgb(5, 18, 43))
            )
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        root.addView(createHeader(), LinearLayout.LayoutParams(-1, dp(116)))

        val center = FrameLayout(this)
        appGrid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        center.addView(appGrid, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderApps()
    }

    private fun createHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val time = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            textSize = 46f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        date = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(215, 228, 245))
        }
        time.addView(clock); time.addView(date)
        header.addView(time)
        header.addView(View(this).apply { setBackgroundColor(Color.argb(130,255,255,255)) },
            LinearLayout.LayoutParams(dp(1), dp(66)).apply { setMargins(dp(24), 0, dp(24), 0) })

        val weatherBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        weatherIcon = TextView(this).apply {
            text = "☁"
            textSize = 42f
            setTextColor(Color.WHITE)
        }
        val weatherLabels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        weatherLabels.addView(TextView(this).apply {
            text = "São Bernardo do Campo"
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        weatherText = TextView(this).apply {
            text = "Carregando previsão…"
            textSize = 14f
            setTextColor(Color.rgb(220, 233, 250))
        }
        weatherLabels.addView(weatherText)
        weatherBlock.addView(weatherIcon)
        weatherBlock.addView(weatherLabels)
        weatherBlock.isFocusable = true
        weatherBlock.setOnClickListener { refreshWeather() }
        header.addView(weatherBlock, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(actionButton(android.R.drawable.ic_btn_speak_now, "Pesquisa por voz") { openVoice() })
        header.addView(actionButton(android.R.drawable.ic_menu_manage, "Configurações") {
            openIntent(Intent(Settings.ACTION_SETTINGS))
        })
        header.addView(actionButton(android.R.drawable.ic_menu_preferences, "Wi-Fi") {
            openIntent(Intent(Settings.ACTION_WIFI_SETTINGS))
        })
        return header
    }

    private fun actionButton(iconRes: Int, description: String, action: () -> Unit): View {
        val button = ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            contentDescription = description
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = rounded(Color.TRANSPARENT, 16, Color.TRANSPARENT)
            setOnClickListener { action() }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) Color.rgb(18, 67, 105) else Color.TRANSPARENT,
                    16, if (focused) accent else Color.TRANSPARENT)
            }
        }
        return button
    }

    private fun openVoice() {
        val intents = listOf(
            Intent(Intent.ACTION_ASSIST),
            Intent("android.intent.action.VOICE_ASSIST"),
            Intent("android.intent.action.VOICE_COMMAND"),
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (_: Exception) { }
        }
        Toast.makeText(this, "O serviço de voz do Google não está disponível", Toast.LENGTH_LONG).show()
    }

    private fun openIntent(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            catch (_: Exception) { Toast.makeText(this, "Não foi possível abrir as configurações", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun refreshClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR")).format(now)
    }

    private fun refreshWeather() {
        weatherText.text = "Atualizando previsão…"
        Thread {
            var label = "Clima indisponível — toque para tentar novamente"
            var symbol = "☁"
            try {
                val address = URL("https://api.open-meteo.com/v1/forecast?latitude=-23.6914&longitude=-46.5650&current=temperature_2m,relative_humidity_2m,weather_code,is_day&timezone=America%2FSao_Paulo")
                val connection = (address.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val current = JSONObject(response).getJSONObject("current")
                val temperature = current.getDouble("temperature_2m").toInt()
                val humidity = current.getInt("relative_humidity_2m")
                val code = current.getInt("weather_code")
                val description = when (code) {
                    0 -> "Céu limpo"
                    1 -> "Poucas nuvens"
                    2 -> "Parcialmente nublado"
                    3 -> "Nublado"
                    45, 48 -> "Neblina"
                    51, 53, 55, 56, 57 -> "Garoa"
                    61, 63, 65, 66, 67 -> "Chuva"
                    80, 81, 82 -> "Pancadas de chuva"
                    95, 96, 99 -> "Trovoadas"
                    else -> "Condição atual"
                }
                symbol = when {
                    code >= 95 -> "⛈"
                    code in 51..67 || code in 80..82 -> "🌧"
                    code == 45 || code == 48 -> "🌫"
                    code == 0 -> if (current.optInt("is_day", 1) == 1) "☀" else "☾"
                    code in 2..3 -> "☁"
                    else -> "🌤"
                }
                label = "$temperature°C  •  $description  •  Umidade $humidity%"
            } catch (_: Exception) { }
            val finalLabel = label
            val finalSymbol = symbol
            runOnUiThread {
                weatherText.text = finalLabel
                weatherIcon.text = finalSymbol
            }
        }.start()
    }

    private fun renderApps(focus: Int = 0) {
        appGrid.removeAllViews()
        appGrid.columnCount = 5
        val screenWidth = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val available = screenWidth - 104
        val tileWidth = (available / 5).coerceIn(dp(142), dp(220))
        val tileHeight = dp(158)
        val visible = if (showingAll) apps.toList() else apps.take(8)
        if (showingAll) {
            visible.forEachIndexed { index, app ->
                addAppCard(app, index, tileWidth, tileHeight, index / 5, index % 5)
            }
        } else {
            visible.take(5).forEachIndexed { index, app ->
                addAppCard(app, index, tileWidth, tileHeight, 0, index)
            }
            visible.drop(5).take(3).forEachIndexed { index, app ->
                addAppCard(app, index + 5, tileWidth, tileHeight, 1, index + 1)
            }
            addAllAppsCard(tileWidth, tileHeight, 1, 4)
        }
        if (appGrid.childCount > 0) {
            val actual = focus.coerceIn(0, appGrid.childCount - 1)
            appGrid.getChildAt(actual)?.requestFocus()
        }
    }

    private fun addAppCard(app: ResolveInfo, index: Int, width: Int, height: Int, row: Int, col: Int) {
        val card = createCard()
        val icon = ImageView(this).apply {
            setImageDrawable(app.loadIcon(packageManager))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(76), dp(76)))
        card.addView(TextView(this).apply {
            text = app.loadLabel(packageManager).toString()
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(2), dp(8), dp(2), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        card.contentDescription = app.loadLabel(packageManager).toString()
        card.setOnClickListener { launch(app) }
        card.setOnLongClickListener {
            if (!showingAll) {
                organizing = true
                moving = index
                Toast.makeText(this, "Use as setas para mover e OK para salvar", Toast.LENGTH_LONG).show()
                true
            } else false
        }
        card.setOnKeyListener { _, key, event ->
            if (organizing && event.action == KeyEvent.ACTION_DOWN) {
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { move(-5); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { move(5); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { saveOrder(); true }
                    KeyEvent.KEYCODE_BACK -> { organizing = false; restoreOrder(); renderApps(); true }
                    else -> false
                }
            } else false
        }
        addGridCard(card, width, height, row, col)
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(12), dp(10), dp(10))
        background = rounded(Color.rgb(13, 35, 65), 20, Color.rgb(83, 147, 197))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnFocusChangeListener { view, focused ->
            view.background = rounded(
                if (focused) Color.rgb(9, 65, 103) else Color.rgb(13, 35, 65),
                20,
                if (focused) accent else Color.rgb(83, 147, 197)
            )
            view.elevation = if (focused) dp(9).toFloat() else 0f
        }
    }

    private fun addAllAppsCard(width: Int, height: Int, row: Int, col: Int) {
        val card = createCard()
        card.addView(TextView(this).apply {
            text = "▦"
            textSize = 62f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(76), dp(76)))
        card.addView(TextView(this).apply {
            text = "Todos os apps"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        card.contentDescription = "Todos os aplicativos"
        card.setOnClickListener { showingAll = true; renderApps() }
        addGridCard(card, width, height, row, col)
    }

    private fun addGridCard(view: View, width: Int, height: Int, row: Int, col: Int) {
        val params = GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col))
        params.width = width
        params.height = height
        params.setMargins(dp(9), dp(9), dp(9), dp(9))
        appGrid.addView(view, params)
    }

    private fun launch(app: ResolveInfo) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)
            if (launchIntent != null) startActivity(launchIntent)
            else {
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(app.activityInfo.packageName, app.activityInfo.name)
                startActivity(intent)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir este aplicativo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findApps(): List<ResolveInfo> {
        val result = LinkedHashMap<String, ResolveInfo>()
        val queries = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        for (query in queries) {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(query, PackageManager.MATCH_ALL).forEach { info ->
                val packageName = info.activityInfo?.packageName ?: return@forEach
                if (packageName != packageNameOfSelf()) result.putIfAbsent(packageName, info)
            }
        }
        return result.values.sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
    }

    private fun packageNameOfSelf() = "br.com.launcher.tv"

    private fun restoreOrder() {
        val saved = prefs.getString("order", null)?.split("|") ?: return
        val byPackage = apps.associateBy { it.activityInfo.packageName }
        val ordered = saved.mapNotNull { byPackage[it] }.toMutableList()
        apps.filter { it.activityInfo.packageName !in saved }.forEach { ordered.add(it) }
        apps.clear()
        apps.addAll(ordered)
    }

    private fun saveOrder() {
        organizing = false
        moving = -1
        prefs.edit().putString("order", apps.joinToString("|") { it.activityInfo.packageName }).apply()
        Toast.makeText(this, "Ordem salva", Toast.LENGTH_SHORT).show()
        renderApps()
    }

    private fun move(offset: Int) {
        if (!organizing || moving !in apps.indices) return
        val target = (moving + offset).coerceIn(0, apps.lastIndex)
        if (target == moving) return
        val item = apps.removeAt(moving)
        apps.add(target, item)
        moving = target
        renderApps(target)
    }

    override fun onBackPressed() {
        if (showingAll) {
            showingAll = false
            renderApps()
        } else super.onBackPressed()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
