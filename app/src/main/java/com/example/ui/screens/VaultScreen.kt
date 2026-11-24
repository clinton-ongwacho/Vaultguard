package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.data.entity.VaultMediaCategory
import com.example.data.entity.VaultMediaEntity
import com.example.ui.VaultUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat

@Composable
fun VaultScreen(
    state: VaultUiState,
    onUnlockVaultMedia: (String) -> Unit,
    onLockVaultMedia: () -> Unit,
    onClearVaultUnlockError: () -> Unit,
    onImportMediaUris: (List<Uri>) -> Unit,
    onGalleryDeleteCompleted: (Boolean) -> Unit,
    onDeleteMediaReturningToGallery: (VaultMediaEntity) -> Unit,
    onDecryptPhotoBytes: suspend (VaultMediaEntity) -> ByteArray?,
    onDecryptStreamFile: suspend (VaultMediaEntity) -> File?,
    onDecryptDocPreviewFile: suspend (VaultMediaEntity) -> File?,
    onSuppressNextBackgroundLock: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Always re-hide Vault media & clean temporary decrypted previews when leaving this screen
    DisposableEffect(Unit) {
        onDispose {
            onLockVaultMedia()
        }
    }

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        onGalleryDeleteCompleted(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(state.pendingGalleryDeleteSender) {
        val sender = state.pendingGalleryDeleteSender ?: return@LaunchedEffect
        onSuppressNextBackgroundLock()
        deleteRequestLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    var showUnlockDialog by remember { mutableStateOf(false) }
    var pendingItemAfterUnlock by remember { mutableStateOf<VaultMediaEntity?>(null) }
    var selectedCategory by remember { mutableStateOf<VaultMediaCategory?>(null) }
    var inspectedPhotoItem by remember { mutableStateOf<VaultMediaEntity?>(null) }
    var playingAudioItem by remember { mutableStateOf<VaultMediaEntity?>(null) }
    var playingVideoItem by remember { mutableStateOf<VaultMediaEntity?>(null) }
    var deleteCandidate by remember { mutableStateOf<VaultMediaEntity?>(null) }
    var externalOpenError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.isVaultMediaUnlocked) {
        if (state.isVaultMediaUnlocked) {
            showUnlockDialog = false
            val queued = pendingItemAfterUnlock
            pendingItemAfterUnlock = null
            if (queued != null) {
                when (queued.categoryEnum) {
                    VaultMediaCategory.PHOTO -> inspectedPhotoItem = queued
                    VaultMediaCategory.VIDEO -> playingVideoItem = queued
                    VaultMediaCategory.AUDIO -> playingAudioItem = queued
                    VaultMediaCategory.DOCUMENT, VaultMediaCategory.OTHER -> {
                        scope.launch {
                            val tempFile = onDecryptDocPreviewFile(queued)
                            if (tempFile == null || !tempFile.exists()) {
                                externalOpenError = "Unable to decrypt ${queued.displayName}."
                                return@launch
                            }
                            val opened = runCatching {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    tempFile,
                                )
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, queued.mimeType.ifBlank { "*/*" })
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                onSuppressNextBackgroundLock()
                                context.startActivity(viewIntent)
                            }.isSuccess
                            if (!opened) {
                                externalOpenError = "No viewer installed for ${queued.displayName}. Delete from Vault to return it to your phone storage."
                            }
                        }
                    }
                }
            }
        }
    }

    val openDocumentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportMediaUris(uris)
        }
    }

    val filteredItems = remember(state.vaultMediaItems, selectedCategory) {
        if (selectedCategory == null) {
            state.vaultMediaItems
        } else {
            state.vaultMediaItems.filter { it.categoryEnum == selectedCategory }
        }
    }

    fun requestUnlockOrRun(item: VaultMediaEntity? = null, unlockedAction: () -> Unit) {
        if (!state.isVaultMediaUnlocked) {
            pendingItemAfterUnlock = item
            onClearVaultUnlockError()
            showUnlockDialog = true
        } else {
            unlockedAction()
        }
    }

    fun openVaultItem(item: VaultMediaEntity) {
        requestUnlockOrRun(item = item) {
            externalOpenError = null
            when (item.categoryEnum) {
                VaultMediaCategory.PHOTO -> inspectedPhotoItem = item
                VaultMediaCategory.VIDEO -> playingVideoItem = item
                VaultMediaCategory.AUDIO -> playingAudioItem = item
                VaultMediaCategory.DOCUMENT, VaultMediaCategory.OTHER -> {
                    scope.launch {
                        val tempFile = onDecryptDocPreviewFile(item)
                        if (tempFile == null || !tempFile.exists()) {
                            externalOpenError = "Unable to decrypt hidden file."
                            return@launch
                        }
                        val opened = runCatching {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                tempFile,
                            )
                            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, item.mimeType.ifBlank { "*/*" })
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            onSuppressNextBackgroundLock()
                            context.startActivity(viewIntent)
                        }.isSuccess
                        if (!opened) {
                            externalOpenError = "No installed viewer found for ${item.displayName}. Delete from Vault to return it to your device."
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Vault",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = "${state.vaultMediaItems.size} files",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Surface(
                        color = if (state.isVaultMediaUnlocked) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (state.isVaultMediaUnlocked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                    ) {
                        IconButton(
                            onClick = {
                                if (state.isVaultMediaUnlocked) {
                                    onLockVaultMedia()
                                } else {
                                    pendingItemAfterUnlock = null
                                    onClearVaultUnlockError()
                                    showUnlockDialog = true
                                }
                            },
                            modifier = Modifier.testTag("vault_eye_toggle_button"),
                        ) {
                            Icon(
                                imageVector = if (state.isVaultMediaUnlocked) {
                                    Icons.Rounded.Visibility
                                } else {
                                    Icons.Rounded.VisibilityOff
                                },
                                contentDescription = if (state.isVaultMediaUnlocked) {
                                    "Hide Vault media"
                                } else {
                                    "Enter Admin password to view Vault media"
                                },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (state.isImportingMedia) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Encrypting…",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        VaultAddListRow(
                            icon = Icons.Rounded.Image,
                            label = "Photos & Video",
                            onClick = {
                                onSuppressNextBackgroundLock()
                                openDocumentsLauncher.launch(arrayOf("image/*", "video/*"))
                            },
                            tag = "vault_add_photos_videos_button",
                        )
                        VaultAddListRow(
                            icon = Icons.Rounded.MusicNote,
                            label = "Music & Audio",
                            onClick = {
                                onSuppressNextBackgroundLock()
                                openDocumentsLauncher.launch(arrayOf("audio/*"))
                            },
                            tag = "vault_add_audio_button",
                        )
                        VaultAddListRow(
                            icon = Icons.Rounded.Description,
                            label = "Documents",
                            onClick = {
                                onSuppressNextBackgroundLock()
                                openDocumentsLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "text/*",
                                        "application/msword",
                                        "application/vnd.openxmlformats-officedocument.*",
                                    )
                                )
                            },
                            tag = "vault_add_documents_button",
                        )
                        VaultAddListRow(
                            icon = Icons.Rounded.Add,
                            label = "Any File",
                            onClick = {
                                onSuppressNextBackgroundLock()
                                openDocumentsLauncher.launch(arrayOf("*/*"))
                            },
                            tag = "vault_add_any_media_button",
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All (${state.vaultMediaItems.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                    VaultMediaCategory.entries.forEach { cat ->
                        val count = state.vaultMediaItems.count { it.categoryEnum == cat }
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text("${cat.label} ($count)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }

            if (externalOpenError != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = externalOpenError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        )
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                            )
                            Text(
                                text = "Empty",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(filteredItems, key = { it.id }) { item ->
                    VaultMediaRowCard(
                        item = item,
                        isRevealed = state.isVaultMediaUnlocked,
                        onDecryptPhotoBytes = onDecryptPhotoBytes,
                        onOpen = { openVaultItem(item) },
                        onDeleteReturningToGallery = {
                            requestUnlockOrRun {
                                deleteCandidate = item
                            }
                        },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(10.dp)) }
        }
    }

    if (showUnlockDialog) {
        VaultEyeUnlockDialog(
            error = state.vaultUnlockError,
            onDismiss = {
                showUnlockDialog = false
                pendingItemAfterUnlock = null
                onClearVaultUnlockError()
            },
            onUnlock = onUnlockVaultMedia,
            onClearError = onClearVaultUnlockError,
        )
    }

    inspectedPhotoItem?.let { photoItem ->
        VaultPhotoViewerDialog(
            item = photoItem,
            onDecryptPhotoBytes = onDecryptPhotoBytes,
            onDismiss = { inspectedPhotoItem = null },
        )
    }

    playingAudioItem?.let { audioItem ->
        VaultAudioPlayerDialog(
            item = audioItem,
            onDecryptStreamFile = onDecryptStreamFile,
            onDismiss = { playingAudioItem = null },
        )
    }

    playingVideoItem?.let { videoItem ->
        VaultVideoPlayerDialog(
            item = videoItem,
            onDecryptStreamFile = onDecryptStreamFile,
            onDismiss = { playingVideoItem = null },
        )
    }

    deleteCandidate?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Return to Gallery") },
            text = {
                Text(target.displayName)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteMediaReturningToGallery(target)
                        deleteCandidate = null
                    },
                    modifier = Modifier.testTag("confirm_vault_return_gallery_button"),
                ) {
                    Text("Remove & Return")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun VaultAddListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun VaultEyeUnlockDialog(
    error: String?,
    onDismiss: () -> Unit,
    onUnlock: (String) -> Unit,
    onClearError: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin Password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    onClearError()
                },
                label = { Text("Password") },
                leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle password",
                        )
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                isError = error != null,
                supportingText = error?.let { err -> { Text(err) } },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_media_password_input"),
            )
        },
        confirmButton = {
            Button(
                onClick = { onUnlock(password) },
                modifier = Modifier.testTag("vault_media_unlock_button"),
            ) {
                Text("View")
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
private fun VaultMediaRowCard(
    item: VaultMediaEntity,
    isRevealed: Boolean,
    onDecryptPhotoBytes: suspend (VaultMediaEntity) -> ByteArray?,
    onOpen: () -> Unit,
    onDeleteReturningToGallery: () -> Unit,
) {
    val thumbBytes by produceState<ByteArray?>(initialValue = null, item.id, isRevealed) {
        value = if (isRevealed && item.categoryEnum == VaultMediaCategory.PHOTO) {
            onDecryptPhotoBytes(item)
        } else {
            null
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .testTag("vault_item_${item.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isRevealed && item.categoryEnum == VaultMediaCategory.PHOTO && thumbBytes != null) {
                AsyncImage(
                    model = thumbBytes,
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (!isRevealed) {
                                Icons.Rounded.VisibilityOff
                            } else {
                                when (item.categoryEnum) {
                                    VaultMediaCategory.PHOTO -> Icons.Rounded.Image
                                    VaultMediaCategory.VIDEO -> Icons.Rounded.VideoLibrary
                                    VaultMediaCategory.AUDIO -> Icons.Rounded.AudioFile
                                    VaultMediaCategory.DOCUMENT -> Icons.Rounded.Description
                                    VaultMediaCategory.OTHER -> Icons.Rounded.InsertDriveFile
                                }
                            },
                            contentDescription = if (isRevealed) item.categoryEnum.label else "Hidden media",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRevealed) item.displayName else "${item.categoryEnum.label} ••••",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.categoryEnum.label} • ${formatMediaBytes(item.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isRevealed) {
                IconButton(
                    onClick = onDeleteReturningToGallery,
                    modifier = Modifier.testTag("vault_delete_${item.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove from Vault and return to gallery",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultPhotoViewerDialog(
    item: VaultMediaEntity,
    onDecryptPhotoBytes: suspend (VaultMediaEntity) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    val photoBytes by produceState<ByteArray?>(initialValue = null, item.id) {
        value = onDecryptPhotoBytes(item)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (photoBytes != null) {
                    AsyncImage(
                        model = photoBytes,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.background),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun VaultAudioPlayerDialog(
    item: VaultMediaEntity,
    onDecryptStreamFile: suspend (VaultMediaEntity) -> File?,
    onDismiss: () -> Unit,
) {
    val streamFile by produceState<File?>(initialValue = null, item.id) {
        value = onDecryptStreamFile(item)
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }

    val mediaPlayer = remember(streamFile) {
        val f = streamFile ?: return@remember null
        MediaPlayer().apply {
            runCatching {
                setDataSource(f.absolutePath)
                prepare()
                durationMs = duration.coerceAtLeast(1)
                start()
                isPlaying = true
            }
        }
    }

    DisposableEffect(mediaPlayer, streamFile) {
        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            positionMs = 0
        }
        onDispose {
            runCatching {
                if (mediaPlayer?.isPlaying == true) mediaPlayer.stop()
                mediaPlayer?.release()
            }
            runCatching { streamFile?.delete() }
        }
    }

    LaunchedEffect(isPlaying, mediaPlayer) {
        while (isPlaying && mediaPlayer != null) {
            positionMs = runCatching { mediaPlayer.currentPosition }.getOrDefault(0)
            delay(250L)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (mediaPlayer == null) {
                    CircularProgressIndicator()
                } else {
                    Slider(
                        value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                        onValueChange = { pos ->
                            positionMs = pos.toInt()
                            runCatching { mediaPlayer.seekTo(positionMs) }
                        },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatMillis(positionMs), style = MaterialTheme.typography.labelSmall)
                        Text(formatMillis(durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                if (mediaPlayer.isPlaying) {
                                    mediaPlayer.pause()
                                    isPlaying = false
                                } else {
                                    mediaPlayer.start()
                                    isPlaying = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(if (isPlaying) "Pause" else "Play")
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultVideoPlayerDialog(
    item: VaultMediaEntity,
    onDecryptStreamFile: suspend (VaultMediaEntity) -> File?,
    onDismiss: () -> Unit,
) {
    val streamFile by produceState<File?>(initialValue = null, item.id) {
        value = onDecryptStreamFile(item)
    }

    DisposableEffect(streamFile) {
        onDispose {
            runCatching { streamFile?.delete() }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val readyFile = streamFile
                if (readyFile == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val controller = MediaController(ctx)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoPath(readyFile.absolutePath)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = false
                                    start()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Close")
                }
            }
        }
    }
}

private fun formatMediaBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        "${DecimalFormat("#0.0").format(mb)} MB"
    } else {
        "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    }
}

private fun formatMillis(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
