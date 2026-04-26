package com.nomkeyboard.app.dict

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * User-defined dictionary, layered on top of the bundled [NomDictionary].
 *
 * Persistence:
 *   - The user dictionary is stored as a UTF-8 TSV file inside the app's private files
 *     directory (see [storageFile]). Each non-empty line has the form:
 *         <ascii-or-diacritic reading>\t<nom1>\t<nom2>\t...
 *     which is intentionally identical to the format of the bundled dictionary assets so
 *     import/export between the two is trivial.
 *   - Imported files replace or merge with the current in-memory map (see [importFromStream]).
 *
 * Lookup priority:
 *   - Entries added by the user take priority over the bundled dictionary. The consumer
 *     ([NomDictionary.lookup]) is responsible for prepending the user hits before the
 *     bundled ones while removing duplicates.
 */
object UserDictionary {
    private const val TAG = "UserDict"
    private const val FILE_NAME = "user_dict.tsv"

    // Original-key -> list of Nom candidates, insertion-ordered so the user's manual ordering
    // is preserved on export / listing.
    private val entries: LinkedHashMap<String, MutableList<String>> = LinkedHashMap()
    // ascii form of the key -> list of original keys, used to surface matches when the user
    // types without diacritics.
    private val asciiIndex: HashMap<String, MutableList<String>> = HashMap()

    @Volatile
    private var loaded = false

    /** Callbacks fired whenever the dictionary contents change. */
    interface ChangeListener {
        fun onUserDictionaryChanged()
    }

    private val listeners: MutableList<ChangeListener> = mutableListOf()

    fun addChangeListener(l: ChangeListener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeChangeListener(l: ChangeListener) { listeners.remove(l) }
    private fun notifyChanged() { for (l in listeners.toList()) l.onUserDictionaryChanged() }

    private fun storageFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * Lazily load the user dictionary into memory. Safe to call multiple times.
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val file = storageFile(context)
            if (file.exists()) {
                try {
                    file.inputStream().use { readTsv(it, replace = true) }
                } catch (e: Exception) {
                    Log.e(TAG, "failed to load user dictionary", e)
                }
            }
            loaded = true
            Log.i(TAG, "user dictionary loaded: size=${entries.size}")
        }
    }

    /** Snapshot of current entries, preserving insertion order. */
    fun allEntries(): List<Pair<String, List<String>>> {
        synchronized(this) {
            return entries.map { (k, v) -> k to v.toList() }
        }
    }

    fun size(): Int {
        synchronized(this) { return entries.size }
    }

    fun lookupSingle(query: String): List<String> {
        if (query.isEmpty()) return emptyList()
        synchronized(this) {
            val q = query.lowercase()
            val result = LinkedHashSet<String>()
            entries[q]?.let { if (!q.contains(' ')) result.addAll(it) }
            val qAscii = NomDictionary.stripDiacritics(q)
            asciiIndex[qAscii]?.forEach { k ->
                if (!k.contains(' ')) entries[k]?.let { result.addAll(it) }
            }
            return result.toList()
        }
    }

    fun lookupWord(query: String): List<String> {
        if (query.isEmpty()) return emptyList()
        synchronized(this) {
            val q = query.lowercase()
            val result = LinkedHashSet<String>()
            entries[q]?.let { result.addAll(it) }
            entries[q.replace(" ", "")]?.let { result.addAll(it) }
            val qAscii = NomDictionary.stripDiacritics(q)
            asciiIndex[qAscii]?.forEach { k -> entries[k]?.let { result.addAll(it) } }
            val qAsciiNoSp = qAscii.replace(" ", "")
            asciiIndex[qAsciiNoSp]?.forEach { k -> entries[k]?.let { result.addAll(it) } }
            return result.toList()
        }
    }

    fun lookupPrefix(query: String, limit: Int = 16): List<String> {
        if (query.isEmpty()) return emptyList()
        synchronized(this) {
            val qAscii = NomDictionary.stripDiacritics(query.lowercase()).replace(" ", "")
            if (qAscii.isEmpty()) return emptyList()
            val result = LinkedHashSet<String>()
            for ((asciiKey, originals) in asciiIndex) {
                if (result.size >= limit) break
                // Compare on a space-stripped form because user-dict keys typically carry
                // real spaces (e.g. "bình kiêu") while queries are tested both with and
                // without spaces (e.g. the user typing "bình k" has qAscii = "binhk").
                // Leaving spaces in asciiKey would cause "binh kieu".startsWith("binhk")
                // to fail and the user's learned phrase would never re-surface on a
                // prefix query.
                val keyCompact = asciiKey.replace(" ", "")
                if (keyCompact.length > qAscii.length && keyCompact.startsWith(qAscii)) {
                    for (orig in originals) {
                        entries[orig]?.let { values ->
                            for (v in values) {
                                result.add(v)
                                if (result.size >= limit) break
                            }
                        }
                        if (result.size >= limit) break
                    }
                }
            }
            return result.toList()
        }
    }

    /**
     * Add or replace an entry. [candidates] is pre-split by the caller; empty lists are
     * treated as a removal request.
     */
    fun putEntry(context: Context, key: String, candidates: List<String>) {
        val normalizedKey = key.trim().lowercase()
        if (normalizedKey.isEmpty()) return
        val cleanCandidates = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        synchronized(this) {
            removeIndexEntry(normalizedKey)
            if (cleanCandidates.isEmpty()) {
                entries.remove(normalizedKey)
            } else {
                entries[normalizedKey] = cleanCandidates.toMutableList()
                addIndexEntry(normalizedKey)
            }
            persist(context)
        }
        notifyChanged()
    }

    fun removeEntry(context: Context, key: String) {
        val normalizedKey = key.trim().lowercase()
        if (normalizedKey.isEmpty()) return
        synchronized(this) {
            removeIndexEntry(normalizedKey)
            entries.remove(normalizedKey)
            persist(context)
        }
        notifyChanged()
    }

    fun clearAll(context: Context) {
        synchronized(this) {
            entries.clear()
            asciiIndex.clear()
            persist(context)
        }
        notifyChanged()
    }

    // ============================ Import / export ============================

    /**
     * Import a TSV stream. When [replace] is true, the current dictionary is discarded
     * first; otherwise new keys are added and existing keys are overwritten. Returns the
     * number of entries imported (merged result).
     */
    fun importFromStream(context: Context, input: InputStream, replace: Boolean): Int {
        synchronized(this) {
            if (replace) {
                entries.clear()
                asciiIndex.clear()
            }
            readTsv(input, replace = false)
            persist(context)
        }
        notifyChanged()
        return size()
    }

    fun importFromUri(context: Context, uri: Uri, replace: Boolean): Int {
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Cannot open input stream for $uri" }
            return importFromStream(context, stream, replace)
        }
    }

    /**
     * Write the dictionary out as TSV to the supplied stream.
     */
    fun exportToStream(output: OutputStream) {
        val snapshot = synchronized(this) { entries.map { it.key to it.value.toList() } }
        output.bufferedWriter(Charsets.UTF_8).use { bw ->
            for ((k, values) in snapshot) {
                bw.write(k)
                for (v in values) {
                    bw.write("\t")
                    bw.write(v)
                }
                bw.write("\n")
            }
            bw.flush()
        }
    }

    fun exportToUri(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "w").use { stream ->
            requireNotNull(stream) { "Cannot open output stream for $uri" }
            exportToStream(stream)
        }
    }

    // ============================ Internals ============================

    private fun readTsv(input: InputStream, replace: Boolean) {
        if (replace) {
            entries.clear()
            asciiIndex.clear()
        }
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { br ->
            var line = br.readLine()
            while (line != null) {
                val trimmed = line.trim { it == '\r' || it == '\n' || it == ' ' }
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split('\t')
                    if (parts.size >= 2) {
                        val key = parts[0].trim().lowercase()
                        val values = parts.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                        if (key.isNotEmpty() && values.isNotEmpty()) {
                            removeIndexEntry(key)
                            entries[key] = values.toMutableList()
                            addIndexEntry(key)
                        }
                    }
                }
                line = br.readLine()
            }
        }
    }

    private fun persist(context: Context) {
        val file = storageFile(context)
        try {
            file.outputStream().use { exportToStream(it) }
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist user dictionary", e)
        }
    }

    private fun addIndexEntry(key: String) {
        val ascii = NomDictionary.stripDiacritics(key)
        val list = asciiIndex.getOrPut(ascii) { ArrayList(2) }
        if (!list.contains(key)) list.add(key)
    }

    private fun removeIndexEntry(key: String) {
        val ascii = NomDictionary.stripDiacritics(key)
        asciiIndex[ascii]?.let { list ->
            list.remove(key)
            if (list.isEmpty()) asciiIndex.remove(ascii)
        }
    }
}
