package com.cyberpulse.studylock.parent

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ParentMainActivity : Activity(), ParentDirectServer.Listener, ParentCloudGateway.Listener {
    private lateinit var directServer: ParentDirectServer
    private lateinit var cloudGateway: ParentCloudGateway

    private lateinit var pairingCodeView: TextView
    private lateinit var directStatusView: TextView
    private lateinit var cloudStatusView: TextView
    private lateinit var studentStatusView: TextView
    private lateinit var commandStatusView: TextView

    private val commandHandler = Handler(Looper.getMainLooper())
    private val pendingCommands = mutableMapOf<String, String>()

    private lateinit var minutesInput: EditText
    private lateinit var subjectInput: EditText
    private lateinit var scheduleTimeInput: EditText
    private lateinit var scheduleMinutesInput: EditText
    private lateinit var scheduleEnabled: CheckBox
    private lateinit var blockedAppsInput: EditText
    private lateinit var allowedAppsInput: EditText
    private lateinit var goalInput: EditText
    private lateinit var assignmentInput: EditText
    private lateinit var quizTopicInput: EditText
    private lateinit var quizDifficultyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        directServer = ParentDirectServer(this, this)
        cloudGateway = ParentCloudGateway(this, this)
        setContentView(buildUi())

        directServer.start()
        pairingCodeView.text = directServer.pairingCode
        cloudGateway.start(directServer.pairingCode)
    }

    override fun onDestroy() {
        commandHandler.removeCallbacksAndMessages(null)
        pendingCommands.clear()
        directServer.stop()
        cloudGateway.close()
        super.onDestroy()
    }

    override fun onDirectStatus(message: String, connected: Boolean) {
        directStatusView.text = "Direct: $message"
        directStatusView.setTextColor(if (connected) OK else MUTED)
    }

    override fun onCloudStatus(message: String) {
        cloudStatusView.text = "Fallback: $message"
    }

    override fun onCommandAck(
        requestId: String,
        action: String,
        ok: Boolean,
        message: String,
        state: JSONObject?
    ) {
        handleCommandAck(requestId, action, ok, message, state)
    }

    override fun onCloudCommandAck(
        requestId: String,
        action: String,
        ok: Boolean,
        message: String,
        state: JSONObject?
    ) {
        handleCommandAck(requestId, action, ok, message, state)
    }

    private fun handleCommandAck(
        requestId: String,
        action: String,
        ok: Boolean,
        message: String,
        state: JSONObject?
    ) {
        if (requestId.isBlank() || pendingCommands.remove(requestId) == null) return
        state?.let(::onStudentState)
        val actionLabel = action.replace('_', ' ')
        commandStatusView.text = if (ok) {
            "$actionLabel applied on Student ✓"
        } else {
            "$actionLabel failed on Student: ${message.ifBlank { "execution failed" }}"
        }
        commandStatusView.setTextColor(if (ok) OK else ERROR)
        toast(commandStatusView.text.toString())
    }

    override fun onStudentState(state: JSONObject) {
        val active = state.optBoolean("focusActive", state.optBoolean("active", false))
        val paused = state.optBoolean("focusPaused", state.optBoolean("paused", false))
        val remaining = state.optInt("remainingSeconds", state.optInt("focusRemainingSeconds", 0))
        val minutes = remaining / 60
        val seconds = remaining % 60
        val streak = state.optInt("streak", 0)
        val sessions = state.optInt("sessionsCompleted", 0)
        val today = state.optString("todayMinutes", state.optString("today", "0m"))
        val mode = state.optString("studyMode", "normal")
        val blockedCount = state.optInt("blockedSitesCount", state.optJSONArray("blockedEntries")?.length() ?: 0)
        val protection = state.optJSONObject("protection")
        val accessibility = protection?.optBoolean("accessibilityEnabled", false) == true
        studentStatusView.text = buildString {
            append(if (active) "FOCUS ACTIVE" else "READY")
            if (paused) append(" • PAUSED")
            append("\nTime: ").append(String.format("%02d:%02d", minutes, seconds))
            append("  •  Today: ").append(today)
            append("\nSessions: ").append(sessions)
            append("  •  Streak: ").append(streak)
            append("  •  Mode: ").append(mode)
            append("\nBlocked: ").append(blockedCount)
            append("  •  Accessibility: ").append(if (accessibility) "ON" else "OFF")
        }
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(text("StudyLock Parent", 26f, Color.WHITE, true))
        root.addView(text("Direct control center for the paired StudyLock Student app", 13f, MUTED, false).withTop(4))

        root.addView(sectionTitle("PAIR STUDENT"))
        pairingCodeView = text("------", 34f, ACCENT, true).apply { gravity = Gravity.CENTER }
        root.addView(card(pairingCodeView))
        directStatusView = text("Direct: starting…", 12f, MUTED, false)
        cloudStatusView = text("Fallback: starting…", 12f, MUTED, false)
        root.addView(directStatusView.withTop(8))
        root.addView(cloudStatusView.withTop(4))
        root.addView(button("Generate new pairing code") {
            val code = directServer.regeneratePairingCode()
            pairingCodeView.text = code
            cloudGateway.switchCode(code)
            toast("New 6-digit pairing code ready")
        }.withTop(10))

        root.addView(sectionTitle("STUDENT STATUS"))
        studentStatusView = text("Waiting for Student…", 15f, Color.WHITE, false)
        root.addView(card(studentStatusView))
        commandStatusView = text("No command pending", 12f, MUTED, false)
        root.addView(commandStatusView.withTop(8))
        root.addView(button("Refresh student state") { send("refresh_state") }.withTop(8))
        root.addView(button("Lock Student Settings now") { send("lock_settings") }.withTop(6))

        root.addView(sectionTitle("FOCUS CONTROL"))
        minutesInput = input("Focus minutes (25–300)", "45", true)
        subjectInput = input("Subject / profile", "Geography", false)
        root.addView(minutesInput)
        root.addView(subjectInput.withTop(6))
        root.addView(button("Start focus") {
            send(
                "start_focus",
                JSONObject()
                    .put("minutes", number(minutesInput, 45).coerceIn(25, 300))
                    .put("subject", subjectInput.text.toString().trim())
            )
        }.withTop(8))
        root.addView(horizontalButtons(
            "Pause" to { send("pause_focus") },
            "Resume" to { send("resume_focus") },
            "End" to { send("end_focus") }
        ).withTop(6))

        root.addView(sectionTitle("DAILY SCHEDULE"))
        scheduleEnabled = CheckBox(this).apply {
            text = "Enable automatic daily StudyLock session"
            setTextColor(Color.WHITE)
            isChecked = true
        }
        scheduleTimeInput = input("Start time (HH:MM)", "16:00", false)
        scheduleMinutesInput = input("Duration minutes", "60", true)
        root.addView(scheduleEnabled)
        root.addView(scheduleTimeInput.withTop(4))
        root.addView(scheduleMinutesInput.withTop(6))
        root.addView(button("Apply schedule") {
            val minuteOfDay = parseTime(scheduleTimeInput.text.toString())
            if (minuteOfDay < 0) {
                toast("Use a time like 16:00")
                return@button
            }
            send(
                "set_schedule",
                JSONObject()
                    .put("enabled", scheduleEnabled.isChecked)
                    .put("startMinuteOfDay", minuteOfDay)
                    .put("minutes", number(scheduleMinutesInput, 60).coerceIn(25, 300))
            )
        }.withTop(8))

        root.addView(sectionTitle("APP & WEBSITE CONTROL"))
        blockedAppsInput = input("Blocked apps/sites, comma separated", "YouTube, Chrome, tiktok.com", false)
        allowedAppsInput = input("Allowed apps during focus, comma separated", "Calculator, StudyLock", false)
        root.addView(blockedAppsInput)
        root.addView(button("Apply blocked list") {
            send("set_blocked", JSONObject().put("entries", csvArray(blockedAppsInput.text.toString())))
        }.withTop(6))
        root.addView(allowedAppsInput.withTop(10))
        root.addView(button("Apply allowed list") {
            send("set_allowed", JSONObject().put("entries", csvArray(allowedAppsInput.text.toString())))
        }.withTop(6))

        root.addView(sectionTitle("GOALS & ASSIGNMENTS"))
        goalInput = input("Parent goal", "Complete one Geography revision session", false)
        assignmentInput = input("Study assignment", "Revise climate and weather notes", false)
        root.addView(goalInput)
        root.addView(button("Send goal") {
            send("set_goal", JSONObject().put("text", goalInput.text.toString().trim()))
        }.withTop(6))
        root.addView(assignmentInput.withTop(10))
        root.addView(button("Send assignment") {
            send("set_assignment", JSONObject().put("text", assignmentInput.text.toString().trim()))
        }.withTop(6))

        root.addView(sectionTitle("QUIZ CONTROL"))
        quizTopicInput = input("Quiz topic", "Geography", false)
        quizDifficultyInput = input("Difficulty (easy / medium / hard)", "medium", false)
        root.addView(quizTopicInput)
        root.addView(quizDifficultyInput.withTop(6))
        root.addView(button("Assign quiz") {
            send(
                "set_quiz",
                JSONObject()
                    .put("topic", quizTopicInput.text.toString().trim())
                    .put("difficulty", quizDifficultyInput.text.toString().trim().lowercase())
            )
        }.withTop(8))

        root.addView(sectionTitle("STUDY MODE"))
        root.addView(horizontalButtons(
            "School" to { send("set_mode", JSONObject().put("mode", "school")) },
            "Holiday" to { send("set_mode", JSONObject().put("mode", "holiday")) },
            "Bedtime" to { send("set_mode", JSONObject().put("mode", "bedtime")) }
        ))
        root.addView(button("Return to normal mode") {
            send("set_mode", JSONObject().put("mode", "normal"))
        }.withTop(6))

        root.addView(sectionTitle("PROTECTION"))
        root.addView(text(
            "Parent controls can change StudyLock settings and study rules, but Android's own permission/admin approval screens remain protected and cannot be bypassed remotely.",
            12f,
            MUTED,
            false
        ))
        root.addView(button("Request protection/status refresh") { send("protection_status") }.withTop(8))

        return scroll
    }

    private fun send(action: String, payload: JSONObject = JSONObject()) {
        val requestId = UUID.randomUUID().toString()
        val direct = directServer.sendCommand(action, payload, requestId)
        val cloud = cloudGateway.sendCommand(action, payload, requestId)
        if (!direct && !cloud) {
            commandStatusView.text = "Student unavailable — command not sent"
            commandStatusView.setTextColor(ERROR)
            toast("Student is not connected yet")
            return
        }

        pendingCommands[requestId] = action
        commandStatusView.text = "Command pending · waiting for Student confirmation…"
        commandStatusView.setTextColor(MUTED)
        toast(if (direct) "Command sent directly · waiting for Student…" else "Command sent by cloud · waiting for Student…")

        commandHandler.postDelayed({
            if (pendingCommands.remove(requestId) != null) {
                commandStatusView.text = "Student unavailable · command was not confirmed"
                commandStatusView.setTextColor(ERROR)
            }
        }, COMMAND_ACK_TIMEOUT_MS)
    }

    private fun parseTime(raw: String): Int {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(raw.trim()) ?: return -1
        val hour = match.groupValues[1].toIntOrNull() ?: return -1
        val minute = match.groupValues[2].toIntOrNull() ?: return -1
        if (hour !in 0..23 || minute !in 0..59) return -1
        return hour * 60 + minute
    }

    private fun csvArray(raw: String): JSONArray = JSONArray().apply {
        raw.split(',').map(String::trim).filter(String::isNotBlank).distinct().forEach(::put)
    }

    private fun number(field: EditText, fallback: Int): Int = field.text.toString().trim().toIntOrNull() ?: fallback

    private fun sectionTitle(value: String) = text(value, 12f, ACCENT, true).withTop(24)

    private fun input(hint: String, value: String, numeric: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.rgb(110, 130, 145))
        setTextColor(Color.WHITE)
        setText(value)
        setBackgroundColor(CARD)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(BUTTON)
        setOnClickListener { action() }
    }

    private fun horizontalButtons(vararg buttons: Pair<String, () -> Unit>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEachIndexed { index, item ->
            addView(
                button(item.first, item.second),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(5)
                }
            )
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun card(content: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(CARD)
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun <T : android.view.View> T.withTop(dpValue: Int): T {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(dpValue)
        }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private val BG = Color.rgb(15, 10, 3)
        private val CARD = Color.rgb(38, 25, 8)
        private val BUTTON = Color.rgb(74, 44, 8)
        private val ACCENT = Color.rgb(255, 177, 0)
        private val MUTED = Color.rgb(226, 204, 160)
        private val OK = Color.rgb(101, 230, 167)
        private val ERROR = Color.rgb(255, 120, 104)
        private const val COMMAND_ACK_TIMEOUT_MS = 12_000L
    }
}
