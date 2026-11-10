package world.gregs.voidps.web.site

import kotlinx.serialization.json.Json
import world.gregs.voidps.web.site.components.WorldEntry
import java.io.File
import java.util.Properties

internal class SiteConfiguration private constructor(
    val properties: Properties,
    val worldsJson: String,
    val worlds: List<WorldEntry>,
    val output: File,
    val selfHostedAssets: Boolean,
    val assetSource: File?,
) {
    companion object {
        const val DEFAULT_WORLDS = "./web/site/src/main/resources/static/worlds.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(path: String? = System.getProperty("void.site.properties") ?: System.getenv("VOID_SITE_PROPERTIES")): SiteConfiguration {
            require(path == null || path.isNotBlank()) { "The site properties path must not be blank." }
            val file = File(path ?: "./game/src/main/resources/game.properties")
            require(file.isFile) { "Site properties file not found: $file" }
            val properties = Properties().apply { file.inputStream().use(::load) }
            val worldPath = properties.getProperty("web.site.worlds")
            require(worldPath == null || worldPath.isNotBlank()) { "web.site.worlds must not be blank." }
            val worldFile = File(worldPath ?: DEFAULT_WORLDS)
            require(worldPath == null || worldFile.isFile) { "World list file not found: $worldFile" }
            val worldsJson = if (worldFile.isFile) worldFile.readText() else "[]"
            val worlds = decodeWorlds(worldsJson)
            val outputPath = properties.getProperty("web.server.pages")
            require(!outputPath.isNullOrBlank()) { "web.server.pages must name the site output directory." }
            val output = File(outputPath)
            require(!output.exists() || output.isDirectory) { "Site output is not a directory: $output" }
            val version = properties.getProperty("web.site.version")
            require(version == null || version.isNotBlank()) { "web.site.version must not be blank." }
            val selfHostedAssets = properties.getProperty("web.site.assets", "false").toBooleanStrictOrNull()
            require(selfHostedAssets != null) { "web.site.assets must be true or false." }
            val assetSource = properties.getProperty("web.site.assets.source")?.let { source ->
                require(source.isNotBlank()) { "web.site.assets.source must not be blank." }
                File(source).also { require(it.isDirectory) { "Site asset source directory not found: $it" } }
            }
            require(assetSource == null || selfHostedAssets) { "web.site.assets.source requires web.site.assets=true." }
            return SiteConfiguration(properties, worldsJson, worlds, output, selfHostedAssets, assetSource)
        }

        fun decodeWorlds(source: String): List<WorldEntry> {
            val worlds = json.decodeFromString<List<WorldEntry>>(source)
            require(worlds.map { it.number }.distinct().size == worlds.size) { "World numbers must be unique." }
            return worlds
        }
    }
}
