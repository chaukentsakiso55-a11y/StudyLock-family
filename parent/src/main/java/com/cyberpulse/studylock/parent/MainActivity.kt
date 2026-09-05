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
import androidx.compose.material3.Switch
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ParentFirebaseController(applicationContext) }
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
    val firebaseReady: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Connecting to Firebase…",
    val student: StudentSnapshot? = null,
    val autoStudyEnabled: Boolean = false,
    val autoStudyMinutes: Int = 25,
    val autoStudyStartMinuteOfDay: Int = 18 * 60,
    val lastCommand: String = ""
)

private class ParentFirebaseController(context: Context) {
    companion object {
        private const val CHANNELS = "studylock_parent_channels"
        private const val PREFS = "studylock_parent_firebase_v2"
        private const val CODE_KEY = "pair_code"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val initialCode = prefs.getString(CODE_KEY, null)
        ?.takeIf { it.matches(Regex("\\d{6}")) }
        ?: createCode().also { prefs.edit().putString(CODE_KEY, it).apply() }

    private val _state = MutableStateFlow(DashboardUiState(code = initialCode))
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private var channelListener: ListenerRegistration? = null
    private var messageListener: ListenerRegistration? = null
    private var listenerStartedAt = 0L
    private var uid: String = ""
    private val acknowledgedStudents = mutableSetOf<String>()

    fun start() {
        ensureIdentity { success ->
            if (!success) return@ensureIdentity
            createChannel(_state.value.code)
        }
    }

    fun regenerateCode() {
        closeListeners()
        acknowledgedStudents.clear()
        val code = createCode()
        prefs.edit().putString(CODE_KEY, code).apply()
        _state.update {
            it.copy(
                code = code,
                connected = false,
                student = null,
                status = "Creating a new Firebase pairing code…",
                lastCommand = ""
            )
        }
        ensureIdentity { success -> if (success) createChannel(code) }
    }

    fun setAutoStudy(enabled: Boolean) {
        _state.update { it.copy(autoStudyEnabled = enabled) }
        saveAutoSettings("Auto Study ${if (enabled) "enabled" else "disabled"}")
    }

    fun setAutoMinutes(minutes: Int) {
        _state.update { it.copy(autoStudyMinutes = minutes.coerceIn(25, 300)) }
        saveAutoSettings("Auto Study duration updated")
    }

    fun setAutoStartMinute(minuteOfDay: Int) {
        _state.update { it.copy(autoStudyStartMinuteOfDay = minuteOfDay.coerceIn(0, 1439)) }
        saveAutoSettings("Auto Study time updated")
    }

    fun startFocusNow() {
        if (!_state.value.connected) return
        val requestId = "start-${System.currentTimeMillis()}-${SecureRandom().nextInt(9999)}"
        channelRef().set(
            mapOf(
                "startRequestId" to requestId,
                "startRequestMinutes" to _state.value.autoStudyMinutes,
                "updatedAtMs" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).addOnSuccessListener {
            _state.update { it.copy(lastCommand = "Start-session command sent") }
        }.addOnFailureListener { error ->
            _state.update { it.copy(lastCommand = friendlyError(error)) }
        }
    }

    fun endFocusSession() {
        if (!_state.value.connected) return
        val requestId = "end-${System.currentTimeMillis()}-${SecureRandom().nextInt(9999)}"
        channelRef().set(
            mapOf(
                "endRequestId" to requestId,
                "updatedAtMs" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).addOnSuccessListener {
            _state.update { it.copy(lastCommand = "End-session command sent") }
        }.addOnFailureListener { error ->
            _state.update { it.copy(lastCommand = friendlyError(error)) }
        }
    }

    fun close() {
        closeListeners()
    }

    private fun ensureIdentity(callback: (Boolean) -> Unit) {
        auth.currentUser?.uid?.let {
            uid = it
            _state.update { state -> state.copy(firebaseReady = true) }
            callback(true)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                uid = result.user?.uid.orEmpty()
                _state.update { it.copy(firebaseReady = uid.isNotBlank()) }
                callback(uid.isNotBlank())
            }
            .addOnFailureListener { error ->
                _state.update {
                    it.copy(firebaseReady = false, status = "Firebase sign-in failed: ${friendlyError(error)}")
                }
                callback(false)
            }
    }

    private fun createChannel(code: String) {
        val now = System.currentTimeMillis()
        val current = _state.value
        channelRef(code).set(
            mapOf(
                "parentUid" to uid,
                "studentUid" to null,
                "connected" to false,
                "studentOnline" to false,
                "createdAtMs" to now,
                "expiresAtMs" to now + 15 * 60 * 1000L,
                "autoStudyEnabled" to current.autoStudyEnabled,
                "autoStudyMinutes" to current.autoStudyMinutes,
                "autoStudyStartMinuteOfDay" to current.autoStudyStartMinuteOfDay,
                "updatedAtMs" to now
            )
        ).addOnSuccessListener {
            _state.update {
                it.copy(
                    firebaseReady = true,
                    status = "Ready. Scan the QR code or enter the passkey in the student app."
                )
            }
            listenToChannel(code)
        }.addOnFailureListener { error ->
            _state.update {
                it.copy(status = "Firebase pairing unavailable: ${friendlyError(error)}")
            }
        }
    }

    private fun listenToChannel(code: String) {
        closeListeners()
        listenerStartedAt = System.currentTimeMillis() - 1500L
        val channel = channelRef(code)
        channelListener = channel.addSnapshotListener { snapshot, error ->
            if (error != null) {
                _state.update { it.copy(status = "Firebase connection error: ${friendlyError(error)}") }
                return@addSnapshotListener
            }
            val data = snapshot?.data ?: return@addSnapshotListener
            val studentUid = data["studentUid"] as? String
            val connected = !studentUid.isNullOrBlank()
            val studentState = (data["studentState"] as? Map<*, *>)?.let(::parseStudent)
            _state.update {
                it.copy(
                    firebaseReady = true,
                    connected = connected,
                    status = when {
                        connected && data["studentOnline"] == true -> "Student connected through Firebase"
                        connected -> "Student paired · waiting for live state"
                        else -> "Waiting for student…"
                    },
                    student = studentState ?: if (connected) it.student else null,
                    autoStudyEnabled = data["autoStudyEnabled"] as? Boolean ?: it.autoStudyEnabled,
                    autoStudyMinutes = (data["autoStudyMinutes"] as? Number)?.toInt() ?: it.autoStudyMinutes,
                    autoStudyStartMinuteOfDay = (data["autoStudyStartMinuteOfDay"] as? Number)?.toInt()
                        ?: it.autoStudyStartMinuteOfDay
                )
            }
            if (connected && studentUid != null) {
                sendAck(channel, studentUid)
            }
        }

        messageListener = channel.collection("messages")
            .whereGreaterThanOrEqualTo("createdAtMs", listenerStartedAt)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type != DocumentChange.Type.ADDED) return@forEach
                    if (change.document.getString("senderRole") != "student") return@forEach
                    val payload = change.document.getString("payload").orEmpty()
                    val message = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
                    if (message.optString("type") == "hello") {
                        val studentUid = change.document.getString("senderUid").orEmpty()
                        if (studentUid.isNotBlank()) sendAck(channel, studentUid)
                    }
                }
            }
    }

    private fun sendAck(
        channel: com.google.firebase.firestore.DocumentReference,
        studentUid: String
    ) {
        val key = "ack:$studentUid"
        if (!acknowledgedStudents.add(key)) return
        val payload = JSONObject()
            .put("type", "ack")
            .put("dashboard", "StudyLock Parent")
            .put("transport", "firebase")
            .toString()
        channel.collection("messages").add(
            mapOf(
                "senderUid" to uid,
                "senderRole" to "parent",
                "type" to "ack",
                "payload" to payload,
                "createdAtMs" to System.currentTimeMillis()
            )
        )
    }

    private fun saveAutoSettings(successMessage: String) {
        if (uid.isBlank()) return
        val state = _state.value
        channelRef().set(
            mapOf(
                "autoStudyEnabled" to state.autoStudyEnabled,
                "autoStudyMinutes" to state.autoStudyMinutes,
                "autoStudyStartMinuteOfDay" to state.autoStudyStartMinuteOfDay,
                "updatedAtMs" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).addOnSuccessListener {
            _state.update { it.copy(lastCommand = successMessage) }
        }.addOnFailureListener { error ->
            _state.update { it.copy(lastCommand = friendlyError(error)) }
        }
    }

    private fun parseStudent(map: Map<*, *>): StudentSnapshot = StudentSnapshot(
        sessionActive = map["sessionActive"] as? Boolean ?: false,
        isPaused = map["isPaused"] as? Boolean ?: false,
        totalSeconds = (map["totalSeconds"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        remainingSeconds = (map["remainingSeconds"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        blockedSitesCount = (map["blockedSitesCount"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        todayMinutes = (map["todayMinutes"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        sessionsCompleted = (map["sessionsCompleted"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        streak = (map["streak"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
    )

    private fun channelRef(code: String = _state.value.code) =
        firestore.collection(CHANNELS).document(code)

    private fun closeListeners() {
        channelListener?.remove()
        messageListener?.remove()
        channelListener = null
        messageListener = null
    }

    private fun createCode(): String =
        (SecureRandom().nextInt(900_000) + 100_000).toString()

    private fun friendlyError(error: Throwable): String =
        error.localizedMessage?.takeIf { it.isNotBlank() } ?: "Firebase request failed"
}

@Composable
private fun StudyLockParentApp(controller: ParentFirebaseController) {
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
        Box(Modifier.fillMaxSize().background(Color(0xFFFFFCF9))) {
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
                            Text("Parent Dashboard · Firebase", color = orange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        ConnectionPill(ui.connected, ui.firebaseReady)
                    }
                }

                item {
                    GlassCard {
                        Text("Connect student", fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Open Settings → Parent dashboard in the student app, then scan this QR code or enter the six-digit passkey.",
                            color = Color(0xFF776A61), fontSize = 13.sp, lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(16.dp))
                        pairingQr(ui.code)?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "StudyLock Firebase pairing QR code",
                                modifier = Modifier
                                    .size(220.dp)
                                    .align(Alignment.CenterHorizontally)
                                    .background(Color.White, RoundedCornerShape(18.dp))
                                    .border(1.dp, Color(0xFFFFD7B7), RoundedCornerShape(18.dp))
                                    .padding(12.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            ui.code.chunked(3).joinToString(" "),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 4.sp,
                            color = orange
                        )
                        Text(ui.status, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Color(0xFF776A61), fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = controller::regenerateCode, modifier = Modifier.fillMaxWidth()) {
                            Text("Generate new passkey")
                        }
                    }
                }

                item {
                    GlassCard {
                        Text("Auto Study", fontSize = 20.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Set a daily StudyLock session from the parent app. The student app stores this Firebase setting and starts it when StudyLock is running, or when it is next opened after the scheduled time.",
                            color = Color(0xFF776A61), fontSize = 13.sp, lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Auto Study enabled", fontWeight = FontWeight.Bold)
                                Text(if (ui.autoStudyEnabled) "ON" else "OFF", color = if (ui.autoStudyEnabled) orange else Color(0xFF8A7E75), fontSize = 12.sp)
                            }
                            Switch(checked = ui.autoStudyEnabled, onCheckedChange = controller::setAutoStudy)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Session length", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(25, 45, 60, 90).forEach { minutes ->
                                ChoiceButton(
                                    label = "${minutes}m",
                                    selected = ui.autoStudyMinutes == minutes,
                                    modifier = Modifier.weight(1f),
                                    onClick = { controller.setAutoMinutes(minutes) }
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Daily start time", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(16, 17, 18, 19, 20).forEach { hour ->
                                ChoiceButton(
                                    label = "%02d:00".format(hour),
                                    selected = ui.autoStudyStartMinuteOfDay == hour * 60,
                                    modifier = Modifier.weight(1f),
                                    onClick = { controller.setAutoStartMinute(hour * 60) }
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = controller::startFocusNow,
                            enabled = ui.connected,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = orange, contentColor = Color.White)
                        ) {
                            Text("Start study session now", fontWeight = FontWeight.Bold)
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
                                modifier = Modifier.fillMaxWidth().background(paleOrange, RoundedCornerShape(18.dp)).padding(18.dp)
                            ) {
                                Text(if (ui.connected) "Student paired. Waiting for live StudyLock state…" else "Waiting for the student app to connect…", color = Color(0xFF6D5E53))
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
                            OutlinedButton(
                                onClick = controller::endFocusSession,
                                enabled = ui.connected && student.sessionActive,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("End focus session")
                            }
                        }
                        if (ui.lastCommand.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(ui.lastCommand, color = Color(0xFF776A61), fontSize = 12.sp)
                        }
                    }
                }

                item {
                    GlassCard {
                        Text("Shared Firebase", fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Parent and student now use the same studylock-family Firebase project for pairing, live state and parent controls. Pairing codes expire after 15 minutes and a new code can be generated at any time.",
                            color = Color(0xFF776A61), fontSize = 13.sp, lineHeight = 19.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionPill(connected: Boolean, ready: Boolean) {
    val color = when {
        connected -> Color(0xFF16834B)
        ready -> Color(0xFFFF7A1F)
        else -> Color(0xFF9A8E85)
    }
    val label = when {
        connected -> "CONNECTED"
        ready -> "FIREBASE READY"
        else -> "OFFLINE"
    }
    Row(
        modifier = Modifier.background(color.copy(alpha = 0.10f), CircleShape).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xFFFFE4CF) else Color.White,
            contentColor = if (selected) Color(0xFFFF7A1F) else Color(0xFF5F5147)
        )
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDFCFB)),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), content = content)
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(Color(0xFFFFF4EA), RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Text(label, color = Color(0xFF8B786A), fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp)
    }
}

private fun pairingQr(code: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode("studylock://pair?code=$code", BarcodeFormat.QR_CODE, 512, 512)
    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}.getOrNull()

private fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
}
