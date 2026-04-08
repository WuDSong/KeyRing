package com.example.keyring.ui.home

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.keyring.R
import com.example.keyring.data.AppPreferences
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.sync.LanSyncNsd
import com.example.keyring.sync.MergeStats
import com.example.keyring.sync.PairingEngine
import com.example.keyring.sync.SyncArchive
import com.example.keyring.sync.SyncClient
import com.example.keyring.sync.SyncServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyncScreen(
    repository: PasswordEntryRepository,
    appPreferences: AppPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dupPolicy by appPreferences.observeDuplicatePolicy().collectAsStateWithLifecycle(
        initialValue = appPreferences.getDuplicatePolicy()
    )

    var deviceName by remember { mutableStateOf(appPreferences.getDeviceName()) }
    var receiverOn by remember { mutableStateOf(false) }
    var serverRunning by remember { mutableStateOf<SyncServer.Running?>(null) }
    var pairCode by remember { mutableStateOf<String?>(null) }
    var nsdReg by remember { mutableStateOf<android.net.nsd.NsdManager.RegistrationListener?>(null) }

    val syncServer = remember { SyncServer(context.applicationContext, repository, appPreferences) }
    val syncClient = remember { SyncClient(appPreferences) }

    DisposableEffect(Unit) {
        onDispose {
            serverRunning?.let { syncServer.stop(it) }
            nsdReg?.let { LanSyncNsd.unregister(context, it) }
            syncClient.close()
        }
    }

    var manualHost by remember { mutableStateOf("") }
    var pairCodeInput by remember { mutableStateOf("") }
    var selectedRemoteId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastPullStats by remember { mutableStateOf<MergeStats?>(null) }
    var lastPushStats by remember { mutableStateOf<MergeStats?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val discovered = remember { mutableStateMapOf<String, LanSyncNsd.ResolvedService>() }

    DisposableEffect(Unit) {
        val discoveryListener = LanSyncNsd.startDiscovery(
            context = context,
            onResolved = { r ->
                val key = "${r.host}:${r.port}"
                discovered[key] = r
            },
            onLost = { name ->
                val toRemove = discovered.filterValues { it.serviceName == name }.keys
                toRemove.forEach { discovered.remove(it) }
            }
        )
        onDispose {
            LanSyncNsd.stopDiscovery(context, discoveryListener)
        }
    }

    fun baseUrl(host: String): String {
        val h = host.trim()
        return "http://$h:${SyncServer.DEFAULT_PORT}"
    }

    fun stopReceiver() {
        nsdReg?.let { LanSyncNsd.unregister(context, it) }
        nsdReg = null
        serverRunning?.let { syncServer.stop(it) }
        serverRunning = null
        pairCode = null
        syncServer.currentPairCode = null
        receiverOn = false
    }

    fun startReceiver() {
        val code = PairingEngine.generatePairCode()
        pairCode = code
        syncServer.currentPairCode = code
        val running = syncServer.start()
        serverRunning = running
        val label = "KeyRing-${deviceName.ifBlank { "device" }}"
        nsdReg = LanSyncNsd.register(
            context = context,
            port = running.port,
            serviceLabel = label
        )
        receiverOn = true
    }

    var trustedVersion by remember { mutableStateOf(0) }
    val trusted = remember(trustedVersion) { appPreferences.getTrustedDevices() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.sync_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.sync_section_device),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = deviceName,
                        onValueChange = { deviceName = it },
                        label = { Text(stringResource(R.string.sync_device_name_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy
                    )
                    TextButton(
                        onClick = {
                            appPreferences.setDeviceName(deviceName)
                            Toast.makeText(
                                context,
                                context.getString(R.string.sync_device_name_saved),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.sync_device_name_save))
                    }
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.sync_receiver_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.sync_receiver_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = receiverOn,
                            onCheckedChange = { on ->
                                if (on) startReceiver() else stopReceiver()
                            },
                            enabled = !busy
                        )
                    }
                    if (receiverOn && pairCode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.sync_pair_code_show, pairCode!!),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = stringResource(R.string.sync_port_hint, SyncServer.DEFAULT_PORT),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.sync_client_pair_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text(stringResource(R.string.sync_manual_ip_label)) },
                        placeholder = { Text(stringResource(R.string.sync_manual_ip_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pairCodeInput,
                        onValueChange = { pairCodeInput = it },
                        label = { Text(stringResource(R.string.sync_pair_code_input_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        onClick = {
                            if (manualHost.isBlank() || pairCodeInput.length < 4) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.sync_pair_invalid),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            scope.launch(Dispatchers.IO) {
                                withContext(Dispatchers.Main) {
                                    busy = true
                                    lastError = null
                                }
                                try {
                                    val salt = PairingEngine.newSaltB64()
                                    val base = baseUrl(manualHost)
                                    val hello = syncClient.pairHello(
                                        baseUrl = base,
                                        clientName = appPreferences.getDeviceName(),
                                        clientSaltB64 = salt
                                    )
                                    val ok = syncClient.pairConfirm(
                                        baseUrl = base,
                                        remoteServerDeviceId = hello.serverDeviceId,
                                        remoteServerName = hello.serverName,
                                        pairCode = pairCodeInput.trim(),
                                        serverSaltB64 = hello.serverSaltB64,
                                        clientSaltB64 = salt
                                    )
                                    withContext(Dispatchers.Main) {
                                        if (ok) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.sync_pair_success),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            selectedRemoteId = hello.serverDeviceId
                                            trustedVersion++
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.sync_pair_failed),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        lastError = e.message
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.sync_error_fmt, e.message ?: ""),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } finally {
                                    withContext(Dispatchers.Main) { busy = false }
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(22.dp)
                                    .padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Icon(Icons.Filled.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.sync_pair_button))
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.sync_discovered_title),
                style = MaterialTheme.typography.titleSmall
            )
        }
        if (discovered.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.sync_discovered_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(discovered.values.toList(), key = { "${it.host}:${it.port}" }) { s ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { manualHost = s.host }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(s.serviceName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${s.host}:${s.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.sync_dup_policy_current),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = when (dupPolicy) {
                            AppPreferences.DuplicatePolicy.SKIP -> stringResource(R.string.settings_sync_dup_skip)
                            AppPreferences.DuplicatePolicy.OVERWRITE_NEWER -> stringResource(R.string.settings_sync_dup_overwrite)
                            AppPreferences.DuplicatePolicy.KEEP_BOTH -> stringResource(R.string.settings_sync_dup_keep_both)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.sync_dup_policy_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.sync_trusted_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (trusted.isEmpty()) {
                        Text(
                            text = stringResource(R.string.sync_no_trusted),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        trusted.forEach { t ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(t.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        t.deviceId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        appPreferences.removeTrustedDevice(t.deviceId)
                                        if (selectedRemoteId == t.deviceId) selectedRemoteId = null
                                        trustedVersion++
                                    },
                                    enabled = !busy
                                ) {
                                    Text(stringResource(R.string.sync_remove_trust))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.sync_select_remote_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = selectedRemoteId.orEmpty(),
                        onValueChange = { selectedRemoteId = it.ifBlank { null } },
                        label = { Text(stringResource(R.string.sync_remote_device_id_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy
                    )
                    Button(
                        onClick = {
                            val rid = selectedRemoteId?.trim().orEmpty()
                            if (rid.isEmpty() || manualHost.isBlank()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.sync_sync_invalid),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            scope.launch(Dispatchers.IO) {
                                withContext(Dispatchers.Main) {
                                    busy = true
                                    lastError = null
                                }
                                try {
                                    val base = baseUrl(manualHost)
                                    val bytes = syncClient.pullArchive(base, rid)
                                    val parsed = SyncArchive.parseZipBytes(context, bytes)
                                    val pull = repository.mergeSyncArchive(parsed, appPreferences.getDuplicatePolicy())
                                    val localBytes = SyncArchive.exportZipBytes(repository.getAllEntriesUnsorted())
                                    val push = syncClient.pushArchive(base, rid, localBytes)
                                    withContext(Dispatchers.Main) {
                                        lastPullStats = pull
                                        lastPushStats = push
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.sync_sync_done),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        lastError = e.message
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.sync_error_fmt, e.message ?: ""),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } finally {
                                    withContext(Dispatchers.Main) { busy = false }
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.sync_bidirectional_button))
                    }
                }
            }
        }

        item {
            if (lastPullStats != null || lastPushStats != null) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.sync_last_result),
                            style = MaterialTheme.typography.titleSmall
                        )
                        lastPullStats?.let {
                            Text(
                                stringResource(
                                    R.string.sync_stats_pull_fmt,
                                    it.inserted,
                                    it.updated,
                                    it.skipped,
                                    it.duplicatedAsCopy
                                )
                            )
                        }
                        lastPushStats?.let {
                            Text(
                                stringResource(
                                    R.string.sync_stats_push_fmt,
                                    it.inserted,
                                    it.updated,
                                    it.skipped,
                                    it.duplicatedAsCopy
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
