package tech.capullo.quantumcast.ui.player

import tech.capullo.audio.snapcast.JoinTarget
import tech.capullo.audio.snapcast.SnapserverListenInfo
import tech.capullo.audio.snapcast.SnapserverPorts

/**
 * Where this device's snapclient dials for a scanned join URL: [port] with [scheme] on [host], and
 * the snapserver HTTP port ([httpPort]) its control client talks to: the LAN HTTP port, or the
 * tunnel's 443 (`wss` scheme).
 */
internal data class JoinRoute(val host: String, val port: Int, val httpPort: Int, val scheme: String)

/**
 * Through the tunnel only HTTPS reaches the broadcaster, so the snapclient streams over `wss` and
 * the control client talks JSON-RPC over `wss`, both on the tunnel's port, and `listen.json` is not
 * fetched. On the LAN the broadcaster's `listen.json` is authoritative (it covers a block that fell
 * back to OS-assigned ports); without it the block is derived from the URL's HTTP port.
 */
internal suspend fun routeJoin(
    target: JoinTarget,
    fetchListenInfo: suspend (host: String, httpPort: Int) -> SnapserverPorts? = SnapserverListenInfo::fetch,
): JoinRoute {
    if (target.secure) return JoinRoute(target.host, target.port, target.httpPort, target.scheme)
    val ports = fetchListenInfo(target.host, target.httpPort) ?: target.ports
    return JoinRoute(
        host = target.host,
        port = ports?.streamPort ?: target.port,
        httpPort = ports?.httpPort ?: target.httpPort,
        scheme = target.scheme,
    )
}
