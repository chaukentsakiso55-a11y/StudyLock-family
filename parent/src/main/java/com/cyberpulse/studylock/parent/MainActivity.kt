package com.cyberpulse.studylock.parent

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CyberPulseApp(appSpec) }
    }
}

private enum class RootTab(val label: String, val glyph: String) {
    HOME("Home", "◈"),
    WORKSPACE("Workspace", "⌁"),
    PROGRESS("Progress", "↗"),
    SETTINGS("Settings", "⚙")
}

@Composable
private fun CyberPulseApp(spec: AppSpec) {
    val primary = Color(spec.primary)
    val secondary = Color(spec.secondary)
    val colors = darkColorScheme(
        primary = primary,
        secondary = secondary,
        background = Color(0xFF05070D),
        surface = Color(0xFF101522),
        surfaceVariant = Color(0xFF171E2E),
        onPrimary = Color(0xFF001014),
        onBackground = Color(0xFFF3F7FF),
        onSurface = Color(0xFFF3F7FF)
    )
    MaterialTheme(colorScheme = colors) {
        AppShell(spec, primary, secondary)
    }
}

@Composable
private fun AppShell(spec: AppSpec, primary: Color, secondary: Color) {
    var selected by remember { mutableStateOf(RootTab.HOME) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AmbientBackground(primary, secondary)
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xEA0B0F19),
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    RootTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selected = tab },
                            icon = { Text(tab.glyph, fontSize = 19.sp) },
                            label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = primary,
                                selectedTextColor = primary,
                                indicatorColor = primary.copy(alpha = 0.14f),
                                unselectedIconColor = Color(0xFF8690A6),
                                unselectedTextColor = Color(0xFF8690A6)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                RootTab.HOME -> HomeScreen(spec, primary, padding) { selected = RootTab.WORKSPACE }
                RootTab.WORKSPACE -> WorkspaceScreen(spec, primary, padding)
                RootTab.PROGRESS -> ProgressScreen(spec, primary, secondary, padding)
                RootTab.SETTINGS -> SettingsScreen(spec, primary, padding)
            }
        }
    }
}

@Composable
private fun AmbientBackground(primary: Color, secondary: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(listOf(primary.copy(alpha = 0.15f), Color.Transparent)),
            radius = size.minDimension * 0.72f,
            center = Offset(size.width * 0.9f, size.height * 0.12f)
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(secondary.copy(alpha = 0.12f), Color.Transparent)),
            radius = size.minDimension * 0.62f,
            center = Offset(size.width * 0.02f, size.height * 0.7f)
        )
    }
}

@Composable
private fun HomeScreen(spec: AppSpec, primary: Color, padding: PaddingValues, openWorkspace: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cyber_pulse_local", Context.MODE_PRIVATE) }
    var streak by remember { mutableIntStateOf(prefs.getInt("streak", 0)) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { BrandHeader(spec, primary) }
        item {
            GlassCard {
                StatusPill("LOCAL-FIRST", primary)
                Spacer(Modifier.height(14.dp))
                Text(spec.hero, fontSize = 29.sp, lineHeight = 33.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text(spec.tagline, color = Color(0xFFADB8CC), fontSize = 15.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = openWorkspace,
                        colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = Color(0xFF001014))
                    ) { Text("Open command space", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = {
                        streak += 1
                        prefs.edit().putInt("streak", streak).apply()
                    }) { Text("Check in · $streak") }
                }
            }
        }
        item { SectionTitle("Core systems", "Tap any card to explore it in Workspace") }
        items(spec.features.chunked(2)) { rowFeatures ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowFeatures.forEach { feature ->
                    FeatureCard(feature, primary, Modifier.weight(1f), openWorkspace)
                }
                if (rowFeatures.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseRing(primary, 0.72f, Modifier.size(70.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Foundation health", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("UI · Local storage · Navigation ready", color = Color(0xFFADB8CC), fontSize = 13.sp)
                        Text("Firebase intentionally deferred", color = primary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceScreen(spec: AppSpec, primary: Color, padding: PaddingValues) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cyber_pulse_local", Context.MODE_PRIVATE) }
    val saved = remember { prefs.getString("activity", "").orEmpty() }
    val activity = remember {
        mutableStateListOf<String>().apply {
            addAll(saved.split("\u001F").filter { it.isNotBlank() })
        }
    }
    var note by remember { mutableStateOf("") }
    var selectedFeature by remember { mutableStateOf(spec.features.first()) }
    var chosenMinutes by remember { mutableIntStateOf(25) }
    var secondsLeft by remember { mutableIntStateOf(25 * 60) }
    var timerRunning by remember { mutableStateOf(false) }

    LaunchedEffect(timerRunning, secondsLeft) {
        if (timerRunning && secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        } else if (secondsLeft == 0) {
            timerRunning = false
            val entry = "Completed ${selectedFeature.title} · $chosenMinutes min"
            activity.add(0, entry)
            prefs.edit().putString("activity", activity.joinToString("\u001F")).apply()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { BrandHeader(spec, primary) }
        item { SectionTitle("Command space", "Everything here works locally on this device") }
        item {
            LazyChipRow(spec.features, selectedFeature, primary) { selectedFeature = it }
        }
        item {
            GlassCard {
                Text(selectedFeature.badge, color = primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(selectedFeature.title, fontSize = 23.sp, fontWeight = FontWeight.Black)
                Text(selectedFeature.description, color = Color(0xFFADB8CC), lineHeight = 21.sp)
                Spacer(Modifier.height(18.dp))
                Text(spec.focusLabel, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(25, 45, 60, 90).forEach { value ->
                        MiniChoice(value.toString(), chosenMinutes == value, primary) {
                            chosenMinutes = value
                            secondsLeft = value * 60
                            timerRunning = false
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(formatTime(secondsLeft), fontSize = 42.sp, fontWeight = FontWeight.Black, color = primary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { timerRunning = !timerRunning },
                        colors = ButtonDefaults.buttonColors(containerColor = primary, contentColor = Color(0xFF001014))
                    ) { Text(if (timerRunning) "Pause" else "Start", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        timerRunning = false
                        secondsLeft = chosenMinutes * 60
                    }) { Text("Reset") }
                }
            }
        }
        item {
            GlassCard {
                Text("Local activity log", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(spec.logHint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (note.isNotBlank()) {
                            activity.add(0, note.trim())
                            prefs.edit().putString("activity", activity.joinToString("\u001F")).apply()
                            note = ""
                        }
                    })
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (note.isNotBlank()) {
                        activity.add(0, note.trim())
                        prefs.edit().putString("activity", activity.joinToString("\u001F")).apply()
                        note = ""
                    }
                }) { Text("Save locally") }
                AnimatedVisibility(activity.isNotEmpty()) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        activity.take(5).forEachIndexed { index, entry ->
                            if (index > 0) Divider(color = Color.White.copy(alpha = 0.07f))
                            Text(entry, modifier = Modifier.padding(vertical = 10.dp), color = Color(0xFFD7DEEC))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressScreen(spec: AppSpec, primary: Color, secondary: Color, padding: PaddingValues) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cyber_pulse_local", Context.MODE_PRIVATE) }
    val activityCount = remember {
        prefs.getString("activity", "").orEmpty().split("\u001F").count { it.isNotBlank() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { BrandHeader(spec, primary) }
        item { SectionTitle("Local insights", "Private progress without an account") }
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Momentum", color = Color(0xFFADB8CC))
                        Text("${(activityCount * 12).coerceAtMost(100)}%", fontSize = 36.sp, fontWeight = FontWeight.Black)
                        Text("$activityCount saved actions", color = primary)
                    }
                    PulseRing(primary, (activityCount * 0.12f).coerceIn(0.05f, 1f), Modifier.size(92.dp))
                }
            }
        }
        items(spec.metrics.chunked(2)) { metricRow ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                metricRow.forEachIndexed { index, metric ->
                    MetricCard(metric, if (index % 2 == 0) primary else secondary, Modifier.weight(1f))
                }
                if (metricRow.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            GlassCard {
                Text("Phase one boundary", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "This version intentionally keeps data on the device. Authentication, remote sync, cloud AI and Firebase rules will arrive as a separate reviewed integration.",
                    color = Color(0xFFADB8CC), lineHeight = 21.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(spec: AppSpec, primary: Color, padding: PaddingValues) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cyber_pulse_local", Context.MODE_PRIVATE) }
    var offlineOnly by remember { mutableStateOf(prefs.getBoolean("offline_only", true)) }
    var reducedMotion by remember { mutableStateOf(prefs.getBoolean("reduced_motion", false)) }
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { BrandHeader(spec, primary) }
        item { SectionTitle("Settings", "Control privacy and the local experience") }
        item {
            GlassCard {
                SettingToggle("Offline-only mode", "No account or network is required", offlineOnly, primary) {
                    offlineOnly = it
                    prefs.edit().putBoolean("offline_only", it).apply()
                }
                Divider(color = Color.White.copy(alpha = 0.07f))
                SettingToggle("Reduced motion", "Prepared for future animation controls", reducedMotion, primary) {
                    reducedMotion = it
                    prefs.edit().putBoolean("reduced_motion", it).apply()
                }
            }
        }
        item {
            GlassCard {
                StatusPill("BACKEND · NOT CONNECTED", Color(0xFFFFC857))
                Spacer(Modifier.height(12.dp))
                Text("Firebase comes later", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("There are no hidden keys, placeholder credentials or fake cloud calls in this repository.", color = Color(0xFFADB8CC), lineHeight = 21.sp)
            }
        }
        item {
            GlassCard {
                Text("About ${spec.shortName}", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(spec.about, color = Color(0xFFADB8CC), lineHeight = 21.sp)
                Spacer(Modifier.height(10.dp))
                Text("Cyber Pulse · v0.1.0 foundation", color = primary, fontWeight = FontWeight.Bold)
            }
        }
        item {
            GlassCard {
                Text("Local data", fontWeight = FontWeight.Bold)
                Text("Clear check-ins, preferences and activity saved by this app.", color = Color(0xFFADB8CC))
                Spacer(Modifier.height(8.dp))
                if (confirmClear) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            prefs.edit().clear().apply()
                            confirmClear = false
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEB6672))) { Text("Confirm clear") }
                        TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
                    }
                } else {
                    OutlinedButton(onClick = { confirmClear = true }) { Text("Clear local data") }
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(spec: AppSpec, primary: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(
                Brush.linearGradient(listOf(primary.copy(alpha = 0.9f), Color(spec.secondary).copy(alpha = 0.9f)))
            ),
            contentAlignment = Alignment.Center
        ) { Text(spec.shortName.take(2).uppercase(), color = Color(0xFF02060A), fontWeight = FontWeight.Black) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(spec.name, fontWeight = FontWeight.Black, fontSize = 19.sp)
            Text("CYBER PULSE / FOUNDATION", color = primary, fontSize = 10.sp, letterSpacing = 1.2.sp)
        }
        StatusPill("v0.1", primary)
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC111724)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}

@Composable
private fun FeatureCard(feature: AppFeature, primary: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.height(145.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xB8111723)),
        border = androidx.compose.foundation.BorderStroke(1.dp, primary.copy(alpha = 0.18f))
    ) {
        Column(Modifier.fillMaxSize().padding(15.dp), verticalArrangement = Arrangement.SpaceBetween) {
            StatusPill(feature.badge, primary)
            Column {
                Text(feature.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(feature.description, color = Color(0xFF9FAABF), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MetricCard(metric: AppMetric, color: Color, modifier: Modifier) {
    Card(
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.09f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.22f))
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(metric.label, color = Color(0xFFADB8CC), fontSize = 12.sp)
            Text(metric.value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = Color(0xFF8D98AD), fontSize = 13.sp)
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp)
    ) { Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp) }
}

@Composable
private fun MiniChoice(label: String, selected: Boolean, primary: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) primary.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp)
    ) { Text(label, color = if (selected) primary else Color(0xFFADB8CC), fontWeight = FontWeight.Bold) }
}

@Composable
private fun LazyChipRow(features: List<AppFeature>, selected: AppFeature, primary: Color, onSelect: (AppFeature) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(features) { feature ->
            Box(
                Modifier.clip(CircleShape)
                    .background(if (selected == feature) primary.copy(alpha = 0.18f) else Color(0xA8111723))
                    .clickable { onSelect(feature) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) { Text(feature.title, color = if (selected == feature) primary else Color(0xFFADB8CC), fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PulseRing(color: Color, progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.09f
        drawArc(Color.White.copy(alpha = 0.07f), -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(color, -90f, 360f * progress, false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun SettingToggle(title: String, subtitle: String, checked: Boolean, primary: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFF8D98AD), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF001014), checkedTrackColor = primary)
        )
    }
}

private fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
