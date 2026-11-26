package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.entity.SecurityQuestionEntity
import com.example.restriction.SystemRequirementIssue
import com.example.ui.VaultUiState
import com.example.ui.components.AdminPasswordConfirmDialog
import com.example.ui.components.StealthTriggersProtectedCard
import com.example.ui.components.SystemIntegrityBanner

private sealed interface PendingSecurityAction {
    data class ToggleNotificationBlock(val enabled: Boolean) : PendingSecurityAction
    data class ToggleCapturePhoto(val enabled: Boolean) : PendingSecurityAction
    data class ToggleUninstallGuard(val enabled: Boolean) : PendingSecurityAction
    data class ToggleInstallGuard(val enabled: Boolean) : PendingSecurityAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
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
    onChangePassword: (current: String, next: String, callback: (Boolean, String) -> Unit) -> Unit,
    onVerifyAdminPassword: (password: String, onResult: (Boolean) -> Unit) -> Unit,
    onDecryptAnswer: (SecurityQuestionEntity) -> String,
    onSaveSecurityQuestions: (List<Pair<String, String>>, (Boolean, String) -> Unit) -> Unit,
    onSuppressNextBackgroundLock: () -> Unit,
    onLockConsole: () -> Unit,
    onSignOut: () -> Unit,
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showQuestionsRevealAuthDialog by remember { mutableStateOf(false) }
    var showQuestionsEditorDialog by remember { mutableStateOf(false) }
    var answersRevealed by remember { mutableStateOf(false) }
    var cameraDeniedMessage by remember { mutableStateOf<String?>(null) }
    var pendingProtectedAction by remember { mutableStateOf<PendingSecurityAction?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onRefreshSystemStatus()
        cameraDeniedMessage = if (granted) null else "Camera permission denied"
    }

    val postNotificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        onRefreshSystemStatus()
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
            if (showHeader) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Security",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                }
                            },
                            onReverify = onRefreshSystemStatus,
                        )
                    }
                }

                // Account Section
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
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AccountCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = state.userDisplayName.ifBlank { "Google Account" },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (state.userEmail.isNotBlank()) {
                                        Text(
                                            text = state.userEmail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                StatusPill(enabled = state.currentUserId.isNotBlank())
                            }
                            if (state.cloudSyncError != null) {
                                Text(
                                    text = state.cloudSyncError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            OutlinedButton(
                                onClick = onSignOut,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("google_sign_out_button"),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Sign Out")
                            }
                        }
                    }
                }

                // Live System Services Verification
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
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AccessibilityNew,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "Accessibility",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatusPill(enabled = state.isAccessibilityEnabled)
                                }
                                Button(
                                    onClick = onOpenAccessibilitySettings,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("security_accessibility_button"),
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
                                        onCheckedChange = { next ->
                                            pendingProtectedAction = PendingSecurityAction.ToggleNotificationBlock(next)
                                        },
                                        modifier = Modifier.testTag("notification_block_switch"),
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "Notification Access",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    StatusPill(enabled = state.isNotificationListenerEnabled)
                                }

                                OutlinedButton(
                                    onClick = onOpenNotificationListenerSettings,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("security_notification_access_button"),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Text(if (state.isNotificationListenerEnabled) "Manage" else "Enable")
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
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AdminPanelSettings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "Device Admin",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatusPill(enabled = state.isDeviceAdminEnabled)
                                }
                                OutlinedButton(
                                    onClick = onRequestDeviceAdmin,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("security_device_admin_button"),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Text(if (state.isDeviceAdminEnabled) "Manage" else "Activate")
                                }
                            }
                        }
                    }
                }

                // Intruder Snap & Tamper Guards (Password-protected)
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
                                            imageVector = Icons.Rounded.CameraAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = "Intruder Snap",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                    Switch(
                                        checked = state.config.capturePhotoOnOpenEnabled,
                                        onCheckedChange = { enabled ->
                                            pendingProtectedAction = PendingSecurityAction.ToggleCapturePhoto(enabled)
                                        },
                                        modifier = Modifier.testTag("capture_photo_switch"),
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "Camera",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    StatusPill(enabled = state.isCameraPermissionGranted)
                                }

                                if (cameraDeniedMessage != null && !state.isCameraPermissionGranted) {
                                    Text(
                                        text = cameraDeniedMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }

                                if (!state.isCameraPermissionGranted) {
                                    OutlinedButton(
                                        onClick = {
                                            onSuppressNextBackgroundLock()
                                            if (cameraDeniedMessage != null) {
                                                val intent = Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.fromParts("package", context.packageName, null),
                                                ).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { context.startActivity(intent) }
                                            } else {
                                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("security_camera_permission_button"),
                                        shape = MaterialTheme.shapes.medium,
                                    ) {
                                        Text("Grant Camera")
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "Tamper & Cache Guard",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                Switch(
                                    checked = state.config.uninstallProtectionEnabled,
                                    onCheckedChange = { enabled ->
                                        pendingProtectedAction = PendingSecurityAction.ToggleUninstallGuard(enabled)
                                    },
                                    modifier = Modifier.testTag("uninstall_guard_switch"),
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.InstallMobile,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "Install Lock",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                Switch(
                                    checked = state.config.installGuardEnabled,
                                    onCheckedChange = { enabled ->
                                        pendingProtectedAction = PendingSecurityAction.ToggleInstallGuard(enabled)
                                    },
                                    modifier = Modifier.testTag("install_guard_switch"),
                                )
                            }
                        }
                    }
                }

                // Reset Questions
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
                                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "Reset Questions (${state.securityQuestions.size}/3+)",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                StatusPill(enabled = state.securityQuestions.size >= 3)
                            }

                            if (state.securityQuestions.isEmpty()) {
                                Text(
                                    text = "Add 3 questions",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                state.securityQuestions.forEachIndexed { idx, q ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.background,
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Text(
                                                text = "${idx + 1}. ${q.question}",
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                            Text(
                                                text = if (answersRevealed) {
                                                    onDecryptAnswer(q)
                                                } else {
                                                    "••••••••"
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (answersRevealed) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (answersRevealed) {
                                            answersRevealed = false
                                        } else {
                                            showQuestionsRevealAuthDialog = true
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("toggle_reveal_answers_button"),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Icon(
                                        imageVector = if (answersRevealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = null,
                                    )
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(if (answersRevealed) "Hide" else "Reveal")
                                }

                                Button(
                                    onClick = {
                                        if (answersRevealed || state.securityQuestions.isEmpty()) {
                                            showQuestionsEditorDialog = true
                                        } else {
                                            showQuestionsRevealAuthDialog = true
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("manage_security_questions_button"),
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Text(if (state.securityQuestions.isEmpty()) "Add" else "Edit")
                                }
                            }
                        }
                    }
                }

                item {
                    StealthTriggersProtectedCard(
                        wakeWord = state.config.wakeWord,
                        dialerPin = state.config.dialerPin,
                        isAccessibilityEnabled = state.isAccessibilityEnabled,
                        onUpdateStealthTriggers = onUpdateStealthTriggers,
                        onVerifyAdminPassword = onVerifyAdminPassword,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    )
                }

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
                            Text(
                                text = "Admin Credentials",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            OutlinedButton(
                                onClick = { showChangePasswordDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("change_password_button"),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.Rounded.Key, contentDescription = null)
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Change Password")
                            }
                            Button(
                                onClick = onLockConsole,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("security_lock_console_button"),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = null)
                                Spacer(modifier = Modifier.size(6.dp))
                                Text("Lock Now")
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(10.dp)) }
            }
        }
    }

    pendingProtectedAction?.let { action ->
        val dialogTitle = when (action) {
            is PendingSecurityAction.ToggleCapturePhoto -> "Confirm Intruder Snap"
            is PendingSecurityAction.ToggleNotificationBlock -> "Confirm Silent Alerts"
            is PendingSecurityAction.ToggleUninstallGuard -> "Confirm Tamper Guard"
            is PendingSecurityAction.ToggleInstallGuard -> "Confirm Install Lock"
        }
        AdminPasswordConfirmDialog(
            title = dialogTitle,
            onDismiss = { pendingProtectedAction = null },
            onVerifyAdminPassword = onVerifyAdminPassword,
            onConfirmed = {
                when (action) {
                    is PendingSecurityAction.ToggleNotificationBlock -> {
                        onToggleNotificationBlock(action.enabled)
                    }
                    is PendingSecurityAction.ToggleCapturePhoto -> {
                        onToggleCapturePhoto(action.enabled)
                        if (action.enabled && !state.isCameraPermissionGranted) {
                            onSuppressNextBackgroundLock()
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                    is PendingSecurityAction.ToggleUninstallGuard -> {
                        onToggleUninstallGuard(action.enabled)
                    }
                    is PendingSecurityAction.ToggleInstallGuard -> {
                        onToggleInstallGuard(action.enabled)
                    }
                }
                pendingProtectedAction = null
            },
        )
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onSubmit = { current, next, onDone ->
                onChangePassword(current, next, onDone)
            },
        )
    }

    if (showQuestionsRevealAuthDialog) {
        VerifyPasswordForQuestionsDialog(
            onDismiss = { showQuestionsRevealAuthDialog = false },
            onVerify = { pwd, onError ->
                onVerifyAdminPassword(pwd) { ok ->
                    if (ok) {
                        answersRevealed = true
                        showQuestionsRevealAuthDialog = false
                    } else {
                        onError("Invalid password")
                    }
                }
            },
        )
    }

    if (showQuestionsEditorDialog) {
        SecurityQuestionsEditorDialog(
            existing = state.securityQuestions,
            onDecryptAnswer = onDecryptAnswer,
            onDismiss = { showQuestionsEditorDialog = false },
            onSave = { pairs, onDone ->
                onSaveSecurityQuestions(pairs) { ok, msg ->
                    if (ok) {
                        answersRevealed = false
                        showQuestionsEditorDialog = false
                    }
                    onDone(ok, msg)
                }
            },
        )
    }
}

@Composable
private fun StatusPill(enabled: Boolean) {
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

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (current: String, next: String, callback: (Boolean, String) -> Unit) -> Unit,
) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it; feedback = null },
                    label = { Text("Current Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it; feedback = null },
                    label = { Text("New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; feedback = null },
                    label = { Text("Confirm Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (feedback != null) {
                    Text(
                        text = feedback.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    next.length < 4 -> feedback = "Min 4 characters"
                    next != confirm -> feedback = "Passwords do not match"
                    else -> {
                        onSubmit(current, next) { ok, msg ->
                            if (ok) {
                                onDismiss()
                            } else {
                                feedback = msg
                            }
                        }
                    }
                }
            }) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun VerifyPasswordForQuestionsDialog(
    onDismiss: () -> Unit,
    onVerify: (String, (String) -> Unit) -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin Password") },
        text = {
            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it; err = null },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                isError = err != null,
                supportingText = err?.let { msg -> { Text(msg) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = {
                if (pwd.isBlank()) {
                    err = "Required"
                } else {
                    onVerify(pwd) { msg -> err = msg }
                }
            }) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SecurityQuestionsEditorDialog(
    existing: List<SecurityQuestionEntity>,
    onDecryptAnswer: (SecurityQuestionEntity) -> String,
    onDismiss: () -> Unit,
    onSave: (List<Pair<String, String>>, (Boolean, String) -> Unit) -> Unit,
) {
    val defaultQuestions = listOf(
        "First school name?",
        "Birth city?",
        "Childhood friend name?",
    )
    val initialPairs = remember(existing) {
        if (existing.size >= 3) {
            existing.map { it.question to onDecryptAnswer(it) }
        } else {
            defaultQuestions.mapIndexed { idx, defQ ->
                val e = existing.getOrNull(idx)
                if (e != null) e.question to onDecryptAnswer(e) else defQ to ""
            }
        }
    }
    val questions = remember(existing) {
        mutableStateListOf<String>().apply { addAll(initialPairs.map { it.first }) }
    }
    val answers = remember(existing) {
        mutableStateListOf<String>().apply { addAll(initialPairs.map { it.second }) }
    }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Security Questions") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (i in 0 until questions.size) {
                    Text(
                        text = "Question ${i + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = questions[i],
                        onValueChange = { questions[i] = it; err = null },
                        label = { Text("Question") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = answers[i],
                        onValueChange = { answers[i] = it; err = null },
                        label = { Text("Answer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedButton(
                    onClick = {
                        questions.add("")
                        answers.add("")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text("+ Add Question")
                }

                if (err != null) {
                    Text(
                        text = err.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val pairs = questions.indices.map { i -> questions[i].trim() to answers[i].trim() }
                    .filter { (q, a) -> q.isNotBlank() && a.isNotBlank() }
                if (pairs.size < 3) {
                    err = "Min 3 questions required"
                } else {
                    onSave(pairs) { ok, msg ->
                        if (!ok) err = msg
                    }
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
