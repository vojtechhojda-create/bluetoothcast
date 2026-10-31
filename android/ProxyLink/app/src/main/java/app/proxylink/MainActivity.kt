package app.proxylink

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private val bg = Color.rgb(0x14, 0x16, 0x1c)
    private val card = Color.rgb(0x1d, 0x20, 0x28)
    private val fg = Color.rgb(0xe8, 0xea, 0xf0)
    private val muted = Color.rgb(0x8b, 0x90, 0xa0)

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var orb: OrbView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var toggle: TextView
    private lateinit var hint: TextView
    private lateinit var segment: FrameLayout
    private lateinit var indicator: View
    private lateinit var pills: List<TextView>
    private lateinit var pairLayer: LinearLayout
    private lateinit var pairOrb: OrbView
    private lateinit var pairName: TextView
    private lateinit var pairCode: TextView
    private val toggleBg = GradientDrawable()
    private val indicatorBg = GradientDrawable()

    private var accentColor = 0
    private var shownTitle = ""
    private var pairShown = false
    private val listener: () -> Unit = { render() }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        buildUi()
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // po zabiti aplikace systemem se sluzba sama znovu zapne
        if (prefs.getBoolean("enabled", false) && ProxyState.phase == Phase.OFF) startSvc(AudioService.ACTION_START)
        intro()
    }

    override fun onStart() {
        super.onStart()
        ProxyState.listen(listener)
        render()
    }

    override fun onStop() {
        ProxyState.unlisten(listener)
        super.onStop()
    }

    // ---------- stavba okna ----------

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        // hlavni strana
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(24), dpi(24), dpi(24), dpi(24))
        }
        orb = OrbView(this)
        column.addView(orb, LinearLayout.LayoutParams(dpi(240), dpi(240)))

        title = text("", 28f, fg, true)
        column.addView(title, wrap(top = 8))
        sub = text("", 14f, muted)
        column.addView(sub, wrap(top = 6))

        column.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        toggleBg.cornerRadius = dp(28f)
        toggle = text("Zapnout", 17f, Color.WHITE, true).apply {
            background = toggleBg
            setOnClickListener {
                startSvc(if (ProxyState.phase == Phase.OFF) AudioService.ACTION_START else AudioService.ACTION_STOP)
            }
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(OvershootInterpolator(2.5f)).start()
                }
                false
            }
        }
        column.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(56)))

        column.addView(text("Latence", 12f, muted).apply { gravity = Gravity.START }, wrap(top = 22))
        buildSegment()
        column.addView(segment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(48)).apply { topMargin = dpi(8) })
        hint = text("", 13f, muted)
        column.addView(hint, wrap(top = 10))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // cista stranka parovani
        pairLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dpi(24), dpi(24), dpi(24), dpi(24))
            visibility = View.GONE
            isClickable = true
        }
        pairOrb = OrbView(this).apply { mode = 2 }
        pairLayer.addView(pairOrb, LinearLayout.LayoutParams(dpi(180), dpi(180)))
        pairLayer.addView(text("Spárování", 14f, muted), wrap(top = 12))
        pairName = text("", 22f, Color.WHITE, true)
        pairLayer.addView(pairName, wrap(top = 4))
        pairCode = text("", 52f, fg, true).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
        }
        pairLayer.addView(pairCode, wrap(top = 28))
        pairLayer.addView(text("Opiš tento kód v aplikaci ProxyLink na PC", 14f, muted), wrap(top = 14))
        pairLayer.addView(
            text("Zamítnout", 15f, fg, true).apply {
                background = GradientDrawable().apply { cornerRadius = dp(24f); setColor(card) }
                setPadding(dpi(32), dpi(14), dpi(32), dpi(14))
                setOnClickListener { startSvc(AudioService.ACTION_CANCEL_PAIR) }
            },
            wrap(top = 36).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
        )
        root.addView(pairLayer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun wrap(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dpi(top); gravity = Gravity.CENTER_HORIZONTAL }

    private fun buildSegment() {
        segment = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(24f); setColor(card) }
            setPadding(dpi(4), dpi(4), dpi(4), dpi(4))
        }
        indicatorBg.cornerRadius = dp(20f)
        indicator = View(this).apply { background = indicatorBg }
        segment.addView(indicator, FrameLayout.LayoutParams(0, -1))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pills = Latency.values().mapIndexed { i, l ->
            text(l.label, 14f, muted, true).apply {
                setOnClickListener { selectLatency(i, true) }
            }.also { row.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
        }
        segment.addView(row, FrameLayout.LayoutParams(-1, -1))
        segment.post {
            val w = (segment.width - segment.paddingLeft - segment.paddingRight) / 3
            indicator.layoutParams = (indicator.layoutParams as FrameLayout.LayoutParams).apply { width = w }
            selectLatency(Prefs.latency(this).ordinal, false)
        }
    }

    private fun selectLatency(i: Int, user: Boolean) {
        val w = indicator.layoutParams.width
        val x = (i * w).toFloat()
        if (user) {
            indicator.animate().translationX(x).setDuration(300).setInterpolator(OvershootInterpolator(0.9f)).start()
        } else {
            indicator.translationX = x
        }
        pills.forEachIndexed { k, p -> p.setTextColor(if (k == i) Color.WHITE else muted) }
        hint.text = Latency.values()[i].hint
        if (user) {
            prefs.edit().putInt("latency", i).apply()
            // novy rezim se projevi po znovupripojeni - PC se pripoji samo behem par vterin
            if (ProxyState.phase == Phase.STREAMING) startSvc(AudioService.ACTION_RESTART_STREAM)
        }
    }

    // ---------- animace ----------

    private fun intro() {
        for (i in 0 until column.childCount) {
            val v = column.getChildAt(i)
            v.alpha = 0f
            v.translationY = dp(28f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(420)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    private fun setAccent(c: Int) {
        if (c == accentColor) return
        val from = if (accentColor == 0) c else accentColor
        accentColor = c
        ValueAnimator.ofArgb(from, c).apply {
            duration = 450
            addUpdateListener { applyAccent(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyAccent(c: Int) {
        toggleBg.setColor(c)
        indicatorBg.setColor(c)
        pairName.setTextColor(c)
        orb.accent = c
        pairOrb.accent = c
    }

    private fun setTitle(t: String) {
        if (t == shownTitle) return
        val first = shownTitle.isEmpty()
        shownTitle = t
        if (first) {
            title.text = t
        } else {
            title.animate().alpha(0f).translationY(-dp(8f)).setDuration(120).withEndAction {
                title.text = t
                title.translationY = dp(8f)
                title.animate().alpha(1f).translationY(0f).setDuration(200).start()
            }.start()
        }
    }

    private fun showPair() {
        val code = ProxyState.pairCode
        pairName.text = "„" + ProxyState.pcName + "“"
        if (code.length == 6) pairCode.text = code.substring(0, 3) + " " + code.substring(3)
        if (pairShown) return
        pairShown = true
        pairLayer.animate().cancel()
        pairLayer.visibility = View.VISIBLE
        pairLayer.alpha = 0f
        pairLayer.animate().alpha(1f).setDuration(280).start()
        pairCode.scaleX = 0.7f
        pairCode.scaleY = 0.7f
        pairCode.animate().scaleX(1f).scaleY(1f).setDuration(480).setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun hidePair() {
        if (!pairShown) return
        pairShown = false
        pairLayer.animate().cancel()
        pairLayer.animate().alpha(0f).setDuration(240).withEndAction {
            if (!pairShown) pairLayer.visibility = View.GONE
        }.start()
    }

    // ---------- vykresleni stavu ----------

    private fun render() {
        val st = ProxyState
        val name = st.pcName.ifEmpty { prefs.getString("last_pc", "") ?: "" }
        setAccent(Palette.accent(name))

        if (st.phase == Phase.PAIRING) showPair() else hidePair()

        val (t, s) = when (st.phase) {
            Phase.OFF -> "Vypnuto" to "Zapni, ať telefon čeká na PC"
            Phase.LISTENING -> "Čekám na PC" to "Spusť ProxyLink na počítači"
            Phase.PAIRING -> "Spárování" to st.pcName
            Phase.STREAMING -> "Streamuji" to
                (st.pcName + "  ·  ~" + st.latencyMs + " ms" + (if (st.playing) "" else "  ·  ticho"))
        }
        setTitle(t)
        sub.text = s
        toggle.text = if (st.phase == Phase.OFF) "Zapnout" else "Vypnout"
        orb.mode = when (st.phase) {
            Phase.OFF -> 0
            Phase.LISTENING -> 1
            Phase.PAIRING -> 2
            Phase.STREAMING -> 3
        }
    }

    private fun startSvc(action: String) {
        val i = Intent(this, AudioService::class.java).setAction(action)
        if (action == AudioService.ACTION_START) startForegroundService(i) else startService(i)
    }
}
