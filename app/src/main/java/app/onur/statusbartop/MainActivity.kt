package app.onur.statusbartop

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var diagText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        column.addView(TextView(this).apply {
            text = "Statusleiste → nach oben scrollen"
            textSize = 22f
        })

        column.addView(TextView(this).apply {
            text = "Tippe auf die Statusleiste (Uhr bzw. Kamera-Bereich), um die aktuelle Liste oder " +
                "Seite ganz nach oben zu scrollen – wie bei iOS. Ein Wischen nach unten in diesem Bereich " +
                "öffnet weiterhin Benachrichtigungen (links) bzw. Schnelleinstellungen (rechts)."
            setPadding(0, dp(12), 0, dp(12))
        })

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }
        column.addView(statusText)

        column.addView(Button(this).apply {
            text = "1) Bedienungshilfen öffnen"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        column.addView(Button(this).apply {
            text = "App-Info öffnen (Eingeschränkte Einstellungen erlauben)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        column.addView(TextView(this).apply {
            text = "Falls der Dienst in den Bedienungshilfen ausgegraut ist: In der App-Info oben rechts " +
                "(⋮) „Eingeschränkte Einstellungen zulassen“ wählen, dann den Dienst unter " +
                "Installierte Apps aktivieren."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(16))
        })

        column.addView(switchRow(prefs, Prefs.ENABLED, true, "Funktion aktiv"))
        column.addView(
            switchRow(
                prefs, Prefs.HIDE_FULLSCREEN, false,
                "Experimentell: In Vollbild durchlässig (erkennt Vollbild auf manchen Handys falsch, dann AUS lassen)"
            )
        )
        column.addView(
            switchRow(prefs, Prefs.DEBUG_TINT, false, "Test-Modus: Streifen rot einfärben + Diagnose aufzeichnen")
        )

        column.addView(TextView(this).apply {
            text = "Diagnose (nur im Test-Modus): Streifen in der anderen App antippen, dann hierher " +
                "zurückkehren und diesen Bereich als Screenshot schicken."
            textSize = 13f
            setPadding(0, dp(20), 0, dp(8))
        })
        diagText = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        column.addView(diagText)

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        val active = isServiceEnabled()
        statusText.text = if (active) "Dienst: aktiv ✓" else "Dienst: NICHT aktiv"
        val p = Prefs.get(this)
        diagText.text = listOf(
            p.getString(Prefs.LAST_STATE, null) ?: "Streifen: (noch kein Zustand aufgezeichnet)",
            p.getString(Prefs.LAST_TOUCH, null) ?: "Touch: (noch keiner angekommen)",
            p.getString(Prefs.LAST_DIAG, null) ?: "Scroll-Diagnose: (noch keine)"
        ).joinToString("\n\n")
    }

    private fun switchRow(
        prefs: android.content.SharedPreferences,
        key: String,
        default: Boolean,
        label: String
    ): View = Switch(this).apply {
        text = label
        isChecked = prefs.getBoolean(key, default)
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, ScrollTopService::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
