package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.security.PasswordHasher
import com.example.ui.components.VaultOrangeBanner

@Composable
fun SetupScreen(
    onActivateVault: (
        password: String,
        wakeWord: String,
        dialerPin: String,
        securityQuestions: List<Pair<String, String>>,
    ) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var wakeWord by remember { mutableStateOf("shadow") }
    var dialerPin by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showAnswers by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val questionInputs = remember {
        mutableStateListOf(
            "First school name?",
            "Birth city?",
            "Childhood friend name?",
        )
    }
    val answerInputs = remember { mutableStateListOf("", "", "") }

    val t9Code = remember(wakeWord) {
        PasswordHasher.wakeWordToT9Digits(wakeWord).ifBlank { "82858" }
    }
    val effectivePin = dialerPin.filter { it.isDigit() }.ifBlank { t9Code }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VaultOrangeBanner(
                title = stringResource(R.string.setup_title),
                subtitle = "",
                height = 124.dp,
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "1. Admin Password",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            validationError = null
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
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_password_input"),
                    )

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            validationError = null
                        },
                        label = { Text(stringResource(R.string.confirm_password_label)) },
                        leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_confirm_password_input"),
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "2. Stealth Triggers",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    OutlinedTextField(
                        value = wakeWord,
                        onValueChange = {
                            wakeWord = it
                            validationError = null
                        },
                        label = { Text(stringResource(R.string.wake_word_label)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_wake_word_input"),
                    )

                    OutlinedTextField(
                        value = dialerPin,
                        onValueChange = { input ->
                            dialerPin = input.filter { it.isDigit() }.take(10)
                        },
                        label = { Text("Dialer PIN") },
                        placeholder = { Text("*#*#$t9Code#*#*") },
                        leadingIcon = { Icon(Icons.Rounded.Dialpad, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_dialer_pin_input"),
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "3. Security Questions",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    for (index in 0 until questionInputs.size) {
                        OutlinedTextField(
                            value = questionInputs[index],
                            onValueChange = {
                                questionInputs[index] = it
                                validationError = null
                            },
                            label = { Text("Question ${index + 1}") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_security_question_$index"),
                        )

                        OutlinedTextField(
                            value = answerInputs[index],
                            onValueChange = {
                                answerInputs[index] = it
                                validationError = null
                            },
                            label = { Text("Answer ${index + 1}") },
                            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { showAnswers = !showAnswers }) {
                                    Icon(
                                        imageVector = if (showAnswers) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = "Toggle answer",
                                    )
                                }
                            },
                            visualTransformation = if (showAnswers) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_security_answer_$index"),
                        )
                    }
                }
            }

            if (validationError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = validationError.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    )
                }
            }

            Button(
                onClick = {
                    val pairs = questionInputs.indices.map { i ->
                        questionInputs[i].trim() to answerInputs[i].trim()
                    }.filter { (q, a) -> q.isNotBlank() && a.isNotBlank() }

                    when {
                        password.length < 4 -> {
                            validationError = "Min 4 characters."
                        }
                        password != confirmPassword -> {
                            validationError = "Passwords do not match."
                        }
                        wakeWord.trim().length < 3 -> {
                            validationError = "Min 3 characters."
                        }
                        pairs.size < 3 -> {
                            validationError = "Complete all 3 questions."
                        }
                        else -> {
                            onActivateVault(password, wakeWord.trim(), effectivePin, pairs)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("activate_vault_button"),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.btn_activate_vault),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
