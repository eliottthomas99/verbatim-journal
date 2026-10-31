package app.verbatim.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkClientTest {
    @Test fun oldCallsKeepOldCredentialsAfterReconfiguration() = runBlocking {
        val oldToken = AtomicReference<String>()
        val newToken = AtomicReference<String>()
        fun server(received: AtomicReference<String>): HttpServer {
            return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/health") { exchange ->
                    received.set(exchange.requestHeaders.getFirst("Authorization"))
                    val bytes = "{\"status\":\"ok\"}".toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        }
        val oldServer = server(oldToken)
        val newServer = server(newToken)
        try {
            NetworkClient.configure("http://127.0.0.1:${oldServer.address.port}", "dummy-old-token")
            val oldApi = NetworkClient.api
            NetworkClient.configure("http://127.0.0.1:${newServer.address.port}", "dummy-new-token")
            assertEquals(200, oldApi.health().code())
            assertEquals(200, NetworkClient.api.health().code())
            assertEquals("Bearer dummy-old-token", oldToken.get())
            assertEquals("Bearer dummy-new-token", newToken.get())
        } finally {
            oldServer.stop(0)
            newServer.stop(0)
            NetworkClient.configure("", "")
        }
    }
}
