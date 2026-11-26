package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.entity.DisguiseIdentity
import com.example.data.entity.RedirectDecoyMode
import com.example.data.entity.RedirectDisplayStyle
import com.example.data.entity.SecurityQuestionEntity
import com.example.restriction.SystemRequirementIssue
import com.example.ui.VaultUiState
import com.example.ui.components.AdminPasswordConfirmDialog
import com.example.ui.components.SystemIntegrityBanner

private enum class SettingsSection(val title: String) {
    SECURITY("1. Security"),
    PERMISSIONS("2. Permissions"),
}

private sealed interface PendingPermissionChange {
    data class ToggleNotificationBlock(val enabled: Boolean) : PendingPermissionChange
    data class ToggleUninstallGuard(val enabled: Boolean) : PendingPermissionChange
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: VaultUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onToggleNotificationBlock: (Boolean) -> Unit,
    onToggleCapturePhoto: (Boolean) -> Unit,
    onRefreshSystemStatus: () -> Unit,
    onToggleUninstallGuard: (Boolean) -> Unit,
    onToggleInstallGuard: (Boolean) -> Unit,
    onUpdateStealthTriggers: (wakeWord: String, dialerPin: String) -> Unit,
    onApplyDisguiseAndHide: (DisguiseIdentity, Boolean) -> Unit = { _, _ -> },
    onUpdateRedirectDisplay: (RedirectDisplayStyle) -> Unit,
    onSetRedirectDecoyMode: (RedirectDecoyMode) -> Unit,
    onSetImmediateLockTimeoutSeconds: (Int) -> Unit,
    onChangePassword: (current: String, next: String, callback: (Boolean, String) -> Unit) -> Unit,
    onVerifyAdminPassword: (password: String, onResult: (Boolean) -> Unit) -> Unit,
    onDecryptAnswer: (SecurityQuestionEntity) -> String,
    onSaveSecurityQuestions: (List<Pair<String, String>>, (Boolean, String) -> Unit) -> Unit,
    onSuppressNextBackgroundLock: () -> Unit,
    onLockConsole: () -> Unit,
    onSignOut: () -> Unit,
) {
    var selectedSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    if (selectedSection != null) {
        BackHandler {
            selectedSection = null
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize(),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = selectedSection?.title ?: "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (selectedSection != null) {
                        IconButton(
                            onClick = { selectedSection = null },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("settings_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )

            when (selectedSection) {
                null -> {
                    SettingsMenuList(
                        state = state,
                        onSelectSection = { selectedSection = it },
                    )
                }
                SettingsSection.SECURITY -> {
                    SecurityScreen(
                        state = state,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onRequestDeviceAdmin = onRequestDeviceAdmin,
                        onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                        onToggleNotificationBlock = onToggleNotificationBlock,
                        onToggleCapturePhoto = onToggleCapturePhoto,
                        onRefreshSystemStatus = onRefreshSystemStatus,
                        onToggleUninstallGuard = onToggleUninstallGuard,
                        onToggleInstallGuard = onToggleInstallGuard,
                        onUpdateStealthTriggers = onUpdateStealthTriggers,
                        onChangePassword = onChangePassword,
                        onVerifyAdminPassword = onVerifyAdminPassword,
                        onDecryptAnswer = onDecryptAnswer,
                        onSaveSecurityQuestions = onSaveSecurityQuestions,
                        onSuppressNextBackgroundLock = onSuppressNextBackgroundLock,
                        onLockConsole = onLockConsole,
                        onSignOut = onSignOut,
                        showHeader = false,
                    )
                }
                SettingsSection.PERMISSIONS -> {
                    PermissionsSection(
                        state = state,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onOpenNotificationListenerSettings = onOpenNotificationListenerSettings,
                        onRequestDeviceAdmin = onRequestDeviceAdmin,
                        onToggleNotificationBlock = onToggleNotificationBlock,
                        onToggleUninstallGuard = onToggleUninstallGuard,
                        onApplyDisguiseAndHide = onApplyDisguiseAndHide,
                        onUpdateRedirectDisplay = onUpdateRedirectDisplay,
                        onSetRedirectDecoyMode = onSetRedirectDecoyMode,
                        onSetImmediateLockTimeoutSeconds = onSetImmediateLockTimeoutSeconds,
                        onVerifyAdminPassword = onVerifyAdminPassword,
                        onRefreshSystemStatus = onRefreshSystemStatus,
                        onSuppressNextBackgroundLock = onSuppressNextBackgroundLock,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuList(
    state: VaultUiState,
    onSelectSection: (SettingsSection) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsMenuItemRow(
                        numberLabel = "1",
                        title = "Security",
                        icon = Icons.Rounded.Security,
                        badgeText = if (state.securityQuestions.size >= 3) "Active" else "Setup",
                        onClick = { onSelectSection(SettingsSection.SECURITY) },
                        testTag = "settings_item_security",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsMenuItemRow(
                        numberLabel = "2",
                        title = "Permissions",
                        icon = Icons.Rounded.Tune,
                        badgeText = if (state.systemIntegrity.isIntegrityConfirmed) "Verified" else "Action Needed",
                        onClick = { onSelectSection(SettingsSection.PERMISSIONS) },
                        testTag = "settings_item_permissions",
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuItemRow(
    numberLabel: String,
    title: String,
    icon: ImageVector,
    badgeText: String,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Text(
            text = "$numberLabel. $title",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )

        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PermissionsSection(
    state: VaultUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onToggleNotificationBlock: (Boolean) -> Unit,
    onToggleUninstallGuard: (Boolean) -> Unit,
    onApplyDisguiseAndHide: (DisguiseIdentity, Boolean) -> Unit,
    onUpdateRedirectDisplay: (RedirectDisplayStyle) -> Unit,
    onSetRedirectDecoyMode: (RedirectDecoyMode) -> Unit,
    onSetImmediateLockTimeoutSeconds: (Int) -> Unit,
    onVerifyAdminPassword: (password: String, onResult: (Boolean) -> Unit) -> Unit,
    onRefreshSystemStatus: () -> Unit,
    onSuppressNextBackgroundLock: () -> Unit,
) {
    val isIconDisplayActive = state.activeRedirectDecoyMode == RedirectDecoyMode.ICON_DISPLAY
    val isFakeCrashActive = state.activeRedirectDecoyMode == RedirectDecoyMode.FAKE_CRASH
    var iconDisplayMenuExpanded by remember { mutableStateOf(false) }
    var selectedIdentity by remember(state.activeDisguise) { mutableStateOf(state.activeDisguise) }
    var disguiseMenuExpanded by remember { mutableStateOf(false) }
    var pendingChange by remember { mutableStateOf<PendingPermissionChange?>(null) }

    val postNotificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        onRefreshSystemStatus()
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        onRefreshSystemStatus()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.systemIntegrity.isIntegrityConfirmed) {
            item {
                SystemIntegrityBanner(
                    integrity = state.systemIntegrity,
                    onFixIssue = { issue ->
                        onSuppressNextBackgroundLock()
                        when (issue) {
                            SystemRequirementIssue.ACCESSIBILITY -> onOpenAccessibilitySettings()
                            SystemRequirementIssue.NOTIFICATION_LISTENER -> onOpenNotificationListenerSettings()
                            SystemRequirementIssue.DEVICE_ADMIN -> onRequestDeviceAdmin()
                            SystemRequirementIssue.CAMERA -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            SystemRequirementIssue.POST_NOTIFICATIONS -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }
                    },
                    onReverify = onRefreshSystemStatus,
                )
            }
        }

        // Core Enforcers (Accessibility, Notification Listener, Device Admin)
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccessibilityNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "App Limiter",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            PermissionStatusPill(enabled = state.isAccessibilityEnabled)
                        }
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("permissions_app_limiter_button"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(if (state.isAccessibilityEnabled) "Manage" else "Enable")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.NotificationsOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Silent Alerts",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Switch(
                                checked = state.config.notificationBlockEnabled,
                                onCheckedChange = { enabled ->
                                    pendingChange = PendingPermissionChange.ToggleNotificationBlock(enabled)
                                },
                                modifier = Modifier.testTag("permissions_silent_alerts_switch"),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Notification Access",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PermissionStatusPill(enabled = state.isNotificationListenerEnabled)
                        }
                        OutlinedButton(
                            onClick = onOpenNotificationListenerSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("permissions_notification_listener_button"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(if (state.isNotificationListenerEnabled) "Manage" else "Enable")
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Post Notifications",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                PermissionStatusPill(enabled = state.isPostNotificationsGranted)
                            }
                            if (!state.isPostNotificationsGranted) {
                                OutlinedButton(
                                    onClick = {
                                        onSuppressNextBackgroundLock()
                                        postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("permissions_post_notifications_button"),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Text("Allow")
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AdminPanelSettings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Uninstall Lock",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = state.config.uninstallProtectionEnabled,
                                onCheckedChange = { enabled ->
                                    pendingChange = PendingPermissionChange.ToggleUninstallGuard(enabled)
                                },
                                modifier = Modifier.testTag("permissions_uninstall_guard_switch"),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Device Admin",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PermissionStatusPill(enabled = state.isDeviceAdminEnabled)
                        }
                        OutlinedButton(
                            onClick = onRequestDeviceAdmin,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("permissions_device_admin_button"),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(if (state.isDeviceAdminEnabled) "Manage" else "Activate")
                        }
                    }
                }
            }
        }

        // Icon Display vs Fake Crash (mutually exclusive)
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Icon Display",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Switch(
                                checked = isIconDisplayActive,
                                onCheckedChange = { enable ->
                                    val next = if (enable) {
                                        RedirectDecoyMode.ICON_DISPLAY
                                    } else {
                                        RedirectDecoyMode.FAKE_CRASH
                                    }
                                    onSetRedirectDecoyMode(next)
                                },
                                modifier = Modifier.testTag("permissions_icon_display_switch"),
                            )
                        }

                        if (isIconDisplayActive) {
                            ExposedDropdownMenuBox(
                                expanded = iconDisplayMenuExpanded,
                                onExpandedChange = { iconDisplayMenuExpanded = !iconDisplayMenuExpanded },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val currentDisplay = state.activeRedirectDisplay
                                Surface(
                                    color = MaterialTheme.colorScheme.background,
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (iconDisplayMenuExpanded) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                        .clip(MaterialTheme.shapes.medium)
                                        .testTag("permissions_icon_display_dropdown"),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = redirectStyleIcon(currentDisplay),
                                            contentDescription = currentDisplay.displayLabel,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Text(
                                            text = currentDisplay.displayLabel,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            imageVector = if (iconDisplayMenuExpanded) {
                                                Icons.Rounded.KeyboardArrowUp
                                            } else {
                                                Icons.Rounded.KeyboardArrowDown
                                            },
                                            contentDescription = "Choose Icon Display",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                ExposedDropdownMenu(
                                    expanded = iconDisplayMenuExpanded,
                                    onDismissRequest = { iconDisplayMenuExpanded = false },
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ) {
                                    RedirectDisplayStyle.entries.forEach { style ->
                                        val selected = state.activeRedirectDisplay == style
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = redirectStyleIcon(style),
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                    Text(
                                                        text = style.displayLabel,
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    if (selected) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.CheckCircle,
                                                            contentDescription = "Selected",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                onUpdateRedirectDisplay(style)
                                                iconDisplayMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "Fake Crash",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Switch(
                                checked = isFakeCrashActive,
                                onCheckedChange = { enable ->
                                    val next = if (enable) {
                                        RedirectDecoyMode.FAKE_CRASH
                                    } else {
                                        RedirectDecoyMode.ICON_DISPLAY
                                    }
                                    onSetRedirectDecoyMode(next)
                                },
                                modifier = Modifier.testTag("permissions_fake_crash_switch"),
                            )
                        }

                        if (isFakeCrashActive) {
                            Surface(
                                color = Color.White,
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.2f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("permissions_fake_crash_preview"),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "App keeps stopping",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.Black,
                                    )
                                    Text(
                                        text = "App info • Close app",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Black.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Immediate Lock
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Immediate Lock",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    val timeoutOptions = listOf(
                        0 to "Immediate",
                        5 to "5 seconds",
                        15 to "15 seconds",
                        60 to "1 minute",
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        timeoutOptions.forEach { (seconds, label) ->
                            FilterChip(
                                selected = state.config.immediateLockTimeoutSeconds == seconds,
                                onClick = { onSetImmediateLockTimeoutSeconds(seconds) },
                                label = { Text(label) },
                                leadingIcon = if (state.config.immediateLockTimeoutSeconds == seconds) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.LockClock,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.testTag("immediate_lock_option_${seconds}s"),
                            )
                        }
                    }
                }
            }
        }

        // Launcher Disguise
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.testTag("permissions_launcher_disguise_card"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DisguiseIdentityIcon(identity = selectedIdentity, sizeDp = 24)
                        Text(
                            text = "Launcher Disguise",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = state.activeDisguise.displayLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = disguiseMenuExpanded,
                        onExpandedChange = { disguiseMenuExpanded = !disguiseMenuExpanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (disguiseMenuExpanded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                .clip(MaterialTheme.shapes.medium)
                                .testTag("permissions_launcher_disguise_dropdown"),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                DisguiseIdentityIcon(identity = selectedIdentity, sizeDp = 40)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedIdentity.displayLabel,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = selectedIdentity.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    imageVector = if (disguiseMenuExpanded) {
                                        Icons.Rounded.KeyboardArrowUp
                                    } else {
                                        Icons.Rounded.KeyboardArrowDown
                                    },
                                    contentDescription = "Choose Launcher Disguise",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        ExposedDropdownMenu(
                            expanded = disguiseMenuExpanded,
                            onDismissRequest = { disguiseMenuExpanded = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            DisguiseIdentity.entries.forEach { identity ->
                                val isSelected = selectedIdentity == identity
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            DisguiseIdentityIcon(identity = identity, sizeDp = 34)
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = identity.displayLabel,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isSelected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                )
                                                Text(
                                                    text = identity.subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CheckCircle,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        selectedIdentity = identity
                                        disguiseMenuExpanded = false
                                    },
                                    modifier = Modifier.testTag("disguise_option_${identity.name.lowercase()}"),
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            onApplyDisguiseAndHide(selectedIdentity, state.config.isAppHidden)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("permissions_apply_disguise_button"),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Apply \"${selectedIdentity.displayLabel}\" Disguise")
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(10.dp)) }
    }

    pendingChange?.let { change ->
        val title = when (change) {
            is PendingPermissionChange.ToggleNotificationBlock -> "Confirm Silent Alerts"
            is PendingPermissionChange.ToggleUninstallGuard -> "Confirm Uninstall Lock"
        }
        AdminPasswordConfirmDialog(
            title = title,
            onDismiss = { pendingChange = null },
            onVerifyAdminPassword = onVerifyAdminPassword,
            onConfirmed = {
                when (change) {
                    is PendingPermissionChange.ToggleNotificationBlock -> onToggleNotificationBlock(change.enabled)
                    is PendingPermissionChange.ToggleUninstallGuard -> onToggleUninstallGuard(change.enabled)
                }
                pendingChange = null
            },
        )
    }
}

@Composable
private fun DisguiseIdentityIcon(
    identity: DisguiseIdentity,
    sizeDp: Int,
) {
    if (identity == DisguiseIdentity.DEFAULT) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(sizeDp.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = identity.displayLabel,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((sizeDp * 0.6f).dp),
                )
            }
        }
        return
    }
    val resId = when (identity) {
        DisguiseIdentity.CALCULATOR -> R.drawable.ic_disguise_calculator
        DisguiseIdentity.NOTES -> R.drawable.ic_disguise_notes
        DisguiseIdentity.WEATHER -> R.drawable.ic_disguise_weather
        DisguiseIdentity.CLOCK -> R.drawable.ic_disguise_clock
        DisguiseIdentity.DEFAULT -> R.drawable.ic_disguise_calculator
    }
    Image(
        painter = painterResource(id = resId),
        contentDescription = identity.displayLabel,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun PermissionStatusPill(enabled: Boolean) {
    Surface(
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = if (enabled) "Active" else "Off",
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
