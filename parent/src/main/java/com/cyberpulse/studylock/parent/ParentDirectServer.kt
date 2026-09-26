package com.cyberpulse.studylock.parent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

class ParentDirectServer(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onDirectStatus(message: String, connected: Boolean)
        fun onStudentState(state: JSONObject)
        fun onCommandAck(requestId: String, action: String, ok: Boolean, message: String, state: JSONObject?)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val writeLock = Any()

    @Volatile private var running = false
    @Volatile private var activeWriter: BufferedWriter? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var sessionToken = ""
    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    var pairingCode: String = PairingCodeStore.current(appContext)
        private set

    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        executor.execute {
            runCatching {
                val server = ServerSocket(0)
                serverSocket = server
                registerService(server.localPort)
                postStatus("Waiting for Student on the same Wi-Fi…", false)
                while (running) {
                    val socket = server.accept()
                    executor.execute { handleClient(socket) }
                }
            }.onFailure { error ->
                if (running) postStatus("Direct sync unavailable: ${error.message ?: "network error"}", false)
            }
        }
    }

    fun regeneratePairingCode(): String {
        pairingCode = PairingCodeStore.regenerate(appContext)
        restartAdvertising()
        return pairingCode
    }

    fun sendCommand(
        action: String,
        payload: JSONObject = JSONObject(),
        requestId: String = UUID.randomUUID().toString()
    ): Boolean {
        val writer = activeWriter ?: return false
        val command = JSONObject()
            .put("type", "cmd")
            .put("action", action)
            .put("requestId", requestId)
            .put("createdAtMs", System.currentTimeMillis())
            .put("payload", payload)
        return runCatching {
            synchronized(writeLock) {
                writer.write(command.toString())
                writer.newLine()
                writer.flush()
            }
            true
        }.getOrDefault(false)
    }

    fun requestState(): Boolean = sendCommand("refresh_state")

    fun stop() {
        running = false
        runCatching { activeSocket?.close() }
        activeSocket = null
        activeWriter = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.soTimeout = 0
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        var paired = false
        try {
            while (running && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
                when (message.optString("type")) {
                    "hello" -> {
                        if (message.optString("code") != pairingCode) {
                            writeJson(writer, JSONObject().put("type", "error").put("message", "Pairing code rejected"))
                            break
                        }
                        paired = true
                        sessionToken = UUID.randomUUID().toString()
                        activeSocket?.takeIf { it !== socket }?.let { old -> runCatching { old.close() } }
                        activeSocket = socket
                        activeWriter = writer
                        writeJson(
                            writer,
                            JSONObject()
                                .put("type", "ack")
                                .put("token", sessionToken)
                                .put("message", "Connected directly to StudyLock Parent")
                        )
                        postStatus("Student connected directly ✓", true)
                    }
                    "state" -> {
                        if (!paired) continue
                        val state = message.optJSONObject("state") ?: JSONObject()
                        mainHandler.post { listener.onStudentState(state) }
                    }
                    "command_ack" -> {
                        if (!paired) continue
                        val requestId = message.optString("requestId")
                        val action = message.optString("action")
                        val ok = message.optBoolean("ok", false)
                        val detail = message.optString("message")
                        val state = message.optJSONObject("state")
                        mainHandler.post {
                            listener.onCommandAck(requestId, action, ok, detail, state)
                        }
                    }
                    "ping" -> if (paired) {
                        writeJson(writer, JSONObject().put("type", "pong").put("at", System.currentTimeMillis()))
                    }
                }
            }
        } catch (_: Throwable) {
            // The status below handles disconnects without exposing socket internals to the UI.
        } finally {
            if (activeSocket === socket) {
                activeSocket = null
                activeWriter = null
                sessionToken = ""
                postStatus("Student disconnected — waiting for reconnection…", false)
            }
            runCatching { socket.close() }
        }
    }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "$SERVICE_PREFIX$pairingCode"
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                postStatus("Direct pairing advertisement failed ($errorCode). Cloud fallback remains available.", false)
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun restartAdvertising() {
        val old = registrationListener
        registrationListener = null
        if (old != null) runCatching { nsdManager.unregisterService(old) }
        val port = serverSocket?.localPort ?: return
        registerService(port)
        postStatus("New pairing code ready — waiting for Student…", false)
    }

    private fun acquireMulticastLock() {
        multicastLock = wifiManager.createMulticastLock("StudyLockParentDiscovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun writeJson(writer: BufferedWriter, value: JSONObject) {
        synchronized(writeLock) {
            writer.write(value.toString())
            writer.newLine()
            writer.flush()
        }
    }

    private fun postStatus(message: String, connected: Boolean) {
        mainHandler.post { listener.onDirectStatus(message, connected) }
    }

    private object PairingCodeStore {
        private const val PREFS = "studylock_parent_pairing"
        private const val KEY_CODE = "code"
        private val random = SecureRandom()

        fun current(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_CODE, null)
            if (saved?.matches(Regex("\\d{6}")) == true) return saved
            return regenerate(context)
        }

        fun regenerate(context: Context): String {
            val code = (100000 + random.nextInt(900000)).toString()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CODE, code)
                .apply()
            return code
        }
    }

    companion object {
        const val SERVICE_TYPE = "_studylock._tcp."
        const val SERVICE_PREFIX = "StudyLockParent-"
    }
}
