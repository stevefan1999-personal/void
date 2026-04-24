package world.gregs.voidps.network

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.writeByte
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import world.gregs.voidps.network.client.ConnectionTracker

internal class WebSocketGameServerTest {

    private var webSocketServer: WebSocketGameServer? = null

    @AfterEach
    fun shutdown() {
        webSocketServer?.stop()
        webSocketServer = null
    }

    @Test
    fun `WebSocket login connect emits data change immediately`() {
        val fileServer = object : Server {
            override suspend fun connect(read: ByteReadChannel, write: ByteWriteChannel, hostname: String) {
                write.writeByte(Response.SUCCESS.toByte())
            }
        }
        val loginServer = object : Server {
            override suspend fun connect(read: ByteReadChannel, write: ByteWriteChannel, hostname: String) {
                write.writeByte(Response.DATA_CHANGE.toByte())
                write.flush()
                // Block waiting for the next login byte so the test proves the
                // first byte reaches the websocket peer without needing another
                // client frame to trigger delivery.
                read.readByte()
            }
        }

        val gameServer = GameServer(fileServer, ConnectionTracker(5)).apply {
            this.loginServer = loginServer
        }
        val port = ServerSocket(0).use { it.localPort }
        webSocketServer = WebSocketGameServer(gameServer, port = port, host = "127.0.0.1", path = "/")
        webSocketServer!!.start()

        val firstFrame = CompletableFuture<ByteArray>()
        val webSocket = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI("ws://127.0.0.1:$port/"),
                object : WebSocket.Listener {
                    override fun onOpen(webSocket: WebSocket) {
                        webSocket.request(1)
                    }

                    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*> {
                        val bytes = ByteArray(data.remaining())
                        data.get(bytes)
                        firstFrame.complete(bytes)
                        webSocket.request(1)
                        return CompletableFuture.completedFuture(null)
                    }
                },
            )
            .get(5, TimeUnit.SECONDS)

        webSocket.sendBinary(ByteBuffer.wrap(byteArrayOf(Request.CONNECT_LOGIN.toByte())), true)
        assertEquals(byteArrayOf(Response.DATA_CHANGE.toByte()).toList(), firstFrame.get(1, TimeUnit.SECONDS).toList())
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS)
    }
}
