package app.onur.statusbartop

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "prefs"

    /** Funktion insgesamt an/aus (Overlay wird entfernt, wenn aus). */
    const val ENABLED = "enabled"

    /** Overlay bei ausgeblendeter Statusleiste (Vollbild-Video, Spiele) durchlässig machen. */
    const val HIDE_FULLSCREEN = "hide_fullscreen"

    /** Streifen rot einfärben, um Position und Aktivität zu testen. */
    const val DEBUG_TINT = "debug_tint"

    /** Letzte Diagnose (nur im Test-Modus): was der Dienst beim letzten Tipp in der App-Oberfläche gesehen hat. */
    const val LAST_DIAG = "last_diag"

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
