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

    /** Test-Modus: aktueller Zustand des Streifens (sichtbar/berührbar/Höhe). */
    const val LAST_STATE = "last_state"

    /** Test-Modus: letzter Touch, der beim Streifen angekommen ist. */
    const val LAST_TOUCH = "last_touch"

    fun isDiagKey(key: String): Boolean =
        key == LAST_DIAG || key == LAST_STATE || key == LAST_TOUCH

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
