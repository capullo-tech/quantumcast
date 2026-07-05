package tech.capullo.quantumcast.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tech.capullo.audio.snapcast.DiscoveredSnapserver
import tech.capullo.audio.snapcast.SnapclientProcess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapcastScreen(
    discoveredServers: List<DiscoveredSnapserver>,
    connectedHost: String,
    snapclientState: SnapclientProcess.ConnectionState,
    lastManualHost: String = "",
    onStartDiscovery: () -> Unit,
    onConnectToServer: (DiscoveredSnapserver) -> Unit,
    onConnectManually: (host: String, port: Int) -> Unit,
    onClearLastManualHost: () -> Unit = {},
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualInput by remember(lastManualHost) { mutableStateOf(lastManualHost) }
    val localIps = remember { usefulLocalIps() }
    var qrIp by remember { mutableStateOf<LocalIp?>(null) }
    LaunchedEffect(Unit) { onStartDiscovery() }

    qrIp?.let { ip -> ListenQrDialog(ips = localIps, initial = ip, onDismiss = { qrIp = null }) }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // --- Connection status ---
            if (connectedHost.isNotEmpty()) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Listening in",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    connectedHost,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    when (snapclientState) {
                                        SnapclientProcess.ConnectionState.CONNECTED -> "Connected"
                                        SnapclientProcess.ConnectionState.STARTING -> "Connecting…"
                                        SnapclientProcess.ConnectionState.ERROR -> "Connection error"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            TextButton(onClick = onDisconnect) {
                                Text("Disconnect", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }

            // --- Discovered servers ---
            item { SectionHeader("Servers on this network") }

            if (discoveredServers.isEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Scanning for QuantumCast servers…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(discoveredServers.filter { it.hostAddress != connectedHost }) { server ->
                    ServerRow(
                        server = server,
                        onClick = { onConnectToServer(server) },
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Connect manually") }
            item {
                fun connect() {
                    val input = manualInput.trim()
                    if (input.isEmpty()) return
                    val host = input.substringBefore(":")
                    val port = input.substringAfter(":", "1604").toIntOrNull() ?: 1604
                    onConnectManually(host, port)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { connect() }),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        trailingIcon = if (manualInput.isNotEmpty()) {
                            {
                                IconButton(onClick = {
                                    manualInput = ""
                                    onClearLastManualHost()
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        } else {
                            null
                        },
                    )
                    Button(onClick = { connect() }) { Text("Connect") }
                }
            }

            // --- This device's addresses (for web players and remote snapclients) ---
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("This device") }
            if (localIps.isNotEmpty()) {
                item {
                    Text(
                        "Web players on the same network can listen at these addresses in a browser. " +
                            "Tap one for a QR code to scan or share - pick the network the other device is on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                items(localIps) { ip -> LocalIpRow(ip, onClick = { qrIp = ip }) }
            } else {
                item {
                    Text(
                        NO_IP_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LocalIpRow(ip: LocalIp, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (ip.label) {
                "Wi-Fi" -> Icons.Default.Wifi
                "Hotspot" -> Icons.Default.WifiTethering
                "Ethernet" -> Icons.Default.SettingsEthernet
                else -> Icons.Default.VpnKey
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("${ip.address}:1680", style = MaterialTheme.typography.bodyMedium)
            Text(ip.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Default.QrCode,
            contentDescription = "Show QR code",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ServerRow(
    server: DiscoveredSnapserver,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.DeviceHub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                server.serviceName.ifBlank { server.hostAddress },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${server.hostAddress}:${server.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
