package br.com.launcher.tv

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.BLACK
    private val panel = Color.rgb(12, 12, 14)
    private val blue = Color.rgb(74, 190, 255)
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var grid: GridLayout
    private var reorderMode = false
    private var movingIndex = -1
    private val appItems = mutableListOf<android.content.pm.ResolveInfo>()
    private val prefs by lazy { getSharedPreferences("launcher_order", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        buildScreen()
        updateClock()
        clock.postDelayed(object : Runnable {
            override fun run() {
                updateClock()
                clock.postDelayed(this, 30_000)
            }
        }, 30_000)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(52), dp(28), dp(52), dp(24))
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            textSize = 44f
            setTextColor(Color.WHITE)
            letterSpacing = 0.01f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        date = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(205, 205, 210))
            setPadding(0, dp(1), 0, 0)
        }
        left.addView(clock)
        left.addView(date)
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)

        root.addView(TextView(this).apply {
            text = "Aplicativos"
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(0, dp(34), 0, dp(13))
        })

        grid = GridLayout(this).apply {
            columnCount = 5
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        appItems.clear()
        appItems.addAll(loadApps())
        applySavedOrder()
        if (appItems.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Nenhum aplicativo encontrado."
                setTextColor(Color.LTGRAY)
                textSize = 16f
            })
        } else {
            rebuildGrid()
            val centered = FrameLayout(this).apply {
                addView(grid, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ))
            }
            root.addView(ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(centered, ViewGroup.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        grid.getChildAt(0)?.requestFocus()
    }

    private fun rebuildGrid(focusIndex: Int = 0) {
        grid.removeAllViews()
        appItems.forEachIndexed { index, app ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(9), dp(5), dp(8))
                background = rounded(panel, 16)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(
                        if (focused) Color.rgb(10, 22, 32) else panel,
                        16, if (focused) blue else Color.rgb(38, 38, 42)
                    )
                    view.scaleX = if (focused) 1.035f else 1f
                    view.scaleY = if (focused) 1.035f else 1f
                    view.elevation = if (focused) dp(7).toFloat() else dp(1).toFloat()
                }
                setOnLongClickListener {
                    beginReorder(index)
                    true
                }
                setOnKeyListener { _, keyCode, event ->
                    if (reorderMode && event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { moveItem(-1); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveItem(1); true }
                            KeyEvent.KEYCODE_DPAD_UP -> { moveItem(-5); true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { moveItem(5); true }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { finishReorder(); true }
                            KeyEvent.KEYCODE_BACK -> { cancelReorder(); true }
                            else -> false
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                        event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
                        beginReorder(index)
                        true
                    } else false
                }
                setOnClickListener {
                    if (reorderMode) finishReorder()
                    else packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)?.let { startActivity(it) }
                }
            }
            val icon = ImageView(this).apply {
                setImageDrawable(app.loadIcon(packageManager))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            tile.addView(TextView(this).apply {
                text = app.loadLabel(packageManager).toString()
                setTextColor(Color.rgb(245, 245, 248))
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(7), 0, 0)
            }, LinearLayout.LayoutParams(dp(108), -2))
            val params = GridLayout.LayoutParams().apply {
                width = dp(126)
                height = dp(104)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            grid.addView(tile, params)
        }
        grid.getChildAt(focusIndex.coerceIn(0, appItems.lastIndex))?.requestFocus()
    }

    private fun beginReorder(index: Int) {
        reorderMode = true
        movingIndex = index
        Toast.makeText(this, "Organizar: use as setas e OK para salvar", Toast.LENGTH_LONG).show()
        grid.getChildAt(index)?.background = rounded(Color.rgb(10, 22, 32), 16, blue)
    }

    private fun moveItem(offset: Int) {
        if (!reorderMode || movingIndex !in appItems.indices) return
        val target = (movingIndex + offset).coerceIn(0, appItems.lastIndex)
        if (target == movingIndex) return
        val item = appItems.removeAt(movingIndex)
        appItems.add(target, item)
        movingIndex = target
        rebuildGrid(target)
        grid.getChildAt(target)?.background = rounded(Color.rgb(10, 22, 32), 16, blue)
    }

    private fun finishReorder() {
        if (!reorderMode) return
        val selected = movingIndex.coerceIn(0, appItems.lastIndex)
        reorderMode = false
        movingIndex = -1
        prefs.edit().putString("packages", appItems.joinToString("|") { it.activityInfo.packageName }).apply()
        Toast.makeText(this, "Ordem salva", Toast.LENGTH_SHORT).show()
        rebuildGrid(selected)
    }

    private fun cancelReorder() {
        reorderMode = false
        movingIndex = -1
        applySavedOrder()
        rebuildGrid()
    }

    private fun applySavedOrder() {
        val saved = prefs.getString("packages", null)?.split("|") ?: return
        val byPackage = appItems.associateBy { it.activityInfo.packageName }
        val ordered = saved.mapNotNull { byPackage[it] }.toMutableList()
        appItems.filter { it.activityInfo.packageName !in saved }.forEach { ordered.add(it) }
        appItems.clear()
        appItems.addAll(ordered)
    }

    private fun requestDefaultLauncher() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val manager = getSystemService(RoleManager::class.java)
                if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                    !manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_HOME), 42)
                    return
                }
            }
        } catch (_: Exception) { }
        val settingsIntents = listOf(
            Intent("android.settings.HOME_SETTINGS"),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
        )
        for (intent in settingsIntents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
        Toast.makeText(this, "Abra Configurações > Apps > Apps padrão > App de início", Toast.LENGTH_LONG).show()
    }

    private fun loadApps(): List<android.content.pm.ResolveInfo> {
        val found = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        intents.forEach { intent ->
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).forEach { info ->
                val pkg = info.activityInfo?.packageName ?: return@forEach
                if (pkg != packageName) found[pkg] = info
            }
        }
        return found.values.sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.getDefault()) }
    }

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR")).format(now)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}