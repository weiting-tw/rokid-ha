package tw.rokidha.app.p0

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thread-safe, bounded, in-memory event log shown on screen and copied into test reports. */
class EventLog(private val capacity: Int = 500) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    fun add(message: String) {
        val line = "${fmt.get()!!.format(Date())} [${Thread.currentThread().name}] $message"
        _lines.update { (it + line).takeLast(capacity) }
    }

    fun clear() = _lines.update { emptyList() }
}
