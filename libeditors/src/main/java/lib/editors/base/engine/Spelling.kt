package lib.editors.base.engine

import android.util.Log
import lib.toolkit.base.managers.utils.EditorDictionaries
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Answers sdkjs' spelling requests (see [EditorDocument.checkSpelling]). */
fun interface SpellChecker {
    /** The requests ([{type, usrWords, usrLang, ...}]) with usrCorrect or usrSuggest added, as JSON. */
    fun answer(requests: String): String
}

/** Hunspell with one dictionary (see [EditorEngine.createSpeller]). */
interface Speller {
    fun spell(words: Array<String>): BooleanArray
    fun suggest(word: String): List<String>
    fun add(word: String)
    fun close()
}

/**
 * Hunspell dictionaries from Euro-Office's dictionaries repository. English comes with the app;
 * other languages are downloaded when needed, from the commit the MobileEditors checkout pinned
 * when the app was built (index.json), so they are always the ones it was built with. Each file
 * is checked against the size and SHA-256 hash index.json has for it.
 *
 * A dictionary is named after its folder in the repository ("de_DE"); sdkjs asks by LCID.
 */
class Dictionaries(runtimeDir: File, private val downloadDir: File) {

    private val bundledDir = File(runtimeDir, "dictionaries")

    private class Index(val commit: String, val baseUrl: String, val languages: Map<String, List<Int>>, val files: Map<String, Checksum>)

    // A file's size and SHA-256 hash (hex), by its name ("de_DE.dic")
    private class Checksum(val size: Long, val sha256: String)

    private val index: Index? by lazy {
        runCatching {
            val json = JSONObject(File(bundledDir, "index.json").readText())
            val languages = json.getJSONObject("languages")
            val files = json.optJSONObject("files") ?: JSONObject()
            Index(
                json.getString("commit"), json.getString("url"),
                languages.keys().asSequence().associateWith { name ->
                    languages.getJSONArray(name).let { codes -> (0 until codes.length()).map { codes.getInt(it) } }
                },
                files.keys().asSequence().associateWith { file ->
                    files.getJSONObject(file).let { Checksum(it.getLong("size"), it.getString("sha256")) }
                }
            )
        }.onFailure { Log.e(TAG, "no dictionary index", it) }.getOrNull()
    }

    /** The dictionary for a language (LCID), installed or not; null if there is none. */
    fun nameFor(lcid: Int): String? = index?.languages?.entries?.firstOrNull { lcid in it.value }?.key

    /** The dictionary for the device's language ("de_DE" for German in Germany, else another German one). */
    fun forLocale(locale: Locale = Locale.getDefault()): String? {
        val names = index?.languages?.keys ?: return null
        val tag = locale.language + "_" + locale.country
        return names.firstOrNull { it == tag } ?: names.sorted().firstOrNull { it == locale.language || it.startsWith(locale.language + "_") }
    }

    /** The .aff and .dic files of an installed dictionary. */
    fun files(name: String): Pair<File, File>? {
        val bundled = File(bundledDir, name)
        if (File(bundled, "$name.dic").exists()) return File(bundled, "$name.aff") to File(bundled, "$name.dic")
        val downloaded = File(downloadDir, name)
        val commit = File(downloaded, COMMIT).takeIf { it.exists() }?.readText()
        if (commit == null || commit != index?.commit) return null
        return File(downloaded, "$name.aff") to File(downloaded, "$name.dic")
    }

    /** The dictionaries there are, installed or not, with their language (their first LCID). */
    fun all(): Map<String, Int> = index?.languages.orEmpty().filterValues { it.isNotEmpty() }.mapValues { it.value.first() }

    /** The installed dictionaries. */
    fun installed(): List<String> = index?.languages?.keys.orEmpty().filter { files(it) != null }

    /** The languages (LCIDs) of the installed dictionaries. */
    fun installedLanguages(): List<Int> =
        index?.languages.orEmpty().filterKeys { files(it) != null }.values.flatten()

    /**
     * Downloads a dictionary; blocks, so call it on an IO thread. [onProgress] gets how much of it
     * came (0 to 1, by whole percents) if its size is known. False if that failed, or a file isn't
     * the one index.json has (its size, its hash).
     */
    fun download(name: String, onProgress: (Float) -> Unit = {}): Boolean {
        val index = index ?: return false
        if (name !in index.languages) return false
        val checksums = EXTENSIONS.map { index.files["$name.$it"] ?: return false }
        val target = File(downloadDir, name)
        val partial = File(downloadDir, "$name.partial").apply {
            deleteRecursively()
            mkdirs()
        }
        val connections = mutableListOf<HttpURLConnection>()
        return runCatching {
            // Both first, for their sizes. Compressed, and decompressed here (asking for it turns
            // HttpURLConnection's own off): the progress goes by the bytes that come.
            for (extension in EXTENSIONS) {
                val connection = URL("${index.baseUrl}/${index.commit}/$name/$name.$extension").openConnection() as HttpURLConnection
                connections += connection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept-Encoding", "gzip")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            }
            val total = connections.sumOf { it.contentLengthLong }.takeIf { connections.all { it.contentLengthLong > 0 } }
            var received = 0L
            var percent = -1
            connections.forEachIndexed { i, connection ->
                val checksum = checksums[i]
                // Compressed, it can't be much bigger than the file
                val maxReceived = checksum.size + checksum.size / 100 + 1024
                var fileReceived = 0L
                val counted = object : FilterInputStream(connection.inputStream) {
                    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it) }
                    private fun count(bytes: Int) {
                        fileReceived += bytes
                        if (fileReceived > maxReceived) error("more than $maxReceived bytes")
                        received += bytes
                        val now = if (total != null) (received * 100 / total).toInt().coerceAtMost(100) else return
                        if (now != percent) {
                            percent = now
                            onProgress(now / 100f)
                        }
                    }
                }
                val input = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(counted) else counted
                input.use { File(partial, "$name.${EXTENSIONS[i]}").outputStream().use { output -> copyChecked(it, output, checksum) } }
            }
            File(partial, COMMIT).writeText(index.commit)
            target.deleteRecursively()
            check(partial.renameTo(target))
            true
        }.onFailure {
            Log.w(TAG, "can't download the dictionary $name", it)
            partial.deleteRecursively()
        }.also {
            connections.forEach { it.disconnect() }
        }.getOrDefault(false)
    }

    // Stops at more than its size; then the size and the hash have to match
    private fun copyChecked(input: InputStream, output: OutputStream, checksum: Checksum) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            if (size > checksum.size) error("more than ${checksum.size} bytes")
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        if (size != checksum.size) error("$size bytes, not ${checksum.size}")
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha256.equals(checksum.sha256, ignoreCase = true)) error("SHA-256 $sha256, not ${checksum.sha256}")
    }

    private companion object {
        const val TAG = "Dictionaries"
        const val COMMIT = "commit"
        const val TIMEOUT_MS = 20_000
        val EXTENSIONS = listOf("aff", "dic")
    }
}

/**
 * Checks words with the installed dictionaries and the user's own words. Not thread-safe: use it
 * on the engine's thread ([EditorEngine.dispatcher]).
 */
class SpellService(
    private val dictionaries: Dictionaries,
    private val userWordsFile: File,
    private val createSpeller: (aff: File, dic: File) -> Speller? = EditorEngine::createSpeller
) : SpellChecker {

    // Loaded on first use; null for a language without a dictionary (its words count as correct)
    private val spellers = mutableMapOf<String, Speller?>()
    private val userWords: MutableSet<String> by lazy {
        runCatching { userWordsFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList()).toMutableSet()
    }

    override fun answer(requests: String): String {
        val array = JSONArray(requests)
        for (i in 0 until array.length()) {
            val request = array.getJSONObject(i)
            val words = request.getJSONArray("usrWords")
            val languages = request.getJSONArray("usrLang")
            if (request.optString("type") == "suggest") {
                val suggestions = JSONArray()
                for (w in 0 until words.length()) {
                    val speller = speller(languages.optInt(w))
                    suggestions.put(JSONArray(speller?.suggest(words.getString(w)).orEmpty().take(MAX_SUGGESTIONS)))
                }
                request.put("usrSuggest", suggestions)
            } else {
                // Words of the same language are checked together
                val correct = BooleanArray(words.length()) { true }
                (0 until words.length()).groupBy { languages.optInt(it) }.forEach { (lcid, indices) ->
                    val speller = speller(lcid) ?: return@forEach
                    val checked = speller.spell(Array(indices.size) { words.getString(indices[it]) })
                    indices.forEachIndexed { k, index -> correct[index] = checked[k] || words.getString(index) in userWords }
                }
                request.put("usrCorrect", JSONArray(correct.toList()))
            }
        }
        return array.toString()
    }

    /** A word of the user's: correct in all languages from now on. */
    fun addWord(word: String) {
        if (!userWords.add(word)) return
        runCatching { userWordsFile.parentFile?.mkdirs(); userWordsFile.appendText(word + "\n") }
        spellers.values.forEach { it?.add(word) }
    }

    /** A dictionary was downloaded: languages without one are looked up again. */
    fun reload() {
        spellers.entries.removeAll { it.value == null }
    }

    private fun speller(lcid: Int): Speller? {
        val name = dictionaries.nameFor(lcid) ?: return null
        // Removed meanwhile (the app's settings): its words count as correct again
        if (spellers[name] != null && dictionaries.files(name) == null) spellers.remove(name)?.close()
        return spellers.getOrPut(name) {
            dictionaries.files(name)?.let { (aff, dic) -> createSpeller(aff, dic) }
                ?.also { speller -> userWords.forEach { speller.add(it) } }
        }
    }

    private companion object {
        const val MAX_SUGGESTIONS = 5
    }
}

/** The app's dictionaries and spell checker, shared by all editors. */
object EditorSpelling {

    @Volatile
    private var dictionaries: Dictionaries? = null
    private var service: SpellService? = null

    fun dictionaries(context: android.content.Context): Dictionaries = dictionaries ?: synchronized(this) {
        dictionaries ?: Dictionaries(EditorEngine.runtimeDir(context), EditorDictionaries.of(context).dir).also { dictionaries = it }
    }

    /** Call on the engine's thread ([EditorEngine.dispatcher]). */
    fun service(context: android.content.Context): SpellService = service
        ?: SpellService(dictionaries(context), File(EditorDictionaries.of(context).dir, "user-words.txt")).also { service = it }
}
