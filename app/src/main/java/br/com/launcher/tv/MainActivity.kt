package br.com.launcher.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private val bg = Color.rgb(5, 7, 11)
    private val blue = Color.rgb(77, 155, 255)
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
            setPadding(dp(42), dp(28), dp(42), dp(24))
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        date = TextView(this).apply { textSize = 14f; setTextColor(Color.LTGRAY) }
        left.addView(clock)
        left.addView(date)
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(this).apply {
            text = "●  Wi-Fi     ⚙"
            textSize = 16f
            setTextColor(Color.LTGRAY)
        })
        root.addView(top)

        root.addView(TextView(this).apply {
            text = "Aplicativos"
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, dp(38), 0, dp(16))
        })

        val grid = GridLayout(this).apply { columnCount = 5; alignmentMode = GridLayout.ALIGN_BOUNDS }
        val apps = loadApps()
        if (apps.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "Nenhum aplicativo encontrado."
                setTextColor(Color.LTGRAY)
                textSize = 16f
            })
        } else {
            apps.forEachIndexed { index, app ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    background = rounded(Color.rgb(18, 22, 30), 14)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnFocusChangeListener { view, focused ->
                        view.background = rounded(
                            if (focused) Color.rgb(25, 42, 64) else Color.rgb(18, 22, 30),
                            14, if (focused) blue else Color.TRANSPARENT
                        )
                    }
                    setOnClickListener {
                        packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)?.let { startActivity(it) }
                    }
                }
                val icon = ImageView(this).apply {
                    setImageDrawable(app.loadIcon(packageManager))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                tile.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
                tile.addView(TextView(this).apply {
                    text = app.loadLabel(packageManager).toString()
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(0, dp(9), 0, 0)
                }, LinearLayout.LayoutParams(dp(112), -2))
                val params = GridLayout.LayoutParams().apply {
                    width = dp(130); height = dp(126)
                    setMargins(dp(4), dp(4), dp(12), dp(12))
                }
                grid.addView(tile, params)
                if (index == 0) tile.requestFocus()
            }
            root.addView(ScrollView(this).apply {
                isFillViewport = true
                addView(grid)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
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
