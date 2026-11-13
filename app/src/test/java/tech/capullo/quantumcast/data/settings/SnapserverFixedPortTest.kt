package tech.capullo.quantumcast.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stored base port: 0 = random; otherwise base..base+2 must be unprivileged, valid ports. */
class SnapserverFixedPortTest {

    @Test
    fun `zero and negative values mean random`() {
        assertEquals(0, normalizeSnapserverFixedPort(0))
        assertEquals(0, normalizeSnapserverFixedPort(-1))
        assertEquals(0, normalizeSnapserverFixedPort(Int.MIN_VALUE))
    }

    @Test
    fun `privileged ports are raised to 1024`() {
        assertEquals(1024, normalizeSnapserverFixedPort(1))
        assertEquals(1024, normalizeSnapserverFixedPort(80))
        assertEquals(1024, normalizeSnapserverFixedPort(1023))
    }

    @Test
    fun `in-range values are kept as typed`() {
        assertEquals(1024, normalizeSnapserverFixedPort(1024))
        assertEquals(34000, normalizeSnapserverFixedPort(34000))
        assertEquals(65531, normalizeSnapserverFixedPort(65531))
    }

    @Test
    fun `values above 65531 are lowered to 65531`() {
        assertEquals(65531, normalizeSnapserverFixedPort(65532))
        assertEquals(65531, normalizeSnapserverFixedPort(65535))
        assertEquals(65531, normalizeSnapserverFixedPort(99999))
        assertEquals(65531, normalizeSnapserverFixedPort(Int.MAX_VALUE))
    }
}
