package world.gregs.voidps.web.route

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import world.gregs.voidps.engine.data.Settings
import world.gregs.voidps.web.WebServer
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebClientTest {

    @TempDir
    lateinit var directory: Path

    @Test
    fun `Defaults to the official client`() {
        assertEquals(Paths.get("./data/void-client.js"), WebServer.clientPath(Settings()))
    }

    @Test
    fun `Existing custom client path still works without a selector`() {
        val settings = Settings().apply { load(mapOf("web.client.path" to "custom.js")) }
        assertEquals(Paths.get("custom.js"), WebServer.clientPath(settings))
    }

    @ParameterizedTest
    @ValueSource(strings = ["official", "teavm"])
    fun `Serves only the operator selected client at the existing URL`(selected: String) = testApplication {
        val official = directory.resolve("official.js").apply { writeText("// official client") }
        val teavm = directory.resolve("teavm.js").apply { writeText("// teavm client") }
        val settings = Settings().apply {
            load(mapOf("web.client.type" to selected, "web.client.path" to official.toString(), "web.client.teavm.path" to teavm.toString()))
        }
        application {
            routing { webclient(WebServer.clientPath(settings)) }
        }

        val response = client.get("/play/void-client.js")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.JavaScript, response.contentType()?.withoutParameters())
        assertEquals("// $selected client", response.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/play/teavm-client.js").status)
    }

    @Test
    fun `Rejects an unknown client type`() {
        val settings = Settings().apply { load(mapOf("web.client.type" to "typo")) }
        val error = assertFailsWith<IllegalArgumentException> { WebServer.clientPath(settings) }
        assertEquals("web.client.type must be official or teavm.", error.message)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "empty", "directory"])
    fun `Rejects an invalid TeaVM attachment instead of falling back to official`(invalid: String) {
        val official = directory.resolve("official.js").apply { writeText("// official client") }
        val attachment = directory.resolve("teavm.js")
        when (invalid) {
            "empty" -> attachment.writeText("")
            "directory" -> attachment.createDirectory()
        }
        val settings = Settings().apply {
            load(mapOf("web.client.type" to "teavm", "web.client.path" to official.toString(), "web.client.teavm.path" to attachment.toString()))
        }

        val error = assertFailsWith<IllegalArgumentException> { WebServer.clientPath(settings) }

        assertEquals("Selected TeaVM client must be a readable, non-empty file: $attachment", error.message)
    }
}
