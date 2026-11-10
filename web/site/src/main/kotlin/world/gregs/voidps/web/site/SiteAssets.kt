package world.gregs.voidps.web.site

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

internal object SiteAssets {
    const val ALPINE_VERSION = "3.14.1"
    private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/153.0.0.0 Safari/537.36"
    private val json = Json { encodeDefaults = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val pins = Json.decodeFromString<List<Asset>>(requireNotNull(javaClass.getResourceAsStream("/site-assets.json")).bufferedReader().use { it.readText() })

    fun install(output: File, source: File? = null) {
        Installer(pins, ::download).install(output, source)
    }

    @Serializable
    internal data class Asset(val source: String, val local: String, val sha256: String, val deployedSha256: String = sha256) {
        val name: String get() = local.removePrefix("/vendor/")
    }

    internal class Installer(manifest: List<Asset>, private val download: (String) -> ByteArray) {
        private val assets = manifest.sortedBy { it.local }
        private val fontSources = assets.filter { it.name.endsWith(".woff2") }.associate { it.source to it.name }

        init {
            require(assets.map { it.local }.distinct().size == assets.size) { "Duplicate public asset path" }
            require(assets.map { it.source }.distinct().size == assets.size) { "Duplicate public asset URL" }
            assets.forEach { asset ->
                require(asset.local == "/vendor/${asset.name}" && asset.name.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")) && asset.name != "sources.json") { "Invalid public asset path: ${asset.local}" }
                val uri = URI(asset.source)
                require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) { "Invalid public asset URL: ${asset.source}" }
                require(listOf(asset.sha256, asset.deployedSha256).all { it.matches(Regex("[0-9a-f]{64}")) }) { "Invalid public asset hash: ${asset.name}" }
            }
        }

        fun install(output: File, source: File? = null) {
            val vendor = File(output, "vendor")
            if (source != null) {
                require(source.isDirectory) { "Public asset source is not a directory: $source" }
                require(source.canonicalFile != vendor.canonicalFile) { "Public asset source must not be the output directory" }
            }
            // Validate every input before replacing any previously staged files.
            val prepared = assets.map { asset ->
                val bytes = source?.let { File(it, asset.name).readBytes() } ?: download(asset.source)
                val original = originalBytes(asset, bytes, source != null)
                val staged = if (asset.name == "fonts.css") relativeFonts(original) else original
                asset.copy(deployedSha256 = digest(staged)) to staged
            }
            vendor.mkdirs()
            prepared.forEach { (asset, bytes) -> File(vendor, asset.name).writeBytes(bytes) }
            File(vendor, "sources.json").writeText(json.encodeToString(prepared.map { it.first }) + "\n")
        }

        private fun originalBytes(asset: Asset, bytes: ByteArray, offline: Boolean): ByteArray {
            if (digest(bytes) == asset.sha256) {
                return bytes
            }
            // Only pinned font references may differ; the original hash still authenticates every CSS byte.
            if (offline && asset.name == "fonts.css") {
                val original = assets.filter { it.name.endsWith(".woff2") }.fold(bytes.toString(Charsets.UTF_8)) { css, font ->
                    css.replace(font.local, font.source).replace("url(${font.name})", "url(${font.source})")
                }.toByteArray()
                require(digest(original) == asset.sha256) { "Unexpected original public asset content: ${asset.name}" }
                return original
            }
            throw IllegalArgumentException("Unexpected public asset content: ${asset.name}")
        }

        private fun relativeFonts(bytes: ByteArray): ByteArray = Regex("url\\(([^)]+)\\)").replace(bytes.toString(Charsets.UTF_8)) { match ->
            val url = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
            val name = requireNotNull(fontSources[url]) { "Unpinned public font URL: $url" }
            "url($name)"
        }.toByteArray()
    }

    private fun download(url: String): ByteArray {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", USER_AGENT).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() == 200) { "Cannot obtain public asset: $url (HTTP ${response.statusCode()})" }
        return response.body()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
