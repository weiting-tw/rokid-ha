package tw.rokidha.app.p0

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.rokidha.app.RokidHaApplication
import tw.rokidha.domain.glasses.DisplayLine
import tw.rokidha.domain.glasses.DisplayScreen
import tw.rokidha.domain.glasses.GlassesLinkState
import tw.rokidha.rokid.link.RequiredRokidApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the P0 compatibility checks (docs/SDK_COMPATIBILITY.md §3).
 *
 * "Observed" = the app saw the SDK callback. It is NOT the same as VERIFIED: an item becomes
 * VERIFIED only when a human also confirms what is visible on the target glasses and records
 * it in docs/SDK_COMPATIBILITY.md.
 */
class P0ViewModel(app: Application) : AndroidViewModel(app) {

    private val rokidApp = app as RokidHaApplication
    val glasses = rokidApp.glasses
    val log = rokidApp.eventLog

    private val _checks = MutableStateFlow(P0Check.entries.associateWith { CheckStatus() })
    val checks: StateFlow<Map<P0Check, CheckStatus>> = _checks.asStateFlow()

    private val _requiredApp = MutableStateFlow<RequiredRokidApp?>(null)
    val requiredApp: StateFlow<RequiredRokidApp?> = _requiredApp.asStateFlow()

    private var openCount = 0
    private var closedAfterOpen = false
    private var updateSeq = 0
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        observe(P0Check.SDK_RESOLVED, "client-l ${glasses.sdkVersion} linked into this APK")
        observe(P0Check.APP_STARTED, "Activity created on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        viewModelScope.launch {
            var previous = GlassesLinkState()
            glasses.state.collect { s ->
                if (s.stage == GlassesLinkState.Stage.AUTHORIZED || glasses.hasToken) observe(P0Check.AUTHORIZED, "token received (not shown)")
                if (s.transportConnected) observe(P0Check.AI_APP_CONNECTED, "onCXRLConnected(true)")
                if (s.glassesConnected) observe(P0Check.GLASSES_STATE, "glasses BT connected")
                if (s.displayOpen && !previous.displayOpen) {
                    openCount++
                    observe(P0Check.VIEW_OPENED, "onCustomViewOpened() #$openCount")
                    if (closedAfterOpen) observe(P0Check.CLOSE_REOPEN, "re-opened after close (open #$openCount)")
                }
                if (!s.displayOpen && previous.displayOpen) closedAfterOpen = true
                previous = s
            }
        }
        viewModelScope.launch {
            glasses.updateAcks.collect { n -> if (n > 0) observe(P0Check.VIEW_UPDATED, "onCustomViewUpdated() x$n") }
        }
        viewModelScope.launch {
            glasses.deviceInfo.collect { info ->
                if (info != null) observe(P0Check.GLASSES_STATE, "device info: ${info.deviceName}, fw ${info.systemVersion}")
            }
        }
    }

    // ------------------------------------------------------------------ actions

    fun checkRequiredApp(activity: Activity) {
        val r = glasses.requiredApp(activity)
        _requiredApp.value = r
        log.add("env: Rokid AI App ok=${r.rokidAiAppOk} (${r.rokidAiAppVersion ?: "not installed"}), " +
            "Hi Rokid ok=${r.hiRokidOk} (${r.hiRokidVersion ?: "not installed"})")
    }

    fun authorize(activity: Activity) = glasses.requestAuthorization(activity, AUTH_REQUEST_CODE)

    fun onAuthResult(resultCode: Int, data: Intent?) {
        glasses.handleAuthorizationResult(resultCode, data)
    }

    fun connect() {
        if (glasses.connect()) observe(P0Check.SESSION_CREATED, "configCXRSession(CUSTOMVIEW)=true, connect() sent")
    }

    fun openTestView() {
        updateSeq = 0
        glasses.show(
            DisplayScreen(
                id = "p0_root",
                lines = listOf(
                    DisplayLine("p0_title", "ROKID HA · P0", DisplayLine.Style.TITLE),
                    DisplayLine("p0_status", "CustomView OK"),
                    DisplayLine("p0_counter", "update #0"),
                    DisplayLine("p0_time", "opened ${clock.format(Date())}", DisplayLine.Style.CAPTION),
                ),
            ),
        )
    }

    fun updateTestView() {
        updateSeq++
        glasses.updateText(
            mapOf(
                "p0_counter" to "update #$updateSeq",
                "p0_time" to "updated ${clock.format(Date())}",
            ),
        )
    }

    fun closeView() = glasses.hide()

    fun disconnect() = glasses.disconnect()

    fun refreshInfo() = glasses.refreshDeviceInfo()

    fun queryViewOpen() = log.add("sdk: customViewIsOpen() -> ${glasses.sdkReportsViewOpen()}")

    /** Human confirmation of what is actually visible on the glasses. */
    fun confirm(check: P0Check, seen: Boolean) {
        _checks.update { m -> m + (check to m.getValue(check).copy(humanConfirmed = seen)) }
        log.add("human: ${check.name} confirmed=$seen")
    }

    fun report(): String = buildString {
        appendLine("# P0 device run report")
        appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("client-l: ${glasses.sdkVersion}")
        appendLine("phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        requiredApp.value?.let { appendLine("Rokid AI App: ${it.rokidAiAppVersion} ok=${it.rokidAiAppOk}; Hi Rokid: ${it.hiRokidVersion} ok=${it.hiRokidOk}") }
        appendLine("glasses: ${glasses.deviceInfo.value?.raw ?: "no device info received"}")
        appendLine("state: ${glasses.state.value}")
        appendLine()
        checks.value.forEach { (c, s) ->
            appendLine("- ${c.number}. ${c.title}: observed=${s.observed} humanConfirmed=${s.humanConfirmed ?: "-"} evidence=${s.evidence ?: "-"}")
        }
        appendLine()
        appendLine("## log")
        log.lines.value.forEach { appendLine(it) }
    }

    private fun observe(check: P0Check, evidence: String) {
        _checks.update { m ->
            val cur = m.getValue(check)
            if (cur.observed && cur.evidence == evidence) m else m + (check to cur.copy(observed = true, evidence = evidence))
        }
    }

    companion object {
        const val AUTH_REQUEST_CODE = 0x5244 // "RD"
    }
}

data class CheckStatus(
    val observed: Boolean = false,
    val evidence: String? = null,
    /** null = not asked / not applicable, true/false = tester's answer. */
    val humanConfirmed: Boolean? = null,
)

/** The 10 P0 checks, numbered as in the project brief and docs/SDK_COMPATIBILITY.md. */
enum class P0Check(val number: Int, val title: String, val needsHumanConfirmation: Boolean) {
    SDK_RESOLVED(1, "Gradle resolves Rokid SDK", false),
    APP_STARTED(2, "App starts", false),
    AUTHORIZED(3, "Rokid authorization (token)", false),
    AI_APP_CONNECTED(4, "Connected to Rokid AI App service", false),
    GLASSES_STATE(5, "Glasses connection state detected", true),
    SESSION_CREATED(6, "CUSTOMVIEW session configured", false),
    VIEW_OPENED(7, "CustomView opened", true),
    TEXT_VISIBLE(8, "TextView content visible on glasses", true),
    VIEW_UPDATED(9, "customViewUpdate applied", true),
    CLOSE_REOPEN(10, "Close and re-open CustomView", true),
}
