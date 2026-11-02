package tw.rokidha.glasses

import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import tw.rokidha.domain.glasses.GlassesInput
import tw.rokidha.glassesproto.GlassesProtocol
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * CXR-S bridge to the phone app (official CXR-L doc "自定义指令", glasses side):
 * setStatusListener → subscribe(CH_TO_GLASSES) → sendMessage(CH_TO_PHONE, Caps).
 */
class PhoneLink(
    private val onFrame: (GlassesProtocol.UiFrame) -> Unit,
    private val onConnection: (connected: Boolean) -> Unit,
) {
    private val bridge = CXRServiceBridge()
    /** New per process, so the phone can tell a restarted glasses app from a duplicate event. */
    private val session = UUID.randomUUID().toString().replace("-", "").take(12)
    private val seq = AtomicLong()
    @Volatile private var lastRev = -1L

    private val status = object : CXRServiceBridge.StatusListener {
        override fun onConnected(name: String?, mac: String?, type: Int) {
            Log.i(TAG, "bridge connected (type=$type)")
            onConnection(true)
            sendHello()
        }
        override fun onDisconnected() {
            Log.i(TAG, "bridge disconnected")
            onConnection(false)
        }
        override fun onConnecting(name: String?, mac: String?, type: Int) = Unit
        override fun onARTCStatus(health: Float, ok: Boolean) = Unit
        override fun onRokidAccountChanged(account: String?) = Unit
        override fun onAudioNoise(noise: Float) = Unit
    }

    private val receiver = CXRServiceBridge.MsgCallback { name, args, _ ->
        if (name != GlassesProtocol.CH_TO_GLASSES || args == null) return@MsgCallback
        val frame = GlassesProtocol.decode(read(args)) as? GlassesProtocol.UiFrame ?: run {
            Log.w(TAG, "ignored malformed message")
            return@MsgCallback
        }
        if (frame.rev < lastRev) return@MsgCallback // out-of-order old frame
        lastRev = frame.rev
        onFrame(frame)
    }

    fun start() {
        bridge.setStatusListener(status)
        val rc = bridge.subscribe(GlassesProtocol.CH_TO_GLASSES, receiver)
        Log.i(TAG, "subscribe -> $rc")
        // The bridge may already be connected (app started by the phone): announce ourselves.
        sendHello()
    }

    fun sendHello() = send(GlassesProtocol.Hello(session))

    fun sendInput(kind: GlassesInput.Kind?, raw: String) =
        send(GlassesProtocol.InputEvent(session, seq.incrementAndGet(), kind, raw))

    private fun send(m: GlassesProtocol.Message): Int {
        val caps = Caps()
        GlassesProtocol.encode(m).forEach { v ->
            when (v) {
                is String -> caps.write(v)
                is Long -> caps.writeInt64(v)
            }
        }
        return runCatching { bridge.sendMessage(GlassesProtocol.CH_TO_PHONE, caps) }
            .onFailure { Log.w(TAG, "sendMessage failed: ${it.message}") }
            .getOrDefault(-1)
    }

    companion object {
        private const val TAG = "RokidHaGlasses"

        /** Caps → plain values (strings and integers only; anything else becomes null → rejected). */
        fun read(c: Caps): List<Any?> = (0 until c.size()).map { i ->
            val v = c.at(i)
            when (v.type()) {
                Caps.Value.TYPE_STRING -> v.string
                Caps.Value.TYPE_INT64, Caps.Value.TYPE_UINT64 -> v.long
                Caps.Value.TYPE_INT32, Caps.Value.TYPE_UINT32 -> v.int.toLong()
                else -> null
            }
        }
    }
}
