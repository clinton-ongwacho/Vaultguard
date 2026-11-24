package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.ota.ApkSecurityVerifier
import com.example.ota.OtaUiState
import java.text.DecimalFormat

@Composable
fun OtaUpdateDialog(
    otaState: OtaUiState,
    onDismiss: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallVerifiedApk: () -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onInspectApkForPublish: (Uri) -> Unit,
    onPublishUpdate: (releaseNotes: String, versionCodeInput: String, versionNameInput: String) -> Unit,
    onSuppressNextBackgroundLock: () -> Unit = {},
) {
    var activeTab by remember { mutableIntStateOf(0) }
    var releaseNotesInput by remember { mutableStateOf("") }
    var versionCodeInput by remember { mutableStateOf("") }
    var versionNameInput by remember { mutableStateOf("") }

    LaunchedEffect(otaState.inspectedPublishApk) {
        val inspected = otaState.inspectedPublishApk
        if (inspected != null) {
            versionCodeInput = inspected.versionCode.takeIf { it > 0L }?.toString()
                ?: (otaState.installedVersionCode + 1L).toString()
            versionNameInput = inspected.versionName.ifBlank { "${otaState.installedVersionCode + 1L}.0" }
        }
    }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onInspectApkForPublish(uri)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .widthIn(max = 560.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
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
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "OTA Updates",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "v${otaState.installedVersionName} (Build ${otaState.installedVersionCode})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_ota_dialog_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close updates dialog",
                        )
                    }
                }

                // Segmented Orange & Black tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OtaTabChip(
                        label = "App Updates",
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ota_tab_updates"),
                    )
                    OtaTabChip(
                        label = "Publish Update",
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ota_tab_publish"),
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (activeTab == 0) {
                        UpdatesTabContent(
                            otaState = otaState,
                            onCheckForUpdates = onCheckForUpdates,
                            onDownloadAndInstall = {
                                onSuppressNextBackgroundLock()
                                onDownloadAndInstall()
                            },
                            onInstallVerifiedApk = {
                                onSuppressNextBackgroundLock()
                                onInstallVerifiedApk()
                            },
                            onOpenUnknownSourcesSettings = {
                                onSuppressNextBackgroundLock()
                                onOpenUnknownSourcesSettings()
                            },
                        )
                    } else {
                        PublishTabContent(
                            otaState = otaState,
                            releaseNotesInput = releaseNotesInput,
                            onReleaseNotesChanged = { releaseNotesInput = it },
                            versionCodeInput = versionCodeInput,
                            onVersionCodeChanged = { s -> versionCodeInput = s.filter { it.isDigit() } },
                            versionNameInput = versionNameInput,
                            onVersionNameChanged = { versionNameInput = it },
                            onPickApk = {
                                onSuppressNextBackgroundLock()
                                apkPickerLauncher.launch(
                                    arrayOf(
                                        "application/vnd.android.package-archive",
                                        "application/octet-stream",
                                        "*/*",
                                    )
                                )
                            },
                            onPublishUpdate = {
                                onPublishUpdate(releaseNotesInput, versionCodeInput, versionNameInput)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OtaTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdatesTabContent(
    otaState: OtaUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallVerifiedApk: () -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Installed Version",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${BuildConfig.APPLICATION_ID} • v${otaState.installedVersionName} (${otaState.installedVersionCode})",
                style = MaterialTheme.typography.bodyMedium,
            )
            val keyPreview = if (otaState.installedSigningSha256.length >= 16) {
                "${otaState.installedSigningSha256.take(12)}…${otaState.installedSigningSha256.takeLast(8)}"
            } else {
                "Verified"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "SHA-256: $keyPreview",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    val latest = otaState.latestRelease
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (otaState.hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Firebase Release Channel",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (otaState.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            if (latest == null) {
                Text(
                    text = "No release available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "v${latest.versionName} (${latest.versionCode})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (otaState.hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Surface(
                        color = if (otaState.hasUpdate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = if (otaState.hasUpdate) "Update Available" else "Up to Date",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (otaState.hasUpdate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                Text(
                    text = formatByteSize(latest.apkSizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (latest.releaseNotes.isNotBlank()) {
                    Text(
                        text = latest.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val keyTrusted = ApkSecurityVerifier.doSigningHashesMatch(
                    installedHashes = setOf(otaState.installedSigningSha256),
                    candidateHashes = setOf(latest.signingSha256),
                    expectedReleaseHash = latest.signingSha256,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (keyTrusted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = if (keyTrusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (keyTrusted) "Key verified" else "Key mismatch",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (keyTrusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (otaState.isDownloading) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val pct = ((otaState.downloadProgress ?: 0f) * 100).toInt()
            Text(
                text = "Downloading APK… $pct% (${formatByteSize(otaState.downloadedBytes)} / ${formatByteSize(otaState.totalBytes)})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (otaState.downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { otaState.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (otaState.updateStatusMessage != null) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = otaState.updateStatusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }
    }

    if (otaState.updateError != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = otaState.updateError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }
    }

    if (otaState.needsUnknownSourcesPermission && otaState.verifiedApkPath != null) {
        OutlinedButton(
            onClick = onOpenUnknownSourcesSettings,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("ota_allow_unknown_sources_button"),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text("Allow Install From This Source")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onCheckForUpdates,
            enabled = !otaState.isChecking && !otaState.isDownloading,
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .testTag("ota_check_updates_button"),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text("Check")
        }

        if (otaState.verifiedApkPath != null) {
            Button(
                onClick = onInstallVerifiedApk,
                modifier = Modifier
                    .weight(1.4f)
                    .height(50.dp)
                    .testTag("ota_install_verified_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.InstallMobile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("Install APK")
            }
        } else {
            Button(
                onClick = onDownloadAndInstall,
                enabled = otaState.hasUpdate && !otaState.isDownloading,
                modifier = Modifier
                    .weight(1.4f)
                    .height(50.dp)
                    .testTag("ota_update_now_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text(if (otaState.isDownloading) "Downloading…" else "Update Now")
            }
        }
    }
}

@Composable
private fun PublishTabContent(
    otaState: OtaUiState,
    releaseNotesInput: String,
    onReleaseNotesChanged: (String) -> Unit,
    versionCodeInput: String,
    onVersionCodeChanged: (String) -> Unit,
    versionNameInput: String,
    onVersionNameChanged: (String) -> Unit,
    onPickApk: () -> Unit,
    onPublishUpdate: () -> Unit,
) {
    OutlinedButton(
        onClick = onPickApk,
        enabled = !otaState.isInspectingApk && !otaState.isPublishing,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("ota_pick_apk_button"),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            if (otaState.isInspectingApk) {
                "Inspecting APK…"
            } else if (otaState.inspectedPublishApk != null) {
                "Choose Another Signed APK"
            } else {
                "Select Signed APK File"
            }
        )
    }

    val inspected = otaState.inspectedPublishApk
    if (inspected != null) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (inspected.isValidForPublish) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = inspected.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Package: ${inspected.packageName.ifBlank { "Unknown" }} • ${formatByteSize(inspected.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = inspected.validationMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (inspected.isValidForPublish) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = versionCodeInput,
                onValueChange = onVersionCodeChanged,
                label = { Text("Version Code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ota_publish_version_code_input"),
            )
            OutlinedTextField(
                value = versionNameInput,
                onValueChange = onVersionNameChanged,
                label = { Text("Version Name") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ota_publish_version_name_input"),
            )
        }

        OutlinedTextField(
            value = releaseNotesInput,
            onValueChange = onReleaseNotesChanged,
            label = { Text("Release Notes") },
            placeholder = { Text("Describe improvements, fixes, and security updates…") },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ota_publish_release_notes_input"),
        )
    }

    if (otaState.isPublishing) {
        val pct = ((otaState.uploadProgress ?: 0f) * 100).toInt()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Uploading APK to Firebase Storage… $pct%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (otaState.uploadProgress != null) {
                LinearProgressIndicator(
                    progress = { otaState.uploadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (otaState.publishError != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = otaState.publishError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }
    }

    if (otaState.publishSuccessMessage != null) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = otaState.publishSuccessMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }
    }

    Button(
        onClick = onPublishUpdate,
        enabled = inspected?.isValidForPublish == true && !otaState.isPublishing,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("ota_publish_submit_button"),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Text(if (otaState.isPublishing) "Publishing Update…" else "Publish Update")
    }
}

private fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        "${DecimalFormat("#0.0").format(mb)} MB"
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}
