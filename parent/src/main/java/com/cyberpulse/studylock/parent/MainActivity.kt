package com.cyberpulse.studylock.parent

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ParentRelayController(applicationContext) }
            DisposableEffect(controller) {
                controller.start()
                onDispose { controller.close() }
            }
            StudyLockParentApp(controller)
        }
    }
}

private data class StudentSnapshot(
    val sessionActive: Boolean = false,
    val isPaused: Boolean = false,
    val totalSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val blockedSitesCount: Int = 0,
    val todayMinutes: Int = 0,
    val sessionsCompleted: Int = 0,
    val streak: Int = 0
)

private data class DashboardUiState(
    val code: String,
    val listening: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Preparing parent dashboard…",
    val student: StudentSnapshot? = null,
    val lastCommand: String = ""
)

private class ParentRelayController(context: Context) {
    companion object {
        private const val RELAY_BASE = "https://ntfy.sh"
        private const val PREFS = "studylock_parent_pairing_v1"
        private const val CODE_KEY = "pair_code"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val initialCode = prefs.getString(CODE_KEY, null)
        ?.takeIf { it.matches(Regex("\\d{6}")) }
        ?: createCode().also { prefs.edit().putString(CODE_KEY, it).apply() }

    private val _state = MutableStateFlow(DashboardUiState(code = initialCode))
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var listenerJob: Job? = null
    @Volatile private var activeCall: Call? = null

    fun start() {
        if (listenerJob?.isActive == true) return
        val code = _state.value.code
        listenerJob = scope.launch { listenLoop(code) }
    }

    fun regenerateCode() {
        val code = createCode()
        prefs.edit().putString(CODE_KEY, code).apply()
        activeCall?.cancel()
        listenerJob?.cancel()
        _state.value = DashboardUiState(
            code = code,
            status = "New pairing code ready. Scan it from the student app."
        )
        listenerJob = scope.launch { listenLoop(code) }
    }

    fun endFocusSession() {
        val current = _state.value
        if (!current.connected || current.student?.sessionActive != true) return
        scope.launch {
            val sent = publish(current.code, JSONObject().put("type", "cmd").put("action", "end"))
            _state.update {
                it.copy(lastCommand = if (sent) "End-session command sent" else "Could not send command")
            }
        }
    }

    fun close() {
        activeCall?.cancel()
        listenerJob?.cancel()
    }

    private suspend fun listenLoop(code: String) {
        val topic = topicFor(code)
        while (currentCoroutineContext().isActive) {
            try {
                _state.update { it.copy(listening = true, status = if (it.connected) "Student connected" else "Waiting for student…") }
                val request = Request.Builder()
                    .url("$RELAY_BASE/$topic/sse")
                    .header("Accept", "text/event-stream")
                    .build()
                val call = client.newCall(request)
                activeCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) error("Relay returned ${response.code}")
                    val source = response.body?.source() ?: error("Relay stream unavailable")
                    while (currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            handleEnvelope(code, line.removePrefix("data:").trim())
                        }
                    }
                }
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive) break
                _state.update { it.copy(listening = false, status = "Reconnecting to pairing service…") }
                delay(1800)
            } finally {
                activeCall = null
            }
        }
    }

    private fun handleEnvelope(code: String, raw: String) {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (envelope.optString("event") != "message") return
        val messageRaw = envelope.optString("message")
        val message = runCatching { JSONObject(messageRaw) }.getOrNull() ?: return

        when (message.optString("type")) {
            "hello" -> {
                val snapshot = parseStudent(message.optJSONObject("state"))
                _state.update {
                    it.copy(
                        listening = true,
                        connected = true,
                        status = "Student connected",
                        student = snapshot,
                        lastCommand = ""
                    )
                }
                scope.launch {
                    publish(code, JSONObject().put("type", "ack").put("dashboard", "StudyLock Parent"))
                }
            }
            "state" -> {
                val snapshot = parseStudent(message.optJSONObject("state"))
                _state.update {
                    it.copy(
                        listening = true,
                        connected = true,
                        status = "Student connected",
                        student = snapshot
                    )
                }
            }
            "bye" -> {
                _state.update {
                    it.copy(
                        connected = false,
                        status = "Student disconnected",
                        student = null,
                        lastCommand = ""
                    )
                }
            }
        }
    }

    private fun parseStudent(json: JSONObject?): StudentSnapshot {
        if (json == null) return StudentSnapshot()
        return StudentSnapshot(
            sessionActive = json.optBoolean("sessionActive", false),
            isPaused = json.optBoolean("isPaused", false),
            totalSeconds = json.optInt("totalSeconds", 0).coerceAtLeast(0),
            remainingSeconds = json.optInt("remainingSeconds", 0).coerceAtLeast(0),
            blockedSitesCount = json.optInt("blockedSitesCount", 0).coerceAtLeast(0),
            todayMinutes = json.optInt("todayMinutes", 0).coerceAtLeast(0),
            sessionsCompleted = json.optInt("sessionsCompleted", 0).coerceAtLeast(0),
            streak = json.optInt("streak", 0).coerceAtLeast(0)
        )
    }

    private fun publish(code: String, payload: JSONObject): Boolean {
        val topic = topicFor(code)
        val body = payload.toString().toRequestBody("text/plain; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$RELAY_BASE/$topic").post(body).build()
        return runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun topicFor(code: String) = "studylock-pair-$code"

    private fun createCode(): String {
        val value = SecureRandom().nextInt(900_000) + 100_000
        return value.toString()
    }
}

@Composable
private fun StudyLockParentApp(controller: ParentRelayController) {
    val ui by controller.state.collectAsState()
    val orange = Color(0xFFFF7A1F)
    val paleOrange = Color(0xFFFFF4EA)
    val scheme = lightColorScheme(
        primary = orange,
        onPrimary = Color.White,
        background = Color(0xFFFFFCF9),
        surface = Color.White,
        onSurface = Color(0xFF21170F),
        secondary = Color(0xFFFFA75F)
    )

    MaterialTheme(colorScheme = scheme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFFFFFCF9))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("StudyLock", fontSize = 27.sp, fontWeight = FontWeight.Black)
                            Text("Parent Dashboard", color = orange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        ConnectionPill(ui.connected, ui.listening)
                    }
                }

                item {
                    GlassCard {
                        Text("Connect student", fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "On the student StudyLock app, open Settings → Parent dashboard, then scan this QR code or enter the 6-digit passkey.",
                            color = Color(0xFF776A61),
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        val qr = remember(ui.code) { pairingQr(ui.code) }
                        qr?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "StudyLock pairing QR code",
                                modifier = Modifier
                                    .size(220.dp)
                                    .align(Alignment.CenterHorizontally)
                                    .background(Color.White, RoundedCornerShape(18.dp))
                                    .border(1.dp, Color(0xFFFFD7B7), RoundedCornerShape(18.dp))
                                    .padding(12.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            ui.code.chunked(3).joinToString(" "),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp,
                            color = orange
                        )
                        Text(
                            ui.status,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color(0xFF776A61),
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = controller::regenerateCode,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Generate new passkey")
                        }
                    }
                }

                item {
                    GlassCard {
                        Text("Student status", fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(10.dp))
                        val student = ui.student
                        if (student == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(paleOrange, RoundedCornerShape(18.dp))
                                    .padding(18.dp)
                            ) {
                                Text(
                                    if (ui.listening) "Waiting for the student app to connect…" else "Connecting to pairing service…",
                                    color = Color(0xFF6D5E53)
                                )
                            }
                        } else {
                            val focusText = when {
                                student.sessionActive && student.isPaused -> "Focus paused"
                                student.sessionActive -> "Focus active"
                                else -> "No active focus session"
                            }
                            Text(focusText, color = if (student.sessionActive) orange else Color(0xFF6D5E53), fontWeight = FontWeight.Bold)
                            if (student.sessionActive) {
                                Spacer(Modifier.height(6.dp))
                                Text(formatDuration(student.remainingSeconds), fontSize = 42.sp, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricTile("Today", "${student.todayMinutes} min", Modifier.weight(1f))
                                MetricTile("Sessions", student.sessionsCompleted.toString(), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MetricTile("Streak", "${student.streak} days", Modifier.weight(1f))
                                MetricTile("Blocked", student.blockedSitesCount.toString(), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = controller::endFocusSession,
                                enabled = student.sessionActive,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = orange,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("End focus session", fontWeight = FontWeight.Bold)
                            }
                            if (ui.lastCommand.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(ui.lastCommand, color = Color(0xFF776A61), fontSize = 12.sp)
                            }
                        }
                    }
                }

                item {
                    GlassCard {
                        Text("How pairing works", fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The current StudyLock student app connects through its existing six-digit ntfy relay topic. This parent app listens for the student hello/state messages, replies with the required acknowledgement, shows live study metrics, and can send the supported remote end-session command.",
                            color = Color(0xFF776A61),
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Keep the parent app open while pairing. For a future production release, this relay should be replaced with authenticated Firebase pairing and stronger per-family session credentials.",
                            color = Color(0xFF9A4D14),
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPill(connected: Boolean, listening: Boolean) {
    val color = when {
        connected -> Color(0xFF16834B)
        listening -> Color(0xFFFF7A1F)
        else -> Color(0xFF9A8E85)
    }
    val label = when {
        connected -> "CONNECTED"
        listening -> "READY"
        else -> "OFFLINE"
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFFFDFC5), RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFFFFF5EC), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Text(label, color = Color(0xFF8A7C72), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color(0xFF21170F), fontSize = 20.sp, fontWeight = FontWeight.Black)
    }
}

private fun pairingQr(code: String): Bitmap? {
    return runCatching {
        val payload = "https://studylock.cyberpulse/pair?code=$code"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 420, 420)
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) {
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    }.getOrNull()
}

private fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
}
