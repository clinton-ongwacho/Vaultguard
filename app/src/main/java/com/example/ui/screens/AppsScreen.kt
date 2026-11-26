package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.entity.AppGuardMode
import com.example.data.entity.NotificationPrivacyMode
import com.example.restriction.EnforcementVerificationState
import com.example.restriction.PackageInspectionReport
import com.example.restriction.SystemRequirementIssue
import com.example.restriction.SystemRestrictionStep
import com.example.restriction.SystemStepKind
import com.example.ui.AppRuleRow
import com.example.ui.VaultUiState
import com.example.ui.components.AppDrawableIcon
import com.example.ui.components.SystemIntegrityBanner

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    state: VaultUiState,
    onScreenOpened: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onToggleLimitedOnly: (Boolean) -> Unit,
    onToggleSystemApps: (Boolean) -> Unit,
    onSetAppGuardRule: (AppRuleRow, AppGuardMode, NotificationPrivacyMode) -> Unit,
    onRefreshApps: () -> Unit,
    onLaunchSystemStep: (String, SystemRestrictionStep) -> Unit,
    onFixSystemIssue: (SystemRequirementIssue) -> Unit,
    onRefreshSystemStatus: () -> Unit,
    onSuppressNextBackgroundLock: () -> Unit = {},
    onInspectApp: (AppRuleRow, Boolean, Boolean) -> Unit = { _, _, _ -> },
    onClearInspectedApp: () -> Unit = {},
    onClearPendingEndRestriction: () -> Unit = {},
) {
    var pendingRulePackage by remember { mutableStateOf<String?>(null) }
    var showNeedsSetupOnly by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        onRefreshSystemStatus()
    }
    val postNotificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        onRefreshSystemStatus()
    }

    var prevPendingEndPkg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.pendingEndRestrictionPackage) {
        val blockedPkg = state.pendingEndRestrictionPackage
        if (blockedPkg != null) {
            pendingRulePackage = blockedPkg
        } else if (prevPendingEndPkg != null && pendingRulePackage == prevPendingEndPkg) {
            pendingRulePackage = null
        }
        prevPendingEndPkg = blockedPkg
    }

    val activeDialogRow = remember(pendingRulePackage, state.apps) {
        pendingRulePackage?.let { pkg -> state.apps.firstOrNull { it.appInfo.packageName == pkg } }
    }

    val displayedApps = remember(state.filteredApps, showNeedsSetupOnly) {
        if (!showNeedsSetupOnly) {
            state.filteredApps
        } else {
            state.filteredApps.filter { row ->
                val report = row.inspectionReport
                (row.isLimited && report?.pendingSystemSteps?.isNotEmpty() == true) ||
                    (report?.restoreSteps?.isNotEmpty() == true)
            }
        }
    }

    LaunchedEffect(Unit) {
        onScreenOpened()
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
                    Column {
                        Text(
                            text = "Manage Apps",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${state.limitedCount} Restricted",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(
                        onClick = onRefreshApps,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_apps_button"),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh apps")
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                if (!state.systemIntegrity.isIntegrityConfirmed) {
                    SystemIntegrityBanner(
                        integrity = state.systemIntegrity,
                        onFixIssue = { issue ->
                            when (issue) {
                                SystemRequirementIssue.CAMERA -> {
                                    onSuppressNextBackgroundLock()
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                                SystemRequirementIssue.POST_NOTIFICATIONS -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        onSuppressNextBackgroundLock()
                                        postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                else -> {
                                    onSuppressNextBackgroundLock()
                                    onFixSystemIssue(issue)
                                }
                            }
                        },
                        onReverify = onRefreshSystemStatus,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }

                OutlinedTextField(
                    value = state.appSearchQuery,
                    onValueChange = onSearchChanged,
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.appSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchChanged("") }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apps_search_input"),
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = state.showLimitedOnly,
                        onClick = { onToggleLimitedOnly(!state.showLimitedOnly) },
                        label = { Text("Restricted (${state.limitedCount})") },
                        modifier = Modifier.testTag("filter_limited_only_chip"),
                    )
                    if (state.pendingSystemGapCount > 0) {
                        FilterChip(
                            selected = showNeedsSetupOnly,
                            onClick = { showNeedsSetupOnly = !showNeedsSetupOnly },
                            label = { Text("Needs System (${state.pendingSystemGapCount})") },
                            modifier = Modifier.testTag("filter_needs_system_chip"),
                        )
                    }
                    FilterChip(
                        selected = state.showSystemApps,
                        onClick = { onToggleSystemApps(!state.showSystemApps) },
                        label = { Text("System Apps") },
                        modifier = Modifier.testTag("filter_system_apps_chip"),
                    )
                }

                if (state.appSearchQuery.isBlank() &&
                    !state.isLoadingApps &&
                    state.suggestedFrequentApps.isNotEmpty()
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state.hasFrequentRestrictionHistory) {
                            "Frequent Restricted"
                        } else {
                            "Quick Select"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("frequent_restricted_apps_row"),
                    ) {
                        items(
                            items = state.suggestedFrequentApps,
                            key = { "freq_${it.appInfo.packageName}" },
                        ) { row ->
                            FrequentAppQuickChip(
                                row = row,
                                onClick = { pendingRulePackage = row.appInfo.packageName },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                when {
                    state.isLoadingApps -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    displayedApps.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (state.showLimitedOnly) "No restricted apps" else "No apps",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 18.dp),
                        ) {
                            itemsIndexed(
                                items = displayedApps,
                                key = { _, item -> item.appInfo.packageName },
                            ) { index, row ->
                                AppRuleListRow(
                                    row = row,
                                    onEditMode = { pendingRulePackage = row.appInfo.packageName },
                                    onToggleSwitch = { checked ->
                                        if (checked) {
                                            pendingRulePackage = row.appInfo.packageName
                                        } else {
                                            if (row.inspectionReport?.restoreSteps?.isNotEmpty() == true) {
                                                onSetAppGuardRule(
                                                    row,
                                                    AppGuardMode.NONE,
                                                    row.notificationPrivacyMode,
                                                )
                                                pendingRulePackage = row.appInfo.packageName
                                            } else {
                                                onSetAppGuardRule(
                                                    row,
                                                    AppGuardMode.NONE,
                                                    row.notificationPrivacyMode,
                                                )
                                            }
                                        }
                                    },
                                )
                                if (index < displayedApps.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    activeDialogRow?.let { row ->
        val isPendingEndForRow = state.pendingEndRestrictionPackage == row.appInfo.packageName
        RestrictAppConfigDialog(
            row = row,
            forceRestoreMode = isPendingEndForRow,
            isNotificationListenerEnabled = state.systemIntegrity.isNotificationListenerEnabled,
            onDismiss = {
                onClearPendingEndRestriction()
                onClearInspectedApp()
                pendingRulePackage = null
            },
            onConfirm = { mode, privacy ->
                val blockedByPermissions = mode == AppGuardMode.RESTRICTED &&
                    row.inspectionReport?.canApplyRestriction != true
                val blockedByRestore = mode == AppGuardMode.NONE &&
                    row.inspectionReport?.restoreSteps?.isNotEmpty() == true
                onSetAppGuardRule(row, mode, privacy)
                if (!blockedByPermissions && !blockedByRestore) {
                    pendingRulePackage = null
                }
            },
            onInspectApp = onInspectApp,
            onLaunchSystemStep = { step ->
                onLaunchSystemStep(row.appInfo.packageName, step)
            },
            onFixMissingCore = { label ->
                onSuppressNextBackgroundLock()
                when (label) {
                    "App Limiter" -> onFixSystemIssue(SystemRequirementIssue.ACCESSIBILITY)
                    "Notification Access" -> onFixSystemIssue(SystemRequirementIssue.NOTIFICATION_LISTENER)
                    "Camera" -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    "Post Notifications" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun FrequentAppQuickChip(
    row: AppRuleRow,
    onClick: () -> Unit,
) {
    Surface(
        color = if (row.isLimited) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (row.isLimited) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .widthIn(max = 180.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("frequent_app_chip_${row.appInfo.packageName}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppDrawableIcon(
                drawable = row.appInfo.icon,
                contentDescription = row.appInfo.appName,
                size = 24.dp,
            )
            Text(
                text = row.appInfo.appName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.restrictionFrequency > 0) {
                Text(
                    text = "${row.restrictionFrequency}x",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (row.isLimited) {
                Icon(
                    imageVector = Icons.Rounded.Block,
                    contentDescription = "Restricted",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun AppRuleListRow(
    row: AppRuleRow,
    onEditMode: () -> Unit,
    onToggleSwitch: (Boolean) -> Unit,
) {
    val report = row.inspectionReport
    val blockCount = row.rule?.blockCount ?: 0
    val hasPendingRestore = !row.isLimited && (report?.restoreSteps?.isNotEmpty() == true)

    val subtitle = when {
        row.isLimited -> {
            val priv = if (row.notificationPrivacyMode == NotificationPrivacyMode.SILENT_AND_HIDE) {
                "Silent & Hidden"
            } else {
                "Show, Hide Content"
            }
            val blocks = if (blockCount > 0) " • $blockCount" else ""
            "$priv$blocks"
        }
        hasPendingRestore -> "Restore OS settings"
        else -> row.appInfo.packageName
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onEditMode)
            .padding(vertical = 12.dp)
            .testTag("app_rule_card_${row.appInfo.packageName}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppDrawableIcon(
            drawable = row.appInfo.icon,
            contentDescription = row.appInfo.appName,
            size = 42.dp,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.appInfo.appName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.isLimited || hasPendingRestore) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.isLimited && report != null) {
            VerificationStatusChip(report = report, onClick = onEditMode)
        } else if (hasPendingRestore) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEditMode),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Restore",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Switch(
            checked = row.isLimited,
            onCheckedChange = onToggleSwitch,
            modifier = Modifier.testTag("app_limit_switch_${row.appInfo.packageName}"),
        )
    }
}

@Composable
private fun VerificationStatusChip(
    report: PackageInspectionReport,
    onClick: () -> Unit,
) {
    val isEnforced = report.verificationState == EnforcementVerificationState.FULLY_ENFORCED
    Surface(
        color = if (isEnforced) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.background
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isEnforced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("verification_chip_${report.packageName}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (isEnforced) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = report.statusBadgeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RestrictAppConfigDialog(
    row: AppRuleRow,
    forceRestoreMode: Boolean,
    isNotificationListenerEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (AppGuardMode, NotificationPrivacyMode) -> Unit,
    onInspectApp: (AppRuleRow, Boolean, Boolean) -> Unit,
    onLaunchSystemStep: (SystemRestrictionStep) -> Unit,
    onFixMissingCore: (String) -> Unit,
) {
    var isRestricted by remember(row.appInfo.packageName, row.isLimited, forceRestoreMode) {
        mutableStateOf(
            when {
                forceRestoreMode -> false
                row.isLimited -> true
                else -> true
            }
        )
    }
    var selectedPrivacy by remember(row.appInfo.packageName, row.notificationPrivacyMode) {
        mutableStateOf(row.notificationPrivacyMode)
    }

    LaunchedEffect(row.appInfo.packageName, isRestricted, selectedPrivacy) {
        onInspectApp(
            row,
            isRestricted,
            selectedPrivacy == NotificationPrivacyMode.SILENT_AND_HIDE,
        )
    }

    val report = row.inspectionReport
    val canSaveRestriction = report != null && report.canApplyRestriction
    val hasPendingRestore = report?.restoreSteps?.isNotEmpty() == true
    val canTurnOff = !hasPendingRestore

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppDrawableIcon(
                    drawable = row.appInfo.icon,
                    contentDescription = row.appInfo.appName,
                    size = 32.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.appInfo.appName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = row.appInfo.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = if (isRestricted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.background
                    },
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isRestricted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { isRestricted = !isRestricted }
                        .testTag("mode_option_restricted"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Block,
                            contentDescription = null,
                            tint = if (isRestricted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Restrict",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = isRestricted,
                            onCheckedChange = { isRestricted = it },
                            modifier = Modifier.testTag("mode_switch_restricted"),
                        )
                    }
                }

                if (isRestricted) {
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    NotificationPrivacyChoiceRow(
                        icon = Icons.Rounded.NotificationsOff,
                        title = "Silent & Hide Notifications",
                        selected = selectedPrivacy == NotificationPrivacyMode.SILENT_AND_HIDE,
                        onClick = { selectedPrivacy = NotificationPrivacyMode.SILENT_AND_HIDE },
                        tag = "privacy_silent_and_hide",
                    )
                    NotificationPrivacyChoiceRow(
                        icon = Icons.Rounded.NotificationsPaused,
                        title = "Show, Hide Content",
                        selected = selectedPrivacy == NotificationPrivacyMode.SHOW_HIDE_SENSITIVE,
                        onClick = { selectedPrivacy = NotificationPrivacyMode.SHOW_HIDE_SENSITIVE },
                        tag = "privacy_show_hide_sensitive",
                    )
                }

                if (report != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PackageInspectionSummaryBlock(
                        report = report,
                        isRestrictedSelected = isRestricted,
                        isNotificationListenerEnabled = isNotificationListenerEnabled,
                        onLaunchStep = onLaunchSystemStep,
                        onFixMissingCore = onFixMissingCore,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mode = if (isRestricted) AppGuardMode.RESTRICTED else AppGuardMode.NONE
                    onConfirm(mode, selectedPrivacy)
                },
                enabled = if (isRestricted) canSaveRestriction else canTurnOff,
                modifier = Modifier.testTag("confirm_app_guard_mode_button"),
            ) {
                Text(
                    when {
                        isRestricted && report == null -> "Checking..."
                        isRestricted && report?.missingCorePermissions?.isNotEmpty() == true -> "Permissions Required"
                        isRestricted && report?.canApplyRestriction != true -> "Approvals Required"
                        isRestricted -> "Save"
                        hasPendingRestore -> "Restore Required"
                        else -> "Turn Off"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun PackageInspectionSummaryBlock(
    report: PackageInspectionReport,
    isRestrictedSelected: Boolean,
    isNotificationListenerEnabled: Boolean,
    onLaunchStep: (SystemRestrictionStep) -> Unit,
    onFixMissingCore: (String) -> Unit,
) {
    val pkg = report.packageName
    val priorityChannel = remember(report.discoveredChannels, isRestrictedSelected) {
        report.discoveredChannels.sortedWith(
            compareByDescending<com.example.restriction.DetectedChannelInfo> {
                if (isRestrictedSelected) !it.isBlockedInSystem else it.isBlockedInSystem
            }
                .thenByDescending { it.isCallChannel }
                .thenByDescending { it.importance }
        ).firstOrNull()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "System Inspection",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = report.statusBadgeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (isRestrictedSelected && !report.canApplyRestriction) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("restrict_permissions_required_banner_$pkg"),
            ) {
                Text(
                    text = if (report.missingCorePermissions.isNotEmpty()) {
                        "Grant ${report.missingCorePermissions.size} required permission(s) to restrict"
                    } else {
                        "Complete ${report.pendingSystemSteps.size} permission approval(s) to restrict"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }

        if (isRestrictedSelected && report.missingCorePermissions.isNotEmpty()) {
            report.missingCorePermissions.forEach { missingLabel ->
                OutlinedButton(
                    onClick = { onFixMissingCore(missingLabel) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("missing_core_${missingLabel.lowercase().replace(' ', '_')}"),
                ) {
                    Text("Enable $missingLabel", modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        InspectionCapabilityLine(
            label = "Notifications",
            status = when (report.postNotificationsGranted) {
                true -> "Allowed in OS"
                false -> "Blocked in OS"
                null -> when {
                    report.isAppNotificationsBannedByOs -> "Blocked in OS"
                    report.unblockedChannelsCount > 0 -> "Active"
                    else -> "Open Settings"
                }
            },
            isBlocked = report.postNotificationsGranted == false ||
                report.isAppNotificationsBannedByOs ||
                report.isSuspendedByDeviceOwner,
            tag = "capability_notifications_$pkg",
            onClick = {
                onLaunchStep(
                    SystemRestrictionStep(
                        kind = SystemStepKind.APP_NOTIFICATIONS,
                        title = "App Notifications",
                        targetPackage = pkg,
                        isRestoreStep = !isRestrictedSelected,
                    )
                )
            },
        )
        InspectionCapabilityLine(
            label = "Channels (${report.discoveredChannels.size})",
            status = if (report.discoveredChannels.isEmpty()) {
                "Open Channels"
            } else {
                "${report.callChannelsCount} call • ${report.unblockedChannelsCount} active"
            },
            isBlocked = report.unblockedChannelsCount == 0 && report.discoveredChannels.isNotEmpty(),
            tag = "capability_channels_$pkg",
            onClick = {
                val step = if (priorityChannel != null) {
                    SystemRestrictionStep(
                        kind = SystemStepKind.CHANNEL_ALERTS,
                        title = priorityChannel.channelName.ifBlank { priorityChannel.channelId },
                        targetPackage = pkg,
                        channelId = priorityChannel.channelId,
                        isRestoreStep = !isRestrictedSelected,
                    )
                } else {
                    SystemRestrictionStep(
                        kind = SystemStepKind.APP_NOTIFICATIONS,
                        title = "App Notification Channels",
                        targetPackage = pkg,
                        isRestoreStep = !isRestrictedSelected,
                    )
                }
                onLaunchStep(step)
            },
        )
        if (report.requestsFullScreenIntent) {
            InspectionCapabilityLine(
                label = "Full-Screen Alerts",
                status = when (report.fullScreenIntentAllowed) {
                    true -> "Allowed in OS"
                    false -> "Denied"
                    null -> "Check in OS"
                },
                isBlocked = report.fullScreenIntentAllowed == false,
                tag = "capability_fullscreen_$pkg",
                onClick = {
                    onLaunchStep(
                        SystemRestrictionStep(
                            kind = SystemStepKind.FULL_SCREEN_INTENT,
                            title = "Full-Screen Alerts",
                            targetPackage = pkg,
                            isRestoreStep = !isRestrictedSelected,
                        )
                    )
                },
            )
        }
        if (report.requestsSystemAlertWindow) {
            InspectionCapabilityLine(
                label = "Overlay Display",
                status = when (report.overlayAllowed) {
                    true -> "Allowed in OS"
                    false -> "Denied"
                    null -> "Check in OS"
                },
                isBlocked = report.overlayAllowed == false,
                tag = "capability_overlay_$pkg",
                onClick = {
                    onLaunchStep(
                        SystemRestrictionStep(
                            kind = SystemStepKind.OVERLAY,
                            title = "Overlay Display",
                            targetPackage = pkg,
                            isRestoreStep = !isRestrictedSelected,
                        )
                    )
                },
            )
        }

        if (isRestrictedSelected && report.pendingSystemSteps.isNotEmpty()) {
            Text(
                text = "System Approvals",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            report.pendingSystemSteps.forEach { step ->
                OutlinedButton(
                    onClick = { onLaunchStep(step) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("system_step_${step.kind.name.lowercase()}"),
                ) {
                    Text(step.title, modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (report.restoreSteps.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .testTag("restore_required_banner_$pkg"),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (isRestrictedSelected) {
                            "Turned Off (${report.restoreSteps.size} to restore later)"
                        } else {
                            "Restore ${report.restoreSteps.size} Setting(s) to End Restriction"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    report.restoreSteps.forEach { step ->
                        OutlinedButton(
                            onClick = { onLaunchStep(step) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("restore_step_${step.kind.name.lowercase()}"),
                        ) {
                            Text(step.title, modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    val needsListenerToVerifyRestore = !isNotificationListenerEnabled &&
                        report.restoreSteps.any {
                            it.kind == SystemStepKind.CHANNEL_ALERTS ||
                                it.kind == SystemStepKind.APP_NOTIFICATIONS
                        }
                    if (needsListenerToVerifyRestore) {
                        OutlinedButton(
                            onClick = { onFixMissingCore("Notification Access") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("restore_fix_notification_access"),
                        ) {
                            Text("Enable Notification Access", modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionCapabilityLine(
    label: String,
    status: String,
    isBlocked: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = if (isBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = "Open $label setting",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun NotificationPrivacyChoiceRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
