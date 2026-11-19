package io.github.pieterjanpintens.ascentcamera

import android.content.Context
import java.security.SecureRandom

/** The pairing code Ascent must send with every request (header X-Key). Kept until the user asks for a new one. */
object Pairing {
    private const val PREFS = "ascent-camera"
    private const val KEY = "pairing_code"

    fun code(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        return newCode(context)
    }

    fun newCode(context: Context): String {
        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        return code
    }
}
