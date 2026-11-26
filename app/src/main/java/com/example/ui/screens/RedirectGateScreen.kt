package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppBlocking
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.DoNotTouch
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.GatekeeperActivity
import com.example.R
import com.example.data.InstalledAppInfo
import com.example.data.entity.RedirectDecoyMode
import com.example.data.entity.RedirectDisplayStyle
import com.example.ui.components.AppDrawableIcon

fun redirectStyleIcon(style: RedirectDisplayStyle): ImageVector = when (style) {
    RedirectDisplayStyle.SUSPENDED -> Icons.Rounded.Block
    RedirectDisplayStyle.TEMPORARILY_PAUSED -> Icons.Rounded.PauseCircle
    RedirectDisplayStyle.BLOCKED -> Icons.Rounded.DoNotDisturbOn
    RedirectDisplayStyle.DISABLED -> Icons.Rounded.PowerSettingsNew
    RedirectDisplayStyle.UNAVAILABLE -> Icons.Rounded.AppBlocking
    RedirectDisplayStyle.CANNOT_BE_REACHED -> Icons.Rounded.CloudOff
    RedirectDisplayStyle.FLAGGED -> Icons.Rounded.Flag
    RedirectDisplayStyle.SUSPICIOUS -> Icons.Rounded.WarningAmber
    RedirectDisplayStyle.CONTACT_SUPPORT -> Icons.Rounded.SupportAgent
    RedirectDisplayStyle.UNDER_REVIEW -> Icons.Rounded.HourglassTop
    RedirectDisplayStyle.ACCESS_DENIED -> Icons.Rounded.GppBad
    RedirectDisplayStyle.NOT_ALLOWED -> Icons.Rounded.DoNotTouch
}

@Composable
fun RedirectGateScreen(
    mode: String,
    targetPackage: String,
    targetAppInfo: InstalledAppInfo?,
    redirectDisplayStyle: RedirectDisplayStyle = RedirectDisplayStyle.SUSPENDED,
    redirectDecoyMode: RedirectDecoyMode = RedirectDecoyMode.ICON_DISPLAY,
    errorMessage: String?,
    failedAttempts: Int,
    isBusy: Boolean = false,
    onGoHome: () -> Unit,
    onVerifyAndProceed: (password: String) -> Unit,
    onClearError: () -> Unit,
) {
    var showPasswordPrompt by remember(mode, targetPackage) {
        mutableStateOf(mode != GatekeeperActivity.MODE_REDIRECT_APP)
    }
    var symbolTapCount by remember(mode, targetPackage) { mutableIntStateOf(0) }
    var lastTapMillis by remember(mode, targetPackage) { mutableLongStateOf(0L) }

    var password by remember(mode, targetPackage) { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val appTitle = targetAppInfo?.appName ?: targetPackage.ifBlank { "VaultGuard" }

    fun registerDoubleTap() {
        val now = System.currentTimeMillis()
        symbolTapCount = if (now - lastTapMillis <= 1_500L) symbolTapCount + 1 else 1
        lastTapMillis = now
        if (symbolTapCount >= 2) {
            symbolTapCount = 0
            showPasswordPrompt = true
        }
    }

    if (mode == GatekeeperActivity.MODE_REDIRECT_APP && !showPasswordPrompt) {
        if (redirectDecoyMode == RedirectDecoyMode.FAKE_CRASH) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .testTag("fake_crash_dialog"),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "$appTitle keeps stopping",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = ::registerDoubleTap,
                                )
                                .padding(horizontal = 22.dp, vertical = 20.dp)
                                .testTag("fake_crash_title_text"),
                        )
                        HorizontalDivider(color = Color.Black.copy(alpha = 0.14f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = ::registerDoubleTap,
                                )
                                .padding(horizontal = 22.dp, vertical = 14.dp)
                                .testTag("fake_crash_app_info_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "App info",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "App info",
                                fontSize = 15.sp,
                                color = Color.Black,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .clickable(enabled = !isBusy, onClick = onGoHome)
                                .padding(horizontal = 22.dp, vertical = 14.dp)
                                .testTag("fake_crash_close_app_button"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close app",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Close app",
                                fontSize = 15.sp,
                                color = Color.Black,
                            )
                        }
                    }
                }
            }
            return
        }

        val statusIcon = redirectStyleIcon(redirectDisplayStyle)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = ::registerDoubleTap,
                        )
                        .testTag("suspended_app_icon_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    AppDrawableIcon(
                        drawable = targetAppInfo?.icon,
                        contentDescription = appTitle,
                        size = 88.dp,
                    )
                }

                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(64.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = ::registerDoubleTap,
                        )
                        .testTag("prohibition_symbol_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = redirectDisplayStyle.displayLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                }

                Text(
                    text = redirectDisplayStyle.displayLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.testTag("suspended_label"),
                )
            }
        }
        return
    }

    val headline = when (mode) {
        GatekeeperActivity.MODE_UNINSTALL_GUARD -> "Tamper Guard"
        GatekeeperActivity.MODE_INSTALL_GUARD -> "Install Guard"
        else -> appTitle
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (mode == GatekeeperActivity.MODE_REDIRECT_APP && targetAppInfo != null) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    AppDrawableIcon(
                        drawable = targetAppInfo.icon,
                        contentDescription = appTitle,
                        size = 82.dp,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Block,
                                contentDescription = headline,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.size(82.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = headline,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }

            Text(
                text = headline,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { input ->
                            password = input
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = errorMessage != null,
                        supportingText = {
                            if (errorMessage != null) {
                                Text(
                                    if (failedAttempts > 1) {
                                        "$errorMessage ($failedAttempts)"
                                    } else {
                                        errorMessage
                                    }
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gate_password_input"),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onGoHome,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("gate_cancel_home_button"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Home")
                        }

                        Button(
                            onClick = { onVerifyAndProceed(password) },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(50.dp)
                                .testTag("gate_verify_open_button"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                when {
                                    isBusy -> "Checking..."
                                    mode == GatekeeperActivity.MODE_REDIRECT_APP -> "Open"
                                    else -> "Unlock"
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
