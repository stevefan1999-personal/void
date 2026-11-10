import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties

abstract class BrowserInstance : Sync() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:InputDirectory
    abstract val distributionDirectory: DirectoryProperty

    @get:Internal
    abstract val instanceDirectory: DirectoryProperty

    @get:InputFile
    val distributionConfiguration: File
        get() = distributionDirectory.file(CONFIGURATION).get().asFile

    init {
        doNotTrackState("The destination contains durable runtime data, not disposable build outputs.")
        from(distributionDirectory) { exclude(PRESERVED) }
        into(instanceDirectory)
        preserve { include(PRESERVED) }
    }

    @TaskAction
    public override fun copy() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        val distribution = distributionDirectory.get().asFile.canonicalFile
        val instance = instanceDirectory.get().asFile.canonicalFile
        validateLocation(repository, distribution, instance)
        val owned = validateOwnership(instance)
        val supplied = configuration(distribution.resolve(CONFIGURATION))
        validateStorage(instance, supplied)
        if (owned) {
            validateApplication(instance)
            val existing = configuration(instance.resolve(CONFIGURATION))
            validateStorage(instance, existing)
            val keys = CRITICAL_KEYS + (supplied.stringPropertyNames() + existing.stringPropertyNames()).filter {
                it.startsWith("storage.database.") && it in setOf("storage.database.jdbcUrl", "storage.database.driver")
            }
            keys.forEach { key ->
                requireInstance(existing.getProperty(key) == supplied.getProperty(key), "Existing game.properties differs for $key. Reconcile it explicitly before updating.")
            }
        }
        super.copy()
        Files.createDirectories(instance.toPath())
        if (!owned) {
            Files.copy(distribution.resolve(CONFIGURATION).toPath(), instance.resolve(CONFIGURATION).toPath())
        }
        STATE_DIRECTORIES.forEach { Files.createDirectories(instance.resolve(it).toPath()) }
        if (!owned) {
            instance.resolve(MARKER).writeText(MARKER_CONTENT)
        }
    }

    private fun validateLocation(repository: File, distribution: File, instance: File) {
        val target = instance.toPath()
        requireInstance(!repository.toPath().startsWith(target), "The instance must not contain the checkout.")
        requireInstance(!target.startsWith(distribution.toPath()) && !distribution.toPath().startsWith(target), "The instance and distribution must be separate directories.")
        val checkout = generateSequence(instance) { it.parentFile }.firstOrNull { it.resolve(".git").exists() }
        requireInstance(checkout == null || checkout == repository, "The instance must not be inside another source checkout.")
        if (target.startsWith(repository.toPath())) {
            val allowed = repository.resolve("instances").toPath()
            requireInstance(target != allowed && target.startsWith(allowed), "Use instances/<name> or an external directory for durable runtime data.")
        }
    }

    private fun validateOwnership(instance: File): Boolean {
        val path = instance.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return false
        }
        requireInstance(Files.isDirectory(path, NOFOLLOW_LINKS), "The instance destination must be a directory.")
        val nonempty = Files.list(path).use { it.findAny().isPresent }
        if (!nonempty) {
            return false
        }
        val marker = instance.resolve(MARKER).toPath()
        requireInstance(Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.readString(marker) == MARKER_CONTENT, "The destination is nonempty and has no valid ownership marker. Choose an empty directory.")
        STATE_DIRECTORIES.forEach { directory ->
            val state = instance.resolve(directory).toPath()
            requireInstance(!Files.exists(state, NOFOLLOW_LINKS) || Files.isDirectory(state, NOFOLLOW_LINKS), "A preserved runtime directory is not a regular directory: $directory.")
        }
        return true
    }

    private fun validateApplication(instance: File) {
        val runtime = STATE_DIRECTORIES.map { instance.resolve(it).toPath() }.toSet()
        Files.walkFileTree(
            instance.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult = if (directory in runtime) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    requireInstance(!attributes.isSymbolicLink, "The application destination contains a symbolic link. Remove it before updating.")
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun configuration(file: File): Properties {
        requireInstance(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS), "A regular game.properties file is required.")
        return Properties().apply { file.inputStream().use(::load) }
    }

    private fun validateStorage(instance: File, properties: Properties) {
        val roots = STATE_DIRECTORIES.map { instance.resolve(it).toPath() }
        fun setting(key: String): String = properties.getProperty(key)?.takeIf(String::isNotBlank)
            ?: throw GradleException("Browser instance: required configuration is missing: $key.")
        CRITICAL_KEYS.forEach(::setting)
        fun preserved(key: String, base: File, value: String, siblings: Boolean = false) {
            val path = base.resolve(value).canonicalFile.toPath()
            val checked = if (siblings) path.parent ?: throw GradleException("Browser instance: invalid storage path for $key.") else path
            requireInstance(roots.any(checked::startsWith), "Configured $key is outside the preserved runtime directories.")
        }
        DIRECT_WRITERS.forEach { key -> preserved(key, instance, setting(key), key in FILE_WRITERS) }
        val players = instance.resolve(setting("storage.players.path"))
        SAVE_WRITERS.forEach { key ->
            preserved(key, players, setting(key), key != "storage.grand.exchange.history.path" && key != "storage.reports.path")
        }
        val caching = setting("storage.caching.path")
        listOf("storage.caching.objects", "storage.caching.collisions").forEach { key ->
            preserved(key, instance, caching + setting(key), true)
        }
        preserved("storage.caching.path/maps.key", instance, caching + "maps.key", true)
    }

    private fun requireInstance(condition: Boolean, message: String) {
        if (!condition) {
            throw GradleException("Browser instance: $message")
        }
    }

    companion object {
        private const val CONFIGURATION = "game.properties"
        private const val MARKER = ".void-browser-instance"
        private const val MARKER_CONTENT = "void-browser-instance=1\n"
        private val STATE_DIRECTORIES = listOf("cache", "saves", "errors", "avatars", "reports", "grand_exchange", ".temp").map { "data/$it" }
        private val PRESERVED = listOf(CONFIGURATION, MARKER) + STATE_DIRECTORIES.flatMap { listOf(it, "$it/**") }
        private val FILE_WRITERS = setOf("storage.wildcards", "bots.chat.model")
        private val DIRECT_WRITERS = listOf(
            "storage.players.path", "storage.players.logs", "storage.players.errors", "storage.bots.path", "storage.cache.path",
            "storage.wildcards", "storage.caching.path", "bots.chat.model", "web.avatars.path",
        )
        private val SAVE_WRITERS = listOf(
            "storage.grand.exchange.offers.path",
            "storage.grand.exchange.offers.buy.path",
            "storage.grand.exchange.offers.sell.path",
            "storage.grand.exchange.offers.claim.path",
            "storage.grand.exchange.history.path",
            "storage.reports.path",
        )
        private val CRITICAL_KEYS = DIRECT_WRITERS + SAVE_WRITERS + listOf(
            "server.revision", "world.id", "world.name", "network.port", "web.server.enabled", "web.server.port", "web.server.pages",
            "web.client.path", "storage.type", "storage.data", "storage.scripts.path", "storage.caching.objects", "storage.caching.collisions",
        )
    }
}
