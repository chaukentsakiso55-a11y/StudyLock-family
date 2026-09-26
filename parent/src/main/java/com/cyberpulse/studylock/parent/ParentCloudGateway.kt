package com.cyberpulse.studylock.parent

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ParentCloudGateway(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onCloudStatus(message: String)
        fun onStudentState(state: JSONObject)
        fun onCloudCommandAck(requestId: String, action: String, ok: Boolean, message: String, state: JSONObject?)
    }

    private val appContext = context.applicationContext
    private val configured = BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
        BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
        BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    private val firebaseApp: FirebaseApp? = if (configured) {
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .build()
        FirebaseApp.getApps(appContext).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(appContext, options, APP_NAME)
    } else null

    private val auth: FirebaseAuth? = firebaseApp?.let(FirebaseAuth::getInstance)
    private val db: FirebaseFirestore? = firebaseApp?.let(FirebaseFirestore::getInstance)
    private var channelListener: ListenerRegistration? = null
    private var messageListener: ListenerRegistration? = null
    private var currentCode = ""
    private var listenerStartedAt = 0L

    fun start(code: String) {
        currentCode = code
        if (!configured || auth == null || db == null) {
            listener.onCloudStatus("Cloud fallback is not configured; direct Wi-Fi control still works.")
            return
        }
        val user = auth.currentUser
        if (user != null) {
            openChannel(code, user.uid)
            return
        }
        listener.onCloudStatus("Connecting cloud fallback…")
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val uid = result.user?.uid.orEmpty()
                if (uid.isNotBlank()) openChannel(code, uid)
                else listener.onCloudStatus("Cloud fallback could not create a parent session.")
            }
            .addOnFailureListener { error ->
                listener.onCloudStatus("Cloud fallback unavailable: ${error.localizedMessage ?: "authentication error"}")
            }
    }

    fun switchCode(code: String) {
        stopListeners()
        start(code)
    }

    fun sendCommand(
        action: String,
        payload: JSONObject = JSONObject(),
        requestId: String = UUID.randomUUID().toString()
    ): Boolean {
        val database = db ?: return false
        val uid = auth?.currentUser?.uid ?: return false
        val code = currentCode.takeIf { it.matches(Regex("\\d{6}")) } ?: return false
        val channel = database.collection(CHANNELS).document(code)

        val update = mutableMapOf<String, Any>(
            "parentOnline" to true,
            "lastParentSeenMs" to System.currentTimeMillis()
        )
        when (action) {
            "start_focus" -> {
                update["startRequestId"] = requestId
                update["startRequestMinutes"] = payload.optInt("minutes", 25).coerceIn(25, 300)
            }
            "end_focus" -> update["endRequestId"] = requestId
            "set_schedule" -> {
                update["autoStudyEnabled"] = payload.optBoolean("enabled", true)
                update["autoStudyMinutes"] = payload.optInt("minutes", 25).coerceIn(25, 300)
                update["autoStudyStartMinuteOfDay"] = payload.optInt("startMinuteOfDay", -1)
            }
        }
        channel.set(update, com.google.firebase.firestore.SetOptions.merge())

        val messagePayload = JSONObject()
            .put("type", "cmd")
            .put("action", action)
            .put("requestId", requestId)
            .put("payload", payload)
        channel.collection("messages").add(
            mapOf(
                "senderUid" to uid,
                "senderRole" to "parent",
                "type" to "cmd",
                "payload" to messagePayload.toString(),
                "createdAtMs" to System.currentTimeMillis()
            )
        )
        return true
    }

    fun close() {
        stopListeners()
        currentCode = ""
    }

    private fun openChannel(code: String, uid: String) {
        val database = db ?: return
        listenerStartedAt = System.currentTimeMillis() - 1_000L
        val channel = database.collection(CHANNELS).document(code)
        channel.set(
            mapOf(
                "parentUid" to uid,
                "connected" to false,
                "parentOnline" to true,
                "lastParentSeenMs" to System.currentTimeMillis(),
                "expiresAtMs" to System.currentTimeMillis() + 30 * 60 * 1000L
            ),
            com.google.firebase.firestore.SetOptions.merge()
        ).addOnSuccessListener {
            listener.onCloudStatus("Cloud fallback ready")
        }.addOnFailureListener { error ->
            listener.onCloudStatus("Cloud fallback unavailable: ${error.localizedMessage ?: "write error"}")
        }

        channelListener = channel.addSnapshotListener { snapshot, error ->
            if (error != null) {
                listener.onCloudStatus("Cloud fallback listener error")
                return@addSnapshotListener
            }
            val data = snapshot?.data ?: return@addSnapshotListener
            val state = data["studentState"]
            if (state is Map<*, *>) listener.onStudentState(mapToJson(state))
            val studentUid = data["studentUid"]?.toString().orEmpty()
            val lastSeen = (data["lastStudentSeenMs"] as? Number)?.toLong() ?: 0L
            val ageMs = System.currentTimeMillis() - lastSeen
            listener.onCloudStatus(
                when {
                    studentUid.isBlank() -> "Cloud fallback ready — waiting for Student"
                    lastSeen > 0L && ageMs <= STUDENT_ONLINE_WINDOW_MS -> "Student online — cloud sync active"
                    else -> "Student paired but offline — waiting for a fresh heartbeat"
                }
            )
        }

        messageListener = channel.collection("messages")
            .orderBy("createdAtMs", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) return@forEach
                    val message = change.document.data
                    if (message["senderRole"] != "student") return@forEach
                    val payload = runCatching { JSONObject(message["payload"]?.toString().orEmpty()) }.getOrNull()
                        ?: return@forEach
                    when (payload.optString("type")) {
                        "hello" -> {
                            val state = payload.optJSONObject("state")
                            if (state != null) listener.onStudentState(state)
                            acknowledge(channel, uid)
                        }
                        "command_ack" -> {
                            listener.onCloudCommandAck(
                                payload.optString("requestId"),
                                payload.optString("action"),
                                payload.optBoolean("ok", false),
                                payload.optString("message"),
                                payload.optJSONObject("state")
                            )
                        }
                        "state" -> payload.optJSONObject("state")?.let(listener::onStudentState)
                    }
                }
            }
    }

    private fun acknowledge(
        channel: com.google.firebase.firestore.DocumentReference,
        uid: String
    ) {
        val ack = JSONObject().put("type", "ack").put("at", System.currentTimeMillis())
        channel.collection("messages").add(
            mapOf(
                "senderUid" to uid,
                "senderRole" to "parent",
                "type" to "ack",
                "payload" to ack.toString(),
                "createdAtMs" to System.currentTimeMillis()
            )
        )
        channel.set(
            mapOf("connected" to true, "parentOnline" to true, "lastParentSeenMs" to System.currentTimeMillis()),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }

    private fun stopListeners() {
        channelListener?.remove()
        messageListener?.remove()
        channelListener = null
        messageListener = null
    }

    private fun mapToJson(source: Map<*, *>): JSONObject = JSONObject().apply {
        source.forEach { (key, value) ->
            if (key != null) put(key.toString(), jsonValue(value))
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> mapToJson(value)
        is List<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        else -> value
    }

    companion object {
        private const val APP_NAME = "studylock-parent-native"
        private const val CHANNELS = "studylock_parent_channels"
        private const val STUDENT_ONLINE_WINDOW_MS = 30_000L
    }
}
