package world.gregs.voidps.network

import com.github.michaelbull.logging.InlineLogger
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.utils.io.InternalAPI
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.io.EOFException
import kotlin.time.Duration.Companion.seconds

/**
 * Accepts browser-style WebSocket connections (binary frames) and tunnels the
 * payload into the same [GameServer.connect] pipeline that serves raw TCP
 * clients. One HTTP listener fronts both the RuneScape JS5 and login protocols;
 * the opcode byte the client speaks first is identical to the raw-TCP stream,
 * so Void's existing protocol code needs no changes.
 *
 * Deployment note: this speaks plain HTTP/WS. Terminate TLS at the edge
 * (Cloudflare, ingress controller, etc.) which forwards `wss://` to plain `ws://`.
 */
class WebSocketGameServer(
    private val gameServer: GameServer,
    private val port: Int,
    private val host: String = "0.0.0.0",
    private val path: String = "/",
) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        engine = embeddedServer(Netty, port = port, host = host) {
            install(WebSockets) {
                // The real traffic carries no framing — rely on TCP-like byte streaming.
                pingPeriod = 20.seconds
                timeout = 60.seconds
                maxFrameSize = Long.MAX_VALUE
            }
            routing {
                webSocket(path) {
                    val remote = call.request.origin.remoteHost
                    logger.trace { "WebSocket connection accepted from $remote" }
                    bridge(remote)
                }
            }
        }.start(wait = false)
        logger.info { "WebSocket game server listening on $host:$port$path" }
    }

    /**
     * Pipes the WebSocket `incoming` channel into a [ByteChannel] that acts as
     * `read`, and a second [ByteChannel] as `write` whose bytes are wrapped in
     * `Frame.Binary` and sent over `outgoing`. Delegates to the raw-TCP
     * [GameServer.connect] which is completely unaware of the transport.
     */
    @OptIn(InternalAPI::class)
    private suspend fun DefaultWebSocketServerSession.bridge(remoteHost: String) = coroutineScope {
        val toServer = ByteChannel(autoFlush = true)
        val fromServer = ByteChannel(autoFlush = true)

        // WS inbound → byte stream the game server reads from
        val inbound = launch(Dispatchers.IO) {
            try {
                for (frame in incoming) {
                    if (frame is Frame.Binary) {
                        toServer.writeFully(frame.readBytes())
                        toServer.flush()
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
            } finally {
                toServer.close()
            }
        }

        // byte stream the game server writes to → WS outbound
        val outbound = launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            try {
                while (!fromServer.isClosedForRead) {
                    // Block until at least one byte is available, then drain whatever
                    // is immediately readable into a single frame. This prevents a JS5
                    // response from being split across multiple frames when readAvailable
                    // returns a partial chunk, which would cause the browser client to
                    // dispatch decompression on a truncated buffer.
                    val first = fromServer.readByte()
                    buf[0] = first
                    var len = 1
                    val source = fromServer.readBuffer
                    while (!source.exhausted() && len < buf.size) {
                        buf[len++] = source.readByte()
                    }
                    send(Frame.Binary(true, buf.copyOfRange(0, len)))
                    flush()
                }
            } catch (_: ClosedReceiveChannelException) {
            } finally {
                close(CloseReason(CloseReason.Codes.NORMAL, "session ended"))
            }
        }

        try {
            withContext(Dispatchers.IO) {
                gameServer.connect(toServer, fromServer, remoteHost)
            }
        } catch (eof: EOFException) {
            // Client closed mid-handshake; matches how raw-TCP path swallows this.
            logger.trace { "WebSocket EOF disconnection from $remoteHost" }
        } catch (cl: ClosedReceiveChannelException) {
            logger.trace { "WebSocket channel closed from $remoteHost" }
        } catch (ioe: java.io.IOException) {
            logger.trace { "WebSocket IO error from $remoteHost: ${ioe.message}" }
        } finally {
            toServer.close()
            fromServer.close()
            inbound.cancelAndJoin()
            outbound.cancelAndJoin()
            logger.trace { "WebSocket session from $remoteHost closed" }
        }
    }

    fun stop() {
        engine?.stop(1_000, 5_000)
        engine = null
    }

    companion object {
        private val logger = InlineLogger()
    }
}
