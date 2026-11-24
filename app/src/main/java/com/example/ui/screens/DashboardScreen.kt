package com.example.ui.screens

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.entity.AccessEventEntity
import com.example.ui.VaultUiState
import com.example.ui.components.AppDrawableIcon
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    state: VaultUiState,
    onNavigateToApps: () -> Unit,
    onNavigateToDisguise: () -> Unit,
    onRefreshSystemStatus: () -> Unit,
    onClearLogs: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstallUpdate: () -> Unit,
    onInstallVerifiedApk: () -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onDismissUpdateBanner: () -> Unit,
    onInspectApkForPublish: (Uri) -> Unit,
    onPublishOtaUpdate: (releaseNotes: String, versionCodeInput: String, versionNameInput: String) -> Unit,
    onSuppressNextBackgroundLock: () -> Unit = {},
) {
    val protectedApps = remember(state.apps) {
        state.apps.filter { it.isLimited }
    }
    var tickerIndex by remember(protectedApps) { mutableIntStateOf(0) }
    var inspectedPhotoEvent by remember { mutableStateOf<AccessEventEntity?>(null) }
    var showOtaDialog by remember { mutableStateOf(false) }

    val bellTransition = rememberInfiniteTransition(label = "disguise_bell_dance")
    val bellRotation by bellTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1600
                0f at 0 using LinearEasing
                -18f at 120 using LinearEasing
                18f at 240 using LinearEasing
                -15f at 360 using LinearEasing
                15f at 480 using LinearEasing
                -10f at 600 using LinearEasing
                8f at 720 using LinearEasing
                0f at 860 using LinearEasing
                0f at 1600 using LinearEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "disguise_bell_rotation",
    )

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        onRefreshSystemStatus()
    }

    LaunchedEffect(protectedApps) {
        if (protectedApps.size > 1) {
            while (true) {
                delay(2_200L)
                tickerIndex = (tickerIndex + 1) % protectedApps.size
            }
        }
    }

    val ota = state.otaState
    val showUpdateCard = (ota.hasUpdate && !ota.updateBannerDismissed) ||
        ota.isDownloading ||
        ota.verifiedApkPath != null

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "VaultGuard",
                        style = MaterialTheme.typography.headlineMedium,
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                            ),
                        ) {
                            IconButton(
                                onClick = onNavigateToDisguise,
                                modifier = Modifier.testTag("dashboard_disguise_bell_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.TheaterComedy,
                                    contentDescription = "Disguise",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.graphicsLayer {
                                        rotationZ = bellRotation
                                        transformOrigin = TransformOrigin(0.5f, 0.15f)
                                    },
                                )
                            }
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (ota.hasUpdate || ota.verifiedApkPath != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                },
                            ),
                        ) {
                            IconButton(
                                onClick = { showOtaDialog = true },
                                modifier = Modifier.testTag("dashboard_update_notifications_button"),
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (ota.hasUpdate || ota.verifiedApkPath != null || ota.isDownloading) {
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                            ) {
                                                Text("1")
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (ota.hasUpdate || ota.verifiedApkPath != null) {
                                            Icons.Rounded.NotificationsActive
                                        } else {
                                            Icons.Outlined.Notifications
                                        },
                                        contentDescription = "App Updates & Notifications",
                                        tint = if (ota.hasUpdate || ota.verifiedApkPath != null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showUpdateCard) {
                item {
                    val release = ota.latestRelease
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("dashboard_update_notification_card"),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SystemUpdate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Column {
                                        Text(
                                            text = when {
                                                ota.verifiedApkPath != null -> "Update Ready"
                                                ota.isDownloading -> "Downloading…"
                                                release != null -> "v${release.versionName}"
                                                else -> "Update Available"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        if (release != null && release.releaseNotes.isNotBlank()) {
                                            Text(
                                                text = release.releaseNotes,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                if (!ota.isDownloading) {
                                    IconButton(
                                        onClick = onDismissUpdateBanner,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Dismiss update banner",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }

                            if (ota.isDownloading) {
                                if (ota.downloadProgress != null) {
                                    LinearProgressIndicator(
                                        progress = { ota.downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }

                            if (ota.updateError != null) {
                                Text(
                                    text = ota.updateError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                TextButton(
                                    onClick = { showOtaDialog = true },
                                    modifier = Modifier.testTag("dashboard_update_details_button"),
                                ) {
                                    Text("Details")
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                if (ota.verifiedApkPath != null) {
                                    Button(
                                        onClick = {
                                            onSuppressNextBackgroundLock()
                                            if (ota.needsUnknownSourcesPermission) {
                                                onOpenUnknownSourcesSettings()
                                            } else {
                                                onInstallVerifiedApk()
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.testTag("dashboard_update_now_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.InstallMobile,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.size(6.dp))
                                        Text(
                                            if (ota.needsUnknownSourcesPermission) {
                                                "Allow & Install"
                                            } else {
                                                "Install Now"
                                            }
                                        )
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            onSuppressNextBackgroundLock()
                                            onDownloadAndInstallUpdate()
                                        },
                                        enabled = !ota.isDownloading,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.testTag("dashboard_update_now_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.size(6.dp))
                                        Text(if (ota.isDownloading) "Downloading…" else "Update Now")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onNavigateToApps,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .testTag("dashboard_manage_apps_button"),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Apps,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Manage Apps",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            if (protectedApps.isNotEmpty()) {
                                val safeIndex = tickerIndex.coerceIn(0, protectedApps.lastIndex)
                                AnimatedContent(
                                    targetState = protectedApps[safeIndex],
                                    contentKey = { it.appInfo.packageName },
                                    transitionSpec = {
                                        (slideInVertically { height -> height } + fadeIn())
                                            .togetherWith(slideOutVertically { height -> -height } + fadeOut())
                                    },
                                    label = "restricted_apps_up_swipe_ticker",
                                    modifier = Modifier.widthIn(max = 175.dp),
                                ) { currentApp ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        AppDrawableIcon(
                                            drawable = currentApp.appInfo.icon,
                                            contentDescription = currentApp.appInfo.appName,
                                            size = 20.dp,
                                        )
                                        Text(
                                            text = currentApp.appInfo.appName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.testTag("restricted_apps_count_badge"),
                            ) {
                                Text(
                                    text = "${state.limitedCount}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Activity",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (state.config.capturePhotoOnOpenEnabled && !state.isCameraPermissionGranted) {
                            TextButton(
                                onClick = {
                                    onSuppressNextBackgroundLock()
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier.testTag("dashboard_enable_camera_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("Camera")
                            }
                        }
                        if (state.recentEvents.isNotEmpty()) {
                            TextButton(
                                onClick = onClearLogs,
                                modifier = Modifier.testTag("clear_logs_button"),
                            ) {
                                Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.size(4.dp))
                                Text("Clear")
                            }
                        }
                    }
                }
            }

            if (state.recentEvents.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Text(
                            text = "No activity",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                        )
                    }
                }
            } else {
                items(state.recentEvents, key = { it.id }) { event ->
                    EventRowCard(
                        event = event,
                        onViewPhoto = { inspectedPhotoEvent = event },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }

    inspectedPhotoEvent?.let { selectedEvent ->
        SessionPhotoViewerDialog(
            event = selectedEvent,
            onDismiss = { inspectedPhotoEvent = null },
        )
    }

    if (showOtaDialog) {
        OtaUpdateDialog(
            otaState = state.otaState,
            onDismiss = { showOtaDialog = false },
            onCheckForUpdates = onCheckForUpdates,
            onDownloadAndInstall = onDownloadAndInstallUpdate,
            onInstallVerifiedApk = onInstallVerifiedApk,
            onOpenUnknownSourcesSettings = onOpenUnknownSourcesSettings,
            onInspectApkForPublish = onInspectApkForPublish,
            onPublishUpdate = onPublishOtaUpdate,
            onSuppressNextBackgroundLock = onSuppressNextBackgroundLock,
        )
    }
}

@Composable
private fun EventRowCard(
    event: AccessEventEntity,
    onViewPhoto: () -> Unit,
) {
    val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val timeStr = formatter.format(Date(event.timestampMillis))
    val isAlert = event.eventType.contains("FAILED") || event.eventType.contains("REDIRECT") || event.eventType.contains("SUSPENDED")
    val photoFile = remember(event.photoPath) {
        event.photoPath?.let { path -> File(path).takeIf { it.exists() && it.length() > 0L } }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${event.appName} • ${event.eventType.replace('_', ' ')}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isAlert) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.detail.isNotBlank()) {
                    Text(
                        text = event.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (photoFile != null) {
                Spacer(modifier = Modifier.size(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            RoundedCornerShape(12.dp),
                        )
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClick = onViewPhoto)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("session_photo_view_${event.id}"),
                ) {
                    AsyncImage(
                        model = photoFile,
                        contentDescription = "Captured session photo for ${event.appName}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            contentDescription = "View session photo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "View",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionPhotoViewerDialog(
    event: AccessEventEntity,
    onDismiss: () -> Unit,
) {
    val formatter = SimpleDateFormat("MMM d, yyyy • HH:mm:ss", Locale.getDefault())
    val timeStr = formatter.format(Date(event.timestampMillis))
    val photoFile = remember(event.photoPath) {
        event.photoPath?.let { File(it) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${event.appName} Session Photo",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (photoFile != null && photoFile.exists()) {
                    AsyncImage(
                        model = photoFile,
                        contentDescription = "Captured session photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.background),
                    )
                }

                if (event.detail.isNotBlank()) {
                    Text(
                        text = event.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("close_session_photo_dialog"),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Close")
                }
            }
        }
    }
}
