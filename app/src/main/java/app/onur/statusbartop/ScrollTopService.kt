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
import kotlin.math.min

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
        val hideInFullscreen = prefs.getBoolean(Prefs.HIDE_FULLSCREEN, false)
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
        resetRun()
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

    // Ablauf: Ziel einmal suchen und die Entfernung zum Anfang schätzen.
    //  - Weit weg: große, sehr schnelle Züge (Inhalt folgt dem Finger 1:1, bis zu ~20 000 px/s). Jeder Zug
    //    nimmt nur 60 % der geschätzten Restentfernung, damit nie über den Anfang hinaus gezogen wird.
    //  - Nah dran / unbekannt: kurze Flicks (Zugweg zu kurz für "Neu laden").
    // Zwischen den Zügen wird nur geprüft, ob die "Hoch"-Aktion noch gemeldet wird – ist sie weg, ist man oben.
    private var currentTarget: AccessibilityNodeInfo? = null
    private var lastFingerprint = 0L
    private var sameCount = 0

    private var refNode: AccessibilityNodeInfo? = null
    private var refTop0 = 0
    private var distance0 = 0
    private var bigDisabled = false
    private var bigRan = false
    private var prevDistance = Int.MAX_VALUE
    private var stuckCount = 0

    private fun resetRun() {
        currentTarget = null
        lastFingerprint = 0L
        sameCount = 0
        refNode = null
        refTop0 = 0
        distance0 = 0
        bigDisabled = false
        bigRan = false
        prevDistance = Int.MAX_VALUE
        stuckCount = 0
    }

    private fun step(token: Int, iter: Int) {
        if (token != scrollToken || iter >= MAX_STEPS) return

        val kept = if (iter > 0) currentTarget else null
        val node: AccessibilityNodeInfo
        val action: AccessibilityAction
        if (kept != null && kept.refresh()) {
            node = kept
            action = scrollUpAction(kept) ?: return // Aktion weg -> oben angekommen
        } else {
            val root = rootInActiveWindow ?: return
            val found = findTarget(root) ?: return // nichts zum Hochscrollen -> fertig
            node = found.first
            action = found.second
            currentTarget = node
        }

        // Falls die App die Aktion auch ganz oben weiter meldet: bei Stillstand aufhören.
        if (iter % FINGERPRINT_EVERY == FINGERPRINT_EVERY - 1) {
            val fp = fingerprint(node)
            if (fp == lastFingerprint) {
                sameCount++
                if (sameCount >= 2) return
            } else {
                sameCount = 0
                lastFingerprint = fp
            }
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        bounds.intersect(wm.currentWindowMetrics.bounds)

        var distance = 0
        if (!bigDisabled && bounds.height() >= 600) {
            distance = remainingDistance(node, bounds)
            if (refNode == null) bigDisabled = true
            // Bewegt sich der Inhalt trotz großer Züge nicht mehr -> große Züge abschalten
            if (bigRan) {
                if (distance >= prevDistance - 60) {
                    stuckCount++
                    if (stuckCount >= 2) bigDisabled = true
                } else {
                    stuckCount = 0
                }
            }
            prevDistance = distance
        }

        if (!bigDisabled && distance > BIG_MIN_PX) {
            bigRan = true
            val length = min(distance * 0.6f, bounds.height() * 0.85f)
            val duration = (length / BIG_SPEED_PX_PER_MS).toLong().coerceIn(40L, 140L)
            val y0 = bounds.top + bounds.height() * 0.08f
            dispatchStroke(token, iter, node, action, bounds.exactCenterX(), y0, y0 + length, duration, BIG_GAP_MS)
        } else {
            bigRan = false
            if (bounds.height() < 400) {
                pageUp(token, iter, node, action)
                return
            }
            val y0 = bounds.top + bounds.height() * 0.40f
            val length = FLICK_DP * resources.displayMetrics.density
            dispatchStroke(token, iter, node, action, bounds.exactCenterX(), y0, y0 + length, FLICK_MS, GAP_MS)
        }
    }

    private fun dispatchStroke(
        token: Int,
        iter: Int,
        node: AccessibilityNodeInfo,
        action: AccessibilityAction,
        x: Float,
        yStart: Float,
        yEnd: Float,
        durationMs: Long,
        gapMs: Long
    ) {
        val path = Path().apply {
            moveTo(x, yStart)
            lineTo(x, yEnd)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed({ step(token, iter + 1) }, gapMs)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed({ step(token, iter + 1) }, 60)
            }
        }, handler)

        if (!dispatched) pageUp(token, iter, node, action)
    }

    /** Notnagel, falls keine Geste möglich ist: eine Seite per Scroll-Aktion nach oben. */
    private fun pageUp(token: Int, iter: Int, node: AccessibilityNodeInfo, action: AccessibilityAction) {
        node.performAction(action.id)
        handler.postDelayed({ step(token, iter + 1) }, PAGE_DELAY_MS)
    }

    // ---------------------------------------------------------------- Entfernungsschätzung

    /**
     * Restentfernung bis zum Anfang in Pixel. Beim ersten Mal wird geschätzt; danach wird nur noch
     * ein Referenz-Element beobachtet (ein einziger schneller Abruf), wie weit es sich bewegt hat.
     */
    private fun remainingDistance(node: AccessibilityNodeInfo, vp: Rect): Int {
        val ref = refNode
        if (ref != null && ref.refresh()) {
            val r = Rect()
            ref.getBoundsInScreen(r)
            val moved = max(0, r.top - refTop0)
            return max(0, distance0 - moved)
        }
        distance0 = estimateDistance(node, vp)
        pickReference(node, vp)
        return distance0
    }

    /**
     * Schätzt die Strecke bis zum Anfang:
     *  A) Webseiten (z. B. Firefox) melden auch Elemente oberhalb des sichtbaren Bereichs -> deren Lage
     *     (10-%-Quantil, damit einzelne versteckte Elemente nicht stören).
     *  B) Listen melden den Index des ersten sichtbaren Eintrags -> Index mal mittlere Eintragshöhe (70 %).
     * Im Zweifel lieber zu klein schätzen (dann gibt es nur mehr Schritte, kein Überschwingen).
     */
    private fun estimateDistance(node: AccessibilityNodeInfo, vp: Rect): Int {
        val r = Rect()

        val tops = ArrayList<Int>()
        var visited = 0
        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || visited >= 3000 || depth > MAX_DEPTH) return
            visited++
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0 && r.bottom <= vp.top && r.top > -300000) tops.add(r.top)
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(node, 0)
        var a = 0
        if (tops.size >= 8) {
            tops.sort()
            a = max(0, vp.top - tops[tops.size / 10])
        }

        var firstIndex = Int.MAX_VALUE
        var firstTop = 0
        var sumHeight = 0
        var count = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            val info = child.collectionItemInfo ?: continue
            child.getBoundsInScreen(r)
            sumHeight += r.height()
            count++
            if (info.rowIndex in 0 until firstIndex) {
                firstIndex = info.rowIndex
                firstTop = r.top
            }
        }
        var b = 0
        if (count > 0 && firstIndex in 1 until Int.MAX_VALUE) {
            b = firstIndex * (sumHeight / count) + max(0, vp.top - firstTop)
        }

        return max(a, (b * 0.7f).toInt())
    }

    /** Wählt ein sichtbares Element nahe der Bildschirmmitte, dessen Bewegung wir verfolgen. */
    private fun pickReference(node: AccessibilityNodeInfo, vp: Rect) {
        refNode = null
        val r = Rect()
        var visited = 0
        var bestDistance = Int.MAX_VALUE
        val centerY = vp.centerY()

        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || visited >= 600 || depth > MAX_DEPTH || !n.isVisibleToUser) return
            visited++
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() in 30..(vp.height() / 2)) {
                val d = abs(r.centerY() - centerY)
                if (d < bestDistance) {
                    bestDistance = d
                    refNode = n
                    refTop0 = r.top
                }
            }
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }
        visit(node, 0)
    }

    /** Grober Abdruck der Positionen im Teilbaum – ändert sich, solange gescrollt wird. */
    private fun fingerprint(node: AccessibilityNodeInfo): Long {
        var h = 17L
        var count = 0
        val r = Rect()

        fun visit(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= 150 || depth > 30) return
            count++
            n.getBoundsInScreen(r)
            h = h * 31 + r.top
            h = h * 31 + r.left
            for (i in 0 until n.childCount) visit(n.getChild(i), depth + 1)
        }

        visit(node, 0)
        return h
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

    private companion object {
        const val MAX_STEPS = 600
        const val MAX_DEPTH = 60
        const val FINGERPRINT_EVERY = 4
        const val FLICK_DP = 48f
        const val FLICK_MS = 32L
        const val GAP_MS = 25L
        const val PAGE_DELAY_MS = 100L
        const val BIG_MIN_PX = 700
        const val BIG_SPEED_PX_PER_MS = 20f
        const val BIG_GAP_MS = 15L
    }
}
