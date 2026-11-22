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
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(10, 13, 19)
    private val panel = Color.rgb(23, 29, 39)
    private val blue = Color.rgb(91, 170, 255)
    private lateinit var clock: TextView
    private lateinit var date: TextView

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
            setPadding(dp(54), dp(34), dp(54), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(15, 22, 34), bg, Color.rgb(8, 11, 16))
            )
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            textSize = 42f
            setTextColor(Color.WHITE)
            letterSpacing = 0.015f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        date = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(166, 178, 196))
            setPadding(0, dp(2), 0, 0)
        }
        left.addView(clock)
        left.addView(date)
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val defaultButton = TextView(this).apply {
            text = "Definir como padrão  ⚙"
            textSize = 14f
            setTextColor(Color.rgb(205, 216, 232))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(Color.rgb(28, 38, 52), 22)
            isFocusable = true
            setOnFocusChangeListener { v, focused ->
                v.background = rounded(if (focused) Color.rgb(40, 65, 94) else Color.rgb(28, 38, 52), 22,
                    if (focused) blue else Color.TRANSPARENT)
            }
            setOnClickListener { requestDefaultLauncher() }
        }
        top.addView(defaultButton)
        root.addView(top)

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(42), 0, dp(18))
        }
        section.addView(TextView(this).apply {
            text = "Seus aplicativos"
            textSize = 21f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        })
        section.addView(TextView(this).apply {
            text = "Acesse rapidamente o que você mais usa"
            textSize = 13f
            setTextColor(Color.rgb(132, 148, 170))
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(section)

        val grid = GridLayout(this).apply {
            columnCount = 5
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val apps = loadApps()
        if (apps.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Nenhum aplicativo encontrado."
                setTextColor(Color.LTGRAY)
                textSize = 16f
            })
        } else {
            apps.forEach { app ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(15), dp(8), dp(13))
                    background = rounded(panel, 16)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnFocusChangeListener { view, focused ->
                        view.background = rounded(
                            if (focused) Color.rgb(31, 48, 68) else panel,
                            16, if (focused) blue else Color.TRANSPARENT
                        )
                        view.scaleX = if (focused) 1.035f else 1f
                        view.scaleY = if (focused) 1.035f else 1f
                        view.elevation = if (focused) dp(5).toFloat() else 0f
                    }
                    setOnClickListener {
                        packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)?.let { startActivity(it) }
                    }
                }
                val icon = ImageView(this).apply {
                    setImageDrawable(app.loadIcon(packageManager))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                tile.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))
                tile.addView(TextView(this).apply {
                    text = app.loadLabel(packageManager).toString()
                    setTextColor(Color.rgb(230, 235, 243))
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(0, dp(10), 0, 0)
                }, LinearLayout.LayoutParams(dp(124), -2))
                val params = GridLayout.LayoutParams().apply {
                    width = dp(142)
                    height = dp(132)
                    setMargins(dp(5), dp(5), dp(10), dp(10))
                }
                grid.addView(tile, params)
            }
            root.addView(ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(grid)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            grid.getChildAt(0)?.requestFocus()
        }
        setContentView(root)
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                }
            }
        } else {
            startActivity(Intent("android.settings.HOME_SETTINGS"))
        }
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
        date.text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(now)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
