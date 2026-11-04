package lib.editors.base.engine

import lib.toolkit.base.managers.utils.EditorDictionaries
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

class SpellingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var runtime: File
    private lateinit var downloads: File
    private lateinit var dictionaries: Dictionaries

    @Before
    fun setUp() {
        runtime = folder.newFolder("runtime")
        downloads = folder.newFolder("downloads")
        File(runtime, "dictionaries/en_US").mkdirs()
        File(runtime, "dictionaries/en_US/en_US.aff").writeText("SET UTF-8")
        File(runtime, "dictionaries/en_US/en_US.dic").writeText("0")
        File(runtime, "dictionaries/index.json").writeText(
            """{"commit": "abc", "url": "https://example.org/dictionaries",
               "languages": {"en_US": [1033], "de_DE": [1031], "de_AT": [3079], "fr_FR": [1036]}}"""
        )
        dictionaries = Dictionaries(runtime, downloads)
    }

    // A downloaded dictionary, of the given commit
    private fun download(name: String, commit: String) {
        File(downloads, name).mkdirs()
        File(downloads, "$name/$name.aff").writeText("SET UTF-8")
        File(downloads, "$name/$name.dic").writeText("0")
        File(downloads, "$name/commit").writeText(commit)
    }

    @Test
    fun `finds dictionaries by language`() {
        assertEquals("de_DE", dictionaries.nameFor(1031))
        assertNull(dictionaries.nameFor(1049))
    }

    @Test
    fun `chooses the dictionary of the device's language`() {
        assertEquals("de_AT", dictionaries.forLocale(Locale("de", "AT")))
        assertEquals("de_AT", dictionaries.forLocale(Locale("de", "CH")))
        assertEquals("fr_FR", dictionaries.forLocale(Locale("fr", "BE")))
        assertNull(dictionaries.forLocale(Locale("ja", "JP")))
    }

    @Test
    fun `knows bundled and downloaded dictionaries of the app's commit only`() {
        assertEquals(listOf(1033), dictionaries.installedLanguages())
        download("de_DE", "abc")
        download("fr_FR", "older")
        assertEquals(setOf(1033, 1031), dictionaries.installedLanguages().toSet())
        assertNull(dictionaries.files("fr_FR"))
    }

    @Test
    fun `lists the dictionaries with their languages`() {
        assertEquals(mapOf("en_US" to 1033, "de_DE" to 1031, "de_AT" to 3079, "fr_FR" to 1036), dictionaries.all())
        download("fr_FR", "abc")
        assertEquals(setOf("en_US", "fr_FR"), dictionaries.installed().toSet())
    }

    @Test
    fun `lists and removes downloaded dictionaries, of older versions too`() {
        download("de_DE", "abc")
        download("fr_FR", "older")
        File(downloads, "it_IT.partial").mkdirs()
        val downloaded = EditorDictionaries(downloads)
        assertEquals(setOf("de_DE", "fr_FR"), downloaded.downloaded().toSet())
        // The files and the commit they are of
        assertEquals(13L, downloaded.size("de_DE"))
        assertFalse(downloaded.remove("en_US"))
        assertTrue(downloaded.remove("de_DE"))
        assertEquals(listOf("fr_FR"), downloaded.downloaded())
        assertEquals(listOf(1033), dictionaries.installedLanguages())
    }

    @Test
    fun `forgets a removed dictionary's words`() {
        download("de_DE", "abc")
        val spellers = mutableListOf<FakeSpeller>()
        val service = SpellService(dictionaries, File(folder.root, "user.txt")) { _, _ -> FakeSpeller(setOf("Wort")).also { spellers += it } }
        val request = """[{"type": "spell", "usrWords": ["Wrot"], "usrLang": [1031]}]"""
        assertEquals("[false]", JSONArray(service.answer(request)).getJSONObject(0).getJSONArray("usrCorrect").toString())
        // Removed in the app's settings
        EditorDictionaries(downloads).remove("de_DE")
        assertEquals("[true]", JSONArray(service.answer(request)).getJSONObject(0).getJSONArray("usrCorrect").toString())
        assertTrue(spellers.single().closed)
    }

    @Test
    fun `names languages by tag or dictionary`() {
        assertEquals("German (Germany)", EditorDictionaries.languageName("de-DE", Locale.ENGLISH))
        assertEquals("German (Austria)", EditorDictionaries.languageName("de_AT", Locale.ENGLISH))
        assertEquals("Allemand (Allemagne)", EditorDictionaries.languageName("de_DE", Locale.FRENCH))
        assertEquals("Serbian (Latin, Serbia)", EditorDictionaries.languageName("sr_Latn_RS", Locale.ENGLISH))
    }

    // An HTTP server for [files] (gzipped, by path), one request at a time
    private fun serve(files: Map<String, String>): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val request = it.getInputStream().bufferedReader()
                    val path = request.readLine().split(" ")[1]
                    while (!request.readLine().isNullOrEmpty()) Unit
                    val output = it.getOutputStream()
                    val text = files[path]
                    if (text == null) {
                        output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { gzip -> gzip.write(text.toByteArray()) } }.toByteArray()
                        output.write("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: ${gzipped.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(gzipped)
                    }
                    output.flush()
                }
            }
        }
        return server
    }

    // index.json for dictionaries at [port], with the sizes and hashes of [checked] (by path)
    private fun writeIndex(port: Int, checked: Map<String, String>) {
        val files = checked.entries.joinToString(", ") { (path, text) ->
            val sha256 = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            "\"${path.substringAfterLast('/')}\": {\"size\": ${text.toByteArray().size}, \"sha256\": \"$sha256\"}"
        }
        File(runtime, "dictionaries/index.json").writeText(
            """{"commit": "abc", "url": "http://127.0.0.1:$port",
               "languages": {"en_US": [1033], "de_DE": [1031], "fr_FR": [1036]}, "files": {$files}}"""
        )
    }

    @Test
    fun `downloads gzipped dictionaries and reports the progress`() {
        val random = Random(1)
        val words = List(20_000) { (1..8).map { 'a' + random.nextInt(26) }.joinToString("") }
        val files = mapOf("/abc/de_DE/de_DE.aff" to "SET UTF-8\n", "/abc/de_DE/de_DE.dic" to "${words.size}\n" + words.joinToString("\n"))
        val server = serve(files)
        try {
            writeIndex(server.localPort, files + mapOf("/abc/fr_FR/fr_FR.aff" to "", "/abc/fr_FR/fr_FR.dic" to ""))
            val dictionaries = Dictionaries(runtime, downloads)
            val progress = mutableListOf<Float>()
            assertTrue(dictionaries.download("de_DE") { progress += it })
            assertEquals(files["/abc/de_DE/de_DE.dic"], File(downloads, "de_DE/de_DE.dic").readText())
            assertEquals(setOf(1033, 1031), dictionaries.installedLanguages().toSet())
            assertTrue("steps: $progress", progress.size > 2 && progress.zipWithNext().all { (a, b) -> a < b })
            assertEquals(1f, progress.last())
            // Not there: nothing left behind
            assertFalse(dictionaries.download("fr_FR"))
            assertNull(dictionaries.files("fr_FR"))
            assertFalse(File(downloads, "fr_FR.partial").exists())
        } finally {
            server.close()
        }
    }

    @Test
    fun `downloads only files of the index's size and hash`() {
        val good = mapOf("/abc/de_DE/de_DE.aff" to "SET UTF-8\n", "/abc/de_DE/de_DE.dic" to "1\nWort")
        // Another file, of the same size or bigger
        for (dic in listOf("1\nWurt", "1\nWort\n" + "x".repeat(100_000))) {
            val server = serve(good + ("/abc/de_DE/de_DE.dic" to dic))
            try {
                writeIndex(server.localPort, good)
                val dictionaries = Dictionaries(runtime, downloads)
                assertFalse(dictionaries.download("de_DE"))
                assertNull(dictionaries.files("de_DE"))
                assertFalse(File(downloads, "de_DE").exists())
                assertFalse(File(downloads, "de_DE.partial").exists())
            } finally {
                server.close()
            }
        }
        // Not in the index: not downloaded
        val server = serve(good)
        try {
            writeIndex(server.localPort, emptyMap())
            assertFalse(Dictionaries(runtime, downloads).download("de_DE"))
        } finally {
            server.close()
        }
    }

    private class FakeSpeller(private val words: Set<String>) : Speller {
        val added = mutableListOf<String>()
        override fun spell(words: Array<String>) = BooleanArray(words.size) { words[it] in this.words || words[it] in added }
        override fun suggest(word: String) = listOf(word.reversed(), word.uppercase())
        override fun add(word: String) {
            added += word
        }
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `answers spell and suggest requests`() {
        val speller = FakeSpeller(setOf("good", "words"))
        val service = SpellService(dictionaries, File(folder.root, "user.txt")) { _, _ -> speller }
        val answer = JSONArray(
            service.answer(
                """[{"type": "spell", "usrWords": ["good", "bda", "words", "Wort"], "usrLang": [1033, 1033, 1033, 1049]},
                    {"type": "suggest", "usrWords": ["bda"], "usrLang": [1033]}]"""
            )
        )
        // Languages without a dictionary count as correct
        assertEquals("[true,false,true,true]", answer.getJSONObject(0).getJSONArray("usrCorrect").toString())
        assertEquals("[[\"adb\",\"BDA\"]]", answer.getJSONObject(1).getJSONArray("usrSuggest").toString())
    }

    @Test
    fun `keeps the user's words`() {
        val userWords = File(folder.root, "user.txt")
        val speller = FakeSpeller(emptySet())
        SpellService(dictionaries, userWords) { _, _ -> speller }.addWord("Euro-Office")
        assertEquals("Euro-Office\n", userWords.readText())
        // A new service (the next start) knows it too
        val answer = SpellService(dictionaries, userWords) { _, _ -> FakeSpeller(emptySet()) }
            .answer("""[{"type": "spell", "usrWords": ["Euro-Office"], "usrLang": [1033]}]""")
        assertEquals("[true]", JSONArray(answer).getJSONObject(0).getJSONArray("usrCorrect").toString())
    }
}
