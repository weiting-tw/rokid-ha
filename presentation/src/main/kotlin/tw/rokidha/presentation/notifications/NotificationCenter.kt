package tw.rokidha.presentation.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tw.rokidha.homeassistant.notifications.HaNotification
import tw.rokidha.homeassistant.notifications.HaNotification.Priority

/**
 * Notification queue with three priorities.
 * - HIGH: shown on the glasses immediately, kept 15 min unless dismissed.
 * - MEDIUM: shown when no HIGH is active, kept 5 min.
 * - LOW: phone only; never occupies the glasses display.
 * Which events become notifications is decided by Home Assistant automations, not here.
 */
class NotificationCenter(private val capacity: Int = 20) {
    private val _all = MutableStateFlow<List<HaNotification>>(emptyList())
    /** Newest first; for the phone UI. */
    val all: StateFlow<List<HaNotification>> = _all.asStateFlow()

    fun add(n: HaNotification) = _all.update { list -> (listOf(n) + list.filterNot { it.id == n.id }).take(capacity) }

    fun dismiss(id: String) = _all.update { list -> list.filterNot { it.id == id } }

    fun dismissAll() = _all.update { emptyList() }

    /** The notification to show on the glasses at [nowMs], or null. */
    fun forGlasses(nowMs: Long, list: List<HaNotification> = _all.value): HaNotification? =
        list.filter { it.priority != Priority.LOW && nowMs - it.receivedAtMs < ttl(it.priority) }
            .sortedWith(compareBy<HaNotification> { it.priority.ordinal }.thenByDescending { it.receivedAtMs })
            .firstOrNull()

    companion object {
        fun ttl(p: Priority): Long = when (p) {
            Priority.HIGH -> 15 * 60_000L
            Priority.MEDIUM -> 5 * 60_000L
            Priority.LOW -> 0L
        }
    }
}
