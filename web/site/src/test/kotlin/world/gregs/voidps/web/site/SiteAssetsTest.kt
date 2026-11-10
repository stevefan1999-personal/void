package world.gregs.voidps.web.site

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteAssetsTest {
    @TempDir
    lateinit var directory: File

    private val fontUrl = "https://fonts.example/synthetic.woff2"
    private val font = byteArrayOf(0, 1, 127, -128, -1)
    private val script = "window.synthetic = true;\r\n".toByteArray()
    private val license = "Synthetic redistribution license\n".toByteArray()
    private val css = "@font-face { src: url($fontUrl) format('woff2'); }\n"
    private val frozenCss = css.replace(fontUrl, "/vendor/font.woff2")
    private val relativeCss = css.replace(fontUrl, "font.woff2")
    private val assets = listOf(
        asset("script.js", script),
        asset("font.woff2", font, fontUrl),
        asset("fonts.css", css.toByteArray()).copy(deployedSha256 = digest(frozenCss.toByteArray())),
        asset("LICENSE.txt", license),
    )
    private val contents = mapOf("script.js" to script, "font.woff2" to font, "fonts.css" to css.toByteArray(), "LICENSE.txt" to license)

    private fun asset(name: String, bytes: ByteArray, source: String = "https://assets.example/$name") = SiteAssets.Asset(source, "/vendor/$name", digest(bytes))

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun source(frozen: Boolean = false): File = File(directory, "input").apply {
        mkdirs()
        contents.forEach { (name, bytes) -> File(this, name).writeBytes(if (frozen && name == "fonts.css") frozenCss.toByteArray() else bytes) }
    }

    private fun offlineInstaller() = SiteAssets.Installer(assets) { error("Offline assets must not fall back to downloads") }

    private fun downloadInstaller() = SiteAssets.Installer(assets) { url -> contents.getValue(assets.single { it.source == url }.name) }

    @Test
    fun `Pinned downloads retain scripts fonts and licenses while CSS uses sibling files`() {
        val requests = mutableListOf<String>()
        val installer = SiteAssets.Installer(assets) { url ->
            requests.add(url)
            contents.getValue(assets.single { it.source == url }.name)
        }
        val output = File(directory, "output")
        installer.install(output)

        assertEquals(assets.sortedBy { it.local }.map { it.source }, requests)
        assertContentEquals(script, File(output, "vendor/script.js").readBytes())
        assertContentEquals(font, File(output, "vendor/font.woff2").readBytes())
        assertContentEquals(license, File(output, "vendor/LICENSE.txt").readBytes())
        assertEquals(relativeCss, File(output, "vendor/fonts.css").readText())
        val published = Json.decodeFromString<List<SiteAssets.Asset>>(File(output, "vendor/sources.json").readText())
        Json.parseToJsonElement(File(output, "vendor/sources.json").readText()).jsonArray.forEach { assertTrue("deployedSha256" in it.jsonObject) }
        assertEquals(assets.sortedBy { it.local }.map { it.local }, published.map { it.local })
        assertEquals(assets.single { it.name == "fonts.css" }.sha256, published.single { it.name == "fonts.css" }.sha256)
        assertEquals(digest(relativeCss.toByteArray()), published.single { it.name == "fonts.css" }.deployedSha256)
    }

    @Test
    fun `Approved frozen CSS is restored and verified without changing offline inputs`() {
        val source = source(frozen = true)
        File(source, "sources.json").writeText("[{\"source\":\"https://untrusted.example\",\"local\":\"/vendor/extra.js\"}]")
        File(source, "extra.js").writeText("untrusted")
        val output = File(directory, "output")

        offlineInstaller().install(output, source)

        assertEquals(relativeCss, File(output, "vendor/fonts.css").readText())
        assertEquals(frozenCss, File(source, "fonts.css").readText())
        assertContentEquals(script, File(source, "script.js").readBytes())
        assertContentEquals(font, File(source, "font.woff2").readBytes())
        assertFalse(File(output, "vendor/extra.js").exists())
    }

    @Test
    fun `Raw offline CSS is accepted without downloads`() {
        val output = File(directory, "output")
        offlineInstaller().install(output, source())
        assertEquals(relativeCss, File(output, "vendor/fonts.css").readText())
    }

    @Test
    fun `Staged assets can be reused offline with identical bytes and metadata`() {
        val first = File(directory, "first")
        downloadInstaller().install(first)
        val input = File(first, "vendor")
        val original = input.listFiles()!!.associate { it.name to it.readBytes() }
        val second = File(directory, "second")

        offlineInstaller().install(second, input)

        assertEquals(original.keys, File(second, "vendor").list()!!.toSet())
        original.forEach { (name, bytes) ->
            assertContentEquals(bytes, File(second, "vendor/$name").readBytes())
            assertContentEquals(bytes, File(input, name).readBytes())
        }
    }

    @Test
    fun `Changed staged CSS is rejected after normalizing its font references`() {
        val first = File(directory, "first")
        downloadInstaller().install(first)
        val input = File(first, "vendor")
        val corrupted = relativeCss + "/* changed */\n"
        File(input, "fonts.css").writeText(corrupted)
        val output = File(directory, "output")

        assertThrows<IllegalArgumentException> { offlineInstaller().install(output, input) }

        assertEquals(corrupted, File(input, "fonts.css").readText())
        assertFalse(File(output, "vendor").exists())
    }

    @Test
    fun `Corrupt offline bytes fail before replacing existing output`() {
        val source = source()
        File(source, "script.js").writeText("corrupt")
        val output = File(directory, "output")
        val previous = File(output, "vendor/script.js").apply {
            parentFile.mkdirs()
            writeText("previous")
        }

        val failure = assertThrows<IllegalArgumentException> { offlineInstaller().install(output, source) }

        assertTrue(failure.message.orEmpty().contains("script.js"))
        assertEquals("previous", previous.readText())
        assertEquals(listOf("script.js"), previous.parentFile.list()!!.toList())
    }

    @Test
    fun `Missing offline bytes fail without downloading or publishing partial assets`() {
        val source = source()
        File(source, "font.woff2").delete()
        val output = File(directory, "output")

        assertThrows<FileNotFoundException> { offlineInstaller().install(output, source) }

        assertFalse(File(output, "vendor").exists())
    }

    @Test
    fun `Unexpected downloaded bytes fail before publishing assets`() {
        val output = File(directory, "output")
        val installer = SiteAssets.Installer(assets) { "corrupt".toByteArray() }

        assertThrows<IllegalArgumentException> { installer.install(output) }

        assertFalse(File(output, "vendor").exists())
    }

    @Test
    fun `An unknown font URL is rejected even when CSS matches its checksum`() {
        val unknownCss = css.replace(fontUrl, "https://fonts.example/unpinned.woff2").toByteArray()
        val manifest = assets.map { if (it.name == "fonts.css") it.copy(sha256 = digest(unknownCss)) else it }
        val installer = SiteAssets.Installer(manifest) { url -> if (url.endsWith("fonts.css")) unknownCss else contents.getValue(assets.single { it.source == url }.name) }
        val output = File(directory, "output")

        val failure = assertThrows<IllegalArgumentException> { installer.install(output) }

        assertTrue(failure.message.orEmpty().contains("Unpinned public font URL"))
        assertFalse(File(output, "vendor").exists())
    }

    @Test
    fun `Invalid paths hashes URLs and duplicate pins fail before downloading`() {
        val original = assets.first()
        val invalid = listOf(
            listOf(original.copy(local = "/vendor/../escape")),
            listOf(original.copy(local = "/vendor/sources.json")),
            listOf(original.copy(sha256 = "invalid")),
            listOf(original.copy(deployedSha256 = "invalid")),
            listOf(original.copy(source = "http://assets.example/script.js")),
            listOf(original, original),
        )
        invalid.forEach { manifest ->
            assertThrows<IllegalArgumentException> { SiteAssets.Installer(manifest) { error("Invalid pins reached the downloader") }.install(directory) }
        }
        assertFalse(File(directory, "vendor").exists())
        assertFalse(File(directory, "escape").exists())
    }

    @Test
    fun `Repeated staging produces the same bytes and metadata`() {
        val input = source()
        val first = File(directory, "first")
        val second = File(directory, "second")
        offlineInstaller().install(first, input)
        offlineInstaller().install(second, input)
        offlineInstaller().install(first, input)

        assertEquals(File(first, "vendor").list()!!.sorted(), File(second, "vendor").list()!!.sorted())
        File(first, "vendor").listFiles()!!.forEach { file -> assertContentEquals(file.readBytes(), File(second, "vendor/${file.name}").readBytes()) }
    }

    @Test
    fun `An offline input cannot also be the vendor output`() {
        val input = source(frozen = true)
        val output = File(directory, "output")
        output.mkdirs()
        input.renameTo(File(output, "vendor"))
        assertThrows<IllegalArgumentException> { offlineInstaller().install(output, File(output, "vendor")) }
        assertEquals(frozenCss, File(output, "vendor/fonts.css").readText())
    }

    @Test
    fun `The committed manifest pins all public dependencies and their licenses`() {
        val manifest = requireNotNull(javaClass.getResourceAsStream("/site-assets.json")).bufferedReader().use { Json.decodeFromString<List<SiteAssets.Asset>>(it.readText()) }
        assertEquals(28, manifest.size)
        assertEquals(22, manifest.count { it.name.endsWith(".woff2") })
        assertEquals(listOf("ALPINE-LICENSE.md", "CINZEL-OFL.txt", "INTER-TIGHT-OFL.txt", "JETBRAINS-MONO-OFL.txt"), manifest.filter { it.name.endsWith(".txt") || it.name.endsWith(".md") }.map { it.name }.sorted())
        assertEquals(listOf("fonts.css"), manifest.filter { it.sha256 != it.deployedSha256 }.map { it.name })
        SiteAssets.Installer(manifest) { error("Manifest validation must not download assets") }
    }
}
