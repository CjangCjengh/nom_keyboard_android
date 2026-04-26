package com.nomkeyboard.app.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.nomkeyboard.app.NomInputMethodService
import com.nomkeyboard.app.R

class SettingsActivity : AppCompatActivity() {

    // Cache the loaded Han-Nom typeface once so that toggling the preference back and forth
    // doesn't hit disk every time.
    private var nomTypeface: Typeface? = null
    private lateinit var etTest: EditText

    // Kept as a field so we can unregister in onDestroy and avoid leaking the activity.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_USE_NOM_FONT) applyTestInputTypeface()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_choose).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .remove(NomInputMethodService.PREF_RECENT)
                .apply()
            Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_user_dict).setOnClickListener {
            startActivity(Intent(this, UserDictionaryActivity::class.java))
        }
        // Tuỳ chọn is now a one-line entry that opens a dedicated full-screen page, so
        // the landing screen doesn't feel cramped anymore. The row itself (a
        // MaterialCardView with a ripple foreground) is the click target, not a Button.
        findViewById<android.view.View>(R.id.btn_open_prefs).setOnClickListener {
            startActivity(Intent(this, PreferencesActivity::class.java))
        }

        // Preload the bundled Han-Nom font; if the asset is missing we silently degrade to
        // the system typeface.
        nomTypeface = try {
            Typeface.createFromAsset(assets, "fonts/HanNomGothic.ttf")
        } catch (_: Throwable) {
            null
        }

        // Focus the test EditText so the keyboard pops up for quick trial.
        etTest = findViewById(R.id.et_test)
        applyTestInputTypeface()
        etTest.requestFocus()

        // React to the "use bundled Nom font" preference being toggled (now on the
        // dedicated Preferences screen): re-apply the typeface when the user comes back.
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onResume() {
        super.onResume()
        // The user may have toggled pref_use_nom_font on the Preferences screen; refresh
        // when we come back to the foreground in case the listener didn't fire (e.g. the
        // activity was recreated while the user was away).
        applyTestInputTypeface()
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    /**
     * Apply the current preference: bundled Han-Nom font (default) or system default.
     */
    private fun applyTestInputTypeface() {
        if (!::etTest.isInitialized) return
        val useNom = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_USE_NOM_FONT, true)
        etTest.typeface = if (useNom && nomTypeface != null) nomTypeface else Typeface.DEFAULT
    }

    companion object {
        const val PREF_USE_NOM_FONT = "pref_use_nom_font"
    }
}
