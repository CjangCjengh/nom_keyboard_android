package com.nomkeyboard.app.dict

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * N-gram frequency model for context-aware candidate re-ranking.
 *
 * Records the number of times each ordered sequence of 1..N Nom tokens has been committed
 * by the user. The value of N is configurable via the preference `pref_ngram_n`
 * (default 3). On candidate lookup we return a *score* combining:
 *     - the unigram frequency of the candidate itself, and
 *     - the conditional frequency given the 1..(N-1) most recently committed tokens.
 *
 * The score is then used by [NomInputMethodService] to re-rank the base dictionary
 * candidates: higher score -> earlier in the list.
 *
 * Persistence: entries are written to `ngram.tsv` in the app's private files dir, with
 * each line being `<token1>\t<token2>\t...\t<tokenK>\t<count>`. The file is rewritten
 * lazily (debounced) whenever the model is mutated, to keep I/O off the hot path.
 *
 * Thread safety: all public methods are synchronized; safe to call from the IME thread
 * and from a background exporter.
 */
object NgramModel {
    private const val TAG = "NgramModel"
    private const val FILE_NAME = "ngram.tsv"
    // Hard cap on how many distinct n-grams we keep, to bound disk and memory usage.
    // When exceeded we prune the lowest-count half. 20000 ~= a few hundred KB TSV on disk.
    private const val MAX_ENTRIES = 20000

    // key = "tok1\u0001tok2\u0001..." -> count
    private val counts: HashMap<String, Int> = HashMap(1024)

    // Ring buffer of the most recently committed tokens, used as context for [score].
    // Size = maxN at the time the context was last reset. Never grows beyond maxN.
    private val context: ArrayDeque<String> = ArrayDeque()

    @Volatile
    private var maxN: Int = 3

    @Volatile
    private var loaded = false
    @Volatile
    private var dirty = false

    fun setMaxN(n: Int) {
        synchronized(this) {
            maxN = n.coerceIn(1, 6)
            while (context.size > maxN - 1 && context.isNotEmpty()) context.removeFirst()
        }
    }

    fun currentMaxN(): Int {
        synchronized(this) { return maxN }
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val file = storageFile(context)
            if (file.exists()) {
                try {
                    file.inputStream().use { stream ->
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br ->
                            var line = br.readLine()
                            while (line != null) {
                                val parts = line.split('\t')
                                if (parts.size >= 2) {
                                    val cnt = parts.last().toIntOrNull()
                                    val toks = parts.subList(0, parts.size - 1)
                                    if (cnt != null && cnt > 0 && toks.isNotEmpty() &&
                                        toks.all { it.isNotEmpty() }
                                    ) {
                                        counts[toks.joinToString(SEP)] = cnt
                                    }
                                }
                                line = br.readLine()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "failed to load ngram model", e)
                }
            }
            loaded = true
            Log.i(TAG, "ngram model loaded: size=${counts.size}")
        }
    }

    /**
     * Reset the running context (call when the user starts a fresh composing session or
     * types punctuation that ends a sentence).
     */
    fun resetContext() {
        synchronized(this) { context.clear() }
    }

    /**
     * Record that [token] has been committed following the current context, and slide the
     * context window. All 1..maxN-grams that end at [token] have their count bumped.
     */
    fun observe(ctx: Context, token: String) {
        if (token.isEmpty()) return
        synchronized(this) {
            // tokens participating in n-grams ending at [token]: the current context + token
            val buf = ArrayList<String>(maxN)
            buf.addAll(context)
            buf.add(token)
            val start = maxOf(0, buf.size - maxN)
            for (s in start until buf.size) {
                val key = buf.subList(s, buf.size).joinToString(SEP)
                counts[key] = (counts[key] ?: 0) + 1
            }
            // slide context
            context.addLast(token)
            while (context.size > maxN - 1 && context.isNotEmpty()) context.removeFirst()
            dirty = true
            if (counts.size > MAX_ENTRIES) pruneLocked()
        }
        persistDebounced(ctx)
    }

    /**
     * Return a non-negative score for [candidate] given the current context. Higher score
     * means the candidate is more likely given the recent commit history.
     *
     * Scoring uses a simple linear interpolation over matched n-gram lengths – a 3-gram
     * hit contributes 3x a unigram hit, etc. This is deliberately simple (no Kneser-Ney)
     * because our dictionary is small and the user is already in the loop through the
     * candidate bar.
     */
    fun score(candidate: String): Int {
        if (candidate.isEmpty()) return 0
        synchronized(this) {
            var total = 0
            val buf = ArrayList<String>(maxN)
            buf.addAll(context)
            buf.add(candidate)
            val start = maxOf(0, buf.size - maxN)
            for (s in start until buf.size) {
                val key = buf.subList(s, buf.size).joinToString(SEP)
                val c = counts[key] ?: continue
                val len = buf.size - s
                total += c * len
            }
            return total
        }
    }

    fun clearAll(ctx: Context) {
        synchronized(this) {
            counts.clear()
            context.clear()
            dirty = true
        }
        persistNow(ctx)
    }

    fun size(): Int {
        synchronized(this) { return counts.size }
    }

    // ============================ Import / Export ============================

    /**
     * Export the current n-gram counts as TSV. The on-disk format (`<tok1>\t...\t<count>`)
     * is used verbatim so the exported file can be round-tripped back through [importFromStream]
     * or hand-edited in a spreadsheet. A `# nom-keyboard n-gram export vN` header line is
     * prepended to help future importers detect corruption or older schema variants.
     */
    fun exportToStream(output: java.io.OutputStream) {
        val snapshot = synchronized(this) { counts.toMap() }
        output.bufferedWriter(Charsets.UTF_8).use { bw ->
            bw.write("# nom-keyboard n-gram export v1\n")
            bw.write("# format: token1<TAB>token2<TAB>...<TAB>tokenK<TAB>count\n")
            for ((k, v) in snapshot) {
                bw.write(k.replace(SEP, "\t"))
                bw.write("\t")
                bw.write(v.toString())
                bw.write("\n")
            }
            bw.flush()
        }
    }

    fun exportToUri(ctx: Context, uri: android.net.Uri) {
        // Flush any in-memory changes first so the exported file really matches what the
        // user sees in the app.
        persistNow(ctx)
        ctx.contentResolver.openOutputStream(uri, "w").use { stream ->
            requireNotNull(stream) { "Cannot open output stream for $uri" }
            exportToStream(stream)
        }
    }

    /**
     * Import n-gram counts from a TSV stream. When [replace] is true, the existing
     * in-memory counts are discarded first. Otherwise counts for identical keys are
     * **added** together – this matches the "frequency" semantics: importing a backup
     * from another device should amplify overlapping habits, not clobber them.
     *
     * Lines starting with `#` are treated as comments and skipped. Returns the number of
     * distinct n-gram entries after the merge (i.e. the final size of the model).
     */
    fun importFromStream(ctx: Context, input: java.io.InputStream, replace: Boolean): Int {
        ensureLoaded(ctx)
        synchronized(this) {
            if (replace) {
                counts.clear()
                context.clear()
            }
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    val trimmed = line.trim { it == '\r' || it == '\n' || it == ' ' }
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split('\t')
                        if (parts.size >= 2) {
                            val cnt = parts.last().toIntOrNull()
                            val toks = parts.subList(0, parts.size - 1)
                            if (cnt != null && cnt > 0 && toks.isNotEmpty() &&
                                toks.all { it.isNotEmpty() }
                            ) {
                                val key = toks.joinToString(SEP)
                                // Merge: add counts for matching keys, insert otherwise.
                                counts[key] = (counts[key] ?: 0) + cnt
                            }
                        }
                    }
                    line = br.readLine()
                }
            }
            if (counts.size > MAX_ENTRIES) pruneLocked()
            dirty = true
        }
        persistNow(ctx)
        return synchronized(this) { counts.size }
    }

    fun importFromUri(ctx: Context, uri: android.net.Uri, replace: Boolean): Int {
        ctx.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Cannot open input stream for $uri" }
            return importFromStream(ctx, stream, replace)
        }
    }

    // ============================ Persistence ============================

    private fun storageFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    // Debounced writer: schedule one write at most every ~2s worth of observes by simply
    // calling [persistNow] once every N observes. We don't need true async debouncing as
    // the file is small and writes are cheap.
    private var observeSinceFlush = 0
    private fun persistDebounced(ctx: Context) {
        synchronized(this) {
            observeSinceFlush++
            if (observeSinceFlush < 20) return
            observeSinceFlush = 0
        }
        persistNow(ctx)
    }

    fun persistNow(ctx: Context) {
        val snapshot = synchronized(this) {
            if (!dirty) return
            dirty = false
            counts.toMap()
        }
        try {
            storageFile(ctx).outputStream().bufferedWriter(Charsets.UTF_8).use { bw ->
                for ((k, v) in snapshot) {
                    bw.write(k.replace(SEP, "\t"))
                    bw.write("\t")
                    bw.write(v.toString())
                    bw.write("\n")
                }
                bw.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist ngram", e)
        }
    }

    private fun pruneLocked() {
        // Keep top half by count (ties broken arbitrarily).
        val keep = counts.entries.sortedByDescending { it.value }.take(MAX_ENTRIES / 2)
        counts.clear()
        for (e in keep) counts[e.key] = e.value
    }

    private const val SEP = "\u0001"
}
