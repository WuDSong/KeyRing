package com.wudongsong.keyring.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wudongsong.keyring.MyPasswordsApplication
import com.wudongsong.keyring.R
import com.wudongsong.keyring.data.AppPreferences
import com.wudongsong.keyring.data.PasswordEntryRepository
import com.wudongsong.keyring.sync.*
import com.wudongsong.keyring.ui.theme.MyPasswordsTheme
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

internal data class SyncViewState(
    val deviceName: String = "",
    val role: Int = 0,
    val receiverOn: Boolean = false,
    val pairCode: String? = null,
    val pairAvailable: Boolean = false,
    val secondsLeft: Int = 300,
    val localIps: List<String> = emptyList(),
    val host: String = "",
    val codeInput: String = "",
    val discovered: List<LanSyncNsd.ResolvedService> = emptyList(),
    val trusted: List<AppPreferences.TrustedDevice> = emptyList(),
    val selectedId: String? = null,
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val pull: MergeStats? = null,
    val push: MergeStats? = null,
    val policy: AppPreferences.DuplicatePolicy = AppPreferences.DuplicatePolicy.OVERWRITE_NEWER,
)

internal data class SyncActions(
    val role: (Int) -> Unit = {},
    val rename: (String) -> Unit = {},
    val receiver: (Boolean) -> Unit = {},
    val regenerate: () -> Unit = {},
    val host: (String) -> Unit = {},
    val code: (String) -> Unit = {},
    val discover: (LanSyncNsd.ResolvedService) -> Unit = {},
    val select: (AppPreferences.TrustedDevice) -> Unit = {},
    val forget: (String) -> Unit = {},
    val pair: () -> Unit = {},
    val sync: () -> Unit = {},
    val newConnection: () -> Unit = {},
    val copy: (String, String) -> Unit = { _, _ -> },
)

@Composable
fun SyncScreen(
    repository: PasswordEntryRepository,
    appPreferences: AppPreferences,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MyPasswordsApplication
    val scope = rememberCoroutineScope()
    val policy by
        appPreferences
            .observeDuplicatePolicy()
            .collectAsStateWithLifecycle(initialValue = appPreferences.getDuplicatePolicy())
    val trusted by
        appPreferences
            .observeTrustedDevices()
            .collectAsStateWithLifecycle(initialValue = emptyList())
    val server = remember { SyncServer(context.applicationContext, repository, appPreferences) }
    val client = remember { SyncClient(appPreferences) }
    val handle = remember { AtomicReference<SyncServer.Running?>(null) }
    val disposed = remember { AtomicBoolean(false) }
    var registration by remember { mutableStateOf<NsdManager.RegistrationListener?>(null) }
    val discovered = remember { mutableStateMapOf<String, LanSyncNsd.ResolvedService>() }
    var state by remember {
        mutableStateOf(SyncViewState(deviceName = appPreferences.getDeviceName()))
    }

    fun closeServer() {
        handle.getAndSet(null)?.let { running ->
            app.backgroundScope.launch { server.stop(running) }
        }
    }
    DisposableEffect(Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val discovery =
            LanSyncNsd.startDiscovery(
                context,
                onResolved = { service ->
                    main.post {
                        if (!disposed.get()) discovered["${service.host}:${service.port}"] = service
                    }
                },
                onLost = { name ->
                    main.post { discovered.entries.removeAll { it.value.serviceName == name } }
                },
            )
        onDispose {
            disposed.set(true)
            registration?.let { LanSyncNsd.unregister(context, it) }
            LanSyncNsd.stopDiscovery(context, discovery)
            closeServer()
            app.backgroundScope.launch { client.close() }
        }
    }
    LaunchedEffect(state.receiverOn, state.pairCode) {
        if (!state.receiverOn) return@LaunchedEffect
        val expires = SystemClock.elapsedRealtime() + 300_000
        while (isActive) {
            state =
                state.copy(
                    secondsLeft =
                        ((expires - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0),
                    pairAvailable = server.isPairingAvailable(),
                )
            delay(1000)
        }
    }
    fun runOperation(status: String, block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, status = status, error = null)
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state =
                    state.copy(error = e.message ?: context.getString(R.string.sync_pair_failed))
            } finally {
                state = state.copy(busy = false, status = null)
            }
        }
    }
    fun baseUrl(): String {
        val host = state.host.trim()
        require(
            host.isNotBlank() &&
                !host.contains('/') &&
                !host.contains('@') &&
                !host.any(Char::isWhitespace)
        ) {
            context.getString(R.string.sync_manual_ip_hint)
        }
        return "http://$host:${SyncServer.DEFAULT_PORT}"
    }
    val actions =
        SyncActions(
            role = { if (!state.busy) state = state.copy(role = it, error = null) },
            rename = { name ->
                runOperation(context.getString(R.string.sync_working)) {
                    withContext(Dispatchers.IO) { appPreferences.setDeviceName(name) }
                    state = state.copy(deviceName = appPreferences.getDeviceName())
                }
            },
            receiver = { on ->
                runOperation(context.getString(R.string.sync_working)) {
                    if (on) {
                        try {
                            val code = PairingEngine.generatePairCode()
                            server.currentPairCode = code
                            val running =
                                withContext(Dispatchers.IO) {
                                    server.start().also {
                                        handle.set(it)
                                        if (disposed.get()) closeServer()
                                    }
                                }
                            registration =
                                LanSyncNsd.register(
                                    context,
                                    running.port,
                                    "KeyRing-${state.deviceName}",
                                )
                            val ips = withContext(Dispatchers.IO) { wifiAddresses(context) }
                            state =
                                state.copy(
                                    receiverOn = true,
                                    pairCode = code,
                                    pairAvailable = true,
                                    localIps = ips,
                                )
                        } catch (e: Exception) {
                            registration?.let { LanSyncNsd.unregister(context, it) }
                            registration = null
                            server.currentPairCode = null
                            closeServer()
                            throw e
                        }
                    } else {
                        registration?.let { LanSyncNsd.unregister(context, it) }
                        registration = null
                        server.currentPairCode = null
                        val running = handle.getAndSet(null)
                        if (running != null)
                            withContext(NonCancellable + Dispatchers.IO) { server.stop(running) }
                        state =
                            state.copy(receiverOn = false, pairCode = null, pairAvailable = false)
                    }
                }
            },
            regenerate = {
                val code = PairingEngine.generatePairCode()
                server.currentPairCode = code
                state = state.copy(pairCode = code, pairAvailable = true, secondsLeft = 300)
            },
            host = { state = state.copy(host = it, error = null) },
            code = {
                state = state.copy(codeInput = it.filterNot(Char::isWhitespace), error = null)
            },
            discover = {
                state = state.copy(host = it.host, selectedId = null, codeInput = "", error = null)
            },
            select = {
                state =
                    state.copy(
                        selectedId = it.deviceId,
                        host = it.lastHost ?: state.host,
                        error = null,
                    )
            },
            forget = { id ->
                runOperation(context.getString(R.string.sync_working)) {
                    withContext(Dispatchers.IO) { appPreferences.removeTrustedDevice(id) }
                    if (state.selectedId == id) state = state.copy(selectedId = null)
                }
            },
            newConnection = { state = state.copy(selectedId = null, codeInput = "", error = null) },
            pair = {
                val host = state.host.trim()
                val code = state.codeInput.trim()
                runOperation(context.getString(R.string.sync_pairing_progress)) {
                    require(PairingEngine.isValidPairCode(code)) {
                        context.getString(R.string.sync_pair_invalid)
                    }
                    val base = baseUrl()
                    val remote =
                        withContext(Dispatchers.IO) {
                            val salt = PairingEngine.newSaltB64()
                            val hello = client.pairHello(base, appPreferences.getDeviceName(), salt)
                            check(
                                client.pairConfirm(
                                    base,
                                    hello.serverDeviceId,
                                    hello.serverName,
                                    code,
                                    hello.serverSaltB64,
                                    salt,
                                )
                            ) {
                                context.getString(R.string.sync_pair_failed)
                            }
                            appPreferences.rememberTrustedHost(hello.serverDeviceId, host)
                            hello.serverDeviceId
                        }
                    state = state.copy(selectedId = remote, codeInput = "")
                    Toast.makeText(context, R.string.sync_pair_success, Toast.LENGTH_SHORT).show()
                }
            },
            sync = {
                val id = state.selectedId
                val host = state.host.trim()
                runOperation(context.getString(R.string.sync_transfer_progress)) {
                    require(!id.isNullOrBlank()) { context.getString(R.string.sync_sync_invalid) }
                    val base = baseUrl()
                    val result =
                        withContext(Dispatchers.IO) {
                            val bytes = client.pullArchive(base, id)
                            val parsed = SyncArchive.parseZipBytes(context, bytes)
                            val pull =
                                repository.mergeSyncArchive(
                                    parsed,
                                    appPreferences.getDuplicatePolicy(),
                                )
                            val push =
                                client.pushArchive(
                                    base,
                                    id,
                                    SyncArchive.exportZipBytes(repository.getAllEntriesUnsorted()),
                                )
                            appPreferences.rememberTrustedHost(id, host)
                            pull to push
                        }
                    state = state.copy(pull = result.first, push = result.second)
                }
            },
            copy = { label, value ->
                app.sensitiveClipboard.copy(
                    label,
                    value,
                    appPreferences.getClipboardAutoClearEnabled(),
                )
                Toast.makeText(
                        context,
                        context.getString(R.string.detail_copied_toast, label),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
            },
        )
    SyncScreenContent(
        state.copy(
            discovered = discovered.values.filter { it.host !in state.localIps },
            trusted = trusted,
            policy = policy,
        ),
        actions,
        modifier,
    )
}

private fun wifiAddresses(context: Context): List<String> =
    runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            manager.allNetworks
                .filter {
                    manager
                        .getNetworkCapabilities(it)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty() }
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .distinct()
        }
        .getOrDefault(emptyList())

@Composable
internal fun SyncScreenContent(
    state: SyncViewState,
    actions: SyncActions,
    modifier: Modifier = Modifier,
) {
    var editName by remember { mutableStateOf(false) }
    var nameInput by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    var forget by remember { mutableStateOf<AppPreferences.TrustedDevice?>(null) }
    val codeLabel = stringResource(R.string.sync_pair_code_short)
    val selected = state.trusted.firstOrNull { it.deviceId == state.selectedId }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ),
            ) {
                Row(
                    Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SyncIcon(Icons.Outlined.WifiProtectedSetup)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            stringResource(R.string.sync_hero_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.sync_hero_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Smartphone,
                    null,
                    Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(state.deviceName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.sync_this_device),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    enabled = !state.busy,
                    onClick = {
                        nameInput = state.deviceName
                        editName = true
                    },
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        stringResource(R.string.sync_rename_device),
                        Modifier.size(20.dp),
                    )
                }
            }
        }
        item {
            TabRow(
                selectedTabIndex = state.role,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                listOf(R.string.sync_connect_tab, R.string.sync_receive_tab).forEachIndexed {
                    index,
                    label ->
                    Tab(
                        selected = state.role == index,
                        enabled = !state.busy,
                        onClick = { actions.role(index) },
                        text = { Text(stringResource(label)) },
                    )
                }
            }
        }
        if (state.busy)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        state.status.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        state.error?.let { error ->
            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            stringResource(R.string.sync_error_fmt, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
        if (state.role == 1) {
            item {
                SyncCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SyncIcon(Icons.Outlined.WifiTethering)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                stringResource(R.string.sync_receive_switch_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    if (state.receiverOn) R.string.sync_receive_on
                                    else R.string.sync_receive_off
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.receiverOn,
                            onCheckedChange = actions.receiver,
                            enabled = !state.busy,
                        )
                    }
                }
            }
            if (state.receiverOn)
                item {
                    SyncCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.sync_pair_code_short),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    if (state.pairAvailable)
                                        stringResource(
                                            R.string.sync_expires_in,
                                            state.secondsLeft / 60,
                                            state.secondsLeft % 60,
                                        )
                                    else stringResource(R.string.sync_code_expired),
                                    Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Text(
                            if (state.pairAvailable) state.pairCode.orEmpty().replace("-", "  ")
                            else "••••  ••••  ••••  ••••",
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { actions.copy(codeLabel, state.pairCode.orEmpty()) },
                                enabled = state.pairAvailable && !state.busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.sync_copy_code))
                            }
                            OutlinedButton(
                                onClick = actions.regenerate,
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.sync_new_code))
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            stringResource(R.string.sync_local_address),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.localIps.isEmpty())
                            Text(
                                stringResource(R.string.sync_wifi_missing),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        state.localIps.forEach { ip ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ip,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = FontFamily.Monospace,
                                )
                                IconButton(onClick = { actions.copy("IP", ip) }) {
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        stringResource(R.string.action_copy),
                                        Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.sync_receive_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        } else {
            if (state.trusted.isNotEmpty()) {
                item {
                    SyncSectionTitle(
                        stringResource(R.string.sync_trusted_title),
                        stringResource(R.string.sync_trusted_short_hint),
                    )
                }
                items(state.trusted, key = { it.deviceId }) { device ->
                    Card(
                        onClick = { actions.select(device) },
                        enabled = !state.busy,
                        shape = RoundedCornerShape(18.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (state.selectedId == device.deviceId)
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (state.selectedId == device.deviceId) Icons.Outlined.CheckCircle
                                else Icons.Outlined.Devices,
                                null,
                                Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(device.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    device.lastHost ?: stringResource(R.string.sync_paired_status),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(enabled = !state.busy, onClick = { forget = device }) {
                                Icon(
                                    Icons.Outlined.LinkOff,
                                    stringResource(R.string.sync_remove_trust),
                                    Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (selected != null) item { SyncConnectionForm(state, selected, actions) }
            item {
                SyncSectionTitle(
                    stringResource(R.string.sync_nearby_title),
                    stringResource(R.string.sync_nearby_hint),
                )
            }
            if (state.discovered.isEmpty())
                item {
                    SyncCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SyncIcon(Icons.Outlined.WifiFind)
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.sync_searching_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(R.string.sync_searching_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            else
                items(state.discovered, key = { "${it.host}:${it.port}" }) { device ->
                    Card(
                        onClick = { actions.discover(device) },
                        enabled = !state.busy,
                        shape = RoundedCornerShape(18.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Devices,
                                null,
                                Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    device.serviceName.removePrefix("KeyRing-"),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    device.host,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp))
                        }
                    }
                }
            if (selected == null) item { SyncConnectionForm(state, selected, actions) }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.MergeType,
                    null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            R.string.sync_policy_compact,
                            stringResource(
                                when (state.policy) {
                                    AppPreferences.DuplicatePolicy.SKIP ->
                                        R.string.settings_sync_dup_skip
                                    AppPreferences.DuplicatePolicy.OVERWRITE_NEWER ->
                                        R.string.settings_sync_dup_overwrite
                                    AppPreferences.DuplicatePolicy.KEEP_BOTH ->
                                        R.string.settings_sync_dup_keep_both
                                }
                            ),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.sync_dup_policy_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.pull != null || state.push != null)
            item {
                SyncCard {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.sync_sync_done),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    state.pull?.let {
                        Text(
                            stringResource(
                                R.string.sync_stats_pull_fmt,
                                it.inserted,
                                it.updated,
                                it.skipped,
                                it.duplicatedAsCopy,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.push?.let {
                        Text(
                            stringResource(
                                R.string.sync_stats_push_fmt,
                                it.inserted,
                                it.updated,
                                it.skipped,
                                it.duplicatedAsCopy,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
    }
    if (editName)
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text(stringResource(R.string.sync_rename_device)) },
            text = {
                OutlinedTextField(
                    nameInput,
                    { nameInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.sync_device_name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.rename(nameInput)
                        editName = false
                    },
                    enabled = nameInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save_entry))
                }
            },
            dismissButton = {
                TextButton(onClick = { editName = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    forget?.let { device ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text(stringResource(R.string.sync_remove_trust)) },
            text = { Text(stringResource(R.string.sync_forget_confirm, device.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.forget(device.deviceId)
                        forget = null
                    }
                ) {
                    Text(stringResource(R.string.sync_remove_trust))
                }
            },
            dismissButton = {
                TextButton(onClick = { forget = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun SyncConnectionForm(
    state: SyncViewState,
    selected: AppPreferences.TrustedDevice?,
    actions: SyncActions,
) {
    SyncCard {
        Text(
            selected?.name ?: stringResource(R.string.sync_pair_form_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(
                if (selected == null) R.string.sync_pair_form_hint
                else R.string.sync_known_form_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            state.host,
            actions.host,
            Modifier.fillMaxWidth(),
            enabled = !state.busy,
            singleLine = true,
            label = { Text(stringResource(R.string.sync_manual_ip_label)) },
            placeholder = { Text(stringResource(R.string.sync_manual_ip_hint)) },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        if (selected == null)
            OutlinedTextField(
                state.codeInput,
                actions.code,
                Modifier.fillMaxWidth(),
                enabled = !state.busy,
                singleLine = true,
                label = { Text(stringResource(R.string.sync_pair_code_input_label)) },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
            )
        Button(
            onClick = if (selected == null) actions.pair else actions.sync,
            enabled =
                !state.busy &&
                    state.host.isNotBlank() &&
                    (selected != null || PairingEngine.isValidPairCode(state.codeInput)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                if (selected == null) Icons.Outlined.Link else Icons.Outlined.Sync,
                null,
                Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (selected == null) R.string.sync_pair_button else R.string.sync_now_short
                )
            )
        }
        if (selected != null)
            TextButton(
                onClick = actions.newConnection,
                enabled = !state.busy,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(R.string.sync_another_device))
            }
    }
}

@Composable
private fun SyncCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SyncIcon(icon: ImageVector) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f),
    ) {
        Icon(
            icon,
            null,
            Modifier.padding(11.dp).size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SyncSectionTitle(title: String, hint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun SyncConnectPreview() {
    MyPasswordsTheme(dynamicColor = false) {
        SyncScreenContent(
            SyncViewState(
                deviceName = "我的手机",
                discovered = listOf(LanSyncNsd.ResolvedService("KeyRing-平板", "192.168.1.12", 39277)),
            ),
            SyncActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun SyncReceivePreview() {
    MyPasswordsTheme(dynamicColor = false) {
        SyncScreenContent(
            SyncViewState(
                deviceName = "我的手机",
                role = 1,
                receiverOn = true,
                pairAvailable = true,
                pairCode = "ABCD-EFGH-JKLM-NPQR",
                localIps = listOf("192.168.1.10"),
                secondsLeft = 279,
            ),
            SyncActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun SyncDarkPreview() {
    MyPasswordsTheme(darkTheme = true, dynamicColor = false) {
        SyncScreenContent(SyncViewState(deviceName = "My phone", role = 1), SyncActions())
    }
}
