import org.gradle.api.GradleException
import org.gradle.api.tasks.Delete
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal class BrowserInstanceTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var repository: File
    private lateinit var distribution: File
    private lateinit var instance: File
    private lateinit var task: BrowserInstance

    @BeforeEach
    fun setup() {
        repository = directory.resolve("checkout").toFile().apply { mkdirs() }
        distribution = repository.resolve("build/distribution").apply { mkdirs() }
        instance = repository.resolve("instances/browser")
        distribution.resolve("server.jar").writeText("application version one")
        distribution.resolve("pages").mkdir()
        distribution.resolve("pages/play.html").writeText("play")
        distribution.resolve("data").mkdir()
        distribution.resolve("data/static.toml").writeText("static configuration")
        writeConfiguration(distribution.resolve("game.properties"), defaults())
        val project = ProjectBuilder.builder().withProjectDir(repository).build()
        task = project.tasks.register("prepareInstance", BrowserInstance::class.java).get()
        task.repositoryDirectory.set(repository)
        task.distributionDirectory.set(distribution)
        task.instanceDirectory.set(instance)
    }

    private fun defaults() = mapOf(
        "server.revision" to "634", "world.id" to "16", "world.name" to "World 1", "network.port" to "43594",
        "web.server.enabled" to "true", "web.server.port" to "8080", "web.server.pages" to "./pages/",
        "web.client.path" to "./data/void-client.js", "storage.type" to "files", "storage.data" to "./data/",
        "storage.scripts.path" to "./data/.temp/scripts/", "storage.players.path" to "./data/saves/",
        "storage.players.logs" to "./data/saves/logs/", "storage.players.errors" to "./data/errors/",
        "storage.bots.path" to "./data/saves/bots/", "storage.cache.path" to "./data/cache/",
        "storage.wildcards" to "./data/.temp/wildcards.txt", "storage.caching.path" to "./data/.temp/",
        "bots.chat.model" to "./data/.temp/bot_chat.model", "web.avatars.path" to "./data/avatars/",
        "storage.grand.exchange.offers.path" to "grand_exchange/offers.toml",
        "storage.grand.exchange.offers.buy.path" to "grand_exchange/buy_offers/",
        "storage.grand.exchange.offers.sell.path" to "grand_exchange/sell_offers/",
        "storage.grand.exchange.offers.claim.path" to "grand_exchange/claimable_offers.toml",
        "storage.grand.exchange.history.path" to "grand_exchange/price_history", "storage.reports.path" to "reports/",
        "storage.caching.objects" to "map_objects.dat", "storage.caching.collisions" to "collisions.dat",
    )

    private fun writeConfiguration(file: File, values: Map<String, String>) {
        file.parentFile.mkdirs()
        Properties().apply { putAll(values) }.let { properties -> file.writer().use { properties.store(it, "synthetic fixture") } }
    }

    private fun snapshot(root: File): Map<String, List<Byte>> = root.walkTopDown().filter(File::isFile).associate {
        it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList()
    }

    private fun savedState(): Map<String, List<Byte>> {
        STATE.forEach { name ->
            instance.resolve("data/$name/synthetic/state.dat").apply {
                parentFile.mkdirs()
                writeText("keep $name")
            }
        }
        return snapshot(instance.resolve("data"))
    }

    @Test
    fun `Initial install copies application config and ownership with empty runtime directories`() {
        STATE.forEach { name ->
            distribution.resolve("data/$name/source-state.dat").apply {
                parentFile.mkdirs()
                writeText("must not import source runtime state")
            }
        }

        task.copy()

        assertEquals("application version one", instance.resolve("server.jar").readText())
        assertEquals("static configuration", instance.resolve("data/static.toml").readText())
        assertEquals(distribution.resolve("game.properties").readText(), instance.resolve("game.properties").readText())
        assertEquals("void-browser-instance=1\n", instance.resolve(".void-browser-instance").readText())
        STATE.forEach { name ->
            val state = instance.resolve("data/$name")
            assertTrue(state.isDirectory)
            assertTrue(state.listFiles().orEmpty().isEmpty(), name)
        }
    }

    @Test
    fun `Application update preserves instance config marker and all named state trees`() {
        task.copy()
        val state = savedState()
        val configuration = instance.resolve("game.properties").apply { appendText("\n# operator setting\nserver.name=Personal instance\n") }.readText()
        val marker = instance.resolve(".void-browser-instance").readText()
        instance.resolve("obsolete-app.jar").writeText("obsolete")
        distribution.resolve("server.jar").writeText("application version two")

        task.copy()

        assertEquals("application version two", instance.resolve("server.jar").readText())
        assertFalse(instance.resolve("obsolete-app.jar").exists())
        assertEquals(configuration, instance.resolve("game.properties").readText())
        assertEquals(marker, instance.resolve(".void-browser-instance").readText())
        assertEquals(state, snapshot(instance.resolve("data")))
    }

    @Test
    fun `Cleaning generated build outputs leaves the durable instance unchanged`() {
        task.copy()
        savedState()
        val before = snapshot(instance)

        val clean = task.project.tasks.register("clean", Delete::class.java) { delete(repository.resolve("build")) }.get()
        clean.actions.forEach { it.execute(clean) }

        assertFalse(distribution.exists())
        assertEquals(before, snapshot(instance))
    }

    @Test
    fun `A new external instance directory is permitted`() {
        val external = directory.resolve("runtime").toFile()
        task.instanceDirectory.set(external)

        task.copy()

        assertEquals("application version one", external.resolve("server.jar").readText())
        assertTrue(external.resolve("data/saves").isDirectory)
    }

    @Test
    fun `A checkout beneath an ancestor named src can own its instances directory`() {
        val checkout = directory.resolve("src/checkout").toFile().apply { mkdirs() }
        checkout.resolve(".git").mkdir()
        val packaged = checkout.resolve("build/distribution")
        distribution.copyRecursively(packaged)
        val runtime = checkout.resolve("instances/browser")
        task.repositoryDirectory.set(checkout)
        task.distributionDirectory.set(packaged)
        task.instanceDirectory.set(runtime)

        task.copy()

        assertEquals("application version one", runtime.resolve("server.jar").readText())
        assertTrue(runtime.resolve("data/saves").isDirectory)
    }

    @Test
    fun `An owned external instance beneath a harmless build ancestor can be updated`() {
        val runtime = directory.resolve("build/owned-runtime").toFile()
        task.instanceDirectory.set(runtime)
        task.copy()
        val saved = runtime.resolve("data/saves/synthetic.toml").apply { writeText("keep this save") }
        distribution.resolve("server.jar").writeText("application version two")

        task.copy()

        assertEquals("application version two", runtime.resolve("server.jar").readText())
        assertEquals("keep this save", saved.readText())
        assertEquals("void-browser-instance=1\n", runtime.resolve(".void-browser-instance").readText())
    }

    @Test
    fun `Nonempty unowned destination is rejected without changing any file`() {
        instance.mkdirs()
        instance.resolve("customer-save.toml").writeText("synthetic foreign state")
        val before = snapshot(instance)

        val failure = assertThrows(GradleException::class.java) { task.copy() }

        assertTrue(failure.message.orEmpty().contains("no valid ownership marker"))
        assertEquals(before, snapshot(instance))
    }

    @Test
    fun `Invalid marker is not permission to replace a nonempty directory`() {
        instance.mkdirs()
        instance.resolve(".void-browser-instance").writeText("another application\n")
        val before = snapshot(instance)

        assertThrows(GradleException::class.java) { task.copy() }

        assertEquals(before, snapshot(instance))
    }

    @Test
    fun `Missing existing configuration is rejected before application update`() {
        task.copy()
        instance.resolve("game.properties").delete()
        distribution.resolve("server.jar").writeText("application version two")
        val before = snapshot(instance)

        assertThrows(GradleException::class.java) { task.copy() }

        assertEquals(before, snapshot(instance))
    }

    @Test
    fun `Changed critical world port and path settings fail before application mutation`() {
        task.copy()
        savedState()
        val before = snapshot(instance)
        distribution.resolve("server.jar").writeText("must not install")
        val changes = mapOf("world.id" to "17", "network.port" to "43595", "web.server.port" to "8081", "storage.players.path" to "./data/saves/another/")
        changes.forEach { (key, value) ->
            writeConfiguration(distribution.resolve("game.properties"), defaults() + (key to value))

            val failure = assertThrows(GradleException::class.java) { task.copy() }

            assertTrue(failure.message.orEmpty().contains(key), key)
            assertEquals(before, snapshot(instance), key)
        }
    }

    @Test
    fun `Runtime directory is rejected in checkout source metadata and generated trees`() {
        listOf("runtime", "build/runtime", ".gradle/runtime", ".git/runtime", "src/runtime").forEach { relative ->
            val target = repository.resolve(relative)
            task.instanceDirectory.set(target)

            assertThrows(GradleException::class.java) { task.copy() }

            assertFalse(target.exists(), relative)
        }
    }

    @Test
    fun `Instance cannot contain its checkout or assembled distribution`() {
        val before = snapshot(repository)
        listOf(repository, directory.toFile(), distribution).forEach { target ->
            task.instanceDirectory.set(target)

            assertThrows(GradleException::class.java) { task.copy() }

            assertEquals(before, snapshot(repository))
        }
    }

    @Test
    fun `An external path inside another Git checkout is rejected`() {
        val other = directory.resolve("another-checkout").toFile().apply { mkdir() }
        other.resolve(".git").mkdir()
        val target = other.resolve("runtime")
        task.instanceDirectory.set(target)

        val failure = assertThrows(GradleException::class.java) { task.copy() }

        assertTrue(failure.message.orEmpty().contains("another source checkout"))
        assertFalse(target.exists())
    }

    @Test
    fun `Custom unpreserved writer paths fail even when configuration otherwise agrees`() {
        val changes = mapOf(
            "storage.players.path" to "./custom-saves/",
            "storage.cache.path" to "./custom-cache/",
            "bots.chat.model" to "./model.bin",
            "storage.reports.path" to "../../custom-reports/",
            "storage.grand.exchange.offers.buy.path" to "../../custom-offers/",
            "storage.caching.objects" to "../../custom-objects.dat",
            "storage.caching.path" to "./data/.temp",
        )
        changes.forEach { (key, value) ->
            writeConfiguration(distribution.resolve("game.properties"), defaults() + (key to value))

            val failure = assertThrows(GradleException::class.java) { task.copy() }

            assertTrue(failure.message.orEmpty().contains("outside the preserved runtime directories"), key)
            assertFalse(instance.exists(), key)
        }
    }

    @Test
    fun `Grand Exchange temporary siblings must also stay inside preserved trees`() {
        writeConfiguration(distribution.resolve("game.properties"), defaults() + ("storage.grand.exchange.offers.buy.path" to "../cache"))

        val failure = assertThrows(GradleException::class.java) { task.copy() }

        assertTrue(failure.message.orEmpty().contains("storage.grand.exchange.offers.buy.path"))
        assertFalse(instance.exists())
    }

    @Test
    fun `Report and history directories may use another preserved root`() {
        writeConfiguration(
            distribution.resolve("game.properties"),
            defaults() + mapOf("storage.reports.path" to "../reports", "storage.grand.exchange.history.path" to "../grand_exchange"),
        )

        task.copy()

        assertTrue(instance.resolve("data/reports").isDirectory)
        assertTrue(instance.resolve("data/grand_exchange").isDirectory)
    }

    @Test
    fun `Incomplete initial configuration is rejected before copying application files`() {
        writeConfiguration(distribution.resolve("game.properties"), defaults() - "world.id")

        val failure = assertThrows(GradleException::class.java) { task.copy() }

        assertTrue(failure.message.orEmpty().contains("world.id"))
        assertFalse(instance.exists())
    }

    @Test
    fun `Storage symlink cannot redirect a preserved root outside the instance`() {
        task.copy()
        val external = directory.resolve("external-cache")
        Files.createDirectories(external)
        instance.resolve("data/cache").delete()
        Files.createSymbolicLink(instance.resolve("data/cache").toPath(), external)
        val script = instance.resolve("server.jar").readText()
        distribution.resolve("server.jar").writeText("must not install")

        assertThrows(GradleException::class.java) { task.copy() }

        assertEquals(script, instance.resolve("server.jar").readText())
        assertTrue(Files.list(external).use { !it.findAny().isPresent })
    }

    @Test
    fun `Application file symlink is rejected without overwriting external bytes or other application files`() {
        task.copy()
        val external = directory.resolve("external-server.jar").toFile().apply { writeText("external file sentinel") }
        val application = instance.resolve("server.jar").toPath()
        Files.delete(application)
        Files.createSymbolicLink(application, external.toPath())
        distribution.resolve("server.jar").writeText("must not overwrite external file")
        distribution.resolve("pages/play.html").writeText("must not update the instance")

        val failure = assertThrows(GradleException::class.java) { task.copy() }

        assertTrue(failure.message.orEmpty().contains("symbolic link"))
        assertEquals("external file sentinel", external.readText())
        assertEquals("play", instance.resolve("pages/play.html").readText())
        assertTrue(Files.isSymbolicLink(application))
    }

    @Test
    fun `Application directory symlink is rejected without changing external sentinels or the application`() {
        task.copy()
        val external = directory.resolve("external-pages").toFile().apply { mkdir() }
        external.resolve("play.html").writeText("external page sentinel")
        external.resolve("keep.txt").writeText("external unrelated sentinel")
        instance.resolve("pages").deleteRecursively()
        Files.createSymbolicLink(instance.resolve("pages").toPath(), external.toPath())
        distribution.resolve("server.jar").writeText("must not update the instance")
        distribution.resolve("pages/play.html").writeText("must not overwrite external page")

        val failure = assertThrows(GradleException::class.java) { task.copy() }

        assertTrue(failure.message.orEmpty().contains("symbolic link"))
        assertEquals("external page sentinel", external.resolve("play.html").readText())
        assertEquals("external unrelated sentinel", external.resolve("keep.txt").readText())
        assertEquals("application version one", instance.resolve("server.jar").readText())
    }

    @Test
    fun `Dangling application symlink is rejected before creating its external target or updating files`() {
        task.copy()
        val external = directory.resolve("missing-external-server.jar")
        val application = instance.resolve("server.jar").toPath()
        Files.delete(application)
        Files.createSymbolicLink(application, external)
        distribution.resolve("pages/play.html").writeText("must not update the instance")

        val failure = assertThrows(GradleException::class.java) { task.copy() }

        assertTrue(failure.message.orEmpty().contains("symbolic link"))
        assertFalse(Files.exists(external))
        assertTrue(Files.isSymbolicLink(application))
        assertEquals("play", instance.resolve("pages/play.html").readText())
    }

    @Test
    fun `Application validation does not descend preserved runtime data`() {
        task.copy()
        val external = directory.resolve("external-synthetic-save").toFile().apply { writeText("runtime sentinel") }
        val runtimeLink = instance.resolve("data/saves/synthetic-link").toPath()
        Files.createSymbolicLink(runtimeLink, external.toPath())
        distribution.resolve("server.jar").writeText("application version two")

        task.copy()

        assertEquals("application version two", instance.resolve("server.jar").readText())
        assertEquals("runtime sentinel", external.readText())
        assertTrue(Files.isSymbolicLink(runtimeLink))
    }

    companion object {
        private val STATE = listOf("cache", "saves", "errors", "avatars", "reports", "grand_exchange", ".temp")
    }
}
