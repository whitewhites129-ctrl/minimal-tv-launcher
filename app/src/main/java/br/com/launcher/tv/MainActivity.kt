package br.com.launcher.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var screenRoot: FrameLayout
    private var notificationPanel: LinearLayout? = null
    private var notificationCount: TextView? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private var saverView: View? = null
    private var saverClock: TextView? = null
    private var saverDate: TextView? = null
    private val idleTask = Runnable { showScreenSaver() }
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
        LauncherNotificationListener.onChanged = {
            runOnUiThread { refreshNotificationPanel() }
        }
        refreshClock()
        resetIdleTimer()
        clock.postDelayed(object : Runnable {
            override fun run() { refreshClock(); clock.postDelayed(this, 30000) }
        }, 30000)
    }

    override fun onResume() {
        super.onResume()
        LauncherNotificationListener.instance?.refreshFromSystem()
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
            setPadding(dp(58), dp(34), dp(58), dp(28))
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        root.addView(createHeader(), LinearLayout.LayoutParams(-1, dp(104)))

        val content = FrameLayout(this)
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val gridFrame = FrameLayout(this)
        appGrid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(0, dp(10), 0, dp(10))
        }
        gridFrame.addView(appGrid, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        scroll.addView(gridFrame, ViewGroup.LayoutParams(-1, -2))
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.post {
            gridFrame.minimumHeight = scroll.height
            gridFrame.requestLayout()
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        screenRoot = FrameLayout(this)
        screenRoot.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(screenRoot)
        renderApps()
    }

    private fun createHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        clock = TextView(this).apply {
            textSize = 43f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        date = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(185, 195, 210))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        left.addView(clock)
        left.addView(date)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val weatherPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(18, 24, 38), 18, Color.rgb(42, 55, 75))
            isFocusable = true
            isClickable = true
            setOnClickListener { refreshWeather() }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) Color.rgb(18, 67, 105) else Color.rgb(18, 24, 38),
                    18, if (focused) accent else Color.rgb(42, 55, 75))
            }
        }
        weatherIcon = TextView(this).apply {
            text = "☀"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        weatherText = TextView(this).apply {
            text = "Clima"
            textSize = 13f
            setTextColor(Color.rgb(220, 232, 246))
            setPadding(dp(7), 0, 0, 0)
            maxLines = 1
        }
        weatherPill.addView(weatherIcon)
        weatherPill.addView(weatherText)
        actions.addView(weatherPill)
        actions.addView(headerAction(R.drawable.ic_notifications_modern, "Notificações") { openNotificationsPanel() })
        actions.addView(headerAction(R.drawable.ic_settings_modern, "Configurações") { openIntent(Intent(Settings.ACTION_SETTINGS)) })
        actions.addView(headerAction(R.drawable.ic_mic_modern, "Pesquisa por voz") { openVoice() })
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
        header.addView(actions, LinearLayout.LayoutParams(-2, -1))
        refreshWeather()
        return header
    }

    private fun headerAction(iconRes: Int, description: String, action: () -> Unit): View {
        return ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(224, 235, 248))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(Color.TRANSPARENT, 18, Color.TRANSPARENT)
            setOnClickListener { action() }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) Color.rgb(18, 67, 105) else Color.TRANSPARENT,
                    18, if (focused) accent else Color.TRANSPARENT)
                v.scaleX = if (focused) 1.08f else 1f
                v.scaleY = if (focused) 1.08f else 1f
            }
        }
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val component = android.content.ComponentName(this, LauncherNotificationListener::class.java)
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(':').any { it.equals(component.flattenToString(), ignoreCase = true) }
    }

    private fun openNotificationsPanel() {
        if (!isNotificationAccessEnabled()) {
            Toast.makeText(this, "Autorize o acesso às notificações para visualizar os avisos dos aplicativos.", Toast.LENGTH_LONG).show()
            try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
            catch (_: Exception) { openIntent(Intent(Settings.ACTION_SETTINGS)) }
            return
        }
        if (screenRoot.findViewWithTag<View>("notification_panel_root") != null) return
        val scrim = FrameLayout(this).apply {
            tag = "notification_panel_root"
            setBackgroundColor(Color.argb(135, 0, 0, 0))
            isFocusable = true
            setOnClickListener { closeNotificationsPanel() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(24), dp(25), dp(24), dp(22))
            background = rounded(Color.rgb(24, 33, 54), 26, Color.rgb(72, 104, 154))
            isFocusable = true
            isClickable = true
            setOnClickListener { }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Notificações"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        notificationCount = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(190, 205, 226))
            gravity = Gravity.CENTER
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(notificationCount)
        panel.addView(header)
        panel.addView(TextView(this).apply {
            text = SimpleDateFormat("EEE, d 'de' MMMM  •  HH:mm", Locale("pt", "BR")).format(Date())
            textSize = 13f
            setTextColor(Color.rgb(190, 205, 226))
            setPadding(0, dp(5), 0, dp(15))
        })
        val listScroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        notificationPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        listScroll.addView(notificationPanel, ViewGroup.LayoutParams(-1, -2))
        panel.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val manage = TextView(this).apply {
            text = "Gerenciar acesso"
            textSize = 13f
            setTextColor(Color.rgb(180, 218, 255))
            isFocusable = true
            isClickable = true
            setOnClickListener {
                try { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
                catch (_: Exception) { openIntent(Intent(Settings.ACTION_SETTINGS)) }
            }
        }
        val clear = TextView(this).apply {
            text = "Limpar tudo"
            textSize = 13f
            setTextColor(Color.rgb(180, 218, 255))
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { LauncherNotificationListener.instance?.clearAll(); refreshNotificationPanel() }
        }
        val close = TextView(this).apply {
            text = "Fechar"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.rgb(42, 59, 91), 14, Color.TRANSPARENT)
            setOnClickListener { closeNotificationsPanel() }
        }
        bottom.addView(manage, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottom.addView(clear)
        bottom.addView(close)
        panel.addView(bottom)
        scrim.addView(panel, FrameLayout.LayoutParams(dp(430), -1, Gravity.RIGHT))
        screenRoot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        refreshNotificationPanel()
        notificationPanel?.post { notificationPanel?.getChildAt(0)?.requestFocus() }
    }

    private fun refreshNotificationPanel() {
        val list = notificationPanel ?: return
        list.removeAllViews()
        val notices = LauncherNotificationListener.snapshot()
        notificationCount?.text = if (notices.isEmpty()) "0 avisos" else "${notices.size} avisos"
        if (notices.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Tudo em dia\\n\\nNenhuma notificação no momento."
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(210, 222, 240))
                setPadding(dp(18), dp(50), dp(18), dp(50))
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        notices.take(40).forEach { notice ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(12), dp(10), dp(12))
                background = rounded(Color.rgb(42, 57, 86), 17, Color.rgb(63, 82, 119))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    LauncherNotificationListener.instance?.openNotification(notice.key)
                    closeNotificationsPanel()
                }
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(if (focused) Color.rgb(35, 88, 137) else Color.rgb(42, 57, 86),
                        17, if (focused) accent else Color.rgb(63, 82, 119))
                }
            }
            val icon = ImageView(this).apply {
                try { setImageDrawable(packageManager.getApplicationIcon(notice.packageName)) }
                catch (_: Exception) { setImageResource(android.R.drawable.sym_def_app_icon) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textColumn.addView(TextView(this).apply {
                text = notice.appName
                textSize = 11f
                setTextColor(Color.rgb(180, 211, 245))
                maxLines = 1
            })
            if (notice.title.isNotBlank()) textColumn.addView(TextView(this).apply {
                text = notice.title
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
            })
            if (notice.message.isNotBlank()) textColumn.addView(TextView(this).apply {
                text = notice.message
                textSize = 13f
                setTextColor(Color.rgb(205, 216, 233))
                maxLines = 3
            })
            row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    private fun closeNotificationsPanel() {
        val panel = screenRoot.findViewWithTag<View>("notification_panel_root") ?: return
        screenRoot.removeView(panel)
        notificationPanel = null
        notificationCount = null
    }

    private fun showQuickPanel() {
        if (screenRoot.findViewWithTag<View>("quick_panel") != null) return
        val scrim = FrameLayout(this).apply {
            tag = "quick_panel"
            setBackgroundColor(Color.argb(125, 0, 0, 0))
            isFocusable = true
            setOnClickListener { screenRoot.removeView(this) }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(26), dp(30), dp(26), dp(24))
            background = rounded(Color.rgb(25, 34, 55), 26, Color.rgb(74, 105, 155))
            isFocusable = true
            isClickable = true
            setOnClickListener { }
        }
        panel.addView(TextView(this).apply {
            text = "Painel rápido"
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        panel.addView(TextView(this).apply {
            text = SimpleDateFormat("EEE, d 'de' MMMM  •  HH:mm", Locale("pt", "BR")).format(Date())
            textSize = 14f
            setTextColor(Color.rgb(190, 205, 226))
            setPadding(0, dp(5), 0, dp(22))
        })
        fun panelAction(title: String, subtitle: String, glyph: String, intent: Intent) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(17), dp(13), dp(17), dp(13))
                background = rounded(Color.rgb(43, 58, 88), 17, Color.rgb(62, 82, 119))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    screenRoot.removeView(scrim)
                    if (title == "Notificações") openNotificationsPanel() else openIntent(intent)
                }
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(if (focused) Color.rgb(30, 91, 145) else Color.rgb(43, 58, 88),
                        17, if (focused) accent else Color.rgb(62, 82, 119))
                }
            }
            tile.addView(TextView(this).apply {
                text = "$glyph   $title"
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            tile.addView(TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.rgb(190, 205, 226))
                setPadding(dp(34), dp(3), 0, 0)
            })
            panel.addView(tile, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(10) })
        }
        panelAction("Wi-Fi e internet", "Conexão de rede", "◉", Intent("android.settings.panel.action.INTERNET_CONNECTIVITY"))
        panelAction("Bluetooth", "Dispositivos conectados", "ᛒ", Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        panelAction("Notificações", "Preferências e permissões", "●", Intent("android.settings.NOTIFICATION_SETTINGS"))
        panelAction("Todas as configurações", "Abrir configurações do sistema", "⚙", Intent(Settings.ACTION_SETTINGS))
        val close = TextView(this).apply {
            text = "Fechar"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setOnClickListener { screenRoot.removeView(scrim) }
            background = rounded(Color.rgb(35, 50, 77), 16, Color.TRANSPARENT)
        }
        panel.addView(close, LinearLayout.LayoutParams(-1, dp(48)))
        scrim.addView(panel, FrameLayout.LayoutParams(dp(390), -1, Gravity.RIGHT))
        screenRoot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel.getChildAt(0).requestFocus()
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
        val contentWidth = screenWidth - 116
        val horizontalMargins = dp(18) * 5
        val tileWidth = dp(((contentWidth - horizontalMargins) / 5).coerceIn(118, 220))
        val tileHeight = (tileWidth * 0.62f).toInt().coerceAtLeast(dp(76))
        val visible = if (showingAll) apps.toList() else apps.take(if (apps.size > 10) 9 else 10)
        if (showingAll) {
            visible.forEachIndexed { index, app ->
                addAppCard(app, index, tileWidth, tileHeight, index / 5, index % 5)
            }
        } else {
            visible.take(5).forEachIndexed { index, app ->
                addAppCard(app, index, tileWidth, tileHeight, 0, index)
            }
            visible.drop(5).take(5).forEachIndexed { index, app ->
                addAppCard(app, index + 5, tileWidth, tileHeight, 1, index)
            }
            if (apps.size > 10) addAllAppsCard(tileWidth, tileHeight, 1, 4)
        }
        if (appGrid.childCount > 0) {
            val actual = focus.coerceIn(0, appGrid.childCount - 1)
            appGrid.post { appGrid.getChildAt(actual)?.requestFocus() }
        }
    }

    private fun addAppCard(app: ResolveInfo, index: Int, width: Int, height: Int, row: Int, col: Int) {
        val card = createCard()
        val banner = try {
        val activityInfo = app.activityInfo
        val bannerRes = if (activityInfo.banner != 0) activityInfo.banner else activityInfo.applicationInfo.banner
        if (bannerRes != 0) packageManager.getDrawable(activityInfo.packageName, bannerRes, activityInfo.applicationInfo) else null
    } catch (_: Exception) { null }
        val artwork = banner ?: app.loadIcon(packageManager)
        val image = ImageView(this).apply {
            setImageDrawable(artwork)
            scaleType = if (banner != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            contentDescription = app.loadLabel(packageManager).toString()
        }
        card.background = if (banner == null) appCardBackground(artwork)
            else rounded(Color.rgb(18, 22, 32), 17, Color.rgb(35, 43, 56))
        card.foreground = rounded(Color.TRANSPARENT, 17, Color.TRANSPARENT)
        card.clipToOutline = true
        card.setPadding(if (banner != null) 0 else dp(8), if (banner != null) 0 else dp(8),
            if (banner != null) 0 else dp(8), if (banner != null) 0 else dp(8))
        card.addView(image, LinearLayout.LayoutParams(
            if (banner != null) -1 else dp(66),
            if (banner != null) -1 else dp(66)
        ))
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
        background = rounded(Color.rgb(14, 18, 27), 20, Color.rgb(35, 43, 56))
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnFocusChangeListener { view, focused ->
            view.background = rounded(
                if (focused) Color.rgb(17, 39, 62) else Color.rgb(14, 18, 27),
                20,
                if (focused) accent else Color.rgb(35, 43, 56)
            )
            view.foreground = rounded(Color.TRANSPARENT, 20, if (focused) accent else Color.TRANSPARENT)
            view.elevation = if (focused) dp(10).toFloat() else 0f
            view.scaleX = if (focused) 1.045f else 1f
            view.scaleY = if (focused) 1.045f else 1f
        }
    }

    private fun appCardBackground(drawable: android.graphics.drawable.Drawable): GradientDrawable {
        val bitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, 48, 48)
        drawable.draw(canvas)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (y in 0 until 48 step 2) {
            for (x in 0 until 48 step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 100) continue
                val hsv = FloatArray(3)
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] > 0.32f && hsv[2] > 0.28f) {
                    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel)
                    count++
                }
            }
        }
        bitmap.recycle()
        val base = if (count > 0) Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
            else Color.rgb(64, 91, 170)
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        hsv[1] = (hsv[1] * 0.82f).coerceIn(0.35f, 0.88f)
        hsv[2] = (hsv[2] * 0.78f).coerceIn(0.30f, 0.72f)
        val dark = Color.HSVToColor(hsv)
        hsv[2] = (hsv[2] * 1.28f).coerceAtMost(0.92f)
        val light = Color.HSVToColor(hsv)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(light, dark)).apply {
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), Color.argb(55, 255, 255, 255))
        }
    }

    private fun addAllAppsCard(width: Int, height: Int, row: Int, col: Int) {
        val card = createCard()
        card.addView(TextView(this).apply {
            text = "＋"
            textSize = 66f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(dp(76), dp(76)))
        card.addView(TextView(this).apply {
            text = "Todos os apps"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        card.contentDescription = "Abrir todos os aplicativos"
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

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (saverView != null) dismissScreenSaver() else resetIdleTimer()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (saverView != null && event.action == KeyEvent.ACTION_DOWN) {
            dismissScreenSaver()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun resetIdleTimer() {
        if (saverView != null) return
        idleHandler.removeCallbacks(idleTask)
        val minutes = prefs.getInt("screensaver_minutes", 5).coerceIn(1, 60)
        idleHandler.postDelayed(idleTask, minutes * 60 * 1000L)
    }

    private fun showScreenSaver() {
        if (saverView != null) return
        val panel = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isFocusable = true; isFocusableInTouchMode = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        saverClock = TextView(this).apply {
            textSize = 76f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); gravity = Gravity.CENTER
        }
        saverDate = TextView(this).apply { textSize = 20f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER }
        content.addView(saverClock); content.addView(saverDate)
        panel.addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        panel.setOnClickListener { dismissScreenSaver() }
        screenRoot.addView(panel, FrameLayout.LayoutParams(-1, -1))
        saverView = panel
        updateSaverClock()
        panel.requestFocus()
        idleHandler.postDelayed(object : Runnable {
            override fun run() {
                if (saverView != null) { updateSaverClock(); idleHandler.postDelayed(this, 30000) }
            }
        }, 30000)
    }

    private fun updateSaverClock() {
        val now = Date()
        saverClock?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        saverDate?.text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(now)
    }

    private fun dismissScreenSaver() {
        val view = saverView ?: return
        screenRoot.removeView(view)
        saverView = null; saverClock = null; saverDate = null
        resetIdleTimer()
    }

    private fun createBottomNav(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun navIcon(icon: Int, label: String, action: () -> Unit): View {
            return ImageView(this).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(205, 218, 232))
                contentDescription = label
                setPadding(dp(18), dp(8), dp(18), dp(8))
                isFocusable = true
                setOnClickListener { action() }
                setOnFocusChangeListener { v, focused ->
                    imageTintList = android.content.res.ColorStateList.valueOf(if (focused) accent else Color.rgb(205, 218, 232))
                    v.alpha = if (focused) 1f else 0.72f
                }
            }
        }
        bar.addView(navIcon(android.R.drawable.ic_menu_view, "Início") { showingAll = false; renderApps() })
        bar.addView(View(this).apply { setBackgroundColor(Color.rgb(55, 75, 98)) }, LinearLayout.LayoutParams(dp(1), dp(30)))
        bar.addView(navIcon(android.R.drawable.ic_menu_slideshow, "Lançamentos") {
            Toast.makeText(this, "A área de lançamentos será conectada ao catálogo na próxima etapa", Toast.LENGTH_SHORT).show()
        })
        bar.addView(View(this).apply { setBackgroundColor(Color.rgb(55, 75, 98)) }, LinearLayout.LayoutParams(dp(1), dp(30)))
        bar.addView(navIcon(android.R.drawable.ic_menu_manage, "Configurações") { openIntent(Intent(Settings.ACTION_SETTINGS)) })
        return bar
    }

    override fun onBackPressed() {
        if (saverView != null) { dismissScreenSaver(); return }
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
