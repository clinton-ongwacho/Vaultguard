package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.entity.SecurityQuestionEntity

@Composable
fun AdminUnlockScreen(
    isHiddenMode: Boolean,
    stealthWakeSource: String?,
    authError: String?,
    failedAttempts: Int,
    securityQuestions: List<SecurityQuestionEntity>,
    onUnlock: (String) -> Unit,
    onClearError: () -> Unit,
    onVerifySecurityAnswers: (Map<Long, String>, (Boolean) -> Unit) -> Unit,
    onResetPassword: (Map<Long, String>, String, (Boolean, String) -> Unit) -> Unit,
    onReturnToDisguise: (() -> Unit)? = null,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = "VaultGuard",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }

            Text(
                text = stringResource(R.string.unlock_vault_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )

            if (stealthWakeSource != null || isHiddenMode) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stealthWakeSource ?: "Hidden Mode",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            onClearError()
                        },
                        label = { Text(stringResource(R.string.admin_password_label)) },
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
                        isError = authError != null,
                        supportingText = authError?.let { err ->
                            {
                                Text(
                                    if (failedAttempts > 1) "$err ($failedAttempts)" else err
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_unlock_password_input"),
                    )

                    Button(
                        onClick = { onUnlock(password) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("admin_unlock_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.btn_unlock),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    if (failedAttempts >= 2) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
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
                                        imageVector = Icons.Rounded.LockReset,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = "Reset Password",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showResetDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("suggest_reset_password_button"),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Security Questions")
                                }
                            }
                        }
                    }

                    if (onReturnToDisguise != null) {
                        OutlinedButton(
                            onClick = onReturnToDisguise,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("return_to_disguise_button"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Back to Disguise")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showResetDialog) {
        SecurityQuestionResetDialog(
            securityQuestions = securityQuestions,
            onDismiss = { showResetDialog = false },
            onVerifySecurityAnswers = onVerifySecurityAnswers,
            onResetPassword = { answers, newPass, onDone ->
                onResetPassword(answers, newPass) { ok, msg ->
                    if (ok) {
                        showResetDialog = false
                    }
                    onDone(ok, msg)
                }
            },
        )
    }
}

@Composable
private fun SecurityQuestionResetDialog(
    securityQuestions: List<SecurityQuestionEntity>,
    onDismiss: () -> Unit,
    onVerifySecurityAnswers: (Map<Long, String>, (Boolean) -> Unit) -> Unit,
    onResetPassword: (Map<Long, String>, String, (Boolean, String) -> Unit) -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateMapOf<Long, String>() }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (step == 0) "Security Questions" else "New Password")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (securityQuestions.size < 3) {
                    Text(
                        text = "Requires 3 saved questions (${securityQuestions.size}/3)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (step == 0) {
                    securityQuestions.forEachIndexed { idx, qEntity ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "${idx + 1}. ${qEntity.question}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        OutlinedTextField(
                            value = answers[qEntity.id].orEmpty(),
                            onValueChange = {
                                answers[qEntity.id] = it
                                errorMsg = null
                            },
                            label = { Text("Answer") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reset_question_answer_$idx"),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            errorMsg = null
                        },
                        label = { Text("New Password") },
                        leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reset_new_password_input"),
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            errorMsg = null
                        },
                        label = { Text("Confirm Password") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reset_confirm_password_input"),
                    )
                }

                if (errorMsg != null) {
                    Text(
                        text = errorMsg.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (securityQuestions.size >= 3) {
                Button(
                    onClick = {
                        if (step == 0) {
                            val providedMap = answers.toMap()
                            val nonBlank = providedMap.values.count { it.trim().isNotEmpty() }
                            if (nonBlank < 2) {
                                errorMsg = "Answer at least 2 questions."
                            } else {
                                onVerifySecurityAnswers(providedMap) { ok ->
                                    if (ok) {
                                        errorMsg = null
                                        step = 1
                                    } else {
                                        errorMsg = "Invalid answers."
                                    }
                                }
                            }
                        } else {
                            when {
                                newPassword.length < 4 -> {
                                    errorMsg = "Min 4 characters."
                                }
                                newPassword != confirmPassword -> {
                                    errorMsg = "Passwords do not match."
                                }
                                else -> {
                                    onResetPassword(answers.toMap(), newPassword) { ok, msg ->
                                        if (!ok) errorMsg = msg
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("reset_password_confirm_button"),
                ) {
                    Text(if (step == 0) "Verify" else "Reset")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
