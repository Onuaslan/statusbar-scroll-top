package app.onur.statusbartop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlin.math.abs
import kotlin.math.max

/**
 * Legt einen unsichtbaren Streifen über die Statusleiste.
 *  - Tippen  -> aktuelle Liste/Seite nach ganz oben scrollen (wie iOS)
 *  - Wischen nach unten -> Benachrichtigungen (linke Hälfte) bzw. Schnelleinstellungen (rechte Hälfte),
 *    weil der Streifen die normale Statusleisten-Geste sonst verschlucken würde.
 */
class ScrollTopService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences

    private var overlay: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    // Zustände, die bestimmen, ob der Streifen Berührungen annimmt
    private var statusBarVisible = true
    private var systemUiActive = false

    // Wird bei jedem neuen Tippen erhöht -> laufende Scroll-Schleife bricht ab
    private var scrollToken = 0

    // Touch-Tracking
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var swiped = false

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        rebuildOverlay()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != null && Prefs.isDiagKey(key)) return // Diagnose-Texte dürfen den Streifen nicht neu aufbauen
        rebuildOverlay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Höhe/Breite ändern sich bei Drehung -> Streifen neu aufbauen
        handler.post { rebuildOverlay() }
    }

    override fun onInterrupt() {
        scrollToken++
    }

    override fun onDestroy() {
        super.onDestroy()
        scrollToken++
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    // ---------------------------------------------------------------- Overlay

    private fun statusBarHeight(): Int {
        val types = WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
        val top = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(types).top
        val minimum = (24 * resources.displayMetrics.density).toInt()
        return max(top, minimum)
    }

    private fun removeOverlay() {
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
        overlayParams = null
    }

    private fun rebuildOverlay() {
        if (!::wm.isInitialized) return
        removeOverlay()
        if (!prefs.getBoolean(Prefs.ENABLED, true)) return

        val view = View(this)
        if (prefs.getBoolean(Prefs.DEBUG_TINT, false)) view.setBackgroundColor(0x66FF0000)

        // Meldet, ob die Statusleiste gerade sichtbar ist (Vollbild-Videos blenden sie aus)
        view.setOnApplyWindowInsetsListener { _, insets ->
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            if (visible != statusBarVisible) {
                statusBarVisible = visible
                applyTouchability()
            }
            recordState()
            insets
        }
        view.setOnTouchListener { _, ev -> onOverlayTouch(ev) }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "StatusbarScrollTop"
        }

        try {
            wm.addView(view, lp)
        } catch (_: Exception) {
            return
        }
        overlay = view
        overlayParams = lp
        applyTouchability()
    }

    /** Streifen nimmt nur Berührungen an, wenn Statusleiste sichtbar und kein System-UI (Panel/Sperrbildschirm) aktiv ist. */
    private fun applyTouchability() {
        val view = overlay ?: return
        val lp = overlayParams ?: return
        val hideInFullscreen = prefs.getBoolean(Prefs.HIDE_FULLSCREEN, true)
        // Das Benachrichtigungsfeld (SystemUI) sperrt den Streifen bewusst nicht mehr:
        // die Erkennung war unzuverlässig und konnte den Streifen dauerhaft abschalten.
        val touchable = statusBarVisible || !hideInFullscreen

        val newFlags = if (touchable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        view.alpha = if (touchable) 1f else 0.15f
        if (newFlags != lp.flags) {
            lp.flags = newFlags
            try {
                wm.updateViewLayout(view, lp)
            } catch (_: Exception) {
            }
        }
        recordState()
    }

    // ---------------------------------------------------------------- Diagnose: Zustand und Touches

    private var touchCount = 0

    private fun now(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())

    private fun recordState() {
        if (!::prefs.isInitialized || !prefs.getBoolean(Prefs.DEBUG_TINT, false)) return
        val lp = overlayParams
        val touchable = lp != null && (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
        val text = "Streifen: berührbar=$touchable, Statusleiste sichtbar=$statusBarVisible, " +
            "SystemUI aktiv=$systemUiActive, Höhe=${lp?.height} px, Stand ${now()}"
        prefs.edit().putString(Prefs.LAST_STATE, text).apply()
    }

    private fun recordTouch(x: Float, y: Float) {
        if (!prefs.getBoolean(Prefs.DEBUG_TINT, false)) return
        touchCount++
        val pkg = rootInActiveWindow?.packageName
        val text = "Touch Nr. $touchCount um ${now()} bei x=${x.toInt()}, y=${y.toInt()}, Vordergrund-App=$pkg"
        prefs.edit().putString(Prefs.LAST_TOUCH, text).apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        updateSystemUiState()
        // Fenster-Wechsel (z. B. Benachrichtigungsfeld) braucht einen Moment, bis es "aktiv" ist
        handler.postDelayed({ updateSystemUiState() }, 350)
    }

    private fun updateSystemUiState() {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: return
        val active = pkg == "com.android.systemui"
        if (active != systemUiActive) {
            systemUiActive = active
            applyTouchability()
        }
    }

    // ---------------------------------------------------------------- Touch

    private fun onOverlayTouch(ev: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                downTime = ev.eventTime
                swiped = false
                recordTouch(ev.rawX, ev.rawY)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!swiped && ev.rawY - downY > slop * 3) {
                    swiped = true
                    openPanel(downX)
                }
            }

            MotionEvent.ACTION_UP -> {
                val isTap = !swiped &&
                    abs(ev.rawX - downX) <= slop * 2 &&
                    abs(ev.rawY - downY) <= slop * 2 &&
                    ev.eventTime - downTime < 400
                if (isTap) scrollToTop()
            }
        }
        return true
    }

    private fun openPanel(x: Float) {
        val width = wm.currentWindowMetrics.bounds.width()
        performGlobalAction(
            if (x < width / 2f) GLOBAL_ACTION_NOTIFICATIONS else GLOBAL_ACTION_QUICK_SETTINGS
        )
    }

    // ---------------------------------------------------------------- Nach oben scrollen

    private fun scrollToTop() {
        val token = ++scrollToken
        if (prefs.getBoolean(Prefs.DEBUG_TINT, false)) {
            val root = rootInActiveWindow
            val target = if (root != null) findTarget(root) else null
            prefs.edit().putString(Prefs.LAST_DIAG, buildDiagnosis(root, target)).apply()
        }
        step(token, 0)
    }

    // ---------------------------------------------------------------- Diagnose (Test-Modus)

    private fun actionNames(n: AccessibilityNodeInfo): String {
        val actions = n.actionList
        val names = ArrayList<String>()
        if (actions.contains(AccessibilityAction.ACTION_SCROLL_UP)) names.add("UP")
        if (actions.contains(AccessibilityAction.ACTION_SCROLL_DOWN)) names.add("DOWN")
        if (actions.contains(AccessibilityAction.ACTION_SCROLL_LEFT)) names.add("LEFT")
        if (actions.contains(AccessibilityAction.ACTION_SCROLL_RIGHT)) names.add("RIGHT")
        if (actions.contains(AccessibilityAction.ACTION_SCROLL_BACKWARD)) names.add("BACKWARD")
        if (actions.contains(AccessibilityAction.ACTION_SCROLL_FORWARD)) names.add("FORWARD")
        if (actions.contains(AccessibilityAction.ACTION_SCROLL_TO_POSITION)) names.add("TO_POSITION")
        return names.joinToString(",")
    }

    private fun buildDiagnosis(
        root: AccessibilityNodeInfo?,
        target: Pair<AccessibilityNodeInfo, AccessibilityAction>?
    ): String {
        val sb = StringBuilder()
        sb.append("Zeit: ")
            .append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date()))
            .append('\n')
        if (root == null) {
            sb.append("Kein aktives Fenster (rootInActiveWindow = null)")
            return sb.toString()
        }
        sb.append("Paket: ").append(root.packageName).append('\n')
        if (target == null) {
            sb.append("Ziel: KEIN Knoten mit Hoch-Scroll-Aktion gefunden\n")
        } else {
            sb.append("Ziel: ").append(target.first.className)
                .append(" (").append(actionNames(target.first)).append(")\n")
        }

        val rect = Rect()
        var listed = 0
        var total = 0

        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > MAX_DEPTH) return
            total++
            val names = actionNames(n)
            if ((n.isScrollable || names.isNotEmpty()) && listed < 25) {
                listed++
                n.getBoundsInScreen(rect)
                sb.append("- ").append(n.className)
                    .append(" ").append(rect.flattenToString())
                    .append(" sichtbar=").append(n.isVisibleToUser)
                    .append(" scrollbar=").append(n.isScrollable)
                    .append(" aktionen=").append(names.ifEmpty { "-" })
                    .append(" tiefe=").append(depth)
                    .append('\n')
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }

        visit(root, 0)
        sb.append("Knoten im Baum gesamt: ").append(total)
        return sb.toString()
    }

    private fun step(token: Int, iter: Int) {
        if (token != scrollToken || iter >= MAX_STEPS) return

        val root = rootInActiveWindow ?: return
        val (node, action) = findTarget(root) ?: return // nichts mehr zum Hochscrollen -> fertig

        // Nah am Anfang: seitenweise (sauber, ohne Überschwingen). Weit weg: schnelle Wisch-Gesten.
        val distance = distanceEstimate(node)
        val useFling = if (distance != null) distance > NEAR_ITEMS else iter >= PAGES_BEFORE_FLING

        if (useFling) flingDown(token, iter, node, action) else pageUp(token, iter, node, action)
    }

    private fun pageUp(token: Int, iter: Int, node: AccessibilityNodeInfo, action: AccessibilityAction) {
        node.performAction(action.id)
        handler.postDelayed({ step(token, iter + 1) }, PAGE_DELAY_MS)
    }

    private fun flingDown(token: Int, iter: Int, node: AccessibilityNodeInfo, action: AccessibilityAction) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        bounds.intersect(wm.currentWindowMetrics.bounds)
        if (bounds.height() < 300) {
            pageUp(token, iter, node, action)
            return
        }

        val x = bounds.exactCenterX()
        val path = Path().apply {
            moveTo(x, bounds.top + bounds.height() * 0.20f)
            lineTo(x, bounds.top + bounds.height() * 0.85f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({ step(token, iter + 1) }, 80)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed({ step(token, iter + 1) }, 120)
            }
        }, handler)

        if (!dispatched) pageUp(token, iter, node, action)
    }

    /**
     * Sucht die größte sichtbare Liste/Seite, die sich noch nach oben scrollen lässt.
     * Ist alles schon oben, gibt es keinen Treffer.
     */
    private fun findTarget(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, AccessibilityAction>? {
        val screen = wm.currentWindowMetrics.bounds
        val tmp = Rect()
        var best: Pair<AccessibilityNodeInfo, AccessibilityAction>? = null
        var bestArea = 0L

        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > MAX_DEPTH || !n.isVisibleToUser) return
            val action = scrollUpAction(n)
            if (action != null) {
                n.getBoundsInScreen(tmp)
                if (tmp.intersect(screen) && tmp.height() >= 150) {
                    val area = tmp.width().toLong() * tmp.height().toLong()
                    if (area > bestArea) {
                        bestArea = area
                        best = n to action
                    }
                }
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }

        visit(root, 0)
        return best
    }

    /** Liefert die "nach oben"-Aktion, falls der Knoten vertikal nach oben scrollbar ist. */
    private fun scrollUpAction(n: AccessibilityNodeInfo): AccessibilityAction? {
        val actions = n.actionList
        if (actions.contains(AccessibilityAction.ACTION_SCROLL_UP)) {
            return AccessibilityAction.ACTION_SCROLL_UP
        }
        // Ohne Richtungsangabe (nur vor/zurück) nehmen wir "zurück" – aber nicht bei erkennbar horizontalen Listen
        val hasDirection = actions.contains(AccessibilityAction.ACTION_SCROLL_DOWN) ||
            actions.contains(AccessibilityAction.ACTION_SCROLL_LEFT) ||
            actions.contains(AccessibilityAction.ACTION_SCROLL_RIGHT)
        if (!hasDirection && actions.contains(AccessibilityAction.ACTION_SCROLL_BACKWARD)) {
            return AccessibilityAction.ACTION_SCROLL_BACKWARD
        }
        return null
    }

    /** Kleinster sichtbarer Listenindex (falls die App ihn meldet) – grobe Entfernung zum Anfang. */
    private fun distanceEstimate(node: AccessibilityNodeInfo): Int? {
        var min: Int? = null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            val index = child.collectionItemInfo?.rowIndex ?: continue
            if (index < 0) continue
            val current = min
            if (current == null || index < current) min = index
        }
        return min
    }

    private companion object {
        const val MAX_STEPS = 80
        const val MAX_DEPTH = 60
        const val NEAR_ITEMS = 10
        const val PAGES_BEFORE_FLING = 3
        const val PAGE_DELAY_MS = 130L
    }
}
