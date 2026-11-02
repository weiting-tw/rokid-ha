package tw.rokidha.presentation.dashboard

import tw.rokidha.domain.glasses.DisplayLine
import tw.rokidha.domain.glasses.DisplayLine.Style
import tw.rokidha.domain.glasses.DisplayScreen
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.notifications.HaNotification
import tw.rokidha.presentation.interaction.CommandStatus
import tw.rokidha.presentation.interaction.InteractionView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the glasses dashboard may show. Contains no URL and no credentials by construction. */
data class DashboardModel(
    val connection: HaConnectionState,
    val favorites: List<String>,
    val entities: Map<String, HaEntity>,
    val lastSyncMs: Long?,
    val notice: HaNotification?,
    /** Non-null when glasses control is enabled. */
    val interaction: InteractionView? = null,
)

/**
 * Screen A (Home Dashboard), rendered as id-addressable text lines so that later changes can be
 * sent with `customViewUpdate` instead of re-opening the view. The set of line ids only changes
 * when the favorite list changes.
 */
class DashboardRenderer(private val timeZone: TimeZone = TimeZone.getDefault()) {

    fun render(m: DashboardModel): DisplayScreen {
        val live = m.connection is HaConnectionState.Connected
        val reconnecting = m.connection is HaConnectionState.Connecting || m.connection is HaConnectionState.Retrying
        val lines = mutableListOf(
            DisplayLine("title", "HOME", Style.TITLE),
            DisplayLine("ha", statusText(m.connection), Style.BODY),
        )
        if (m.favorites.isEmpty()) {
            lines += DisplayLine("e_none", "Choose entities in the phone app", Style.CAPTION)
        } else {
            m.favorites.forEachIndexed { i, id ->
                val e = m.entities[id]
                val text = when {
                    e == null -> "$id: —"
                    live -> EntityFormatter.line(e)
                    // Never present cached values as current: mark them while reconnecting,
                    // hide them when we are not trying to reconnect at all.
                    reconnecting && m.lastSyncMs != null -> "~ " + EntityFormatter.line(e)
                    else -> "${e.friendlyName}: —"
                }
                // ASCII cursor: glyph support on the glasses font is not verified.
                val cursor = m.interaction?.target?.entityId == id
                lines += DisplayLine("e$i", if (cursor) "> $text" else text, Style.BODY)
            }
        }
        m.interaction?.let { lines += DisplayLine("action", interactionText(it, m.entities, live), Style.BODY) }
        val high = m.notice?.priority == HaNotification.Priority.HIGH
        lines += DisplayLine("notice", m.notice?.let(::noticeText) ?: "No alerts", if (high) Style.TITLE else Style.CAPTION)
        lines += DisplayLine("updated", updatedText(live, m.lastSyncMs), Style.CAPTION)
        return DisplayScreen("dashboard", lines)
    }

    fun statusText(s: HaConnectionState): String = when (s) {
        is HaConnectionState.Connected -> "HA ONLINE"
        is HaConnectionState.Connecting, is HaConnectionState.Retrying -> "HA OFFLINE · reconnecting"
        is HaConnectionState.CredentialsRejected -> "HA LOGIN REJECTED · check phone"
        HaConnectionState.Stopped -> "HA DISCONNECTED · check phone"
        HaConnectionState.NotConfigured -> "HA NOT SET UP · use phone app"
    }

    /** Pending, success, failure and unavailable are worded differently; "sent" is never shown as success. */
    fun interactionText(v: InteractionView, entities: Map<String, HaEntity>, live: Boolean): String {
        val t = v.target ?: return v.hint ?: "No quick actions — set them up on the phone"
        fun name(id: String) = entities[id]?.friendlyName ?: id
        val st = v.status
        return when {
            !live -> "Controls paused: HA offline"
            v.armedUntilMs != null -> "Confirm ${t.label} · ${name(t.entityId)}? Press again"
            st is CommandStatus.Sending -> "Sending ${st.action.label} · ${name(st.action.entityId)}…"
            st is CommandStatus.AwaitingState -> "Waiting for HA: ${name(st.action.entityId)}…"
            st != null && v.hint == null -> when (st) {
                is CommandStatus.Confirmed -> "OK: ${name(st.action.entityId)} is ${st.state.uppercase()}"
                is CommandStatus.Accepted -> "OK: ${st.action.label} · ${name(st.action.entityId)} (HA accepted)"
                is CommandStatus.Unconfirmed -> "Not confirmed: ${name(st.action.entityId)} is ${st.state?.uppercase() ?: "?"}"
                is CommandStatus.Rejected -> "Blocked: ${st.reason}"
                is CommandStatus.Unavailable -> "Unavailable: ${st.reason}"
                is CommandStatus.Failed -> "Failed: ${st.reason}"
                else -> "Press: ${t.label} · ${name(t.entityId)}"
            }
            v.hint != null -> v.hint
            else -> "Press: ${t.label} · ${name(t.entityId)}"
        }
    }

    private fun noticeText(n: HaNotification): String {
        val prefix = if (n.priority == HaNotification.Priority.HIGH) "! " else ""
        return prefix + listOfNotNull(n.title, n.message).joinToString(": ")
    }

    private fun updatedText(live: Boolean, lastSyncMs: Long?): String = when {
        lastSyncMs == null -> "No data yet"
        live -> "Live · ${time(lastSyncMs)}"
        else -> "Not live · last data ${time(lastSyncMs)}"
    }

    private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = this@DashboardRenderer.timeZone }.format(Date(ms))
}
