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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
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
        val now = SystemClock.elapsedRealtime()
        lifecycleCoordinator.onCreated(now)
        logSessionEvent(sessionStateMachine.accept(LauncherSessionEvent.Created(now)))
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
        // Reopen the guided setup once after an app update that changes setup steps.
        if (prefs.getInt("setup_wizard_version", 0) < 10) {
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
        root.addView(createChannelRow(), LinearLayout.LayoutParams(-1, dp(142)))

        val content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val gridFrame = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        appGrid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(14), dp(4), dp(14))
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


    private fun savedChannels(): MutableList<String> = favoriteChannels.load()

    private fun saveChannels(channels: List<String>) {
        favoriteChannels.save(channels)
        if (::channelRowContainer.isInitialized) renderChannels()
    }

    private fun createChannelRow(): View {
        channelRowContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(3), 0, dp(8))
        }
        return channelRowContainer.also { renderChannels() }
    }

    private fun renderChannels(force: Boolean = false) {
        if (!::channelRowContainer.isInitialized) return
        val channels = savedChannels()
        if (!force && channels == lastRenderedChannels) return
        focusRestorationCoordinator.capture(window.decorView.findFocus())
        lastRenderedChannels = channels.toList()
        channelRowContainer.removeAllViews()

        val scroller = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }

        ChannelLogos.ensure(this, channels) {
            if (::channelRowContainer.isInitialized) renderChannels(force = true)
        }

        channels.forEach { name ->
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
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(name)
                        .setItems(arrayOf("Editar nome", "Remover dos favoritos", "Cancelar")) { dialog, which ->
                            when (which) {
                                0 -> showEditChannelDialog(name)
                                1 -> AlertDialog.Builder(this@MainActivity)
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
            strip.addView(tile, LinearLayout.LayoutParams(dp(132), dp(82)).apply {
                rightMargin = dp(9)
            })
        }

        val addTile = LinearLayout(this).apply {
            tag = FocusAnchor("focus:channel:add")
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(20, 32, 55), 17, Color.rgb(62, 86, 132))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
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
                textSize = 48f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setTextColor(Color.rgb(220, 235, 250))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -1))
        }
        strip.addView(addTile, LinearLayout.LayoutParams(dp(132), dp(82)))
        scroller.addView(strip, ViewGroup.LayoutParams(-2, -1))
        scroller.post {
            strip.minimumWidth = (scroller.width - scroller.paddingLeft - scroller.paddingRight).coerceAtLeast(0)
        }
        channelRowContainer.addView(scroller, LinearLayout.LayoutParams(-1, -1))
        if (::screenRoot.isInitialized && hasWindowFocus()) {
            channelRowContainer.post { focusRestorationCoordinator.restore(screenRoot) }
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
        val dialog = AlertDialog.Builder(this)
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
        val dialog = AlertDialog.Builder(this)
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

    private fun showLauncherSettingsDialog() {
        AlertDialog.Builder(this).setTitle("Configurações")
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

    private fun showSetupWizard() {
        if (isFinishing || setupDialog?.isShowing == true) return

        val step = prefs.getInt("setup_wizard_step", 0).coerceIn(0, 2)
        val title: String
        val message: String
        val actionLabel: String
        val canSkip = step == 2

        when (step) {
            0 -> {
                title = "Bem-vindo ao Launcher Lite"
                message = "Vamos preparar sua TV em poucos passos. Você vai escolher o Launcher Lite como tela inicial. O Google TV original continuará instalado; o acesso às notificações é opcional."
                actionLabel = "Começar"
            }
            1 -> {
                title = "1 de 2 — Tela inicial padrão"
                message = if (isHomeRoleHeld()) {
                    "Perfeito! O Launcher Lite já está definido como tela inicial padrão."
                } else {
                    "Toque em Continuar para abrir a confirmação do Android. Escolha Launcher Lite e confirme como app de início."
                }
                actionLabel = if (isHomeRoleHeld()) "Próximo" else "Escolher Launcher Lite"
            }
            2 -> {
                title = "2 de 2 — Notificações (opcional)"
                message = if (isNotificationAccessEnabled()) {
                    "Acesso às notificações autorizado."
                } else {
                    "Se quiser ver notificações na tela inicial, autorize o acesso. Você pode pular esta etapa e ativar depois."
                }
                actionLabel = if (isNotificationAccessEnabled()) "Finalizar" else "Autorizar notificações"
            }
            else -> {
                finishSetupWizard()
                return
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(8))
            setBackgroundColor(Color.rgb(12, 19, 32))
        }
        val progress = TextView(this).apply {
            text = when (step) {
                0 -> "CONFIGURAÇÃO INICIAL"
                else -> "ETAPA $step DE 2"
            }
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(87, 190, 230))
        }
        val body = TextView(this).apply {
            text = message
            textSize = 18f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(0, dp(14), 0, dp(10))
        }
        content.addView(progress)
        content.addView(body)
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(actionLabel, null)
            .setNegativeButton(if (step == 0) "Agora não" else "Voltar", null)
            .apply {
                if (canSkip) setNeutralButton("Pular", null)
                
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (step) {
                    0 -> {
                        prefs.edit().putInt("setup_wizard_step", 1).putBoolean("setup_wizard_pending", true).apply()
                        dialog.dismiss()
                        showSetupWizard()
                    }
                    1 -> {
                        if (isHomeRoleHeld()) {
                            prefs.edit().putInt("setup_wizard_step", 2).apply()
                            dialog.dismiss()
                            showSetupWizard()
                        } else {
                            dialog.dismiss()
                            requestDefaultHome()
                        }
                    }
                    2 -> {
                        if (isNotificationAccessEnabled()) {
                            dialog.dismiss()
                            finishSetupWizard()
                        } else {
                            prefs.edit().putBoolean("setup_wizard_pending", true).apply()
                            dialog.dismiss()
                            openIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if (step == 0) {
                    dialog.dismiss()
                    prefs.edit().putBoolean("setup_wizard_pending", false).apply()
                } else {
                    prefs.edit().putInt("setup_wizard_step", (step - 1).coerceAtLeast(0)).apply()
                    dialog.dismiss()
                    showSetupWizard()
                }
            }
            if (canSkip) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    dialog.dismiss()
                    finishSetupWizard()
                }
            }
        }
        setupDialog = dialog
        dialog.setOnDismissListener { if (setupDialog === dialog) setupDialog = null }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.72f).toInt(), -2)
    }

    private fun finishSetupWizard() {
        prefs.edit()
            .putBoolean("setup_wizard_finished", true)
            .putBoolean("setup_wizard_seen", true)
            .putBoolean("setup_wizard_pending", false)
            .putInt("setup_wizard_version", 10)
            .putInt("setup_wizard_step", 4)
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
        weatherText = TextView(this).apply {
            text = "Clima"
            textSize = 13f
            setTextColor(Color.rgb(220, 232, 246))
            setPadding(dp(7), 0, 0, 0)
            maxLines = 1
        }
        weatherPill.addView(weatherIcon)
        weatherPill.addView(weatherText)
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
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga: tempo")
            }
            startActivityForResult(intent, 701)
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_ASSIST)) }
            catch (_: Exception) { Toast.makeText(this, "O serviço de voz não está disponível", Toast.LENGTH_LONG).show() }
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
        top.addView(TextView(this).apply { text = "Previsão do tempo"; textSize = 27f; setTextColor(Color.WHITE) })
        page.addView(top)
        page.addView(TextView(this).apply { text = "Batistini • São Bernardo do Campo, SP"; textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0, dp(5), 0, dp(12)) })
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
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=-23.7717&longitude=-46.59097&hourly=temperature_2m,precipitation_probability,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=7&timezone=America%2FSao_Paulo")
                val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 12000; readTimeout = 12000 }
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
                val h = j.getJSONObject("hourly"); val d = j.getJSONObject("daily")
                runOnUiThread {
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

    private fun refreshWeather() {
        weatherText.text = "Atualizando previsão…"
        Thread {
            var label = "Clima indisponível — toque para tentar novamente"
            var symbol = "☁"
            try {
                val address = URL("https://api.open-meteo.com/v1/forecast?latitude=-23.7717&longitude=-46.59097&current=temperature_2m,relative_humidity_2m,weather_code,is_day&timezone=America%2FSao_Paulo")
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
        card.addView(image, LinearLayout.LayoutParams(
            if (banner != null) -1 else dp(66),
            if (banner != null) -1 else dp(66)
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
        val card = createCard()
        card.addView(TextView(this).apply {
            text = "▦"
            textSize = 62f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            contentDescription = "Grade de aplicativos"
        }, LinearLayout.LayoutParams(dp(82), dp(82)))
        card.contentDescription = "Abrir gaveta com todos os aplicativos"
        card.setOnClickListener { showAppDrawer() }
        addGridCard(card, width, height, row, col)
    }

    private fun showAppDrawer() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(42), dp(28), dp(42), dp(24))
            setBackgroundColor(Color.rgb(8, 13, 24))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "Aplicativos"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        top.addView(TextView(this).apply {
            text = "✕"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            contentDescription = "Fechar gaveta"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(58), dp(54)))
        root.addView(top)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val grid = GridLayout(this).apply {
            columnCount = 5
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(dp(4), dp(16), dp(4), dp(16))
        }
        val screenWidth = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val cellWidth = dp(((screenWidth - 120) / 5).coerceIn(118, 220))
        apps.forEachIndexed { index, app ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(10), dp(9), dp(8))
                background = rounded(Color.rgb(19, 29, 48), 17, Color.rgb(47, 65, 94))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(
                        if (focused) Color.rgb(31, 55, 84) else Color.rgb(19, 29, 48),
                        17,
                        if (focused) accent else Color.rgb(47, 65, 94)
                    )
                    v.scaleX = if (focused) 1.04f else 1f
                    v.scaleY = if (focused) 1.04f else 1f
                }
                setOnClickListener { dialog.dismiss(); launch(app) }
                contentDescription = app.loadLabel(packageManager).toString()
            }
            val icon = ImageView(this).apply {
                setImageDrawable(app.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(62), dp(62)))
            item.addView(TextView(this).apply {
                text = app.loadLabel(packageManager).toString()
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(36)))
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 5), GridLayout.spec(index % 5)
            )
            params.width = cellWidth
            params.height = dp(112)
            params.setMargins(dp(8), dp(8), dp(8), dp(8))
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
        if (screenRoot.findViewWithTag<View>("notification_panel_root") != null) { closeNotificationsPanel(); return }
        val quick = screenRoot.findViewWithTag<View>("quick_panel")
        if (quick != null) { screenRoot.removeView(quick); return }
        if (weatherPage != null) { screenRoot.removeView(weatherPage); weatherPage = null; return }
        if (organizing) { stopOrganizingUi(); organizing = false; moving = -1; restoreOrder(); renderApps(); return }
        super.onBackPressed()
    }

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
