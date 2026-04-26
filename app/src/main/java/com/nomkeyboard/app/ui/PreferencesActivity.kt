package com.nomkeyboard.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.nomkeyboard.app.R
import com.nomkeyboard.app.dict.NgramModel

/**
 * Full-screen host for the preference list. Split out from [SettingsActivity] so the
 * main landing screen no longer has to cram the whole preference tree inside a single
 * Material card – the list now gets the full height of the device and scrolls naturally.
 *
 * The n-gram import / export buttons need to be driven by SAF launchers registered at
 * the Activity level, so the launchers live here (not on [SettingsActivity] anymore).
 */
class PreferencesActivity : AppCompatActivity() {

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
        setContentView(R.layout.activity_preferences)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.prefs_container, SettingsFragment())
                .commit()
        }
    }

    // ============================ N-gram import / export ============================

    fun requestNgramExport() {
        NgramModel.ensureLoaded(applicationContext)
        NgramModel.persistNow(applicationContext)
        ngramExportLauncher.launch(getString(R.string.ngram_export_default_name))
    }

    fun requestNgramImport() {
        ngramImportLauncher.launch(arrayOf(
            "text/tab-separated-values",
            "text/plain",
            "application/octet-stream",
            "*/*"
        ))
    }

    private fun performNgramExport(uri: Uri) {
        try {
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
            findPreference<Preference>("pref_ngram_export")?.setOnPreferenceClickListener {
                (activity as? PreferencesActivity)?.requestNgramExport()
                true
            }
            findPreference<Preference>("pref_ngram_import")?.setOnPreferenceClickListener {
                (activity as? PreferencesActivity)?.requestNgramImport()
                true
            }
        }
    }
}
