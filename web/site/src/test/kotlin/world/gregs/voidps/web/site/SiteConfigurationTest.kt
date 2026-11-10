package world.gregs.voidps.web.site

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import world.gregs.voidps.engine.data.Settings
import java.io.File
import java.net.URI
import java.util.Properties
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteConfigurationTest {

    @TempDir
    lateinit var directory: File

    private val worldJson = """[{"number":73,"region":"Test region","mode":"Test mode","name":"Synthetic world","web":"https://world.example"}]"""

    @AfterEach
    fun clear() {
        Settings.clear()
    }

    private fun configuration(vararg overrides: Pair<String, String>): SiteConfiguration {
        val worlds = File(directory, "input-worlds.json").apply { writeText(worldJson) }
        val properties = Properties().apply {
            setProperty("web.site.worlds", worlds.path)
            setProperty("web.site.version", "v-test-fixed")
            setProperty("web.server.pages", File(directory, "pages").path)
            overrides.forEach { (key, value) -> setProperty(key, value) }
        }
        val file = File(directory, "site.properties")
        file.outputStream().use { properties.store(it, null) }
        return SiteConfiguration.load(file.path)
    }

    @Test
    fun `Configured worlds render the picker and publish the same JSON after static copying`() {
        val configuration = configuration()
        Site.configure(configuration)
        val page = Play.page()
        configuration.output.mkdirs()
        File(configuration.output, "play.html").writeText(page)

        Site.publish(configuration)

        val published = File(configuration.output, "play.html").readText()
        assertTrue(published.contains("Synthetic world"))
        assertTrue(published.contains("select(73)"))
        assertFalse(published.contains("select(1)"))
        assertTrue(published.contains("v-test-fixed"))
        assertEquals(worldJson, File(configuration.output, "worlds.json").readText())
        assertEquals(listOf(73), SiteConfiguration.decodeWorlds(File(configuration.output, "worlds.json").readText()).map { it.number })
        assertContentEquals(File("web/site/src/main/resources/static/js/void.js").readBytes(), File(configuration.output, "js/void.js").readBytes())
    }

    @Test
    fun `Configured worlds are loaded once and explicit version renders stable output`() {
        val configuration = configuration()
        File(directory, "input-worlds.json").writeText("[]")
        Site.configure(configuration)
        val first = Play.page()
        val second = Play.page()

        Site.publish(configuration)

        assertEquals(first, second)
        assertTrue(first.contains("Synthetic world"))
        assertEquals("v-test-fixed", Site.version)
        assertEquals(worldJson, File(configuration.output, "worlds.json").readText())
    }

    @Test
    fun `Source Markdown links resolve to generated documentation pages`() {
        val configuration = configuration()
        Site.configure(configuration)
        val docs = File(directory, "docs").apply { mkdirs() }
        File(docs, "First.md").writeText("# First\n\n[Browser setup](Second.md#setup)\n")
        File(docs, "Second.md").writeText("# Setup\n")

        Docs.generate(docs, configuration.output)

        val page = File(configuration.output, "docs/first.html").readText()
        assertTrue(page.contains("href=\"second.html#setup\""))
        assertFalse(page.contains("href=\"Second.md#setup\""))
    }

    @Test
    fun `JVM property selects an explicit properties file`() {
        val configuration = configuration()
        val previous = System.getProperty("void.site.properties")
        try {
            System.setProperty("void.site.properties", File(directory, "site.properties").path)
            assertEquals(configuration.worlds, SiteConfiguration.load().worlds)
        } finally {
            if (previous == null) System.clearProperty("void.site.properties") else System.setProperty("void.site.properties", previous)
        }
    }

    @Test
    fun `Missing explicit properties and worlds fail instead of using defaults`() {
        assertThrows<IllegalArgumentException> { SiteConfiguration.load(File(directory, "missing.properties").path) }
        assertThrows<IllegalArgumentException> { SiteConfiguration.load(" ") }
        assertThrows<IllegalArgumentException> { configuration("web.site.worlds" to File(directory, "missing.json").path) }
        assertThrows<IllegalArgumentException> { configuration("web.site.worlds" to " ") }
    }

    @Test
    fun `Malformed and ambiguous worlds are rejected`() {
        val invalid = File(directory, "invalid.json").apply { writeText("not json") }
        assertThrows<IllegalArgumentException> { configuration("web.site.worlds" to invalid.path) }
        val incomplete = File(directory, "incomplete.json").apply { writeText("""[{"number":73}]""") }
        assertThrows<IllegalArgumentException> { configuration("web.site.worlds" to incomplete.path) }
        val entry = worldJson.removeSurrounding("[", "]")
        val duplicate = File(directory, "duplicate.json").apply { writeText("[$entry,$entry]") }
        assertThrows<IllegalArgumentException> { configuration("web.site.worlds" to duplicate.path) }
    }

    @Test
    fun `Invalid explicit output version and asset options fail early`() {
        assertThrows<IllegalArgumentException> { configuration("web.server.pages" to "") }
        val outputFile = File(directory, "not-a-directory").apply { writeText("file") }
        assertThrows<IllegalArgumentException> { configuration("web.server.pages" to outputFile.path) }
        assertThrows<IllegalArgumentException> { configuration("web.site.version" to " ") }
        assertThrows<IllegalArgumentException> { configuration("web.site.assets" to "yes") }
        assertThrows<IllegalArgumentException> { configuration("web.site.assets.source" to directory.path) }
        assertThrows<IllegalArgumentException> { configuration("web.site.assets" to "true", "web.site.assets.source" to File(directory, "missing").path) }
    }

    @Test
    fun `Ordinary rendering retains CDN assets and self-hosted pages use relative URLs`() {
        Site.configure(configuration())
        assertTrue(voidPage("Default") {}.contains("https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js"))

        Site.configure(configuration("web.site.assets" to "true"))
        val root = voidPage("Root") {}
        val docs = File(directory, "docs-source").apply { mkdirs() }
        File(docs, "Guide.md").writeText("# Synthetic guide\n\nA deterministic fixture.")
        val output = File(directory, "docs-output")
        Docs.generate(docs, output)
        val nested = File(output, "docs/guide.html").readText()
        assertTrue(root.contains("src=\"vendor/alpine-3.14.1.js\""))
        assertTrue(nested.contains("src=\"../vendor/alpine-3.14.1.js\""))
        assertFalse(nested.contains("cdn.jsdelivr.net"))
        assertEquals(URI("https://site.example/void/vendor/alpine-3.14.1.js"), URI("https://site.example/void/docs/guide.html").resolve("../vendor/alpine-3.14.1.js"))
        assertThrows<IllegalArgumentException> { voidPage("Wrong version", alpineVersion = "3.14.0") {} }
    }
}
