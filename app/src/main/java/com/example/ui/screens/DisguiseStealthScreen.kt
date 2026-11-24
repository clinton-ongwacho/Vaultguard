package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.entity.DisguiseIdentity
import com.example.data.entity.RedirectDisplayStyle
import com.example.ui.VaultUiState
import com.example.ui.components.StealthTriggersProtectedCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisguiseStealthScreen(
    state: VaultUiState,
    onApplyDisguiseAndHide: (DisguiseIdentity, Boolean) -> Unit,
    onUpdateRedirectDisplay: (RedirectDisplayStyle) -> Unit,
    onUpdateStealthTriggers: (wakeWord: String, dialerPin: String) -> Unit,
    onVerifyAdminPassword: (password: String, onResult: (Boolean) -> Unit) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
) {
    var selectedIdentity by remember(state.activeDisguise) { mutableStateOf(state.activeDisguise) }
    var disguiseMenuExpanded by remember { mutableStateOf(false) }
    var iconDisplayMenuExpanded by remember { mutableStateOf(false) }

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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onNavigateBack != null) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("disguise_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = "Disguise",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "1. Launcher Disguise",
                            style = MaterialTheme.typography.titleMedium,
                        )

                        ExposedDropdownMenuBox(
                            expanded = disguiseMenuExpanded,
                            onExpandedChange = { disguiseMenuExpanded = !disguiseMenuExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (disguiseMenuExpanded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                    },
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .clip(RoundedCornerShape(16.dp))
                                    .testTag("disguise_dropdown_anchor"),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    DisguiseIconPreview(identity = selectedIdentity)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedIdentity.displayLabel,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = selectedIdentity.subtitle,
                                            style = MaterialTheme.typography.bodyMedium,
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
                                        contentDescription = "Choose disguise",
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
                                                DisguiseIconPreview(identity = identity, sizeDp = 38)
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
                                                        modifier = Modifier.size(20.dp),
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
                                .height(50.dp)
                                .testTag("apply_disguise_button"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Apply \"${selectedIdentity.displayLabel}\" Disguise")
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "2. Icon Display",
                            style = MaterialTheme.typography.titleMedium,
                        )

                        ExposedDropdownMenuBox(
                            expanded = iconDisplayMenuExpanded,
                            onExpandedChange = { iconDisplayMenuExpanded = !iconDisplayMenuExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val currentDisplay = state.activeRedirectDisplay
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (iconDisplayMenuExpanded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                                    },
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    .clip(RoundedCornerShape(16.dp))
                                    .testTag("icon_display_dropdown_anchor"),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.size(44.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = redirectStyleIcon(currentDisplay),
                                                contentDescription = currentDisplay.displayLabel,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = currentDisplay.displayLabel,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            text = currentDisplay.description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Icon(
                                        imageVector = if (iconDisplayMenuExpanded) {
                                            Icons.Rounded.KeyboardArrowUp
                                        } else {
                                            Icons.Rounded.KeyboardArrowDown
                                        },
                                        contentDescription = "Choose icon display",
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
                                    val isSelected = state.activeRedirectDisplay == style
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.size(38.dp),
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = redirectStyleIcon(style),
                                                            contentDescription = style.displayLabel,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(20.dp),
                                                        )
                                                    }
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = style.displayLabel,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (isSelected) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                        },
                                                    )
                                                    Text(
                                                        text = style.description,
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
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            onUpdateRedirectDisplay(style)
                                            iconDisplayMenuExpanded = false
                                        },
                                        modifier = Modifier.testTag("icon_display_option_${style.name.lowercase()}"),
                                    )
                                }
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

            item { Spacer(modifier = Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun DisguiseIconPreview(
    identity: DisguiseIdentity,
    sizeDp: Int = 46,
) {
    if (identity == DisguiseIdentity.DEFAULT) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(12.dp),
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
            .clip(RoundedCornerShape(12.dp)),
    )
}
