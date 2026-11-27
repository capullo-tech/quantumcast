package tech.capullo.quantumcast.ui.player

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import tech.capullo.audio.snapcast.JoinUrl
import tech.capullo.audio.snapcast.SnapserverPorts

/** Where a scanned join URL points this device's snapclient, and what it asks the broadcaster first. */
class JoinRouteTest {

    private fun target(url: String) = JoinUrl.parse(url) ?: error("not a join URL: $url")

    @Test
    fun `a tunnel join streams over wss on 443 without fetching listen json`() = runBlocking {
        val route = routeJoin(target("https://calm-river-1234.trycloudflare.com/join/")) { _, _ ->
            fail("listen.json is not reachable through the tunnel")
            null
        }
        assertEquals(JoinRoute("calm-river-1234.trycloudflare.com", 443, 443, "wss"), route)
    }

    @Test
    fun `a LAN join takes the stream and control ports from the broadcaster's listen json`() = runBlocking {
        // An OS-assigned fallback block: nothing about it is derivable from the URL's HTTP port.
        val served = SnapserverPorts(streamPort = 40213, tcpPort = 38817, httpPort = 41950)
        var asked: Pair<String, Int>? = null
        val route = routeJoin(target("http://192.168.1.20:41950/join/")) { host, httpPort ->
            asked = host to httpPort
            served
        }
        assertEquals("192.168.1.20" to 41950, asked)
        assertEquals(JoinRoute("192.168.1.20", 40213, 41950, "tcp"), route)
    }

    @Test
    fun `a LAN join without listen json dials the block derived from the URL`() = runBlocking {
        val route = routeJoin(target("http://192.168.1.20:34002/join/")) { _, _ -> null }
        assertEquals(JoinRoute("192.168.1.20", 34000, 34002, "tcp"), route)
    }
}
