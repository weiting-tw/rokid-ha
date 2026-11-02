package tw.rokidha.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tw.rokidha.domain.glasses.DisplayScreen
import tw.rokidha.domain.glasses.GlassesDisplayPort
import tw.rokidha.domain.glasses.GlassesLinkState
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.notifications.HaNotification
import tw.rokidha.homeassistant.selection.GlassesSelection
import tw.rokidha.presentation.dashboard.DashboardModel
import tw.rokidha.presentation.dashboard.DashboardRenderer
import tw.rokidha.presentation.interaction.InteractionView

/**
 * Glasses UI bridge: keeps the glasses dashboard in sync with Home Assistant.
 *
 * - As soon as the glasses link is ready (and [autoShow] is on) the dashboard is opened
 *   automatically; no input is needed on the glasses.
 * - Afterwards only changed lines are sent (`customViewUpdate`), at most every [minIntervalMs].
 * - If the favorite list changes, the view is closed and re-opened with the new layout.
 *
 * Inputs are entity states, connection state and notifications only. The bridge never receives
 * the server URL or the access token, so it cannot send them to the glasses.
 */
class GlassesDashboardBridge(
    private val display: GlassesDisplayPort,
    private val haState: StateFlow<HaConnectionState>,
    private val entities: StateFlow<Map<String, HaEntity>>,
    private val lastSyncMs: StateFlow<Long?>,
    private val selection: StateFlow<GlassesSelection>,
    private val notifications: StateFlow<List<HaNotification>>,
    private val noticeFor: (nowMs: Long, list: List<HaNotification>) -> HaNotification?,
    private val interaction: StateFlow<InteractionView?> = MutableStateFlow(null),
    private val renderer: DashboardRenderer = DashboardRenderer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 500,
    private val tickMs: Long = 30_000,
) {
    /** Turn automatic dashboard display on/off (phone setting). */
    val autoShow = MutableStateFlow(true)

    private val _lastScreen = MutableStateFlow<DisplayScreen?>(null)
    /** Last screen content sent to the glasses (for the phone preview and tests). */
    val lastScreen: StateFlow<DisplayScreen?> = _lastScreen.asStateFlow()

    private var shownLayout: List<String>? = null
    private var sentText: Map<String, String> = emptyMap()
    private var openRequestedAt: Long? = null

    fun start(scope: CoroutineScope): Job = scope.launch {
        val ticker: Flow<Unit> = flow { while (isActive) { emit(Unit); delay(tickMs) } }
        val ha = combine(haState, entities, lastSyncMs) { s, e, t -> Triple(s, e, t) }
        val ui = combine(selection, notifications, autoShow, ticker) { sel, n, auto, _ -> Triple(sel, n, auto) }
        combine(display.state, ha, ui, interaction) { link, h, u, i -> Snapshot(link, h.first, h.second, h.third, u.first, u.second, u.third, i) }
            .conflate()
            .collect { snap ->
                apply(snap)
                delay(minIntervalMs)
            }
    }

    private data class Snapshot(
        val link: GlassesLinkState,
        val ha: HaConnectionState,
        val entities: Map<String, HaEntity>,
        val lastSyncMs: Long?,
        val selection: GlassesSelection,
        val notifications: List<HaNotification>,
        val autoShow: Boolean,
        val interaction: InteractionView?,
    )

    private fun apply(s: Snapshot) {
        val now = clock()
        val screen = renderer.render(
            DashboardModel(s.ha, s.selection.favorites, s.entities, s.lastSyncMs, noticeFor(now, s.notifications), s.interaction),
        )
        val layout = screen.lines.map { it.id }
        val texts = screen.lines.associate { it.id to it.text }

        if (!s.link.displayOpen) {
            shownLayout = null
            sentText = emptyMap()
            val pending = openRequestedAt?.let { now - it < OPEN_TIMEOUT_MS } ?: false
            if (s.autoShow && s.link.linkReady && !pending) {
                if (display.show(screen)) {
                    openRequestedAt = now
                    shownLayout = layout
                    sentText = texts
                    _lastScreen.value = screen
                }
            }
            return
        }
        openRequestedAt = null
        // autoShow off (e.g. the P0 diagnostics screen owns the view): leave it alone.
        if (!s.autoShow) return
        if (shownLayout == null) {
            // A view we did not open (layout unknown): close it, ours is re-opened afterwards.
            display.hide()
            return
        }
        if (layout != shownLayout) {
            display.hide() // re-opened with the new layout once onCustomViewClosed arrives
            return
        }
        val changes = texts.filter { (id, t) -> sentText[id] != t }
        if (changes.isNotEmpty() && display.updateText(changes)) {
            sentText = texts
            _lastScreen.value = screen
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 5_000L
    }
}
