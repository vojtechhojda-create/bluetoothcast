package com.example.pcaudio

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val accent = Color.parseColor("#1976D2")
    private val green = Color.parseColor("#2E7D32")
    private val grey = Color.parseColor("#ECEFF1")

    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var toggleBtn: Button
    private lateinit var pinText: TextView
    private lateinit var notifWarn: TextView
    private lateinit var batteryText: TextView

    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun label(s: String, size: Float, color: String, bold: Boolean = false, top: Int = 0) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = lp(top)
        }

    private fun section(s: String) = label(s, 18f, "#0D47A1", bold = true, top = 28)

    private fun body(s: String, top: Int = 6) = label(s, 14f, "#455A64", top = top)

    private fun button(s: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        setTextColor(if (filled) Color.WHITE else accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            if (filled) setColor(accent) else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), accent)
            }
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        stateListAnimator = null
        layoutParams = lp(10)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        root.addView(label("BluetoothCast", 28f, "#0D47A1", bold = true))
        root.addView(label("PC → telefon → Bluetooth zařízení", 14f, "#607D8B"))

        statusTitle = label("", 22f, "#FFFFFF", bold = true)
        statusDetail = label("", 14f, "#FFFFFF", top = 4)
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = lp(20)
            addView(statusTitle)
            addView(statusDetail)
        }
        root.addView(statusCard)

        toggleBtn = button("Zapnout", true) { toggle() }
        root.addView(toggleBtn)

        notifWarn = label(
            "Notifikace jsou vypnuté, takže neuvidíš žádost o povolení. Klepni sem a zapni je.",
            14f, "#E65100", top = 14
        ).apply {
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
        root.addView(notifWarn)

        root.addView(section("Rychlé nastavení"))
        root.addView(body("Dlaždice vedle Wi-Fi a Bluetooth, kterou službu zapneš jedním klepnutím a uvidíš na ní, k jakému PC jsi připojený."))
        root.addView(button("Přidat do rychlých nastavení", false) { addTile() })

        root.addView(section("Automatické povolení"))
        root.addView(body("Normálně potvrzuješ každé připojení v notifikaci. S touto volbou se bez ptaní povolí jen PC, které pošle správný PIN (zadáš ho v settings.json na PC)."))
        root.addView(Switch(this).apply {
            text = "Povolit automaticky s PINem"
            isChecked = Prefs.autoApprove(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setAutoApprove(this@MainActivity, v) }
            layoutParams = lp(10)
        })
        pinText = label("", 26f, "#0D47A1", top = 8).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        }
        root.addView(pinText)
        root.addView(button("Zkopírovat PIN", false) { copyPin() })
        root.addView(button("Vygenerovat nový PIN", false) {
            Prefs.newPin(this)
            refresh()
        })

        root.addView(section("Stabilita"))
        batteryText = body("")
        root.addView(batteryText)
        root.addView(button("Nastavení optimalizace baterie", false) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "Nastavení se nepodařilo otevřít.", Toast.LENGTH_LONG).show()
            }
        })

        root.addView(section("Jak na to"))
        root.addView(body("1. Připoj sluchátka nebo reproduktor k telefonu.\n" +
            "2. Klepni na Zapnout (nebo na dlaždici).\n" +
            "3. Na PC spusť start.bat.\n" +
            "4. V notifikaci klepni na Povolit."))

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            ""
        }
        root.addView(label("BluetoothCast $version", 12f, "#90A4AE", top = 28))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            addView(root)
        })
    }

    override fun onStart() {
        super.onStart()
        CastState.addListener(listener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        CastState.removeListener(listener)
        super.onStop()
    }

    private fun toggle() {
        val svc = Intent(this, AudioService::class.java)
        if (CastState.running) stopService(svc) else startForegroundService(svc)
    }

    private fun refresh() {
        val running = CastState.running
        val to = CastState.connectedTo
        val bg: Int
        val fg: Int
        when {
            !running -> {
                bg = grey; fg = Color.parseColor("#37474F")
                statusTitle.text = "Vypnuto"
                statusDetail.text = "Klepni na Zapnout, aby telefon mohl přijímat zvuk z PC."
                toggleBtn.text = "Zapnout"
            }
            to != null -> {
                bg = green; fg = Color.WHITE
                statusTitle.text = "Připojeno k $to"
                statusDetail.text = "Zvuk z PC hraje v tomto telefonu."
                toggleBtn.text = "Vypnout"
            }
            else -> {
                bg = accent; fg = Color.WHITE
                statusTitle.text = "Čeká na PC"
                statusDetail.text = "Adresa telefonu: ${lanIp()}  (port ${AudioService.PORT})"
                toggleBtn.text = "Vypnout"
            }
        }
        statusCard.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(bg)
        }
        statusTitle.setTextColor(fg)
        statusDetail.setTextColor(fg)

        val pin = Prefs.pin(this)
        pinText.text = "PIN  ${pin.substring(0, 3)} ${pin.substring(3)}"

        val nm = getSystemService(NotificationManager::class.java)
        notifWarn.visibility = if (nm.areNotificationsEnabled()) android.view.View.GONE else android.view.View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        batteryText.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "Optimalizace baterie je pro aplikaci vypnutá, služba by měla běžet stabilně."
        } else {
            "Systém může službu po čase ukončit. Doporučujeme pro BluetoothCast vypnout optimalizaci baterie (Bez omezení)."
        }
    }

    private fun copyPin() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("PIN", Prefs.pin(this)))
        Toast.makeText(this, "PIN zkopírován", Toast.LENGTH_SHORT).show()
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val sbm = getSystemService(StatusBarManager::class.java)
                sbm.requestAddTileService(
                    ComponentName(this, CastTileService::class.java),
                    "BluetoothCast",
                    Icon.createWithResource(this, R.drawable.ic_tile),
                    mainExecutor
                ) { }
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(
            this,
            "Rozbal rychlá nastavení, klepni na tužku a přetáhni dlaždici BluetoothCast nahoru.",
            Toast.LENGTH_LONG
        ).show()
    }
}
