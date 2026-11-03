package com.nomkeyboard.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nomkeyboard.app.R
import com.nomkeyboard.app.dict.UserDictionary

/**
 * Management screen for the user-defined dictionary.
 *
 * Supports:
 *   - Adding / editing / deleting entries (reading -> one or more Nom candidates).
 *   - Importing a TSV file via the Storage Access Framework picker; the user can choose
 *     whether to replace the current contents or merge into them.
 *   - Exporting the current dictionary as a TSV file through the SAF create-document flow.
 *   - Bulk clearing all entries.
 */
class UserDictionaryActivity : AppCompatActivity(), UserDictionary.ChangeListener {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: EntryAdapter
    private var nomTypeface: Typeface? = null

    private val importLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) askImportMode(uri)
        }

    private val exportLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
            if (uri != null) performExport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_dict)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        UserDictionary.ensureLoaded(applicationContext)
        UserDictionary.addChangeListener(this)

        nomTypeface = try {
            Typeface.createFromAsset(assets, "fonts/HanNomGothic.ttf")
        } catch (e: Exception) {
            null
        }

        recycler = findViewById(R.id.rv_entries)
        emptyView = findViewById(R.id.tv_empty)
        adapter = EntryAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<Button>(R.id.btn_add).setOnClickListener { showEditDialog(null) }
        findViewById<Button>(R.id.btn_import).setOnClickListener {
            // TSV doesn't always get recognised as text/tab-separated-values on every device,
            // so we advertise a few common MIME types as well as a generic */* fallback.
            importLauncher.launch(arrayOf(
                "text/tab-separated-values",
                "text/plain",
                "application/octet-stream",
                "*/*"
            ))
        }
        findViewById<Button>(R.id.btn_export).setOnClickListener {
            exportLauncher.launch("user_dict.tsv")
        }
        findViewById<Button>(R.id.btn_clear_all).setOnClickListener { confirmClearAll() }

        refresh()
    }

    override fun onDestroy() {
        UserDictionary.removeChangeListener(this)
        super.onDestroy()
    }

    override fun onUserDictionaryChanged() {
        runOnUiThread { refresh() }
    }

    private fun refresh() {
        val list = UserDictionary.allEntries()
        adapter.submit(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    // ============================ Dialogs ============================

    private fun showEditDialog(existing: Pair<String, List<String>>?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val keyEdit = EditText(this).apply {
            hint = getString(R.string.user_dict_hint_key)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(existing?.first ?: "")
        }
        val valuesEdit = EditText(this).apply {
            hint = getString(R.string.user_dict_hint_values)
            inputType = InputType.TYPE_CLASS_TEXT
            nomTypeface?.let { typeface = it }
            setText(existing?.second?.joinToString(" ") ?: "")
        }
        container.addView(keyEdit, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(valuesEdit, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.user_dict_add_title else R.string.user_dict_edit_title)
            .setView(container)
            .setPositiveButton(R.string.user_dict_save, null)
            .setNegativeButton(R.string.user_dict_cancel, null)
            .apply {
                if (existing != null) {
                    setNeutralButton(R.string.user_dict_delete) { _, _ ->
                        UserDictionary.removeEntry(applicationContext, existing.first)
                    }
                }
            }
            .create()
        // Override the positive button click so the dialog only dismisses on successful save.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = keyEdit.text.toString().trim()
                if (key.isEmpty()) {
                    keyEdit.error = getString(R.string.user_dict_err_empty_key)
                    return@setOnClickListener
                }
                // Split by whitespace / common separators so users can paste several Nom
                // candidates in a single go.
                val values = valuesEdit.text.toString()
                    .split(Regex("[\\s,;/|]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (values.isEmpty()) {
                    valuesEdit.error = getString(R.string.user_dict_err_empty_values)
                    return@setOnClickListener
                }
                // If the user renamed the key while editing, drop the old record first.
                if (existing != null && existing.first != key.lowercase()) {
                    UserDictionary.removeEntry(applicationContext, existing.first)
                }
                UserDictionary.putEntry(applicationContext, key, values)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun askImportMode(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.user_dict_import_title)
            .setMessage(R.string.user_dict_import_message)
            .setPositiveButton(R.string.user_dict_import_merge) { _, _ -> performImport(uri, replace = false) }
            .setNegativeButton(R.string.user_dict_import_replace) { _, _ -> performImport(uri, replace = true) }
            .setNeutralButton(R.string.user_dict_cancel, null)
            .show()
    }

    private fun performImport(uri: Uri, replace: Boolean) {
        try {
            val count = UserDictionary.importFromUri(applicationContext, uri, replace)
            Toast.makeText(this, getString(R.string.user_dict_imported_fmt, count), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.user_dict_import_failed_fmt, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun performExport(uri: Uri) {
        try {
            // Request persistable permission so the URI keeps working if the user chose a
            // location that needs ongoing write access (e.g. SAF tree documents).
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Not all URIs support persistable permissions; the one-shot grant is enough.
            }
            UserDictionary.exportToUri(applicationContext, uri)
            Toast.makeText(this, R.string.user_dict_exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.user_dict_export_failed_fmt, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.user_dict_clear_all)
            .setMessage(R.string.user_dict_clear_all_message)
            .setPositiveButton(R.string.user_dict_delete) { _, _ ->
                UserDictionary.clearAll(applicationContext)
            }
            .setNegativeButton(R.string.user_dict_cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ============================ Adapter ============================

    private inner class EntryAdapter : RecyclerView.Adapter<EntryViewHolder>() {
        private val data: MutableList<Pair<String, List<String>>> = mutableListOf()

        fun submit(newData: List<Pair<String, List<String>>>) {
            data.clear()
            data.addAll(newData)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_user_dict, parent, false)
            return EntryViewHolder(view)
        }

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
            val entry = data[position]
            holder.bind(entry)
            holder.itemView.setOnClickListener { showEditDialog(entry) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@UserDictionaryActivity)
                    .setTitle(entry.first)
                    .setItems(arrayOf(
                        getString(R.string.user_dict_edit_title),
                        getString(R.string.user_dict_delete)
                    )) { _, which ->
                        when (which) {
                            0 -> showEditDialog(entry)
                            1 -> UserDictionary.removeEntry(applicationContext, entry.first)
                        }
                    }
                    .show()
                true
            }
        }

        override fun getItemCount(): Int = data.size
    }

    private inner class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val keyView: TextView = view.findViewById(R.id.tv_key)
        private val valuesView: TextView = view.findViewById(R.id.tv_values)

        fun bind(entry: Pair<String, List<String>>) {
            keyView.text = entry.first
            valuesView.text = entry.second.joinToString("  ")
            nomTypeface?.let { valuesView.typeface = it }
        }
    }
}
