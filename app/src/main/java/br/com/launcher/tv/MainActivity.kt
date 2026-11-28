package br.com.launcher.tv

import android.app.Activity
import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.content.pm.ResolveInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import android.app.AlertDialog
import br.com.launcher.tv.core.HomeRoleController
import br.com.launcher.tv.core.LauncherLifecycleCoordinator
import br.com.launcher.tv.data.AppCatalogRepository
import br.com.launcher.tv.data.FavoriteChannelsRepository
import br.com.launcher.tv.ui.FocusAnchor
import br.com.launcher.tv.ui.FocusRestorationCoordinator
import br.com.launcher.tv.navigation.LauncherSessionEvent
import br.com.launcher.tv.navigation.LauncherSessionStateMachine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private data class WeatherLocation(val latitude: Double, val longitude: Double, val cep: String, val label: String)
    @Volatile private var cachedWeatherLocation: WeatherLocation? = null
    private var weatherLocationLabel = "CEP 09843-790 • São Bernardo do Campo, SP"
    private val homeRoleController by lazy { HomeRoleController(this) }
    private val lifecycleCoordinator = LauncherLifecycleCoordinator()
    private val sessionStateMachine = LauncherSessionStateMachine()
    private val focusRestorationCoordinator = FocusRestorationCoordinator()
    private val lifecycleLogTag = "LauncherLite.Lifecycle"
    private val appCatalog by lazy { AppCatalogRepository(this) }
    private val favoriteChannels by lazy { FavoriteChannelsRepository(this) }
    private val accent = Color.rgb(24, 142, 184)
    private val apps = mutableListOf<ResolveInfo>()
    private val prefs by lazy { getSharedPreferences("launcher_v2", MODE_PRIVATE) }
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var weatherIcon: TextView
    private lateinit var weatherText: TextView
    private lateinit var weatherUpdatedText: TextView
    private lateinit var appGrid: GridLayout
    private lateinit var channelRowContainer: LinearLayout
    private var lastRenderedChannels: List<String>? = null
    private lateinit var screenRoot: FrameLayout
    private var notificationPanel: LinearLayout? = null
    private var notificationCount: TextView? = null
    private var weatherPage: View? = null
    private val idleHandler = Handler(Looper.getMainLooper())
    private var saverView: View? = null
    private var saverClock: TextView? = null
    private var saverDate: TextView? = null
    private val idleTask = Runnable { showScreenSaver() }
    private var showingAll = false
    private var organizing = false
    private var moving = -1
    private var sortPulse: ObjectAnimator? = null
    private var sortDoneButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("LauncherLite.Boot", "MainActivity onCreate; intentAction=${intent?.action}; categories=${intent?.categories}; elapsedRealtime=${SystemClock.elapsedRealtime()}")
        val now = SystemClock.elapsedRealtime()
        lifecycleCoordinator.onCreated(now)
        logSessionEvent(sessionStateMachine.accept(LauncherSessionEvent.Created(now)))
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // Keep the TV awake while Launcher Lite is the visible HOME surface.
        // The in-app clock screensaver remains active; this prevents Android
        // from powering the display down behind it due to the idle timeout.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        apps.addAll(findApps())
        restoreOrder()
        createHome()
        LauncherNotificationListener.onChanged = {
            runOnUiThread { refreshNotificationPanel() }
        }
        refreshClock()
        resetIdleTimer()
        // Reopen the guided setup once after an app update that changes setup steps.
        if (prefs.getInt("setup_wizard_version", 0) < 12) {
            prefs.edit()
                .putInt("setup_wizard_step", 0)
                .putBoolean("setup_wizard_finished", false)
                .putBoolean("setup_wizard_pending", false)
                .apply()
        }
        if (!prefs.getBoolean("setup_wizard_finished", false)) {
            window.decorView.postDelayed({ showSetupWizard() }, 500)
        }
        clock.postDelayed(object : Runnable {
            override fun run() { refreshClock(); clock.postDelayed(this, 30000) }
        }, 30000)
    }

    override fun onStart() {
        super.onStart()
        val now = SystemClock.elapsedRealtime()
        lifecycleCoordinator.onStarted(now)
        logSessionEvent(sessionStateMachine.accept(LauncherSessionEvent.Started(now)))
    }

    override fun onResume() {
        super.onResume()
        val now = SystemClock.elapsedRealtime()
        val lifecycle = lifecycleCoordinator.onResumed(now)
        logSessionEvent(sessionStateMachine.accept(LauncherSessionEvent.Resumed(now)))
        Log.d(lifecycleLogTag, "resumed; pauseGapMs=${lifecycle.lastResumeGapMs}, count=${lifecycle.resumeCount}")
        LauncherNotificationListener.instance?.refreshFromSystem()
        if (::channelRowContainer.isInitialized) renderChannels()
        if (prefs.getBoolean("setup_wizard_pending", false) || !prefs.getBoolean("setup_wizard_finished", false)) {
            window.decorView.postDelayed({ showSetupWizard() }, 350)
        }
        if (::appGrid.isInitialized) {
            val fresh = findApps()
            if (fresh.map { it.activityInfo.packageName } != apps.map { it.activityInfo.packageName }) {
                apps.clear(); apps.addAll(fresh); restoreOrder(); renderApps()
            }
        }
    }

    override fun onPause() {
        focusRestorationCoordinator.capture(window.decorView.findFocus())
        val now = SystemClock.elapsedRealtime()
        lifecycleCoordinator.onPaused(now)
        logSessionEvent(sessionStateMachine.accept(LauncherSessionEvent.Paused(now)))
        super.onPause()
    }

    override fun onStop() {
        val now = SystemClock.elapsedRealtime()
        lifecycleCoordinator.onStopped(now)
        logSessionEvent(sessionStateMachine.accept(LauncherSessionEvent.Stopped(now)))
        super.onStop()
    }

    override fun onDestroy() {
        val now = SystemClock.elapsedRealtime()
        lifecycleCoordinator.onDestroyed(now)
        logSessionEvent(sessionStateMachine.accept(LauncherSessionEvent.Destroyed(now)))
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        val focusAt = SystemClock.elapsedRealtime()
        logSessionEvent(sessionStateMachine.accept(LauncherSessionEvent.WindowFocusChanged(hasWindowFocus, focusAt)))
        if (!::screenRoot.isInitialized) return

        if (!hasWindowFocus) {
            focusRestorationCoordinator.capture(window.decorView.findFocus())
            return
        }

        val overlayIsOpen = saverView != null || weatherPage != null ||
            screenRoot.findViewWithTag<View>("notification_panel_root") != null ||
            screenRoot.findViewWithTag<View>("quick_panel") != null

        if (!overlayIsOpen) {
            screenRoot.post { focusRestorationCoordinator.restore(screenRoot) }
        }
    }

    private fun logSessionEvent(snapshot: br.com.launcher.tv.navigation.LauncherSessionSnapshot) {
        Log.d("LauncherLite.Session", "stage=${snapshot.activityStage}; windowFocus=${snapshot.windowFocus}; interactive=${snapshot.isInteractive}; transitions=${snapshot.transitionCount}; at=${snapshot.lastTransitionAtMs}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (saverView != null) dismissScreenSaver()
        resetIdleTimer()
    }

    private fun createHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(58), dp(34), dp(58), dp(28))
            setBackgroundColor(Color.BLACK)
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        root.addView(createHeader(), LinearLayout.LayoutParams(-1, dp(104)))
        // Two fixed zones: channels above and apps below. Neither zone scrolls vertically.
        root.addView(createChannelRow(), LinearLayout.LayoutParams(-1, 0, 1f))

        val content = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        appGrid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(10))
        }
        content.addView(appGrid, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        screenRoot = FrameLayout(this)
        screenRoot.addView(root, FrameLayout.LayoutParams(-1, -1))
        setContentView(screenRoot)
        renderApps()
    }


    private fun savedChannels(): MutableList<String> = favoriteChannels.load()

    private fun saveChannels(channels: List<String>) {
        favoriteChannels.save(channels)
        if (::channelRowContainer.isInitialized) renderChannels()
    }

    private fun createChannelRow(): View {
        channelRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(3), 0, dp(10))
        }
        return channelRowContainer.also { renderChannels() }
    }

    private fun channelRegionHeight(): Int {
        val rows = prefs.getInt("channel_rows", 1).coerceIn(1, 2)
        val size = prefs.getInt("channel_size", 1).coerceIn(0, 2)
        val cardHeight = when (size) { 0 -> 62; 2 -> 100; else -> 82 }
        return 60 + rows * cardHeight
    }

    private fun renderChannels(force: Boolean = false) {
        if (!::channelRowContainer.isInitialized) return
        val channels = savedChannels()
        if (!force && channels == lastRenderedChannels) return
        focusRestorationCoordinator.capture(window.decorView.findFocus())
        lastRenderedChannels = channels.toList()
        channelRowContainer.removeAllViews()
        val columns = prefs.getInt("channel_columns", 5).coerceIn(3, 7)
        val size = prefs.getInt("channel_size", 1).coerceIn(0, 2)
        val cardHeight = when (size) { 0 -> dp(62); 2 -> dp(100); else -> dp(82) }
        val screenWidth = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val contentWidth = screenWidth - 116
        val grid = GridLayout(this).apply {
            columnCount = columns
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            clipChildren = false
            clipToPadding = false
        }
        ChannelLogos.ensure(this, channels) {
            if (::channelRowContainer.isInitialized) renderChannels(force = true)
        }
        fun addChannelTile(tile: View, index: Int) {
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / columns),
                GridLayout.spec(index % columns, 1f)
            ).apply {
                width = 0
                height = cardHeight
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(tile, params)
        }
        channels.take((columns * 2 - 1).coerceAtLeast(1)).forEachIndexed { index, name ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(6), dp(7), dp(6))
                background = rounded(Color.rgb(20, 32, 55), 17, Color.rgb(62, 86, 132))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                tag = FocusAnchor("focus:channel:$name")
                clipChildren = true
                clipToPadding = true
                setOnClickListener { openSaimoChannel(name) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity, R.style.AppDialogTheme)
                        .setTitle(name)
                        .setItems(arrayOf("Editar nome", "Remover dos favoritos", "Cancelar")) { _, which ->
                            when (which) {
                                0 -> showEditChannelDialog(name)
                                1 -> AlertDialog.Builder(this@MainActivity, R.style.AppDialogTheme)
                                    .setTitle("Remover canal")
                                    .setMessage("Remover $name dos favoritos?")
                                    .setNegativeButton("Cancelar", null)
                                    .setPositiveButton("Remover") { _, _ ->
                                        saveChannels(savedChannels().filterNot { it == name })
                                    }.show()
                            }
                        }.show()
                    true
                }
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(
                        if (focused) Color.rgb(29, 53, 83) else Color.rgb(20, 32, 55),
                        17,
                        if (focused) Color.rgb(65, 157, 205) else Color.rgb(62, 86, 132)
                    )
                    v.elevation = if (focused) dp(4).toFloat() else 0f
                    v.scaleX = if (focused) 1.025f else 1f
                    v.scaleY = if (focused) 1.025f else 1f
                }
            }
            val logoImage = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                visibility = View.GONE
                setPadding(dp(3), dp(3), dp(3), dp(3))
                contentDescription = "Canal $name"
            }
            val logoFallback = TextView(this).apply {
                text = "▶"
                textSize = 27f
                setTextColor(Color.rgb(130, 207, 239))
                gravity = Gravity.CENTER
                contentDescription = "Canal $name"
            }
            tile.addView(logoImage, LinearLayout.LayoutParams(-1, -1))
            tile.addView(logoFallback, LinearLayout.LayoutParams(-1, -1))
            ChannelLogos.load(this@MainActivity, name, logoImage) { loaded ->
                logoImage.visibility = if (loaded) View.VISIBLE else View.GONE
                logoFallback.visibility = if (loaded) View.GONE else View.VISIBLE
            }
            addChannelTile(tile, index)
        }
        val addTile = LinearLayout(this).apply {
            tag = FocusAnchor("focus:channel:add")
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(20, 32, 55), 17, Color.rgb(62, 86, 132))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = "Adicionar canal favorito"
            setOnClickListener { showAddChannelDialog() }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(
                    if (focused) Color.rgb(29, 53, 83) else Color.rgb(20, 32, 55),
                    17,
                    if (focused) Color.rgb(65, 157, 205) else Color.rgb(62, 86, 132)
                )
                v.elevation = if (focused) dp(4).toFloat() else 0f
                v.scaleX = if (focused) 1.025f else 1f
                v.scaleY = if (focused) 1.025f else 1f
            }
            addView(TextView(this@MainActivity).apply {
                text = "+"
                textSize = 42f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setTextColor(Color.rgb(220, 235, 250))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -1))
        }
        addChannelTile(addTile, channels.take((columns * 2 - 1).coerceAtLeast(1)).size)
        channelRowContainer.addView(grid, LinearLayout.LayoutParams(-1, -2))
        if (::screenRoot.isInitialized && hasWindowFocus()) channelRowContainer.post {
            focusRestorationCoordinator.restore(screenRoot)
        }
    }
    private fun showAddChannelDialog() {
        val field = EditText(this).apply {
            hint = "Nome do canal (ex.: sbt, record, globo)"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(field, LinearLayout.LayoutParams(-1, dp(52)))
        }
        val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Adicionar canal do Saímo")
            .setMessage("Digite o nome do canal exatamente como aparece na lista do Saímo TV.")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = field.text.toString().trim()
                if (name.isBlank()) {
                    field.error = "Informe o nome do canal"
                    return@setOnClickListener
                }
                val current = savedChannels()
                if (current.any { it.equals(name, ignoreCase = true) }) {
                    field.error = "Esse canal já está nos favoritos"
                    return@setOnClickListener
                }
                current.add(name)
                saveChannels(current)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showEditChannelDialog(oldName: String) {
        val field = EditText(this).apply {
            setText(oldName)
            setSelection(text.length)
            hint = "Nome do canal no Saímo TV"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(field, LinearLayout.LayoutParams(-1, dp(52)))
        }
        val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Editar canal")
            .setMessage("Altere o nome usado para localizar e abrir o canal no Saímo TV.")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = field.text.toString().trim()
                if (newName.isBlank()) {
                    field.error = "Informe o nome do canal"
                    return@setOnClickListener
                }
                val current = savedChannels()
                if (current.any { it != oldName && it.equals(newName, ignoreCase = true) }) {
                    field.error = "Esse canal já está nos favoritos"
                    return@setOnClickListener
                }
                val updated = current.map { if (it == oldName) newName else it }
                saveChannels(updated)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun openSaimoChannel(name: String) {
        try {
            val uri = Uri.Builder().scheme("saimo").authority("canal")
                .appendQueryParameter("n", name.trim()).build()
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("br.com.saimo.tv"))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir o Saímo TV. Confira se ele está instalado e se o nome do canal está correto.", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyHomeLayoutPreferences() {
        val normalizedChannelRows = prefs.getInt("channel_rows", 1).coerceIn(1, 2)
        val normalizedAppRows = prefs.getInt("home_app_rows", 2).coerceIn(1, 2)
        prefs.edit().putInt("channel_rows", normalizedChannelRows)
            .putInt("home_app_rows", normalizedAppRows).apply()
        if (::appGrid.isInitialized) renderApps()
        if (::channelRowContainer.isInitialized) {
            renderChannels(force = true)
            channelRowContainer.requestLayout()
        }
        if (::appGrid.isInitialized) appGrid.requestLayout()
    }

    private fun addSettingsSection(parent: LinearLayout, title: String, description: String? = null) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(230, 238, 247)); setPadding(0, dp(19), 0, dp(5))
        })
        if (!description.isNullOrBlank()) parent.addView(TextView(this).apply {
            text = description; textSize = 11f
            setTextColor(Color.rgb(145, 164, 188)); setPadding(0, 0, 0, dp(8))
        })
    }

    private fun addSettingsChoices(
        parent: LinearLayout, title: String, key: String,
        options: List<Pair<String, Int>>, defaultValue: Int, onChanged: () -> Unit = {}
    ) {
        parent.addView(TextView(this).apply {
            text = title; textSize = 12f
            setTextColor(Color.rgb(154, 174, 196)); setPadding(0, dp(11), 0, dp(7))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val chips = mutableListOf<Pair<TextView, Int>>()
        fun paintSelection(value: Int) {
            chips.forEach { (chip, optionValue) ->
                val selected = optionValue == value
                chip.text = (if (selected) "✓  " else "") + options.first { it.second == optionValue }.first
                chip.background = rounded(
                    if (selected) Color.rgb(24, 67, 88) else Color.rgb(20, 29, 43),
                    9,
                    if (selected) Color.rgb(66, 170, 205) else Color.rgb(48, 63, 82)
                )
                chip.setTextColor(if (selected) Color.WHITE else Color.rgb(183, 195, 209))
                chip.alpha = if (selected) 1f else 0.82f
                chip.elevation = 0f
                chip.contentDescription = options.first { it.second == optionValue }.first +
                    if (selected) ", selecionado" else ""
            }
        }
        options.forEachIndexed { index, option ->
            val chip = TextView(this).apply {
                text = option.first; textSize = 12f; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true; setPadding(dp(7), dp(7), dp(7), dp(7))
                setOnFocusChangeListener { view, focused ->
                    val selected = prefs.getInt(key, defaultValue) == option.second
                    view.background = rounded(
                        if (selected) Color.rgb(24, 67, 88) else if (focused) Color.rgb(39, 57, 76) else Color.rgb(20, 29, 43),
                        9,
                        if (focused) Color.rgb(220, 237, 248) else if (selected) Color.rgb(66, 170, 205) else Color.rgb(48, 63, 82)
                    )
                    (view as TextView).setTextColor(if (selected || focused) Color.WHITE else Color.rgb(185, 201, 221))
                    view.scaleX = if (focused) 1.045f else 1f
                    view.scaleY = if (focused) 1.045f else 1f
                    view.elevation = if (focused || selected) dp(4).toFloat() else 0f
                }
                setOnClickListener {
                    prefs.edit().putInt(key, option.second).apply()
                    paintSelection(option.second); onChanged()
                }
            }
            chips.add(chip to option.second)
            row.addView(chip, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index < options.lastIndex) rightMargin = dp(6)
            })
        }
        parent.addView(row)
        paintSelection(prefs.getInt(key, defaultValue).coerceIn(options.minOf { it.second }, options.maxOf { it.second }))
    }

    private fun showDrawerSettingsDialog() {
        prefs.edit()
            .putInt("channel_rows", prefs.getInt("channel_rows", 1).coerceIn(1, 2))
            .putInt("home_app_rows", prefs.getInt("home_app_rows", 2).coerceIn(1, 2))
            .apply()
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val bg = Color.rgb(12, 17, 27)
        val panel = Color.rgb(22, 30, 43)
        val border = Color.rgb(55, 69, 88)
        val selected = Color.rgb(48, 67, 88)
        val accentSoft = Color.rgb(139, 170, 198)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(34), dp(22), dp(34), dp(22))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(TextView(this).apply {
            text = "Configurações"; textSize = 26f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(239, 243, 248))
        })
        titleBlock.addView(TextView(this).apply {
            text = "Personalize a organização e a aparência do Launcher Lite"
            textSize = 12f; setTextColor(Color.rgb(153, 166, 183)); setPadding(0, dp(3), 0, 0)
        })
        header.addView(titleBlock, LinearLayout.LayoutParams(0, dp(58), 1f))
        val close = TextView(this).apply {
            text = "✕"; textSize = 21f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 202, 216)); isFocusable = true; isClickable = true
            background = rounded(panel, 12, border)
            contentDescription = "Fechar configurações"
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) selected else panel, 12, if (focused) accentSoft else border)
                (v as TextView).setTextColor(Color.WHITE)
                v.scaleX = if (focused) 1.035f else 1f
                v.scaleY = if (focused) 1.035f else 1f
            }
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val contentScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(3), 0, dp(4))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(12))
        }
        contentScroll.addView(content, ViewGroup.LayoutParams(-1, -2))

        val tabNames = listOf("Canais", "Aplicativos", "Aparência", "Clima", "Permissões")
        val tabViews = mutableListOf<TextView>()
        var activeTab = 0
        fun sectionCard(parent: LinearLayout, title: String, key: String, options: List<Pair<String, Int>>, defaultValue: Int, onChanged: () -> Unit = {}) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(14))
                background = rounded(panel, 15, border)
            }
            addSettingsChoices(card, title, key, options, defaultValue, onChanged)
            parent.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(6); rightMargin = dp(6); bottomMargin = dp(12)
            })
        }
        fun pairRow(left: () -> Unit, right: () -> Unit) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            left()
            right()
            content.addView(row)
        }
        fun renderTab(index: Int) {
            activeTab = index
            tabViews.forEachIndexed { i, tab ->
                val isSelected = i == index
                tab.background = rounded(if (isSelected) selected else panel, 12, if (isSelected) accentSoft else border)
                tab.setTextColor(if (isSelected) Color.WHITE else Color.rgb(183, 195, 209))
                tab.alpha = if (isSelected) 1f else 0.88f
            }
            content.removeAllViews()
            when (index) {
                0 -> {
                    addSettingsSection(content, "Canais favoritos", "Defina como os canais aparecem na tela inicial.")
                    val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    sectionCard(row1, "Canais por fileira", "channel_columns", listOf("3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7), 5) { applyHomeLayoutPreferences() }
                    sectionCard(row1, "Fileiras de canais", "channel_rows", listOf("1" to 1, "2" to 2), 1) { applyHomeLayoutPreferences() }
                    content.addView(row1)
                    val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    sectionCard(row2, "Tamanho dos cards", "channel_size", listOf("Pequeno" to 0, "Médio" to 1, "Grande" to 2), 1) { applyHomeLayoutPreferences() }
                    content.addView(row2)
                }
                1 -> {
                    addSettingsSection(content, "Aplicativos da tela inicial", "Organize os aplicativos fixados e a gaveta completa.")
                    val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    sectionCard(row1, "Apps por fileira", "home_app_columns", listOf("3" to 3, "4" to 4, "5" to 5, "6" to 6, "7" to 7), 5) { applyHomeLayoutPreferences() }
                    sectionCard(row1, "Fileiras de aplicativos", "home_app_rows", listOf("1" to 1, "2" to 2), 2) { applyHomeLayoutPreferences() }
                    content.addView(row1)
                    val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    sectionCard(row2, "Tamanho dos cards", "home_app_size", listOf("Pequeno" to 0, "Médio" to 1, "Grande" to 2), 1) { applyHomeLayoutPreferences() }
                    content.addView(row2)
                }
                2 -> {
                    addSettingsSection(content, "Aparência", "Ajustes visuais dos elementos da tela inicial.")
                    val info = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), dp(16), dp(18), dp(16))
                        background = rounded(panel, 15, border)
                    }
                    info.addView(TextView(this).apply {
                        text = "Estilo visual"; textSize = 15f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Color.rgb(229, 236, 244))
                    })
                    info.addView(TextView(this).apply {
                        text = "O Launcher Lite utiliza uma paleta escura e discreta. O destaque azul acinzentado aparece apenas nos itens em foco."
                        textSize = 12f; setTextColor(Color.rgb(153, 168, 187))
                        setPadding(0, dp(7), 0, 0)
                    })
                    content.addView(info, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                    val reset = TextView(this).apply {
                        text = "Restaurar configurações padrão"; textSize = 13f
                        setTextColor(Color.rgb(218, 226, 236)); gravity = Gravity.CENTER
                        isFocusable = true; isClickable = true
                        background = rounded(panel, 12, border)
                        setPadding(dp(16), dp(13), dp(16), dp(13))
                        setOnFocusChangeListener { v, focused -> v.background = rounded(if (focused) selected else panel, 12, if (focused) accentSoft else border) }
                        setOnClickListener {
                            prefs.edit()
                                .putInt("channel_columns", 5).putInt("channel_rows", 1).putInt("channel_size", 1)
                                .putInt("home_app_columns", 5).putInt("home_app_rows", 2).putInt("home_app_size", 1)
                                .putString("weather_cep", "09843790").apply()
                            cachedWeatherLocation = null
                            applyHomeLayoutPreferences(); refreshWeather()
                            renderTab(activeTab)
                            Toast.makeText(this@MainActivity, "Preferências padrão restauradas.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    content.addView(reset, LinearLayout.LayoutParams(-1, -2))
                    val saverTitle = TextView(this).apply {
                        text = "Descanso de tela"; textSize = 15f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Color.rgb(229, 236, 244))
                        setPadding(0, dp(18), 0, dp(5))
                    }
                    content.addView(saverTitle)
                    val saverCard = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(10), dp(16), dp(14))
                        background = rounded(panel, 15, border)
                    }
                    addSettingsChoices(saverCard, "Iniciar após", "screensaver_minutes",
                        listOf("1 min" to 1, "3 min" to 3, "5 min" to 5, "10 min" to 10, "15 min" to 15, "30 min" to 30), 5)
                    addSettingsChoices(saverCard, "Mostrar clima no descanso de tela", "screensaver_show_weather",
                        listOf("Não" to 0, "Sim" to 1), 0)
                    content.addView(saverCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                    val system = TextView(this).apply {
                        text = "Configurações do sistema"; textSize = 13f
                        setTextColor(Color.rgb(218, 226, 236)); gravity = Gravity.CENTER
                        isFocusable = true; isClickable = true
                        background = rounded(panel, 12, border)
                        setPadding(dp(16), dp(13), dp(16), dp(13))
                        setOnFocusChangeListener { v, focused -> v.background = rounded(if (focused) selected else panel, 12, if (focused) accentSoft else border) }
                        setOnClickListener { dialog.dismiss(); showLauncherSettingsDialog() }
                    }
                    content.addView(system, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                }
                3 -> {
                    addSettingsSection(content, "Clima", "A previsão é localizada pelo CEP informado.")
                    val cepCard = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), dp(16), dp(18), dp(16))
                        background = rounded(panel, 15, border)
                    }
                    cepCard.addView(TextView(this).apply {
                        text = "CEP da localização"; textSize = 14f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(Color.rgb(229, 236, 244))
                    })
                    val cepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
                    val cepField = EditText(this).apply {
                        setText(currentWeatherCep()); hint = "CEP com 8 números"; textSize = 16f
                        setTextColor(Color.WHITE); setHintTextColor(Color.rgb(132, 147, 166))
                        inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true)
                        setPadding(dp(12), 0, dp(12), 0)
                        background = rounded(Color.rgb(14, 21, 32), 10, border)
                    }
                    cepRow.addView(cepField, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) })
                    val saveCep = TextView(this).apply {
                        text = "Salvar"; textSize = 13f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        gravity = Gravity.CENTER; setTextColor(Color.WHITE); isFocusable = true; isClickable = true
                        background = rounded(selected, 10, accentSoft)
                        setPadding(dp(18), 0, dp(18), 0)
                        setOnFocusChangeListener { v, focused -> v.background = rounded(if (focused) Color.rgb(61, 80, 101) else selected, 10, if (focused) Color.rgb(190, 205, 221) else accentSoft) }
                        setOnClickListener {
                            val cep = cepField.text.toString().filter { it.isDigit() }
                            if (cep.length != 8) Toast.makeText(this@MainActivity, "Digite um CEP válido com 8 números.", Toast.LENGTH_SHORT).show()
                            else {
                                prefs.edit().putString("weather_cep", cep).apply()
                                cachedWeatherLocation = null
                                weatherUpdatedText.text = "Atualizando para $cep"
                                refreshWeather()
                                Toast.makeText(this@MainActivity, "CEP salvo. Atualizando previsão…", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    cepRow.addView(saveCep, LinearLayout.LayoutParams(dp(105), dp(48)))
                    cepCard.addView(cepRow)
                    cepCard.addView(TextView(this).apply {
                        text = "Padrão: 09843790"; textSize = 10f
                        setTextColor(Color.rgb(132, 147, 166)); setPadding(0, dp(8), 0, 0)
                    })
                    content.addView(cepCard, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
        tabNames.forEachIndexed { index, name ->
            val tab = TextView(this).apply {
                text = name; textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                isFocusable = true; isClickable = true
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(panel, 12, border)
                setOnFocusChangeListener { v, focused ->
                    if (index != activeTab) v.background = rounded(if (focused) Color.rgb(42, 54, 70) else panel, 12, if (focused) accentSoft else border)
                }
                setOnClickListener { renderTab(index) }
            }
            tabViews.add(tab)
            tabs.addView(tab, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                if (index > 0) leftMargin = dp(8)
            })
        }
        root.addView(tabs)
        root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnDismissListener { resetIdleTimer() }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        renderTab(0)
        close.requestFocus()
    }

    private fun showLauncherSettingsDialog() {
        AlertDialog.Builder(this, R.style.AppDialogTheme).setTitle("Configurações")
            .setItems(arrayOf("Assistente de configuração", "Definir Launcher Lite como tela inicial", "Ativar acessibilidade (opcional)", "Acesso às notificações", "Configurações do Android")) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().putInt("setup_wizard_step", 0)
                            .putBoolean("setup_wizard_finished", false)
                            .putBoolean("setup_wizard_pending", true).apply()
                        showSetupWizard()
                    }
                    1 -> requestDefaultHome()
                    2 -> openAccessibilitySettings()
                    3 -> {
                        prefs.edit().putBoolean("setup_wizard_pending", true).apply()
                        openIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    4 -> openIntent(Intent(Settings.ACTION_SETTINGS))
                }
            }.setNegativeButton("Fechar", null).show()
    }

    private fun isHomeRoleHeld(): Boolean =
        homeRoleController.snapshot().isHeldByThisApp

    private fun isRegisteredAsHome(): Boolean = try {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo?.packageName == packageName }
    } catch (_: Exception) { false }

    private fun openAccessibilitySettings() {
        try {
            val component = android.content.ComponentName(this, LauncherAccessibilityService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val detailsIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                    putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
                }
                startActivity(detailsIntent)
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Exception) { openIntent(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun openHomeSettingsFallback() {
        val actions = listOf(
            Settings.ACTION_HOME_SETTINGS,
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
        )
        for (action in actions) {
            try {
                val settingsIntent = Intent(action)
                if (settingsIntent.resolveActivity(packageManager) != null) {
                    startActivity(settingsIntent)
                    return
                }
            } catch (_: Exception) { }
        }
        Toast.makeText(
            this,
            "Abra Configurações > Apps > Apps padrão > App de início e selecione Launcher Lite.",
            Toast.LENGTH_LONG
        ).show()
    }

    private var setupDialog: AlertDialog? = null

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = android.content.ComponentName(this, LauncherAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun createSetupQrBitmap(size: Int): android.graphics.Bitmap? {
        return try {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                com.google.zxing.EncodeHintType.MARGIN to 1
            )
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
                "appops set br.com.launcher.tv ACCESS_RESTRICTED_SETTINGS allow",
                com.google.zxing.BarcodeFormat.QR_CODE,
                size,
                size,
                hints
            )
            val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (error: Exception) {
            Log.e("LauncherLite.Setup", "Unable to generate setup QR code", error)
            null
        }
    }

    private fun showSetupWizard() {
        if (isFinishing || setupDialog?.isShowing == true) return

        val step = prefs.getInt("setup_wizard_step", 0).coerceIn(0, 5)
        val homeEnabled = isHomeRoleHeld()
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val notificationsEnabled = isNotificationAccessEnabled()
        val command = "appops set br.com.launcher.tv ACCESS_RESTRICTED_SETTINGS allow"
        val totalSteps = 5

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(26), dp(30), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.rgb(10, 16, 27))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.rgb(39, 56, 77))
            }
            isFocusable = false
        }

        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "LAUNCHER LITE  /  CONFIGURAÇÃO"
            textSize = 11f
            letterSpacing = 0.13f
            setTextColor(Color.rgb(92, 190, 226))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val stepLabel = TextView(this).apply {
            text = if (step == 0) "BOAS-VINDAS" else "PASSO " + step + " DE " + totalSteps
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(Color.rgb(151, 170, 194))
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        topLine.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        topLine.addView(stepLabel)
        root.addView(topLine)

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(15), 0, dp(18))
        }
        for (index in 1..totalSteps) {
            val segment = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (step > 0 && index <= step) Color.rgb(47, 164, 211) else Color.rgb(39, 52, 70))
                }
            }
            progressRow.addView(segment, LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                if (index > 1) leftMargin = dp(6)
            })
        }
        root.addView(progressRow)

        val title = when (step) {
            0 -> "Sua TV, do seu jeito."
            1 -> "Defina a tela inicial"
            2 -> "Libere o acesso especial"
            3 -> "Ative a acessibilidade"
            4 -> "Notificações na tela inicial"
            else -> "Tudo pronto para começar"
        }
        val subtitle = when (step) {
            0 -> "Vamos preparar o Launcher Lite. Em poucos passos, você configura a navegação e conhece os recursos principais."
            1 -> if (homeEnabled) "O Launcher Lite já está selecionado como tela inicial padrão." else "Defina o Launcher Lite como tela inicial para abrir sua TV diretamente nele ao pressionar HOME."
            2 -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                "Primeiro ative a Depuração Wi-Fi nas Opções do desenvolvedor da TV. Depois escaneie o QR Code com o celular, execute o comando no Bugjaeger e volte aqui para continuar."
            } else {
                "Nesta versão do Android, esta autorização adicional normalmente não é necessária. Você já pode seguir para a acessibilidade."
            }
            3 -> if (accessibilityEnabled) "Acessibilidade já está ativa. O serviço permite ao Launcher Lite encaminhar a tecla HOME." else "Ative o serviço Launcher Lite nas configurações do Android. Essa autorização é necessária para o recurso de encaminhamento da tecla HOME."
            4 -> if (notificationsEnabled) "Acesso às notificações autorizado. Os avisos compatíveis poderão aparecer no launcher." else "Permita o acesso somente se quiser visualizar notificações na tela inicial. Esta etapa é opcional."
            else -> "O Launcher Lite está preparado. Você pode ajustar aparência, quantidade de linhas, canais, aplicativos e clima nas configurações."
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 27f
            setTextColor(Color.rgb(245, 248, 253))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(titleView)
        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 15f
            setTextColor(Color.rgb(174, 190, 211))
            setLineSpacing(dp(4).toFloat(), 1.0f)
            setPadding(0, dp(9), 0, dp(19))
        }
        root.addView(subtitleView)

        fun cardView(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 28, 43))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(43, 61, 83))
            }
        }

        fun addCardTitle(parent: LinearLayout, text: String) {
            parent.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setTextColor(Color.rgb(239, 245, 253))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }

        fun addCardBody(parent: LinearLayout, text: String) {
            parent.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(Color.rgb(163, 181, 204))
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(5), 0, 0)
            })
        }

        val mainCard = cardView()
        when (step) {
            0 -> {
                addCardTitle(mainCard, "O que você vai encontrar")
                val featureRows = listOf(
                    "APPS E CANAIS" to "Organize seus aplicativos e atalhos favoritos na tela inicial.",
                    "CLIMA E NOTIFICAÇÕES" to "Consulte o clima e acompanhe avisos compatíveis.",
                    "ASSISTENTE DE VOZ" to "O microfone chama o assistente padrão da TV, como Gemini quando configurado.",
                    "PERSONALIZAÇÃO" to "Ajuste aparência, linhas, cartões e organização."
                )
                featureRows.forEachIndexed { index, item ->
                    val line = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, if (index == 0) dp(12) else dp(10), 0, 0)
                    }
                    line.addView(TextView(this).apply {
                        text = item.first
                        textSize = 10f
                        letterSpacing = 0.1f
                        setTextColor(Color.rgb(76, 184, 222))
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    addCardBody(line, item.second)
                    mainCard.addView(line)
                }
            }
            1 -> {
                addCardTitle(mainCard, if (homeEnabled) "Tela inicial configurada" else "Como definir o Launcher Lite")
                addCardBody(mainCard, if (homeEnabled) {
                    "Nenhuma alteração é necessária. Toque em continuar para revisar as permissões."
                } else {
                    "Ao abrir a tela do Android, escolha Launcher Lite na lista de aplicativos de início e confirme. O launcher original continuará instalado."
                })
            }
            2 -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val qrRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val qrImage = ImageView(this).apply {
                        setImageBitmap(createSetupQrBitmap(360))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(9), dp(9), dp(9), dp(9))
                        background = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = dp(12).toFloat()
                        }
                        contentDescription = "QR Code com comando para liberar configurações restritas"
                    }
                    qrRow.addView(qrImage, LinearLayout.LayoutParams(dp(178), dp(178)))
                    val instructions = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), 0, 0, 0)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    addCardTitle(instructions, "Siga esta ordem")
                    addCardBody(instructions, "1. Ative a Depuração Wi-Fi nas Opções do desenvolvedor da TV.\n2. Volte para esta tela e escaneie o QR Code com a câmera do celular.\n3. Copie o comando reconhecido, abra o Bugjaeger, conecte à TV e execute o comando.\n4. Volte ao Launcher Lite e toque em JÁ EXECUTEI.")
                    val commandView = TextView(this).apply {
                        text = command
                        textSize = 11f
                        setTextColor(Color.rgb(215, 228, 243))
                        setPadding(dp(10), dp(9), dp(10), dp(9))
                        setTextIsSelectable(false)
                        background = GradientDrawable().apply {
                            setColor(Color.rgb(8, 14, 23))
                            cornerRadius = dp(8).toFloat()
                        }
                    }
                    instructions.addView(commandView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                    qrRow.addView(instructions, LinearLayout.LayoutParams(0, -2, 1f))
                    mainCard.addView(qrRow)
                    mainCard.addView(TextView(this).apply {
                        text = "Importante: o QR Code apenas mostra o comando. Ele precisa ser executado no Bugjaeger depois que a Depuração Wi-Fi estiver ativada."
                        textSize = 11f
                        setTextColor(Color.rgb(136, 157, 183))
                        setPadding(0, dp(12), 0, 0)
                    })
                } else {
                    addCardTitle(mainCard, "Esta etapa pode ser ignorada")
                    addCardBody(mainCard, "Seu Android não exige este desbloqueio adicional. Continue para abrir as configurações de acessibilidade.")
                }
            }
            3 -> {
                addCardTitle(mainCard, if (accessibilityEnabled) "Serviço ativado" else "Onde ativar")
                addCardBody(mainCard, if (accessibilityEnabled) {
                    "O serviço Launcher Lite aparece como ativado. Você pode continuar."
                } else {
                    "Na tela seguinte, procure Launcher Lite na lista de serviços instalados. Abra, habilite o serviço e confirme a mensagem de autorização do Android."
                })
                val status = TextView(this).apply {
                    text = if (accessibilityEnabled) "✓  ATIVO" else "○  AGUARDANDO ATIVAÇÃO"
                    textSize = 11f
                    letterSpacing = 0.08f
                    setTextColor(if (accessibilityEnabled) Color.rgb(100, 211, 155) else Color.rgb(231, 184, 95))
                    setPadding(0, dp(13), 0, 0)
                }
                mainCard.addView(status)
            }
            4 -> {
                addCardTitle(mainCard, if (notificationsEnabled) "Acesso autorizado" else "Opcional — você decide")
                addCardBody(mainCard, if (notificationsEnabled) {
                    "As notificações já estão autorizadas. Não é necessário fazer mais nada."
                } else {
                    "Você pode pular esta etapa e continuar usando o launcher. O acesso pode ser ativado depois em Configurações."
                })
            }
            else -> {
                addCardTitle(mainCard, "Resumo da configuração")
                val summary = listOf(
                    "Tela inicial" to if (homeEnabled) "Configurada" else "Não configurada",
                    "Acessibilidade" to if (accessibilityEnabled) "Ativada" else "Pendente",
                    "Notificações" to if (notificationsEnabled) "Autorizadas" else "Opcional / não autorizadas"
                )
                summary.forEach { item ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, dp(8))
                    }
                    row.addView(TextView(this).apply {
                        text = item.first
                        textSize = 13f
                        setTextColor(Color.rgb(178, 194, 214))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(TextView(this).apply {
                        text = item.second
                        textSize = 12f
                        setTextColor(Color.rgb(232, 239, 248))
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    })
                    mainCard.addView(row)
                }
                addCardBody(mainCard, "Para personalizar depois, abra Configurações no topo da tela inicial e entre em Aparência, Aplicativos ou Canais.")
            }
        }
        root.addView(mainCard, LinearLayout.LayoutParams(-1, -2))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, 0)
        }

        lateinit var dialog: AlertDialog

        fun makeAction(label: String, primary: Boolean, action: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(12), dp(18), dp(12))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(11).toFloat()
                    setColor(Color.rgb(22, 32, 47))
                    setStroke(dp(1), Color.rgb(55, 72, 94))
                }
                setTextColor(Color.WHITE)
                setOnFocusChangeListener { view, focused ->
                    view.background = GradientDrawable().apply {
                        cornerRadius = dp(11).toFloat()
                        setColor(if (focused) Color.rgb(17, 105, 145) else Color.rgb(22, 32, 47))
                        setStroke(dp(if (focused) 2 else 1), if (focused) Color.rgb(112, 226, 255) else Color.rgb(55, 72, 94))
                    }
                }
                setOnClickListener { action() }
            }
        }

        fun moveTo(nextStep: Int) {
            prefs.edit().putInt("setup_wizard_step", nextStep)
                .putBoolean("setup_wizard_pending", true).apply()
            dialog.dismiss()
            window.decorView.postDelayed({ showSetupWizard() }, 180)
        }

        fun closeAndRun(action: () -> Unit) {
            prefs.edit().putBoolean("setup_wizard_pending", true).apply()
            dialog.dismiss()
            action()
        }

        when (step) {
            0 -> {
                actions.addView(makeAction("Agora não", false) {
                    prefs.edit().putBoolean("setup_wizard_pending", false).apply()
                    finishSetupWizard()
                    dialog.dismiss()
                })
                actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                actions.addView(makeAction("COMEÇAR", true) { moveTo(1) })
            }
            1 -> {
                actions.addView(makeAction("VOLTAR", false) { moveTo(0) })
                actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                actions.addView(makeAction(if (homeEnabled) "CONTINUAR" else "DEFINIR TELA INICIAL", true) {
                    if (homeEnabled) moveTo(2) else closeAndRun { requestDefaultHome() }
                })
            }
            2 -> {
                actions.addView(makeAction("VOLTAR", false) { moveTo(1) })
                actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                actions.addView(makeAction("ABRIR OPÇÕES DO DESENVOLVEDOR", false) {
                    closeAndRun {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                }, LinearLayout.LayoutParams(-2, -2))
                actions.addView(makeAction("JÁ EXECUTEI — CONTINUAR", true) {
                    prefs.edit().putInt("setup_wizard_step", 3).putBoolean("setup_wizard_pending", true).apply()
                    closeAndRun { openAccessibilitySettings() }
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            }
            3 -> {
                actions.addView(makeAction("VOLTAR", false) { moveTo(2) })
                actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                actions.addView(makeAction("DEIXAR PARA DEPOIS", false) { moveTo(4) })
                actions.addView(makeAction(if (accessibilityEnabled) "CONTINUAR" else "ABRIR ACESSIBILIDADE", true) {
                    if (accessibilityEnabled) moveTo(4) else closeAndRun { openAccessibilitySettings() }
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            }
            4 -> {
                actions.addView(makeAction("VOLTAR", false) { moveTo(3) })
                actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                actions.addView(makeAction("PULAR", false) { moveTo(5) })
                actions.addView(makeAction(if (notificationsEnabled) "CONTINUAR" else "AUTORIZAR", true) {
                    if (notificationsEnabled) moveTo(5) else closeAndRun {
                        openIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            }
            else -> {
                actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                actions.addView(makeAction("CONCLUIR", true) {
                    dialog.dismiss()
                    finishSetupWizard()
                })
            }
        }
        root.addView(actions)

        dialog = AlertDialog.Builder(this, R.style.AppDialogTheme).setView(root).create()
        setupDialog = dialog
        dialog.setOnDismissListener { if (setupDialog === dialog) setupDialog = null }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.82f).toInt(), -2)
        actions.getChildAt(actions.childCount - 1)?.requestFocus()
    }

    private fun finishSetupWizard() {
        prefs.edit()
            .putBoolean("setup_wizard_finished", true)
            .putBoolean("setup_wizard_seen", true)
            .putBoolean("setup_wizard_pending", false)
            .putInt("setup_wizard_version", 12)
            .putInt("setup_wizard_step", 5)
            .apply()
        Toast.makeText(this, "Configuração concluída. Bem-vindo ao Launcher Lite!", Toast.LENGTH_LONG).show()
    }

    private fun requestDefaultHome() {
        prefs.edit().putBoolean("setup_wizard_pending", true).apply()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val manager = getSystemService(android.app.role.RoleManager::class.java)
                if (manager != null && manager.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                    if (manager.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                        prefs.edit().putBoolean("setup_wizard_pending", false).apply()
                        Toast.makeText(this, "Launcher Lite já é o padrão.", Toast.LENGTH_LONG).show()
                    } else {
                        startActivityForResult(
                            manager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME),
                            4102
                        )
                    }
                    return
                }
            }
            openHomeSettingsFallback()
        } catch (_: Exception) {
            openHomeSettingsFallback()
        }
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
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            clipChildren = false
            clipToPadding = false
        }
        val weatherPill = LinearLayout(this).apply {
            tag = FocusAnchor("focus:weather")
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(7, 11, 18), 18, Color.rgb(28, 40, 55))
            isFocusable = true
            isClickable = true
            setOnClickListener { openWeatherScreen() }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) Color.rgb(8, 24, 39) else Color.rgb(8, 13, 22),
                    18, if (focused) accent else Color.rgb(42, 55, 75))
            }
        }
        weatherIcon = TextView(this).apply {
            text = "☀"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        val weatherInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), 0, 0, 0)
        }
        weatherText = TextView(this).apply {
            text = "Clima"
            textSize = 13f
            setTextColor(Color.rgb(232, 241, 252))
            maxLines = 1
        }
        weatherUpdatedText = TextView(this).apply {
            text = "CEP 09843-790"
            textSize = 9f
            setTextColor(Color.rgb(142, 162, 186))
            maxLines = 1
            setPadding(0, dp(2), 0, 0)
        }
        weatherInfo.addView(weatherText)
        weatherInfo.addView(weatherUpdatedText)
        weatherPill.addView(weatherIcon)
        weatherPill.addView(weatherInfo)
        actions.addView(headerAction(R.drawable.ic_mic_modern, "Pesquisa por voz") { openVoice() })
        actions.addView(headerAction(R.drawable.ic_notifications_modern, "Notificações") { openNotificationsPanel() })
        actions.addView(headerAction(R.drawable.ic_settings_modern, "Configurações") { showLauncherSettingsDialog() })
        val centeredHeader = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        centeredHeader.addView(left, FrameLayout.LayoutParams(dp(230), -1, Gravity.LEFT or Gravity.CENTER_VERTICAL))
        centeredHeader.addView(weatherPill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        centeredHeader.addView(actions, FrameLayout.LayoutParams(-2, -1, Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        header.addView(centeredHeader, LinearLayout.LayoutParams(-1, -1))
        refreshWeather()
        return header
    }

    private fun headerAction(iconRes: Int, description: String, action: () -> Unit): View {
        return ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(224, 235, 248))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = description
            tag = FocusAnchor("focus:header:$description")
            isFocusable = true
            isClickable = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.TRANSPARENT, 16, Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
            clipToOutline = false
            setOnClickListener { action() }
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) Color.rgb(7, 20, 33) else Color.TRANSPARENT,
                    16, if (focused) Color.rgb(16, 94, 126) else Color.TRANSPARENT)
                v.scaleX = 1f; v.scaleY = 1f
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
            setPadding(dp(18), dp(20), dp(18), dp(16))
            background = rounded(Color.BLACK, 0, Color.TRANSPARENT)
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
            textSize = 22f
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
            id = View.generateViewId()
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
            id = View.generateViewId()
            text = "Limpar tudo"
            textSize = 11f
            setTextColor(Color.rgb(180, 218, 255))
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(9), dp(6), dp(9), dp(6))
            minWidth = dp(82); minHeight = dp(40)
            background = rounded(Color.rgb(25, 25, 25), 12, Color.rgb(70, 70, 70))
            setOnFocusChangeListener { v, focused -> v.background = rounded(if (focused) Color.rgb(18, 67, 105) else Color.rgb(25,25,25), 12, if (focused) accent else Color.rgb(70,70,70)) }
            setOnClickListener { LauncherNotificationListener.instance?.clearAll(); refreshNotificationPanel(); closeNotificationsPanel() }
        }
        val close = TextView(this).apply {
            id = View.generateViewId()
            text = "Fechar"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(dp(9), dp(6), dp(9), dp(6))
            minWidth = dp(66); minHeight = dp(40)
            background = rounded(Color.rgb(28, 38, 54), 12, Color.rgb(70, 80, 95))
            setOnFocusChangeListener { v, focused -> v.background = rounded(if (focused) Color.rgb(18, 67, 105) else Color.rgb(28,38,54), 12, if (focused) accent else Color.rgb(70,80,95)) }
            setOnClickListener { closeNotificationsPanel() }
        }
        bottom.addView(manage, LinearLayout.LayoutParams(0, dp(42), 1f))
        bottom.addView(clear, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
        bottom.addView(close, LinearLayout.LayoutParams(-2, dp(42)))
        panel.addView(bottom)
        scrim.addView(panel, FrameLayout.LayoutParams(dp(390), -1, Gravity.RIGHT))
        screenRoot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        refreshNotificationPanel()
        panel.post { (notificationPanel?.getChildAt(0) ?: manage).requestFocus() }
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
                background = rounded(Color.rgb(18, 18, 18), 17, Color.rgb(55, 55, 55))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    LauncherNotificationListener.instance?.openNotification(notice.key)
                    closeNotificationsPanel()
                }
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(if (focused) Color.rgb(13, 38, 61) else Color.rgb(18, 18, 18),
                        17, if (focused) accent else Color.rgb(55, 55, 55))
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
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
            })
            if (notice.message.isNotBlank()) textColumn.addView(TextView(this).apply {
                text = notice.message
                textSize = 11f
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
                background = rounded(Color.rgb(20, 30, 44), 17, Color.rgb(62, 82, 119))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    screenRoot.removeView(scrim)
                    if (title == "Notificações") openNotificationsPanel() else openIntent(intent)
                }
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(if (focused) Color.rgb(13, 38, 61) else Color.rgb(43, 58, 88),
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
            background = rounded(Color.rgb(13, 38, 61), 16, Color.TRANSPARENT)
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
        // Delegate to the system's configured digital assistant (for example, Gemini).
        try {
            startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                startActivity(Intent("android.intent.action.VOICE_ASSIST").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(this, "Configure um assistente digital nas configurações do Android.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated by Android, retained for TV speech recognizer compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4102) {
            if (isHomeRoleHeld()) {
                prefs.edit().putInt("setup_wizard_step", 2).putBoolean("setup_wizard_pending", true).apply()
                Toast.makeText(this, "Launcher Lite definido como padrão!", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putBoolean("setup_wizard_pending", true).apply()
                Toast.makeText(this, "Escolha Launcher Lite na tela de apps de início.", Toast.LENGTH_LONG).show()
                openHomeSettingsFallback()
            }
            return
        }
        if (requestCode != 701 || resultCode != RESULT_OK) return
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        val normalized = spoken.lowercase(Locale("pt", "BR"))
        if (listOf("tempo", "clima", "previsão", "previsao", "temperatura").any { normalized.contains(it) }) {
            openWeatherScreen()
        } else if (spoken.isNotBlank()) {
            try { startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", spoken)) }
            catch (_: Exception) { Toast.makeText(this, "Não entendi o comando: $spoken", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun openWeatherScreen() {
        if (weatherPage?.parent != null) return
        weatherPage = null
        val page = LinearLayout(this).apply {
            tag = "weather_screen"; orientation = LinearLayout.VERTICAL
            setPadding(dp(42), dp(28), dp(42), dp(24))
            setBackgroundColor(Color.rgb(5, 8, 14))
            isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        weatherPage = page
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "Previsão do tempo"; textSize = 27f; setTextColor(Color.WHITE) },
            LinearLayout.LayoutParams(0, -2, 1f))
        val refreshForecast = TextView(this).apply {
            text = "↻"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(174, 186, 199))
            isFocusable = true; isClickable = true
            contentDescription = "Atualizar previsão"
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(Color.rgb(14, 18, 25), 10, Color.rgb(48, 57, 68))
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(Color.rgb(14, 18, 25), 10,
                    if (focused) Color.rgb(135, 151, 168) else Color.rgb(48, 57, 68))
                v.alpha = if (focused) 1f else 0.72f
            }
            setOnClickListener {
                screenRoot.removeView(page)
                weatherPage = null
                refreshWeather()
                openWeatherScreen()
            }
        }
        top.addView(refreshForecast, LinearLayout.LayoutParams(dp(42), dp(38)))
        page.addView(top)
        val weatherLocationView = TextView(this).apply {
            text = weatherLocationLabel
            textSize = 13f
            setTextColor(Color.rgb(156, 177, 202))
            setPadding(0, dp(5), 0, dp(12))
        }
        page.addView(weatherLocationView)
        page.addView(TextView(this).apply { text = weatherIcon.text.toString() + "   " + weatherText.text.toString(); textSize = 19f; setTextColor(Color.WHITE); setPadding(dp(18), dp(10), dp(18), dp(10)); background = rounded(Color.rgb(16, 24, 38), 18, Color.rgb(45, 65, 90)) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val hoursTitle = TextView(this).apply { text = "Previsão por hora — hoje"; textSize = 19f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(8)) }
        page.addView(hoursTitle)
        val hs = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val hr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isFocusable = false }
        hs.addView(hr)
        page.addView(hs, LinearLayout.LayoutParams(-1, dp(126)).apply { bottomMargin = dp(18) })
        page.addView(TextView(this).apply { text = "Próximos dias"; textSize = 19f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(8)) })
        val ds = android.widget.ScrollView(this).apply {
            isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val dl = android.widget.GridLayout(this).apply {
            columnCount = 7; useDefaultMargins = false
            isFocusable = false; isFocusableInTouchMode = false
        }
        ds.addView(dl)
        page.addView(ds, LinearLayout.LayoutParams(-1, 0, 1f))
        screenRoot.addView(page, FrameLayout.LayoutParams(-1, -1)); page.isFocusableInTouchMode = false
        Thread {
            try {
                val location = cachedWeatherLocation?.takeIf { it.cep == currentWeatherCep() }
                    ?: resolveWeatherLocation(currentWeatherCep()).also { cachedWeatherLocation = it }
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&hourly=temperature_2m,precipitation_probability,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=7&timezone=America%2FSao_Paulo")
                val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 10000 }
                val status = c.responseCode
                val response = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                c.disconnect()
                if (status !in 200..299) throw IOException("Previsão HTTP $status")
                val j = JSONObject(response)
                val h = j.getJSONObject("hourly"); val d = j.getJSONObject("daily")
                runOnUiThread {
                    weatherLocationLabel = location.label
                    weatherLocationView.text = location.label
                    hr.removeAllViews(); val ts = h.getJSONArray("time"); val tv = h.getJSONArray("temperature_2m"); val rp = h.getJSONArray("precipitation_probability"); val wc = h.getJSONArray("weather_code")
                    val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault()); val fmt = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                    val first = (0 until ts.length()).firstOrNull { i -> try { !parser.parse(ts.getString(i)).before(Date()) } catch (_: Exception) { false } } ?: 0
                    var selectedHourCard: View? = null
                    var firstDayCard: View? = null
                    var selectedDayKey = ""
                    val dayCards = mutableListOf<View>()
                    fun renderHoursForDate(dateKey: String, focusFirstHour: Boolean = false) {
                        val parsedDay = try { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateKey) } catch (_: Exception) { null }
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        val dayLabel = if (dateKey == today) "Hoje" else try { SimpleDateFormat("EEEE", Locale("pt", "BR")).format(parsedDay!!).replaceFirstChar { it.uppercase() } } catch (_: Exception) { dateKey }
                        val dateLabel = try { SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(parsedDay!!) } catch (_: Exception) { "" }
                        hoursTitle.text = "Previsão por hora — $dayLabel${if (dateLabel.isNotBlank()) ", $dateLabel" else ""}"
                        hr.removeAllViews()
                        val indices = (0 until ts.length()).filter { ts.getString(it).startsWith(dateKey) }
                        val nowMillis = System.currentTimeMillis()
                        val nowIndex = if (dateKey == today) indices.filter { i ->
                            try { parser.parse(ts.getString(i))!!.time <= nowMillis } catch (_: Exception) { false }
                        }.maxByOrNull { i ->
                            try { parser.parse(ts.getString(i))!!.time } catch (_: Exception) { Long.MIN_VALUE }
                        } else null
                        indices.forEach { i ->
                            val currentHour = i == nowIndex
                            val card = LinearLayout(this).apply {
                                id = View.generateViewId()
                                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                                setPadding(dp(9), dp(7), dp(9), dp(7))
                                background = rounded(Color.rgb(12, 17, 27), 13, if (currentHour) Color.rgb(16, 94, 126) else Color.rgb(30, 42, 57))
                                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                                setOnFocusChangeListener { v, focused ->
                                    v.background = rounded(
                                        if (focused) Color.rgb(13, 38, 61) else Color.rgb(12, 17, 27),
                                        13,
                                        if (focused) accent else if (currentHour) Color.rgb(16, 94, 126) else Color.rgb(30, 42, 57)
                                    )
                                }
                            }
                            val hourRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
                            if (currentHour) {
                                hourRow.addView(View(this).apply {
                                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.RED) }
                                }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) })
                            }
                            val hour = try { fmt.format(parser.parse(ts.getString(i))!!) } catch (_: Exception) { "--:--" }
                            hourRow.addView(TextView(this).apply { text = hour; textSize = 13f; setTextColor(Color.LTGRAY) })
                            card.addView(hourRow)
                            card.addView(TextView(this).apply { text = weatherSymbol(wc.getInt(i)); textSize = 24f; setTextColor(Color.WHITE) })
                            card.addView(TextView(this).apply { text = tv.getDouble(i).toInt().toString() + "°"; textSize = 19f; setTextColor(Color.WHITE) })
                            card.addView(TextView(this).apply { text = rp.optInt(i).toString() + "% chuva"; textSize = 11f; setTextColor(Color.rgb(155,195,235)) })
                            hr.addView(card, LinearLayout.LayoutParams(dp(88), dp(112)).apply { rightMargin = dp(8) })
                            }
                        val preferredIndex = nowIndex?.let { indices.indexOf(it) } ?: 0
                        selectedHourCard = hr.getChildAt(preferredIndex) ?: hr.getChildAt(0)
                        // Rebuild ALL DPAD links whenever the hourly row is recreated.
                        // Old hour-card IDs become invalid after hr.removeAllViews().
                        val freshHourCards = (0 until hr.childCount).map { hr.getChildAt(it) }
                        val returnTarget = selectedHourCard
                        freshHourCards.forEachIndexed { index, hourCard ->
                            hourCard.nextFocusLeftId = freshHourCards.getOrNull(index - 1)?.id ?: hourCard.id
                            hourCard.nextFocusRightId = freshHourCards.getOrNull(index + 1)?.id ?: hourCard.id
                            hourCard.nextFocusUpId = hourCard.id
                            hourCard.nextFocusDownId = firstDayCard?.id ?: hourCard.id
                        }
                        dayCards.forEachIndexed { index, dayCard ->
                            dayCard.nextFocusLeftId = dayCards.getOrNull(index - 1)?.id ?: dayCard.id
                            dayCard.nextFocusRightId = dayCards.getOrNull(index + 1)?.id ?: dayCard.id
                            dayCard.nextFocusUpId = returnTarget?.id ?: dayCard.id
                            dayCard.nextFocusDownId = dayCard.id
                        }
                        if (focusFirstHour && indices.isNotEmpty()) hs.post {
                            val target = selectedHourCard
                            if (target != null) {
                                hs.scrollTo((target.left - dp(24)).coerceAtLeast(0), 0)
                                target.requestFocus()
                            }
                        }
                    }
                    val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    selectedDayKey = todayKey
                    renderHoursForDate(todayKey, true)
                    dl.removeAllViews(); dayCards.clear(); val dates = d.getJSONArray("time"); val mx = d.getJSONArray("temperature_2m_max"); val mn = d.getJSONArray("temperature_2m_min"); val ch = d.getJSONArray("precipitation_probability_max"); val codes = d.getJSONArray("weather_code")
                    val df = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val out = java.text.SimpleDateFormat("EEE, dd/MM", Locale("pt", "BR"))
                    firstDayCard = null
                    for (i in 0 until dates.length()) {
                        val dateKey = dates.getString(i)
                        val day = if (dateKey == todayKey) "Hoje" else try { out.format(df.parse(dateKey)!!) } catch (_: Exception) { dateKey }
                        val card = LinearLayout(this).apply {
                            id = View.generateViewId()
                            tag = dateKey
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(4), dp(8), dp(4), dp(8))
                            background = rounded(Color.rgb(12, 17, 27), 13, if (dateKey == selectedDayKey) accent else Color.rgb(30, 42, 57))
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isClickable = true
                            setOnFocusChangeListener { v, focused ->
                                v.background = rounded(if (focused) Color.rgb(13, 38, 61) else Color.rgb(12,17,27), 13, if (focused || dateKey == selectedDayKey) accent else Color.rgb(30, 42, 57))
                            }
                            setOnClickListener { clickedView ->
                                selectedDayKey = dateKey
                                dayCards.forEach { day ->
                                    val isSelected = (day.tag as? String) == selectedDayKey
                                    val isFocused = day.hasFocus()
                                    day.background = rounded(if (isFocused) Color.rgb(13, 38, 61) else Color.rgb(12, 17, 27), 13,
                                        if (isFocused || isSelected) accent else Color.rgb(30, 42, 57))
                                }
                                renderHoursForDate(dateKey, false)
                                clickedView.requestFocus()
                            }
                        }
                        card.addView(TextView(this).apply { text = day; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1 })
                        card.addView(TextView(this).apply { text = weatherSymbol(codes.getInt(i)); textSize = 21f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(3), 0, dp(3)) })
                        card.addView(TextView(this).apply { text = mn.getDouble(i).toInt().toString() + "° / " + mx.getDouble(i).toInt() + "°"; textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 1 })
                        card.addView(TextView(this).apply { text = ch.optInt(i).toString() + "%"; textSize = 10f; setTextColor(Color.rgb(155,195,235)); gravity = Gravity.CENTER })
                        val params = android.widget.GridLayout.LayoutParams(
                            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
                            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                        ).apply {
                            width = 0
                            height = dp(120)
                            setMargins(dp(3), dp(4), dp(3), dp(4))
                        }
                        dl.addView(card, params)
                        dayCards.add(card)
                        if (firstDayCard == null) firstDayCard = card
                    }
                    // Keep DPAD focus strictly inside the two forecast sections.
                    val hourCards = (0 until hr.childCount).map { hr.getChildAt(it) }
                    val allDayCards = (0 until dl.childCount).map { dl.getChildAt(it) }
                    val firstHour = hourCards.firstOrNull()
                    val lastHour = hourCards.lastOrNull()
                    val firstDay = firstDayCard
                    val returnHour = selectedHourCard ?: firstHour
                    hourCards.forEachIndexed { index, card ->
                        card.nextFocusLeftId = hourCards.getOrNull(index - 1)?.id ?: card.id
                        card.nextFocusRightId = hourCards.getOrNull(index + 1)?.id ?: card.id
                        card.nextFocusUpId = card.id
                        card.nextFocusDownId = firstDay?.id ?: card.id
                    }
                    allDayCards.forEachIndexed { index, card ->
                        card.nextFocusLeftId = dayCards.getOrNull(index - 1)?.id ?: card.id
                        card.nextFocusRightId = dayCards.getOrNull(index + 1)?.id ?: card.id
                        card.nextFocusUpId = returnHour?.id ?: card.id
                        card.nextFocusDownId = card.id
                    }
                    // The containers and page must never become focus targets.
                    page.isFocusable = false
                    hs.isFocusable = false
                    hr.isFocusable = false
                    ds.isFocusable = false
                    dl.isFocusable = false
                }
            } catch (_: Exception) { runOnUiThread { hr.removeAllViews(); hr.addView(TextView(this).apply { text = "Não foi possível carregar a previsão. Verifique a internet."; setTextColor(Color.LTGRAY); textSize = 15f }) } }
        }.start()
    }

    private fun weatherSymbol(code: Int): String = when { code >= 95 -> "⛈"; code in 51..67 || code in 80..82 -> "🌧"; code == 45 || code == 48 -> "🌫"; code == 0 -> "☀"; code in 2..3 -> "☁"; else -> "🌤" }
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

    private fun currentWeatherCep(): String =
        prefs.getString("weather_cep", "09843790").orEmpty().filter { it.isDigit() }.take(8).padStart(8, '0')

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LauncherLite/0.7")
        }
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("HTTP $status: ${body.take(160)}")
            return body
        } finally { connection.disconnect() }
    }

    private fun resolveWeatherLocation(cep: String): WeatherLocation {
        if (cep.length != 8) throw IOException("Informe um CEP com 8 números.")
        val address = JSONObject(httpGet("https://brasilapi.com.br/api/cep/v2/$cep"))
        val coordinates = address.optJSONObject("location")?.optJSONObject("coordinates")
        fun number(value: Any?): Double? = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        val latitude = number(coordinates?.opt("latitude"))
        val longitude = number(coordinates?.opt("longitude"))
        val city = address.optString("city", address.optString("localidade", "")).trim()
        val state = address.optString("state", address.optString("uf", "")).trim()
        val neighborhood = address.optString("neighborhood", address.optString("bairro", "")).trim()
        val formattedCep = cep.substring(0, 5) + "-" + cep.substring(5)
        val placeLabel = listOf(neighborhood, city, state).filter { it.isNotBlank() }.joinToString(" • ")
        val label = if (placeLabel.isBlank()) "CEP $formattedCep" else "$placeLabel • CEP $formattedCep"
        if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            return WeatherLocation(latitude, longitude, cep, label)
        }
        if (city.isBlank()) throw IOException("Não foi possível localizar esse CEP.")
        val queries = listOf(
            listOf(neighborhood, city, state).filter { it.isNotBlank() }.joinToString(", "),
            listOf(city, state).filter { it.isNotBlank() }.joinToString(", ")
        ).distinct()
        for (query in queries) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val geo = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=5&language=pt&format=json&countryCode=BR"))
            val results = geo.optJSONArray("results") ?: continue
            for (i in 0 until results.length()) {
                val result = results.optJSONObject(i) ?: continue
                if (result.optString("country_code") != "BR") continue
                val lat = result.optDouble("latitude", Double.NaN)
                val lon = result.optDouble("longitude", Double.NaN)
                if (lat.isFinite() && lon.isFinite()) return WeatherLocation(lat, lon, cep, label)
            }
        }
        throw IOException("Não encontramos a localização desse CEP.")
    }

    private fun refreshWeather() {
        if (::weatherText.isInitialized) weatherText.text = "Atualizando clima…"
        if (::weatherUpdatedText.isInitialized) weatherUpdatedText.text = "Consultando CEP"
        val cep = currentWeatherCep()
        Thread {
            var finalLabel = "Clima indisponível • toque para tentar novamente"
            var finalSymbol = "☁"
            var updated = "Não atualizado"
            var locationLabel = weatherLocationLabel
            try {
                val location = cachedWeatherLocation?.takeIf { it.cep == cep } ?: resolveWeatherLocation(cep)
                cachedWeatherLocation = location
                locationLabel = location.label
                val query = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,relative_humidity_2m,weather_code,is_day&timezone=America%2FSao_Paulo"
                val current = JSONObject(httpGet(query)).getJSONObject("current")
                val temperature = current.getDouble("temperature_2m").toInt()
                val humidity = current.getInt("relative_humidity_2m")
                val code = current.getInt("weather_code")
                val description = when (code) {
                    0 -> "Céu limpo"; 1 -> "Poucas nuvens"; 2 -> "Parcialmente nublado"; 3 -> "Nublado"
                    45, 48 -> "Neblina"; 51, 53, 55, 56, 57 -> "Garoa"
                    61, 63, 65, 66, 67 -> "Chuva"; 80, 81, 82 -> "Pancadas de chuva"
                    95, 96, 99 -> "Trovoadas"; else -> "Condição atual"
                }
                finalSymbol = when {
                    code >= 95 -> "⛈"; code in 51..67 || code in 80..82 -> "🌧"
                    code == 45 || code == 48 -> "🌫"
                    code == 0 -> if (current.optInt("is_day", 1) == 1) "☀" else "☾"
                    code in 2..3 -> "☁"; else -> "🌤"
                }
                finalLabel = "$temperature°C  •  $description  •  Umidade $humidity%"
                updated = "Atualizado às " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            } catch (error: Exception) {
                Log.w("LauncherLite.Weather", "Falha ao atualizar clima para o CEP $cep", error)
                finalLabel = if (error.message?.contains("CEP", true) == true)
                    "CEP não localizado • toque para configurar"
                else "Clima indisponível • toque para tentar novamente"
            }
            val resultLabel = finalLabel
            val resultSymbol = finalSymbol
            val resultUpdated = updated
            val resultLocation = locationLabel
            runOnUiThread {
                if (::weatherText.isInitialized) weatherText.text = resultLabel
                if (::weatherIcon.isInitialized) weatherIcon.text = resultSymbol
                if (::weatherUpdatedText.isInitialized) weatherUpdatedText.text = resultUpdated
                weatherLocationLabel = resultLocation
            }
        }.start()
    }

    private fun homeApps(): List<ResolveInfo> {
        val saved = prefs.getString("home_apps_order", null) ?: return apps.take(9)
        val byPackage = apps.associateBy { it.activityInfo.packageName }
        return saved.split("|").filter { it.isNotBlank() }.mapNotNull { byPackage[it] }.take(9)
    }

    private fun saveHomeApps(items: List<ResolveInfo>) {
        prefs.edit().putString(
            "home_apps_order",
            items.distinctBy { it.activityInfo.packageName }
                .joinToString("|") { it.activityInfo.packageName }
        ).apply()
    }

    private fun renderApps(focus: Int = 0) {
        appGrid.removeAllViews()
        val columns = prefs.getInt("home_app_columns", 5).coerceIn(3, 7)
        val rows = prefs.getInt("home_app_rows", 2).coerceIn(1, 2)
        val size = prefs.getInt("home_app_size", 1).coerceIn(0, 2)
        appGrid.columnCount = columns
        val screenWidth = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val contentWidth = screenWidth - 116
        val horizontalMargins = 18 * columns
        val tileWidth = dp((((contentWidth - horizontalMargins) / columns) * 0.93f).toInt().coerceIn(90, 232))
        val ratio = when (size) { 0 -> 0.53f; 2 -> 0.72f; else -> 0.62f }
        val tileHeight = ((tileWidth * ratio) * 0.96f).toInt().coerceAtLeast(dp(68))
        val visible = homeApps().take((columns * rows - 1).coerceAtLeast(1))
        visible.forEachIndexed { index, app ->
            addAppCard(app, index, tileWidth, tileHeight, index / columns, index % columns)
        }
        val drawerIndex = visible.size
        addAllAppsCard(tileWidth, tileHeight, drawerIndex / columns, drawerIndex % columns)
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
        if (organizing && index == moving) {
            val arrows = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(5)) }
            val left = TextView(this).apply {
                text = "◀"; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(dp(12), dp(3), dp(12), dp(3)); isFocusable = false
                setOnClickListener { move(-1) }
            }
            val right = TextView(this).apply {
                text = "▶"; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(dp(12), dp(3), dp(12), dp(3)); isFocusable = false
                setOnClickListener { move(1) }
            }
            arrows.addView(left); arrows.addView(right)
            card.addView(arrows, LinearLayout.LayoutParams(-1, dp(28)))
        }
        val artworkSize = when (prefs.getInt("home_app_size", 1).coerceIn(0, 2)) {
            0 -> dp(48); 2 -> dp(78); else -> dp(66)
        }
        card.addView(image, LinearLayout.LayoutParams(
            if (banner != null) -1 else artworkSize,
            if (banner != null) -1 else artworkSize
        ))
        card.contentDescription = app.loadLabel(packageManager).toString()
        card.tag = FocusAnchor("focus:app:${app.activityInfo.packageName}", index)
        card.setOnClickListener { if (!organizing) launch(app) }
        card.setOnLongClickListener { startOrganizing(index); true }
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
        val card = createCard().apply {
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = rounded(Color.rgb(20, 29, 42), 16, Color.rgb(60, 76, 96))
        }

        // Ícone real em 2x2: cada quadrado é uma View independente.
        // Evita falha de desenho do Canvas em alguns firmwares de Android TV.
        val glyph = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        repeat(2) {
            val iconRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            repeat(2) { column ->
                val square = View(this).apply {
                    background = rounded(Color.rgb(211, 222, 235), 3, Color.TRANSPARENT)
                    isFocusable = false
                }
                iconRow.addView(square, LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    if (column > 0) leftMargin = dp(5)
                })
            }
            glyph.addView(iconRow, LinearLayout.LayoutParams(-2, dp(10)).apply {
                if (glyph.childCount > 0) topMargin = dp(5)
            })
        }
        val glyphHolder = FrameLayout(this).apply {
            background = rounded(Color.rgb(29, 39, 53), 12, Color.rgb(68, 82, 101))
            addView(glyph, FrameLayout.LayoutParams(-1, -1))
        }
        card.addView(glyphHolder, LinearLayout.LayoutParams(dp(52), dp(52)))
        card.addView(TextView(this).apply {
            text = "Todos os apps"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(226, 234, 244))
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        card.contentDescription = "Abrir gaveta com todos os aplicativos"
        card.setOnClickListener { showAppDrawer() }
        addGridCard(card, width, height, row, col)
    }

    private fun showAppDrawer() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(22), dp(30), dp(18))
            setBackgroundColor(Color.rgb(7, 12, 21))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleBlock.addView(TextView(this).apply {
            text = "Aplicativos"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        titleBlock.addView(TextView(this).apply {
            text = "Escolha um app ou segure OK para fixar na tela inicial"
            textSize = 12f
            setTextColor(Color.rgb(153, 174, 198))
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(titleBlock, LinearLayout.LayoutParams(0, dp(58), 1f))
        top.addView(TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(Color.rgb(216, 232, 248))
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = rounded(Color.rgb(21, 31, 45), 15, Color.rgb(52, 69, 91))
            contentDescription = "Configurações da Launcher Lite"
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(
                    if (focused) Color.rgb(15, 93, 126) else Color.rgb(21, 31, 45),
                    15, if (focused) Color.rgb(79, 218, 255) else Color.rgb(52, 69, 91)
                )
                (view as TextView).setTextColor(if (focused) Color.WHITE else Color.rgb(183, 199, 218))
                view.scaleX = if (focused) 1.08f else 1f
                view.scaleY = if (focused) 1.08f else 1f
                view.elevation = if (focused) dp(6).toFloat() else 0f
            }
            setOnClickListener { dialog.dismiss(); showDrawerSettingsDialog() }
        }, LinearLayout.LayoutParams(dp(50), dp(46)).apply { rightMargin = dp(8) })
        top.addView(TextView(this).apply {
            text = "✕"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = rounded(Color.rgb(21, 31, 45), 15, Color.rgb(52, 69, 91))
            contentDescription = "Fechar gaveta"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(top)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(12), 0, dp(8))
        }
        val drawerColumns = prefs.getInt("home_app_columns", 5).coerceIn(3, 7)
        val drawerSize = prefs.getInt("home_app_size", 1).coerceIn(0, 2)
        val grid = GridLayout(this).apply {
            columnCount = drawerColumns
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            clipChildren = false
            clipToPadding = false
        }
        apps.forEachIndexed { index, app ->
            val packageName = app.activityInfo.packageName
            val pinned = prefs.getString("home_apps_order", null)
                ?.split("|")?.filter { it.isNotBlank() }?.contains(packageName) == true
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(9), dp(7), dp(7))
                background = rounded(Color.rgb(16, 25, 39), 17,
                    if (pinned) Color.rgb(48, 123, 157) else Color.rgb(39, 54, 75))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(
                        if (focused) Color.rgb(27, 48, 70) else Color.rgb(16, 25, 39),
                        17,
                        if (focused) accent else if (pinned) Color.rgb(48, 123, 157) else Color.rgb(39, 54, 75)
                    )
                    v.scaleX = if (focused) 1.035f else 1f
                    v.scaleY = if (focused) 1.035f else 1f
                }
                setOnClickListener { dialog.dismiss(); launch(app) }
                setOnLongClickListener {
                    val current = homeApps().toMutableList()
                    val alreadyPinned = current.any { it.activityInfo.packageName == packageName }
                    val action = if (alreadyPinned) "Remover da tela inicial" else "Adicionar à tela inicial"
                    AlertDialog.Builder(this@MainActivity, R.style.AppDialogTheme)
                        .setTitle(app.loadLabel(packageManager).toString())
                        .setItems(arrayOf(action, "Cancelar")) { _, which ->
                            if (which == 0) {
                                if (alreadyPinned) {
                                    current.removeAll { it.activityInfo.packageName == packageName }
                                    saveHomeApps(current)
                                    Toast.makeText(this@MainActivity, "App removido da tela inicial", Toast.LENGTH_SHORT).show()
                                } else if (current.size >= 9) {
                                    Toast.makeText(this@MainActivity, "A tela inicial comporta até 9 aplicativos fixados", Toast.LENGTH_LONG).show()
                                } else {
                                    current.add(app)
                                    saveHomeApps(current)
                                    Toast.makeText(this@MainActivity, "App adicionado à tela inicial", Toast.LENGTH_SHORT).show()
                                }
                                renderApps()
                                dialog.dismiss()
                            }
                        }.show()
                    true
                }
                contentDescription = app.loadLabel(packageManager).toString() +
                    if (pinned) ", fixado na tela inicial" else ", não fixado"
            }
            val icon = ImageView(this).apply {
                setImageDrawable(app.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val drawerIconSize = when (drawerSize) { 0 -> dp(48); 2 -> dp(70); else -> dp(58) }
            item.addView(icon, LinearLayout.LayoutParams(drawerIconSize, drawerIconSize))
            item.addView(TextView(this).apply {
                text = app.loadLabel(packageManager).toString()
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(34)))
            if (pinned) {
                item.addView(TextView(this).apply {
                    text = "NA HOME"
                    textSize = 8f
                    letterSpacing = 0.08f
                    setTextColor(Color.rgb(126, 213, 245))
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, dp(12)))
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / drawerColumns),
                GridLayout.spec(index % drawerColumns, 1f)
            ).apply {
                width = 0
                height = when (drawerSize) { 0 -> dp(98); 2 -> dp(132); else -> dp(116) }
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(item, params)
        }
        scroll.addView(grid, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.setLayout(-1, -1)
        dialog.setOnDismissListener { resetIdleTimer() }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        grid.post { grid.getChildAt(0)?.requestFocus() }
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
            startActivity(appCatalog.createLaunchIntent(app))
        } catch (_: Exception) {
            Toast.makeText(this, "Não foi possível abrir este aplicativo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findApps(): List<ResolveInfo> = appCatalog.loadLaunchableApps()

    private fun restoreOrder() {
        val saved = prefs.getString("order", null)?.split("|") ?: return
        val byPackage = apps.associateBy { it.activityInfo.packageName }
        val ordered = saved.mapNotNull { byPackage[it] }.toMutableList()
        apps.filter { it.activityInfo.packageName !in saved }.forEach { ordered.add(it) }
        apps.clear()
        apps.addAll(ordered)
    }

    private fun startOrganizing(index: Int) {
        if (index !in apps.indices) return
        organizing = true
        moving = index
        renderApps(index)
        val card = appGrid.getChildAt(index) ?: return
        sortPulse?.cancel()
        sortPulse = ObjectAnimator.ofFloat(card, "alpha", 1f, 0.62f).apply {
            duration = 420
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        Toast.makeText(this, "Segure OK para pegar. Use as setas e toque em Concluir.", Toast.LENGTH_SHORT).show()
    }

    private fun stopOrganizingUi() {
        sortPulse?.cancel()
        sortPulse = null
        if (moving in apps.indices) appGrid.getChildAt(moving)?.alpha = 1f
    }

    private fun saveOrder() {
        stopOrganizingUi()
        organizing = false
        moving = -1
        prefs.edit().putString("order", apps.joinToString("|") { it.activityInfo.packageName }).apply()
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
        val card = appGrid.getChildAt(target)
        sortPulse?.cancel()
        sortPulse = card?.let { ObjectAnimator.ofFloat(it, "alpha", 1f, 0.62f).apply {
            duration = 420
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        } }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (saverView != null) dismissScreenSaver() else resetIdleTimer()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (saverView != null) { dismissScreenSaver(); return true }
            if (!organizing && event.repeatCount >= 1 && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                val focusedTag = currentFocus?.tag
                val appIndex = (focusedTag as? FocusAnchor)?.appIndex ?: (focusedTag as? Int)
                if (appIndex != null && appIndex in apps.indices) { startOrganizing(appIndex); return true }
            }
            if (organizing && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                val delta = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; KeyEvent.KEYCODE_DPAD_UP -> -5; else -> 5 }
                if (event.repeatCount == 0) move(delta)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (screenRoot.findViewWithTag<View>("notification_panel_root") != null) { closeNotificationsPanel(); return true }
                if (screenRoot.findViewWithTag<View>("weather_screen") != null) { screenRoot.removeView(screenRoot.findViewWithTag("weather_screen")); weatherPage = null; return true }
                if (screenRoot.findViewWithTag<View>("quick_panel") != null) { screenRoot.removeView(screenRoot.findViewWithTag("quick_panel")); return true }
                if (organizing) { stopOrganizingUi(); organizing = false; moving = -1; restoreOrder(); renderApps(); return true }
            }
            if (organizing && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) { saveOrder(); return true }
            if (organizing && event.keyCode == KeyEvent.KEYCODE_MENU) { saveOrder(); return true }
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
        val saverWeatherText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.rgb(170, 181, 194))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            visibility = if (prefs.getInt("screensaver_show_weather", 0) == 1) View.VISIBLE else View.GONE
        }
        content.addView(saverWeatherText)
        panel.addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        panel.tag = saverWeatherText
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
        val weatherView = saverView?.tag as? TextView
        weatherView?.text = if (::weatherText.isInitialized) "${weatherIcon.text}  ${weatherText.text}" else "Clima indisponível"
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
        if (screenRoot.findViewWithTag<View>("notification_panel_root") != null) { closeNotificationsPanel(); return }
        val quick = screenRoot.findViewWithTag<View>("quick_panel")
        if (quick != null) { screenRoot.removeView(quick); return }
        if (weatherPage != null) { screenRoot.removeView(weatherPage); weatherPage = null; return }
        if (organizing) { stopOrganizingUi(); organizing = false; moving = -1; restoreOrder(); renderApps(); return }

        // The launcher is the HOME surface: BACK at its root must not finish
        // MainActivity, otherwise Android exposes the fallback Google TV launcher.
        Log.d("LauncherLite.Navigation", "BACK at launcher root; keeping MainActivity alive")
        screenRoot.post { focusRestorationCoordinator.restore(screenRoot) }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}