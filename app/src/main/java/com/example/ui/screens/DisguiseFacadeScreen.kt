package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.entity.DisguiseIdentity
import kotlinx.coroutines.delay

@Composable
fun DisguiseFacadeScreen(
    identity: DisguiseIdentity,
    onCheckWakeTrigger: (String) -> Boolean,
    onTriggerAdminUnlock: () -> Unit,
) {
    when (identity) {
        DisguiseIdentity.CALCULATOR -> CalculatorDisguise(
            onCheckWakeTrigger = onCheckWakeTrigger,
            onTriggerAdminUnlock = onTriggerAdminUnlock,
        )
        DisguiseIdentity.NOTES -> NotesDisguise(
            onCheckWakeTrigger = onCheckWakeTrigger,
            onTriggerAdminUnlock = onTriggerAdminUnlock,
        )
        DisguiseIdentity.WEATHER -> WeatherDisguise(
            onCheckWakeTrigger = onCheckWakeTrigger,
            onTriggerAdminUnlock = onTriggerAdminUnlock,
        )
        DisguiseIdentity.CLOCK -> TimerDisguise(
            onCheckWakeTrigger = onCheckWakeTrigger,
            onTriggerAdminUnlock = onTriggerAdminUnlock,
        )
        DisguiseIdentity.DEFAULT -> onTriggerAdminUnlock()
    }
}

@Composable
private fun CalculatorDisguise(
    onCheckWakeTrigger: (String) -> Boolean,
    onTriggerAdminUnlock: () -> Unit,
) {
    var display by remember { mutableStateOf("0") }
    var pendingOp by remember { mutableStateOf<Char?>(null) }
    var accumulator by remember { mutableStateOf<Double?>(null) }
    var resetOnNextDigit by remember { mutableStateOf(false) }

    fun pressDigit(d: String) {
        display = if (display == "0" || resetOnNextDigit) {
            resetOnNextDigit = false
            d
        } else {
            (display + d).take(14)
        }
    }

    fun evaluate() {
        if (onCheckWakeTrigger(display)) {
            onTriggerAdminUnlock()
            return
        }
        val curr = display.toDoubleOrNull() ?: return
        val acc = accumulator
        val op = pendingOp
        if (acc != null && op != null) {
            val res = when (op) {
                '+' -> acc + curr
                '-' -> acc - curr
                '×' -> acc * curr
                '÷' -> if (curr != 0.0) acc / curr else Double.NaN
                else -> curr
            }
            display = if (res.isNaN()) "Error" else if (res % 1.0 == 0.0) res.toLong().toString() else "%.4f".format(res).trimEnd('0').trimEnd('.')
            accumulator = null
            pendingOp = null
            resetOnNextDigit = true
        }
    }

    val rows = listOf(
        listOf("C", "⌫", "%", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "-"),
        listOf("1", "2", "3", "+"),
        listOf("0", ".", "="),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 24.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    if (pendingOp != null && accumulator != null) {
                        Text(
                            text = "${accumulator?.let { if (it % 1.0 == 0.0) it.toLong() else it }} $pendingOp",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = display,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        modifier = Modifier.testTag("calc_display"),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { key ->
                            val isEquals = key == "="
                            val isOp = key in listOf("+", "-", "×", "÷", "%")
                            val isClear = key in listOf("C", "⌫")
                            val bg = when {
                                isEquals -> MaterialTheme.colorScheme.primary
                                isOp -> MaterialTheme.colorScheme.primaryContainer
                                isClear -> MaterialTheme.colorScheme.surfaceVariant
                                else -> MaterialTheme.colorScheme.surface
                            }
                            val fg = when {
                                isEquals -> MaterialTheme.colorScheme.onPrimary
                                isOp -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }

                            Surface(
                                color = bg,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(if (key == "0") 2f else 1f)
                                    .aspectRatio(if (key == "0") 2.1f else 1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable {
                                        when (key) {
                                            "C" -> {
                                                display = "0"
                                                accumulator = null
                                                pendingOp = null
                                            }
                                            "⌫" -> {
                                                display = if (display.length <= 1) "0" else display.dropLast(1)
                                            }
                                            "%" -> {
                                                val v = display.toDoubleOrNull() ?: 0.0
                                                display = (v / 100.0).toString()
                                            }
                                            "+", "-", "×", "÷" -> {
                                                accumulator = display.toDoubleOrNull() ?: 0.0
                                                pendingOp = key.first()
                                                resetOnNextDigit = true
                                            }
                                            "=" -> evaluate()
                                            "." -> if (!display.contains(".")) display += "."
                                            else -> pressDigit(key)
                                        }
                                    }
                                    .testTag("calc_key_$key"),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = fg,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesDisguise(
    onCheckWakeTrigger: (String) -> Boolean,
    onTriggerAdminUnlock: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    val notes = remember {
        mutableStateListOf(
            "Grocery List" to "Oat milk, sourdough, avocados, espresso beans",
            "Weekend Tasks" to "Car wash, organize desk drawer, reply to Alex",
            "Book Quotes" to "Discipline is choosing between what you want now and what you want most.",
        )
    }
    var newNoteTitle by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Quick Notes",
                    style = MaterialTheme.typography.headlineMedium,
                )
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Note")
                }
            }

            OutlinedTextField(
                value = search,
                onValueChange = { q ->
                    search = q
                    if (onCheckWakeTrigger(q)) {
                        onTriggerAdminUnlock()
                    }
                },
                placeholder = { Text("Search notes...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notes_disguise_search"),
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val filtered = notes.filter {
                    search.isBlank() ||
                        it.first.contains(search, ignoreCase = true) ||
                        it.second.contains(search, ignoreCase = true)
                }
                items(filtered) { (title, body) ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(text = title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Note") },
            text = {
                OutlinedTextField(
                    value = newNoteTitle,
                    onValueChange = { newNoteTitle = it },
                    label = { Text("Title") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newNoteTitle.isNotBlank()) {
                        notes.add(0, newNoteTitle.trim() to "Saved locally")
                        newNoteTitle = ""
                    }
                    showAddDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WeatherDisguise(
    onCheckWakeTrigger: (String) -> Boolean,
    onTriggerAdminUnlock: () -> Unit,
) {
    var cityQuery by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OutlinedTextField(
                value = cityQuery,
                onValueChange = { q ->
                    cityQuery = q
                    if (onCheckWakeTrigger(q)) {
                        onTriggerAdminUnlock()
                    }
                },
                placeholder = { Text("Search city forecast...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("weather_disguise_search"),
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WbSunny,
                        contentDescription = "Clear sky",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        text = cityQuery.ifBlank { "Local Forecast" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "22°C",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Mostly Clear • Wind 8 km/h • Humidity 48%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerDisguise(
    onCheckWakeTrigger: (String) -> Boolean,
    onTriggerAdminUnlock: () -> Unit,
) {
    var secondsLeft by remember { mutableIntStateOf(300) }
    var running by remember { mutableStateOf(false) }
    var labelInput by remember { mutableStateOf("") }

    LaunchedEffect(running) {
        while (running && secondsLeft > 0) {
            delay(1_000L)
            secondsLeft -= 1
        }
        if (secondsLeft == 0) running = false
    }

    val mins = secondsLeft / 60
    val secs = secondsLeft % 60

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 500.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            OutlinedTextField(
                value = labelInput,
                onValueChange = { q ->
                    labelInput = q
                    if (onCheckWakeTrigger(q)) {
                        onTriggerAdminUnlock()
                    }
                },
                placeholder = { Text("Timer label or preset...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("timer_disguise_search"),
            )

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                modifier = Modifier.size(220.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "%02d:%02d".format(mins, secs),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { running = !running },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(if (running) "Pause" else "Start")
                }
                Button(
                    onClick = {
                        running = false
                        secondsLeft = 300
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Reset")
                }
            }
        }
    }
}
