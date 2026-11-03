package com.nomkeyboard.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.nomkeyboard.app.R

/**
 * Keyboard colour theme. Picks a light or dark palette based on the user preference
 * (or the system night-mode when preference is "system"). Colour choices follow
 * the Gboard look &amp; feel.
 */
data class KeyboardTheme(
    val bg: Int,
    val key: Int,
    val keyFunc: Int,
    val text: Int,
    val textFunc: Int,
    val action: Int,
    val press: Int,
    val candidateBg: Int,
    val candidateText: Int,
    val divider: Int,
) {
    companion object {
        fun from(context: Context): KeyboardTheme {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val darkPref = prefs.getString(PREF_THEME, "system") ?: "system"
            val systemDark = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val dark = when (darkPref) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            return if (dark) dark(context) else light(context)
        }

        const val PREF_THEME = "pref_theme_mode"

        private fun c(context: Context, id: Int): Int = ContextCompat.getColor(context, id)

        fun light(context: Context) = KeyboardTheme(
            bg = c(context, R.color.kb_bg_light),
            key = c(context, R.color.kb_key_light),
            keyFunc = c(context, R.color.kb_key_func_light),
            text = c(context, R.color.kb_text_light),
            textFunc = c(context, R.color.kb_text_func_light),
            action = c(context, R.color.kb_action_light),
            press = c(context, R.color.kb_press_light),
            candidateBg = c(context, R.color.kb_candidate_bg_light),
            candidateText = c(context, R.color.kb_candidate_text_light),
            divider = c(context, R.color.kb_divider_light),
        )

        fun dark(context: Context) = KeyboardTheme(
            bg = c(context, R.color.kb_bg_dark),
            key = c(context, R.color.kb_key_dark),
            keyFunc = c(context, R.color.kb_key_func_dark),
            text = c(context, R.color.kb_text_dark),
            textFunc = c(context, R.color.kb_text_func_dark),
            action = c(context, R.color.kb_action_dark),
            press = c(context, R.color.kb_press_dark),
            candidateBg = c(context, R.color.kb_candidate_bg_dark),
            candidateText = c(context, R.color.kb_candidate_text_dark),
            divider = c(context, R.color.kb_divider_dark),
        )
    }
}
