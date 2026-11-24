package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun StealthTriggersProtectedCard(
    wakeWord: String,
    dialerPin: String,
    isAccessibilityEnabled: Boolean,
    onUpdateStealthTriggers: (wakeWord: String, dialerPin: String) -> Unit,
    onVerifyAdminPassword: (password: String, onResult: (Boolean) -> Unit) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
) {
    var wakeWordInput by remember(wakeWord) { mutableStateOf(wakeWord) }
    var dialerPinInput by remember(dialerPin) { mutableStateOf(dialerPin) }
    var triggersRevealed by remember { mutableStateOf(false) }
    var showRevealAuthDialog by remember { mutableStateOf(false) }

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
            if (!triggersRevealed) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Stealth Triggers",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = "Wake Word: ••••••••",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Dialer PIN: ••••••",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { showRevealAuthDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("reveal_stealth_triggers_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Reveal")
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = wakeWordInput,
                    onValueChange = { wakeWordInput = it },
                    label = { Text("Wake Word") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stealth_wake_word_input"),
                )

                OutlinedTextField(
                    value = dialerPinInput,
                    onValueChange = { s -> dialerPinInput = s.filter { it.isDigit() }.take(10) },
                    label = { Text("Dialer PIN") },
                    leadingIcon = { Icon(Icons.Rounded.Dialpad, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stealth_dialer_pin_input"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { triggersRevealed = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("hide_stealth_triggers_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Hide")
                    }
                    Button(
                        onClick = {
                            onUpdateStealthTriggers(wakeWordInput, dialerPinInput)
                            triggersRevealed = false
                        },
                        enabled = wakeWordInput.trim().length >= 3,
                        modifier = Modifier
                            .weight(1.4f)
                            .height(48.dp)
                            .testTag("save_stealth_triggers_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Save")
                    }
                }
            }

            if (!isAccessibilityEnabled) {
                OutlinedButton(
                    onClick = onOpenAccessibilitySettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stealth_enable_accessibility_button"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Enable Accessibility")
                }
            }
        }
    }

    if (showRevealAuthDialog) {
        RevealStealthAuthDialog(
            onDismiss = { showRevealAuthDialog = false },
            onVerify = { password, onError ->
                onVerifyAdminPassword(password) { ok ->
                    if (ok) {
                        triggersRevealed = true
                        showRevealAuthDialog = false
                    } else {
                        onError("Invalid password")
                    }
                }
            },
        )
    }
}

@Composable
private fun RevealStealthAuthDialog(
    onDismiss: () -> Unit,
    onVerify: (password: String, onError: (String) -> Unit) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin Password") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMsg = null
                },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                isError = errorMsg != null,
                supportingText = errorMsg?.let { err -> { Text(err) } },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reveal_triggers_password_input"),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.isBlank()) {
                        errorMsg = "Required"
                    } else {
                        onVerify(password) { msg -> errorMsg = msg }
                    }
                },
                modifier = Modifier.testTag("reveal_triggers_confirm_button"),
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
