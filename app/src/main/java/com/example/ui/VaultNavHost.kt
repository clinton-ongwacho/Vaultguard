package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.data.entity.DisguiseIdentity
import com.example.ui.screens.AppsScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DisguiseStealthScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VaultScreen

object VaultRoutes {
    const val GUARD = "guard"
    const val APPS = "apps"
    const val VAULT = "vault"
    const val DISGUISE = "disguise"
    const val SETTINGS = "settings"
}

private data class NavTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

private val NAV_TABS = listOf(
    NavTab(
        route = VaultRoutes.GUARD,
        labelRes = R.string.nav_guard,
        selectedIcon = Icons.Rounded.Shield,
        unselectedIcon = Icons.Outlined.Shield,
        testTag = "nav_tab_guard",
    ),
    NavTab(
        route = VaultRoutes.APPS,
        labelRes = R.string.nav_apps,
        selectedIcon = Icons.Rounded.Apps,
        unselectedIcon = Icons.Outlined.Apps,
        testTag = "nav_tab_apps",
    ),
    NavTab(
        route = VaultRoutes.VAULT,
        labelRes = R.string.nav_vault,
        selectedIcon = Icons.Rounded.Lock,
        unselectedIcon = Icons.Outlined.Lock,
        testTag = "nav_tab_vault",
    ),
    NavTab(
        route = VaultRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings",
    ),
)

@Composable
fun VaultConsoleHost(
    state: VaultUiState,
    viewModel: VaultViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenNotificationPolicySettings: () -> Unit,
    onSuppressNextBackgroundLock: () -> Unit,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusBannerMessage) {
        val msg = state.statusBannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        NAV_TABS.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            val label = stringResource(tab.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = label,
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        NAV_TABS.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            val label = stringResource(tab.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = label,
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = VaultRoutes.GUARD,
                    ) {
                        composable(VaultRoutes.GUARD) {
                            DashboardScreen(
                                state = state,
                                onNavigateToApps = { navigateToTab(VaultRoutes.APPS) },
                                onNavigateToDisguise = {
                                    navController.navigate(VaultRoutes.DISGUISE) {
                                        launchSingleTop = true
                                    }
                                },
                                onRefreshSystemStatus = viewModel::refreshSystemStatus,
                                onClearLogs = viewModel::clearLogs,
                                onCheckForUpdates = viewModel::checkForUpdates,
                                onDownloadAndInstallUpdate = viewModel::downloadAndInstallUpdate,
                                onInstallVerifiedApk = viewModel::installVerifiedApk,
                                onOpenUnknownSourcesSettings = viewModel::openUnknownSourcesSettings,
                                onDismissUpdateBanner = viewModel::dismissUpdateBanner,
                                onInspectApkForPublish = viewModel::inspectApkForPublish,
                                onPublishOtaUpdate = viewModel::publishOtaUpdate,
                                onSuppressNextBackgroundLock = onSuppressNextBackgroundLock,
                            )
                        }
                        composable(VaultRoutes.APPS) {
                            AppsScreen(
                                state = state,
                                onScreenOpened = viewModel::onAppsScreenOpened,
                                onSearchChanged = viewModel::setAppSearchQuery,
                                onToggleLimitedOnly = viewModel::setShowLimitedOnly,
                                onToggleSystemApps = viewModel::setShowSystemApps,
                                onSetAppGuardRule = viewModel::setAppGuardRule,
                                onRefreshApps = viewModel::refreshInstalledApps,
                            )
                        }
                        composable(VaultRoutes.VAULT) {
                            VaultScreen(
                                state = state,
                                onUnlockVaultMedia = viewModel::unlockVaultMedia,
                                onLockVaultMedia = viewModel::lockVaultMedia,
                                onClearVaultUnlockError = viewModel::clearVaultUnlockError,
                                onImportMediaUris = viewModel::importMediaToVault,
                                onGalleryDeleteCompleted = viewModel::onGalleryDeleteCompleted,
                                onDeleteMediaReturningToGallery = viewModel::deleteVaultMediaReturningToGallery,
                                onDecryptPhotoBytes = viewModel::decryptVaultPhotoBytes,
                                onDecryptStreamFile = viewModel::decryptVaultStreamFile,
                                onDecryptDocPreviewFile = viewModel::decryptVaultDocPreviewFile,
                                onSuppressNextBackgroundLock = onSuppressNextBackgroundLock,
                            )
                        }
                        composable(VaultRoutes.DISGUISE) {
                            DisguiseStealthScreen(
                                state = state,
                                onApplyDisguiseAndHide = { identity: DisguiseIdentity, hide: Boolean ->
                                    viewModel.applyDisguiseAndHiddenState(identity, hide)
                                },
                                onUpdateRedirectDisplay = viewModel::setRedirectDisplayStyle,
                                onUpdateStealthTriggers = viewModel::updateStealthTriggers,
                                onVerifyAdminPassword = viewModel::verifyAdminPassword,
                                onOpenAccessibilitySettings = {
                                    onSuppressNextBackgroundLock()
                                    onOpenAccessibilitySettings()
                                },
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                        composable(VaultRoutes.SETTINGS) {
                            SettingsScreen(
                                state = state,
                                onOpenAccessibilitySettings = {
                                    onSuppressNextBackgroundLock()
                                    onOpenAccessibilitySettings()
                                },
                                onRequestDeviceAdmin = {
                                    onSuppressNextBackgroundLock()
                                    onRequestDeviceAdmin()
                                },
                                onOpenNotificationListenerSettings = {
                                    onSuppressNextBackgroundLock()
                                    onOpenNotificationListenerSettings()
                                },
                                onOpenNotificationPolicySettings = {
                                    onSuppressNextBackgroundLock()
                                    onOpenNotificationPolicySettings()
                                },
                                onToggleNotificationBlock = viewModel::setNotificationBlockEnabled,
                                onToggleCapturePhoto = viewModel::setCapturePhotoOnOpenEnabled,
                                onRefreshSystemStatus = viewModel::refreshSystemStatus,
                                onToggleUninstallGuard = viewModel::setUninstallProtection,
                                onToggleInstallGuard = viewModel::setInstallGuard,
                                onUpdateStealthTriggers = viewModel::updateStealthTriggers,
                                onUpdateRedirectDisplay = viewModel::setRedirectDisplayStyle,
                                onSetRedirectDecoyMode = viewModel::setRedirectDecoyMode,
                                onSetImmediateLockTimeoutSeconds = viewModel::setImmediateLockTimeoutSeconds,
                                onChangePassword = viewModel::changeAdminPassword,
                                onVerifyAdminPassword = viewModel::verifyAdminPassword,
                                onDecryptAnswer = viewModel::decryptStoredAnswer,
                                onSaveSecurityQuestions = viewModel::saveSecurityQuestions,
                                onSuppressNextBackgroundLock = onSuppressNextBackgroundLock,
                                onLockConsole = viewModel::lockSession,
                                onSignOut = onSignOut,
                            )
                        }
                    }
                }
            }
        }
    }
}

