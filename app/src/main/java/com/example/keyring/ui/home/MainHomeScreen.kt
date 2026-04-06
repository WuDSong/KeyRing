package com.example.keyring.ui.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.keyring.R
import com.example.keyring.data.AppPreferences
import com.example.keyring.data.BiometricPasswordVault
import com.example.keyring.data.PasswordEntryRepository
import com.example.keyring.data.PasswordStore
import com.example.keyring.data.ThemeMode
import com.example.keyring.ui.auth.ChangePasswordScreen
import com.example.keyring.ui.entries.AddPasswordScreen
import com.example.keyring.ui.entries.PasswordEntryDetailScreen
import com.example.keyring.ui.entries.PasswordListScreen
import com.example.keyring.ui.entries.PasswordSearchScreen
import com.example.keyring.ui.navigation.HomeRoutes
import com.example.keyring.util.PasswordBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainHomeScreen(
    passwordStore: PasswordStore,
    entryRepository: PasswordEntryRepository,
    appPreferences: AppPreferences,
    biometricVault: BiometricPasswordVault,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onRequireRelogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showChangePassword by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    val navController = rememberNavController()
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val drawerWidth = minOf(screenWidth * 0.86f, 360.dp)
    val entryCount by entryRepository.observeEntryCount().collectAsStateWithLifecycle(
        initialValue = 0
    )
    val lastUnlockAtMillis by appPreferences.observeLastSuccessfulUnlockTimestamp()
        .collectAsStateWithLifecycle(
            initialValue = appPreferences.getLastSuccessfulUnlockTimestamp()
        )

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val entries = entryRepository.getAllEntries()
                PasswordBackup.exportToZip(context, uri, entries)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_export_success),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_export_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val count = PasswordBackup.importFrom(context, uri, entryRepository)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_import_success, count),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.backup_import_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                    DrawerHeaderBanner()
                    DrawerStatsCard(
                        entryCount = entryCount,
                        lastUnlockAtMillis = lastUnlockAtMillis
                    )
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.drawer_change_password)) },
                        selected = false,
                        onClick = {
                            showChangePassword = true
                            scope.launch { drawerState.close() }
                        }
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.FileUpload,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.drawer_export_data)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                exportLauncher.launch(
                                    "KeyRing_backup_${System.currentTimeMillis()}.zip"
                                )
                            }
                        }
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.drawer_import_data)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                importLauncher.launch(
                                    arrayOf("application/zip", "application/json")
                                )
                            }
                        }
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.drawer_settings)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                navController.navigate(HomeRoutes.SETTINGS)
                            }
                        }
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.drawer_lan_sync)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                navController.navigate(HomeRoutes.LAN_SYNC)
                            }
                        }
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Feedback,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.drawer_feedback)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                showFeedbackDialog = true
                            }
                        }
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Translate,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.drawer_language)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                navController.navigate(HomeRoutes.LANGUAGE)
                            }
                        }
                    )
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null
                            )
                        },
                        label = { Text(stringResource(R.string.drawer_about_app)) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                navController.navigate(HomeRoutes.ABOUT)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        },
        modifier = modifier
    ) {
        MainScaffoldContent(
            showChangePassword = showChangePassword,
            onDismissChangePassword = { showChangePassword = false },
            passwordStore = passwordStore,
            onRequireRelogin = onRequireRelogin,
            entryRepository = entryRepository,
            appPreferences = appPreferences,
            biometricVault = biometricVault,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            navController = navController,
            drawerState = drawerState,
            scope = scope
        )
    }

    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = { showFeedbackDialog = false },
            title = { Text(stringResource(R.string.feedback_dialog_title)) },
            text = { Text(stringResource(R.string.feedback_dialog_message)) },
            confirmButton = {
                TextButton(onClick = { showFeedbackDialog = false }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffoldContent(
    showChangePassword: Boolean,
    onDismissChangePassword: () -> Unit,
    passwordStore: PasswordStore,
    onRequireRelogin: () -> Unit,
    entryRepository: PasswordEntryRepository,
    appPreferences: AppPreferences,
    biometricVault: BiometricPasswordVault,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    navController: NavHostController,
    drawerState: DrawerState,
    scope: CoroutineScope
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route.orEmpty()
    val isAdd = route == HomeRoutes.ADD
    val isDetail = route == HomeRoutes.DETAIL || route.startsWith("detail/")
    val isEdit = route == HomeRoutes.EDIT || route.startsWith("edit/")
    val isList = route == HomeRoutes.LIST
    val isSearch = route == HomeRoutes.SEARCH
    val isSettings = route == HomeRoutes.SETTINGS
    val isLanSync = route == HomeRoutes.LAN_SYNC
    val isLanguage = route == HomeRoutes.LANGUAGE
    val isAbout = route == HomeRoutes.ABOUT
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchFieldFocusRequester = remember { FocusRequester() }
    var topBarSaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDeleteEntrySheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val deleteSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(route) {
        when (route) {
            HomeRoutes.LIST -> searchQuery = ""
            HomeRoutes.SEARCH -> {
                delay(50)
                searchFieldFocusRequester.requestFocus()
            }
            else -> Unit
        }
    }

    LaunchedEffect(isDetail) {
        if (!isDetail) {
            showDeleteEntrySheet = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            when {
                showChangePassword -> {
                    TopAppBar(
                        title = { Text(stringResource(R.string.change_password_title)) },
                        navigationIcon = {
                            IconButton(onClick = { onDismissChangePassword() }) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.content_desc_back)
                                )
                            }
                        }
                    )
                }
                isSearch -> {
                    Column {
                        TopAppBar(
                            title = {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFieldFocusRequester),
                                    placeholder = {
                                        Text(stringResource(R.string.search_hint))
                                    },
                                    singleLine = true,
                                    maxLines = 1
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.content_desc_back)
                                    )
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
                else -> {
                    TopAppBar(
                        title = {
                            when {
                                isAdd -> Text(stringResource(R.string.add_entry_title))
                                isEdit -> Text(stringResource(R.string.edit_entry_title))
                                isDetail -> Text(stringResource(R.string.detail_title))
                                isSettings -> Text(stringResource(R.string.settings_title))
                                isLanSync -> Text(stringResource(R.string.sync_screen_title))
                                isLanguage -> Text(stringResource(R.string.language_title))
                                isAbout -> Text(stringResource(R.string.about_screen_title))
                                isList -> {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Surface(
                                            onClick = { navController.navigate(HomeRoutes.SEARCH) },
                                            shape = RoundedCornerShape(24.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                            modifier = Modifier.fillMaxWidth(0.88f)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Search,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = stringResource(R.string.search_hint),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> Text(stringResource(R.string.main_title))
                            }
                        },
                        navigationIcon = {
                            when {
                                isAdd || isDetail || isEdit || isSettings || isLanSync || isLanguage || isAbout -> {
                                    IconButton(onClick = { navController.popBackStack() }) {
                                        Icon(
                                            imageVector = Icons.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.content_desc_back)
                                        )
                                    }
                                }
                                else -> {
                                    IconButton(
                                        onClick = {
                                            scope.launch { drawerState.open() }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Menu,
                                            contentDescription = stringResource(R.string.main_menu_content_desc)
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            when {
                                isAdd || isEdit -> {
                                    IconButton(onClick = { topBarSaveAction?.invoke() }) {
                                        Icon(
                                            imageVector = Icons.Filled.Save,
                                            contentDescription = stringResource(R.string.action_save_entry)
                                        )
                                    }
                                }
                                isDetail -> {
                                    val entryId = backStackEntry?.arguments?.getLong("entryId")
                                    if (entryId != null) {
                                        IconButton(
                                            onClick = { showDeleteEntrySheet = true }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Delete,
                                                contentDescription = stringResource(R.string.content_desc_delete_entry)
                                            )
                                        }
                                        IconButton(
                                            onClick = { navController.navigate(HomeRoutes.edit(entryId)) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                contentDescription = stringResource(R.string.content_desc_edit_entry)
                                            )
                                        }
                                    }
                                }
                                isList -> {
                                    IconButton(
                                        onClick = { navController.navigate(HomeRoutes.ADD) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = stringResource(R.string.content_desc_add_entry)
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (showChangePassword) {
                ChangePasswordScreen(
                    passwordStore = passwordStore,
                    onSuccess = {
                        onDismissChangePassword()
                        onRequireRelogin()
                    },
                    onCancel = { onDismissChangePassword() },
                    onPasswordChanged = {
                        biometricVault.clear()
                        appPreferences.setBiometricUnlockEnabled(false)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                NavHost(
                    navController = navController,
                    startDestination = HomeRoutes.LIST,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(HomeRoutes.LIST) {
                        PasswordListScreen(
                            repository = entryRepository,
                            onEntryClick = { id -> navController.navigate(HomeRoutes.detail(id)) }
                        )
                    }
                    composable(HomeRoutes.SEARCH) {
                        PasswordSearchScreen(
                            repository = entryRepository,
                            query = searchQuery,
                            onEntryClick = { id -> navController.navigate(HomeRoutes.detail(id)) }
                        )
                    }
                    composable(HomeRoutes.ADD) {
                        AddPasswordScreen(
                            repository = entryRepository,
                            appPreferences = appPreferences,
                            onSaved = { navController.popBackStack() },
                            onRegisterTopBarSave = { fn -> topBarSaveAction = fn },
                            onOpenSettings = { navController.navigate(HomeRoutes.SETTINGS) }
                        )
                    }
                    composable(
                        route = HomeRoutes.DETAIL,
                        arguments = listOf(
                            navArgument("entryId") { type = NavType.LongType }
                        )
                    ) {
                        val entryId = it.arguments?.getLong("entryId") ?: return@composable
                        PasswordEntryDetailScreen(
                            entryId = entryId,
                            repository = entryRepository,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = HomeRoutes.EDIT,
                        arguments = listOf(
                            navArgument("entryId") { type = NavType.LongType }
                        )
                    ) {
                        val entryId = it.arguments?.getLong("entryId") ?: return@composable
                        AddPasswordScreen(
                            repository = entryRepository,
                            appPreferences = appPreferences,
                            entryId = entryId,
                            onSaved = { navController.popBackStack() },
                            onRegisterTopBarSave = { fn -> topBarSaveAction = fn },
                            onOpenSettings = { navController.navigate(HomeRoutes.SETTINGS) }
                        )
                    }
                    composable(HomeRoutes.SETTINGS) {
                        SettingsScreen(
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            appPreferences = appPreferences,
                            passwordStore = passwordStore,
                            biometricVault = biometricVault,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    composable(HomeRoutes.LANGUAGE) {
                        LanguageScreen(
                            appPreferences = appPreferences,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    composable(HomeRoutes.LAN_SYNC) {
                        SyncScreen(
                            repository = entryRepository,
                            appPreferences = appPreferences,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    composable(HomeRoutes.ABOUT) {
                        AboutScreen(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

        if (showDeleteEntrySheet && isDetail) {
            val deleteEntryId = backStackEntry?.arguments?.getLong("entryId")
            if (deleteEntryId != null) {
                ModalBottomSheet(
                    onDismissRequest = { showDeleteEntrySheet = false },
                    sheetState = deleteSheetState
                ) {
                    DeleteEntryConfirmSheet(
                        passwordStore = passwordStore,
                        onDismiss = { showDeleteEntrySheet = false },
                        onVerifiedDelete = {
                            scope.launch(Dispatchers.IO) {
                                entryRepository.deleteEntry(context, deleteEntryId)
                                withContext(Dispatchers.Main) {
                                    showDeleteEntrySheet = false
                                    navController.popBackStack(
                                        HomeRoutes.LIST,
                                        inclusive = false
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteEntryConfirmSheet(
    passwordStore: PasswordStore,
    onDismiss: () -> Unit,
    onVerifiedDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(280)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
        Text(
            text = stringResource(R.string.delete_entry_sheet_title),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.delete_entry_sheet_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = { Text(stringResource(R.string.auth_password_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            )
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.delete_entry_cancel))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    if (passwordStore.verifyPassword(password)) {
                        onVerifiedDelete()
                    } else {
                        errorMessage = context.getString(R.string.delete_entry_error_password)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(stringResource(R.string.delete_entry_confirm))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun DrawerStatsCard(
    entryCount: Int,
    lastUnlockAtMillis: Long,
    modifier: Modifier = Modifier
) {
    val pattern = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val unlockLine = if (lastUnlockAtMillis <= 0L) {
        stringResource(R.string.drawer_stats_last_unlock_none)
    } else {
        val formatted = remember(lastUnlockAtMillis, pattern) {
            val zdt = Instant.ofEpochMilli(lastUnlockAtMillis).atZone(ZoneId.systemDefault())
            pattern.format(zdt)
        }
        stringResource(R.string.drawer_stats_last_unlock, formatted)
    }
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.drawer_stats_total_entries, entryCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = unlockLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DrawerHeaderBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val widthPx = with(density) { widthDp.dp.roundToPx() }
    val ratio = 1920f / 1280f
    val heightPx = (widthPx / ratio).roundToInt().coerceAtLeast(1)

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(R.drawable.menu_main_img)
            .size(widthPx, heightPx)
            .build(),
        contentDescription = null,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio),
        contentScale = ContentScale.Crop
    )
}


