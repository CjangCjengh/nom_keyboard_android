package com.nomkeyboard.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.nomkeyboard.app.NomInputMethodService
import com.nomkeyboard.app.R
import com.nomkeyboard.app.dict.NgramModel

class SettingsActivity : AppCompatActivity() {

    // Cache the loaded Han-Nom typeface once so that toggling the preference back and forth
    // doesn't hit disk every time.
    private var nomTypeface: Typeface? = null
    private lateinit var etTest: EditText

    // Kept as a field so we can unregister in onDestroy and avoid leaking the activity.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_USE_NOM_FONT) applyTestInputTypeface()
    }

    // SAF launchers for exporting/importing the n-gram frequency model. Registered at the
    // Activity level (required by AndroidX) and driven by the two Preference rows hosted
    // inside [SettingsFragment].
    private val ngramExportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
            if (uri != null) performNgramExport(uri)
        }

    private val ngramImportLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) askNgramImportMode(uri)
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

        // React to the "use bundled Nom font" preference being toggled on the same screen.
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
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

    // ============================ N-gram import / export ============================

    fun requestNgramExport() {
        // Make sure in-memory counts are flushed so the exported snapshot really matches
        // what the user sees in the app right now.
        NgramModel.ensureLoaded(applicationContext)
        NgramModel.persistNow(applicationContext)
        ngramExportLauncher.launch(getString(R.string.ngram_export_default_name))
    }

    fun requestNgramImport() {
        // Advertise several MIME types because TSV isn't universally recognised by file
        // pickers. `*/*` is a last-resort fallback that lets the user pick anything.
        ngramImportLauncher.launch(arrayOf(
            "text/tab-separated-values",
            "text/plain",
            "application/octet-stream",
            "*/*"
        ))
    }

    private fun performNgramExport(uri: Uri) {
        try {
            // Persistable permission is nice-to-have but not required for a one-shot write;
            // swallow failures silently.
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Not all providers support persistable grants.
            }
            NgramModel.ensureLoaded(applicationContext)
            NgramModel.exportToUri(applicationContext, uri)
            Toast.makeText(this,
                getString(R.string.ngram_exported_fmt, NgramModel.size()),
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this,
                getString(R.string.ngram_export_failed_fmt, e.message ?: ""),
                Toast.LENGTH_LONG).show()
        }
    }

    private fun askNgramImportMode(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.ngram_import_title)
            .setMessage(R.string.ngram_import_message)
            .setPositiveButton(R.string.ngram_import_merge) { _, _ -> performNgramImport(uri, replace = false) }
            .setNegativeButton(R.string.ngram_import_replace) { _, _ -> performNgramImport(uri, replace = true) }
            .setNeutralButton(R.string.user_dict_cancel, null)
            .show()
    }

    private fun performNgramImport(uri: Uri, replace: Boolean) {
        try {
            val total = NgramModel.importFromUri(applicationContext, uri, replace)
            // After import the running context is no longer meaningful (the user just
            // fiddled with the model); reset it so the next commit starts a fresh n-gram
            // window.
            NgramModel.resetContext()
            Toast.makeText(this,
                getString(R.string.ngram_imported_fmt, total),
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this,
                getString(R.string.ngram_import_failed_fmt, e.message ?: ""),
                Toast.LENGTH_LONG).show()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            // Wire the two "import / export n-gram frequencies" Preference rows to the
            // hosting Activity's SAF launchers.
            findPreference<Preference>("pref_ngram_export")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.requestNgramExport()
                true
            }
            findPreference<Preference>("pref_ngram_import")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.requestNgramImport()
                true
            }
        }
    }

    companion object {
        const val PREF_USE_NOM_FONT = "pref_use_nom_font"
    }
}
