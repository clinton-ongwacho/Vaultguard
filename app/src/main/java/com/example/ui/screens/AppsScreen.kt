package com.example.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.entity.AppGuardMode
import com.example.data.entity.NotificationPrivacyMode
import com.example.ui.AppRuleRow
import com.example.ui.VaultUiState
import com.example.ui.components.AppDrawableIcon

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppsScreen(
    state: VaultUiState,
    onScreenOpened: () -> Unit,
    onSearchChanged: (String) -> Unit,
    onToggleLimitedOnly: (Boolean) -> Unit,
    onToggleSystemApps: (Boolean) -> Unit,
    onSetAppGuardRule: (AppRuleRow, AppGuardMode, NotificationPrivacyMode) -> Unit,
    onRefreshApps: () -> Unit,
) {
    var pendingRuleRow by remember { mutableStateOf<AppRuleRow?>(null) }

    LaunchedEffect(Unit) {
        onScreenOpened()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Manage Apps",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "${state.limitedCount} Restricted",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onRefreshApps,
                    modifier = Modifier.testTag("refresh_apps_button"),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh apps")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                shape = RoundedCornerShape(16.dp),
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
                FilterChip(
                    selected = state.showSystemApps,
                    onClick = { onToggleSystemApps(!state.showSystemApps) },
                    label = { Text("System Apps") },
                    modifier = Modifier.testTag("filter_system_apps_chip"),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                state.isLoadingApps -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.filteredApps.isEmpty() -> {
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
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = state.filteredApps,
                            key = { it.appInfo.packageName },
                        ) { row ->
                            AppRuleCard(
                                row = row,
                                onEditMode = { pendingRuleRow = row },
                                onToggleSwitch = { checked ->
                                    if (checked) {
                                        pendingRuleRow = row
                                    } else {
                                        onSetAppGuardRule(
                                            row,
                                            AppGuardMode.NONE,
                                            row.notificationPrivacyMode,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingRuleRow?.let { row ->
        RestrictAppConfigDialog(
            row = row,
            onDismiss = { pendingRuleRow = null },
            onConfirm = { mode, privacy ->
                onSetAppGuardRule(row, mode, privacy)
                pendingRuleRow = null
            },
        )
    }
}

@Composable
private fun AppRuleCard(
    row: AppRuleRow,
    onEditMode: () -> Unit,
    onToggleSwitch: (Boolean) -> Unit,
) {
    val blockCount = row.rule?.blockCount ?: 0
    val countSuffix = if (blockCount > 0) " • $blockCount" else ""
    val statusDetail = if (row.isLimited) {
        val notifLabel = if (row.notificationPrivacyMode == NotificationPrivacyMode.SILENT_AND_HIDE) {
            "Silent & Hidden"
        } else {
            "Show, Hide Content"
        }
        "$notifLabel$countSuffix"
    } else {
        "Off"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onEditMode)
            .testTag("app_rule_card_${row.appInfo.packageName}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppDrawableIcon(
                drawable = row.appInfo.icon,
                contentDescription = row.appInfo.appName,
                size = 44.dp,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.appInfo.appName,
                    style = MaterialTheme.typography.titleMedium,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (row.isLimited) Icons.Rounded.Block else Icons.Rounded.LockOpen,
                        contentDescription = null,
                        tint = if (row.isLimited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = statusDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.isLimited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Switch(
                checked = row.isLimited,
                onCheckedChange = onToggleSwitch,
                modifier = Modifier.testTag("app_limit_switch_${row.appInfo.packageName}"),
            )
        }
    }
}

@Composable
private fun RestrictAppConfigDialog(
    row: AppRuleRow,
    onDismiss: () -> Unit,
    onConfirm: (AppGuardMode, NotificationPrivacyMode) -> Unit,
) {
    var isRestricted by remember(row) {
        mutableStateOf(true)
    }
    var selectedPrivacy by remember(row) {
        mutableStateOf(row.notificationPrivacyMode)
    }

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
                Text(
                    text = row.appInfo.appName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    color = if (isRestricted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.background
                    },
                    shape = RoundedCornerShape(14.dp),
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
                        .clip(RoundedCornerShape(14.dp))
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mode = if (isRestricted) AppGuardMode.RESTRICTED else AppGuardMode.NONE
                    onConfirm(mode, selectedPrivacy)
                },
                modifier = Modifier.testTag("confirm_app_guard_mode_button"),
            ) {
                Text(if (isRestricted) "Save" else "Turn Off")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NotificationPrivacyChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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
