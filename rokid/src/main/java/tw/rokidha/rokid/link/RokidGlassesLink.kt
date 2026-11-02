package tw.rokidha.rokid.link

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.Caps
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tw.rokidha.domain.glasses.DisplayScreen
import tw.rokidha.domain.glasses.GlassesDeviceInfo
import tw.rokidha.domain.glasses.GlassesDisplayPort
import tw.rokidha.domain.glasses.GlassesInput
import tw.rokidha.domain.glasses.GlassesInputSource
import tw.rokidha.domain.glasses.GlassesLinkState
import tw.rokidha.domain.glasses.GlassesLinkState.Stage
import tw.rokidha.rokid.BuildConfig
import tw.rokidha.rokid.customview.CustomViewNode
import tw.rokidha.rokid.customview.CustomViewUpdate
import tw.rokidha.rokid.customview.CustomViewValidator
import tw.rokidha.rokid.customview.DisplayScreenRenderer
import tw.rokidha.glassesproto.GlassesProtocol
import java.io.File

/**
 * Rokid CXR-L adapter (CUSTOMVIEW session only, P0 scope).
 *
 * Call order follows the official CXR-L v1.0.4 docs ("连接与会话", "眼镜端自定义View"):
 *   authorize → CXRLink(ctx) → configCXRSession(CUSTOMVIEW) → setCXRLinkCbk →
 *   setCXRCustomViewCbk → connect(token) → wait onCXRLConnected(true) AND
 *   onGlassBtConnected(true) → customViewOpen → onCustomViewOpened → customViewUpdate* →
 *   customViewClose.
 *
 * Only APIs present in BOTH client-l 1.0.3 and 1.0.4 are used, so the project compiles
 * against either (docs/SDK_COMPATIBILITY.md §2).
 *
 * Process-wide singleton: the docs require one CXRLink per process, reused by all pages.
 *
 * Threading: SDK callbacks arrive on binder threads; all state goes through [MutableStateFlow]
 * atomic updates. Public methods are expected to be called from the main thread.
 *
 * Security: the Rokid auth token is held in memory only and is never logged or displayed.
 */
class RokidGlassesLink(
    context: Context,
    private val logger: (String) -> Unit = {},
    private val renderer: DisplayScreenRenderer = DisplayScreenRenderer(),
) : GlassesDisplayPort, GlassesInputSource {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(GlassesLinkState())
    override val state: StateFlow<GlassesLinkState> = _state.asStateFlow()

    private val _deviceInfo = MutableStateFlow<GlassesDeviceInfo?>(null)
    val deviceInfo: StateFlow<GlassesDeviceInfo?> = _deviceInfo.asStateFlow()

    /** Number of `onCustomViewUpdated` callbacks since the view was last opened. */
    private val _updateAcks = MutableStateFlow(0)
    val updateAcks: StateFlow<Int> = _updateAcks.asStateFlow()

    private val _inputs = MutableSharedFlow<GlassesInput>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Glasses input reaching the phone through CXR-L. The only candidate in CXR-L 1.0.3/1.0.4 is
     * the AI key / wake callback ([ICXRLinkCbk.onGlassAiAssistStart], and
     * [ICXRLinkCbk.onGlassAiInterrupt] when interrupt mode is on): one event per press, no
     * short/long distinction (`onAiKeyUp` is not forwarded by the SDK). DOCUMENTED as a callback,
     * EXPERIMENTAL as a control input; not verified on the target glasses.
     * CustomView itself has no input callbacks.
     */
    override val inputs: SharedFlow<GlassesInput> = _inputs.asSharedFlow()

    /** Forward AI key / wake events as [GlassesInput.Kind.PRIMARY]. Off by default. */
    @Volatile var aiKeyInputEnabled: Boolean = false
    private val inputSeq = java.util.concurrent.atomic.AtomicLong()

    /**
     * How the glasses are driven. Applies at the next [connect] (CXR-L fixes the session type per link).
     * - CUSTOM_VIEW: phone renders a CustomView JSON layout; no glasses input (default, P0 path).
     * - CUSTOM_APP: the Rokid HA glasses app (CXR-S) renders frames sent over custom commands and
     *   reports button/touchpad input. DOCUMENTED by Rokid; not yet verified on the target glasses.
     */
    enum class SessionMode { CUSTOM_VIEW, CUSTOM_APP }

    @Volatile var sessionMode: SessionMode = SessionMode.CUSTOM_VIEW
    @Volatile private var activeMode: SessionMode = SessionMode.CUSTOM_VIEW
    val activeSessionMode: SessionMode get() = activeMode

    private val _glassesAppInstalled = MutableStateFlow<Boolean?>(null)
    /** CUSTOM_APP: result of `appIsInstalled` (null = unknown). */
    val glassesAppInstalled: StateFlow<Boolean?> = _glassesAppInstalled.asStateFlow()

    private val _lastGlassesEvent = MutableStateFlow<String?>(null)
    /** CUSTOM_APP: last raw input name reported by the glasses app (diagnostics for verification). */
    val lastGlassesEvent: StateFlow<String?> = _lastGlassesEvent.asStateFlow()

    // CUSTOM_APP display state (guarded by `this`).
    private var appRunning = false
    private var userExitedApp = false
    private var appStartRequestedAt = 0L
    private var screenShown = false
    private var currentScreen: DisplayScreen? = null
    private var frameRev = 0L
    private val lastGlassesSeq = HashMap<String, Long>()

    @Volatile private var link: CXRLink? = null
    @Volatile private var token: String? = null
    @Volatile private var openTree: CustomViewNode? = null

    val sdkVersion: String get() = BuildConfig.CXRL_VERSION
    val hasToken: Boolean get() = !token.isNullOrEmpty()

    // ---------------------------------------------------------------- required companion app

    /** Which Rokid companion app (if any) satisfies the SDK's install + version check. */
    fun requiredApp(activity: Activity): RequiredRokidApp {
        val cn = runCatching { AuthorizationHelper.isRequiredRokidAppInstalled(activity) }.getOrDefault(false)
        val global = runCatching { AuthorizationHelper.isRequiredHiRokidInstalled(activity) }.getOrDefault(false)
        return RequiredRokidApp(
            rokidAiAppOk = cn,
            hiRokidOk = global,
            rokidAiAppVersion = packageVersion(RokidPackages.ROKID_AI_APP),
            hiRokidVersion = packageVersion(RokidPackages.HI_ROKID),
        )
    }

    private fun packageVersion(pkg: String): String? = try {
        val info = appContext.packageManager.getPackageInfo(pkg, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    // ---------------------------------------------------------------- authorization

    /**
     * Starts the authorization UI hosted by the Rokid companion app.
     *
     * No glasses permissions (camera/microphone) are requested: P0 only needs display.
     * If the user already authorized, the SDK may return the result synchronously; that case
     * is handled here directly. Otherwise the result arrives in Activity.onActivityResult and
     * must be passed to [handleAuthorizationResult].
     */
    fun requestAuthorization(activity: Activity, requestCode: Int) {
        _state.update { it.copy(stage = Stage.AUTHORIZING, lastError = null) }
        log("auth: requestAuthorization(requestCode=$requestCode, permissions=none)")
        val immediate = try {
            AuthorizationHelper.requestAuthorization(activity, requestCode = requestCode) // permissions = null (default)
        } catch (t: Throwable) {
            fail("auth: requestAuthorization threw ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (immediate != null) {
            log("auth: synchronous result returned (already authorized)")
            handleAuthorizationResult(immediate.first ?: Activity.RESULT_CANCELED, immediate.second)
        } else {
            log("auth: waiting for onActivityResult")
        }
    }

    /** Pass `resultCode` (NOT requestCode) and data from onActivityResult. */
    fun handleAuthorizationResult(resultCode: Int, data: Intent?): AuthOutcome {
        val outcome = when (val r = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> {
                val t = r.token
                if (t.isNullOrEmpty()) AuthOutcome.FAILED else {
                    token = t
                    AuthOutcome.SUCCESS
                }
            }
            is AuthResult.AuthCancel -> AuthOutcome.CANCELLED
            else -> AuthOutcome.FAILED
        }
        if (outcome != AuthOutcome.SUCCESS) token = null
        log("auth: result=$outcome resultCode=$resultCode tokenPresent=${hasToken}")
        _state.update {
            it.copy(
                stage = if (outcome == AuthOutcome.SUCCESS) Stage.AUTHORIZED else Stage.IDLE,
                lastError = if (outcome == AuthOutcome.SUCCESS) null else "authorization $outcome",
            )
        }
        return outcome
    }

    // ---------------------------------------------------------------- connection

    /** Creates the CXRLink, configures a CUSTOMVIEW session and connects. */
    fun connect(): Boolean {
        val t = token
        if (t.isNullOrEmpty()) return failFalse("connect: no token, authorize first")
        if (link != null) return failFalse("connect: already connected/connecting; disconnect first")

        val l = try {
            CXRLink(appContext)
        } catch (e: UnsatisfiedLinkError) {
            // client-l loads libcxr-sock-proto-jni, shipped only for arm64-v8a / armeabi-v7a.
            return failFalse("connect: native library unavailable on ABIs ${Build.SUPPORTED_ABIS.joinToString()}: ${e.message}")
        } catch (t2: Throwable) {
            return failFalse("connect: CXRLink() threw ${t2.javaClass.simpleName}: ${t2.message}")
        }

        val mode = sessionMode
        val session = if (mode == SessionMode.CUSTOM_APP) {
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, GlassesProtocol.GLASSES_PACKAGE)
        } else {
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW)
        }
        val configured = l.configCXRSession(session)
        log("session: configCXRSession(${session.sessionType}) -> $configured")
        if (!configured) {
            l.disconnect()
            return failFalse("session: configCXRSession(${session.sessionType}) returned false")
        }
        activeMode = mode
        resetCustomAppState()
        // Both callbacks MUST be set before connect(): the SDK dereferences them without null
        // checks when the service binds (observed in client-l bytecode, docs/SDK_RESEARCH.md §8).
        l.setCXRLinkCbk(linkCallback)
        l.setCXRCustomViewCbk(customViewCallback)
        l.setCXRCustomCmdCbk(customCmdCallback)

        link = l
        _state.update { it.copy(stage = Stage.CONNECTING, lastError = null) }
        val sent = l.connect(t)
        log("link: connect(token) -> $sent (true only means the bind request was sent)")
        if (!sent) {
            link = null
            l.disconnect()
            return failFalse("link: connect returned false (Rokid app not running / not logged in?)")
        }
        return true
    }

    /** Unbinds from the companion service. Closes the view first if it is open. */
    fun disconnect() {
        val l = link ?: run {
            log("link: disconnect ignored, no link")
            return
        }
        if (_state.value.displayOpen && activeMode == SessionMode.CUSTOM_VIEW) {
            log("view: customViewClose() before disconnect -> ${runCatching { l.customViewClose() }.getOrDefault(false)}")
        }
        // CUSTOM_APP: the glasses app is left running; it shows "PHONE DISCONNECTED" by itself.
        resetCustomAppState()
        runCatching { l.disconnect() }.onFailure { log("link: disconnect threw ${it.message}") }
        link = null
        openTree = null
        // unbindService() does not trigger onServiceDisconnected, so reset state ourselves.
        _state.update {
            GlassesLinkState(stage = if (hasToken) Stage.AUTHORIZED else Stage.IDLE)
        }
        log("link: disconnected (token kept in memory: $hasToken)")
    }

    /** Asks the glasses for device info; result arrives via [deviceInfo]. */
    fun refreshDeviceInfo() {
        val l = link ?: return
        runCatching { l.getGlassDeviceInfo() }.onFailure { log("info: getGlassDeviceInfo threw ${it.message}") }
        log("info: getGlassDeviceInfo() requested")
    }

    fun forgetToken() {
        token = null
        log("auth: token cleared from memory")
    }

    // ---------------------------------------------------------------- display (GlassesDisplayPort)

    override fun show(screen: DisplayScreen): Boolean =
        if (activeMode == SessionMode.CUSTOM_APP) appShow(screen) else openTree(renderer.render(screen))

    /** Opens an arbitrary validated CustomView tree. */
    fun openTree(tree: CustomViewNode): Boolean {
        val l = link ?: return failFalse("view: open rejected, not connected")
        val s = _state.value
        if (!s.linkReady) return failFalse("view: open rejected, link not ready (cxrl=${s.transportConnected}, bt=${s.glassesConnected})")
        if (s.displayOpen) return failFalse("view: open rejected, already open; close first")
        val errors = CustomViewValidator.validateTree(tree, knownIcons = emptySet())
        if (errors.isNotEmpty()) return failFalse("view: invalid tree: ${errors.joinToString("; ")}")
        val json = tree.toJson()
        val ok = runCatching { l.customViewOpen(json) }.getOrElse {
            return failFalse("view: customViewOpen threw ${it.message}")
        }
        log("view: customViewOpen(${json.length} chars) -> $ok")
        if (ok) {
            openTree = tree
            _updateAcks.value = 0
        }
        return ok
    }

    override fun updateText(changes: Map<String, String>): Boolean {
        if (activeMode == SessionMode.CUSTOM_APP) return appUpdate(changes)
        val l = link ?: return failFalse("view: update rejected, not connected")
        val tree = openTree ?: return failFalse("view: update rejected, no open tree")
        if (!_state.value.displayOpen) return failFalse("view: update rejected, view not open")
        val updates = renderer.renderTextUpdates(changes)
        val errors = CustomViewValidator.validateUpdates(updates, tree)
        if (errors.isNotEmpty()) return failFalse("view: invalid update: ${errors.joinToString("; ")}")
        val json = CustomViewUpdate.toJson(updates)
        val ok = runCatching { l.customViewUpdate(json) }.getOrElse {
            return failFalse("view: customViewUpdate threw ${it.message}")
        }
        log("view: customViewUpdate(${updates.size} item(s)) -> $ok")
        return ok
    }

    override fun hide(): Boolean {
        if (activeMode == SessionMode.CUSTOM_APP) return appHide()
        val l = link ?: return failFalse("view: close rejected, not connected")
        val ok = runCatching { l.customViewClose() }.getOrElse {
            return failFalse("view: customViewClose threw ${it.message}")
        }
        log("view: customViewClose() -> $ok")
        return ok
    }

    /** Direct SDK query, for verification only (the callback-driven state is authoritative). */
    fun sdkReportsViewOpen(): Boolean? = link?.let { runCatching { it.customViewIsOpen() }.getOrNull() }

    // ---------------------------------------------------------------- SDK callbacks

    private val linkCallback = object : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            // Seed BT state: if the glasses were already connected before we bound, the docs do
            // not guarantee an onGlassBtConnected(true) event (EXPERIMENTAL, see compat doc).
            val bt = if (connected) runCatching { link?.isGlassBtConnected() }.getOrNull() ?: false else false
            log("cb: onCXRLConnected($connected), isGlassBtConnected()=$bt")
            _state.update {
                recompute(
                    it.copy(
                        transportConnected = connected,
                        glassesConnected = if (connected) it.glassesConnected || bt else false,
                        displayOpen = if (connected) it.displayOpen else false,
                    ),
                )
            }
            if (connected && bt) refreshDeviceInfo()
            onLinkStateChanged()
        }

        override fun onGlassBtConnected(connected: Boolean) {
            log("cb: onGlassBtConnected($connected)")
            _state.update {
                recompute(it.copy(glassesConnected = connected, displayOpen = if (connected) it.displayOpen else false))
            }
            if (connected) refreshDeviceInfo()
            onLinkStateChanged()
        }

        override fun onGlassDeviceInfo(info: GlassInfo) {
            // GlassInfo.toString() includes the serial number; keep it out of logs/reports.
            val raw = info.toString().replace(SN_FIELD, "sn='<redacted>'")
            log("cb: onGlassDeviceInfo($raw)")
            _deviceInfo.value = GlassesDeviceInfo(
                deviceName = info.deviceName,
                systemVersion = info.systemVersion,
                batteryLevel = info.batteryLevel,
                charging = info.ischarging,
                wearingStatus = info.wearingStatus,
                raw = raw,
            )
        }

        override fun onGlassWearingStatus(wearing: Boolean) = log("cb: onGlassWearingStatus($wearing)")
        override fun onGlassAiAssistStart() {
            log("cb: onGlassAiAssistStart() inputEnabled=$aiKeyInputEnabled")
            emitAiKey()
        }

        override fun onGlassAiAssistStop() = log("cb: onGlassAiAssistStop()")

        override fun onGlassAiInterrupt(interruptWake: Boolean) {
            log("cb: onGlassAiInterrupt($interruptWake) inputEnabled=$aiKeyInputEnabled")
            if (interruptWake) emitAiKey()
        }
    }

    private val customViewCallback = object : ICustomViewCbk {
        override fun onCustomViewOpened() {
            log("cb: onCustomViewOpened()")
            _state.update { recompute(it.copy(displayOpen = true, lastError = null)) }
        }

        override fun onCustomViewUpdated() {
            val n = _updateAcks.value + 1
            _updateAcks.value = n
            log("cb: onCustomViewUpdated() #$n")
        }

        override fun onCustomViewClosed() {
            log("cb: onCustomViewClosed()")
            openTree = null
            _state.update { recompute(it.copy(displayOpen = false)) }
        }

        override fun onCustomViewIconsSent() = log("cb: onCustomViewIconsSent()")

        override fun onCustomViewError(code: Int, message: String?) {
            log("cb: onCustomViewError($code, $message)")
            openTree = null
            _state.update { recompute(it.copy(displayOpen = false, lastError = "customView error $code: $message")) }
        }
    }

    // ---------------------------------------------------------------- CUSTOM_APP (CXR-S glasses app)

    /**
     * Installs the bundled glasses APK through the Rokid app (no ADB). The SDK opens [apk] in this
     * process and passes a file descriptor, so an app-private path works.
     */
    fun installGlassesApp(apk: File): Boolean {
        val l = link ?: return failFalse("app: install rejected, not connected")
        if (activeMode != SessionMode.CUSTOM_APP) return failFalse("app: install needs the CustomApp session mode")
        if (!_state.value.linkReady) return failFalse("app: install rejected, link not ready")
        if (!apk.isFile || !apk.canRead()) return failFalse("app: APK not readable: ${apk.name}")
        log("app: appUploadAndInstall(${apk.name}, ${apk.length()} bytes)")
        runCatching { l.appUploadAndInstall(apk.absolutePath, appCallback) }.onFailure { return failFalse("app: install threw ${it.message}") }
        return true
    }

    /** Starts (or re-opens after the user left it) the glasses app. */
    @Synchronized
    fun openGlassesApp(): Boolean {
        userExitedApp = false
        return startGlassesApp(force = true)
    }

    @Synchronized
    private fun startGlassesApp(force: Boolean): Boolean {
        val l = link ?: return false
        if (activeMode != SessionMode.CUSTOM_APP || !_state.value.linkReady) return false
        if (_glassesAppInstalled.value == false) return failFalse("app: glasses app not installed — install it from the Glasses tab")
        val now = System.currentTimeMillis()
        if (!force && now - appStartRequestedAt < APP_START_TIMEOUT_MS) return true // already starting
        appStartRequestedAt = now
        log("app: appStart(${GlassesProtocol.GLASSES_ENTRY})")
        runCatching { l.appStart(GlassesProtocol.GLASSES_ENTRY, appCallback) }.onFailure { return failFalse("app: appStart threw ${it.message}") }
        return true
    }

    private fun onLinkStateChanged() {
        if (activeMode != SessionMode.CUSTOM_APP) return
        val ready = _state.value.linkReady
        synchronized(this) {
            if (!ready) {
                appRunning = false
                screenShown = false
                return
            }
        }
        // Link (re)established: check the glasses app; onQueryAppResult starts it when installed.
        runCatching { link?.appIsInstalled(appCallback) }.onFailure { log("app: appIsInstalled threw ${it.message}") }
    }

    @Synchronized
    private fun appShow(screen: DisplayScreen): Boolean {
        if (!_state.value.linkReady) return false
        currentScreen = screen
        if (appRunning) return sendFrame()
        if (userExitedApp) return false // user left the app on the glasses; reopen from the phone
        return startGlassesApp(force = false) // frame is sent once the app reports open/hello
    }

    @Synchronized
    private fun appUpdate(changes: Map<String, String>): Boolean {
        val s = currentScreen ?: return false
        if (!screenShown) return false
        currentScreen = s.copy(lines = s.lines.map { l -> changes[l.id]?.let { l.copy(text = it) } ?: l })
        return sendFrame()
    }

    @Synchronized
    private fun appHide(): Boolean {
        // The glasses app keeps running; the next show() replaces its content.
        screenShown = false
        _state.update { recompute(it.copy(displayOpen = false)) }
        return true
    }

    /** Sends the current screen as one frame. Caller holds the lock. */
    private fun sendFrame(): Boolean {
        val l = link ?: return false
        val screen = currentScreen ?: return false
        val frame = GlassesProtocol.UiFrame(
            rev = ++frameRev,
            lines = screen.lines.map { GlassesProtocol.Line(it.id, it.text, it.style.name) },
            status = "",
        )
        val caps = Caps()
        GlassesProtocol.encode(frame).forEach { v -> if (v is String) caps.write(v) else if (v is Long) caps.writeInt64(v) }
        // Deprecated in client-l with "Attention to Caps size"; it is the call the official docs use.
        // Frames are capped by GlassesProtocol (16 lines x 160 chars).
        @Suppress("DEPRECATION")
        val rc = runCatching { l.sendCustomCmd(GlassesProtocol.CH_TO_GLASSES, caps) }.getOrElse {
            return failFalse("app: sendCustomCmd threw ${it.message}")
        }
        if (rc == null || rc < 0) return failFalse("app: sendCustomCmd -> $rc")
        if (!screenShown) {
            screenShown = true
            _state.update { recompute(it.copy(displayOpen = true, lastError = null)) }
        }
        return true
    }

    @Synchronized
    private fun resetCustomAppState() {
        appRunning = false
        screenShown = false
        userExitedApp = false
        appStartRequestedAt = 0L
        currentScreen = null
        lastGlassesSeq.clear()
        _glassesAppInstalled.value = null
    }

    private val appCallback = object : IGlassAppCbk {
        override fun onQueryAppResult(installed: Boolean) {
            log("cb: onQueryAppResult(installed=$installed)")
            _glassesAppInstalled.value = installed
            if (installed) synchronized(this@RokidGlassesLink) { if (!appRunning && !userExitedApp) startGlassesApp(force = false) }
        }

        override fun onInstallAppResult(success: Boolean) {
            log("cb: onInstallAppResult($success)")
            if (success) {
                _glassesAppInstalled.value = true
                openGlassesApp()
            } else {
                fail("app: installation on the glasses failed")
            }
        }

        override fun onUnInstallAppResult(success: Boolean) = log("cb: onUnInstallAppResult($success)")

        override fun onOpenAppResult(success: Boolean) {
            log("cb: onOpenAppResult($success)")
            synchronized(this@RokidGlassesLink) {
                appStartRequestedAt = 0L
                appRunning = success
                if (success && currentScreen != null) sendFrame()
            }
            if (!success) fail("app: the glasses app could not be started")
        }

        override fun onStopAppResult(success: Boolean) {
            log("cb: onStopAppResult($success)")
            if (success) markAppGone(userLeft = false)
        }

        override fun onGlassAppResume(resumed: Boolean) {
            log("cb: onGlassAppResume($resumed)")
            if (resumed) {
                synchronized(this@RokidGlassesLink) {
                    appRunning = true
                    userExitedApp = false
                    if (currentScreen != null) sendFrame()
                }
            } else {
                markAppGone(userLeft = true) // e.g. double-click exit: do not relaunch automatically
            }
        }
    }

    private fun markAppGone(userLeft: Boolean) {
        synchronized(this) {
            appRunning = false
            screenShown = false
            if (userLeft) userExitedApp = true
        }
        _state.update { recompute(it.copy(displayOpen = false)) }
    }

    private val customCmdCallback = ICustomCmdCbk { name, payload ->
        if (name != GlassesProtocol.CH_TO_PHONE || payload == null) return@ICustomCmdCbk
        val fields = runCatching { readCaps(Caps.fromBytes(payload)) }.getOrNull() ?: return@ICustomCmdCbk
        when (val m = GlassesProtocol.decode(fields)) {
            is GlassesProtocol.Hello -> {
                log("glasses: hello (session ${m.session})")
                synchronized(this) {
                    appRunning = true
                    userExitedApp = false
                    if (currentScreen != null) sendFrame()
                }
            }
            is GlassesProtocol.InputEvent -> onGlassesInput(m)
            else -> log("glasses: ignored malformed message on $name")
        }
    }

    private fun onGlassesInput(e: GlassesProtocol.InputEvent) {
        synchronized(this) {
            val last = lastGlassesSeq[e.session]
            if (last != null && e.seq <= last) {
                log("glasses: duplicate input #${e.seq} dropped")
                return
            }
            lastGlassesSeq[e.session] = e.seq
        }
        _lastGlassesEvent.value = "${e.raw} → ${e.kind ?: "(not mapped)"}"
        log("glasses: input ${e.raw} → ${e.kind ?: "not mapped"} (#${e.seq})")
        val kind = e.kind ?: return
        _inputs.tryEmit(GlassesInput(kind, GlassesInput.Source.CXRS_CUSTOM_APP, System.currentTimeMillis(), inputSeq.incrementAndGet()))
    }

    private fun readCaps(c: Caps): List<Any?> = (0 until c.size()).map { i ->
        val v = c.at(i)
        when (v.type()) {
            Caps.Value.TYPE_STRING -> v.string
            Caps.Value.TYPE_INT64, Caps.Value.TYPE_UINT64 -> v.long
            Caps.Value.TYPE_INT32, Caps.Value.TYPE_UINT32 -> v.int.toLong()
            else -> null
        }
    }

    private fun emitAiKey() {
        if (!aiKeyInputEnabled) return
        // One press may arrive through both callbacks; the interaction layer debounces.
        _inputs.tryEmit(GlassesInput(GlassesInput.Kind.PRIMARY, GlassesInput.Source.CXRL_AI_KEY, System.currentTimeMillis(), inputSeq.incrementAndGet()))
    }

    /**
     * Asks the Rokid app to stop handling AI-key wake-ups itself (`setInterruptAiWake`). Present in
     * client-l 1.0.3/1.0.4 binaries but NOT described in the official docs: EXPERIMENTAL.
     * Effect on the target firmware is unknown; requires a connected link.
     */
    fun setAssistantInterrupt(enabled: Boolean): Boolean {
        val l = link ?: return failFalse("assistant interrupt: not connected")
        val ok = runCatching { l.setInterruptAiWake(enabled) }.getOrElse { return failFalse("setInterruptAiWake threw ${it.message}") }
        log("ai: setInterruptAiWake($enabled) -> $ok (EXPERIMENTAL)")
        return ok
    }

    private fun recompute(s: GlassesLinkState): GlassesLinkState {
        val stage = when {
            link == null -> if (hasToken) Stage.AUTHORIZED else Stage.IDLE
            s.displayOpen -> Stage.DISPLAY_OPEN
            s.linkReady -> Stage.LINK_READY
            s.transportConnected -> Stage.CONNECTING
            s.stage == Stage.CONNECTING -> Stage.CONNECTING
            else -> Stage.DISCONNECTED
        }
        return s.copy(stage = stage)
    }

    // ---------------------------------------------------------------- helpers

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logger(msg)
    }

    private fun fail(msg: String) {
        log("ERROR $msg")
        _state.update { it.copy(lastError = msg, stage = if (it.stage == Stage.AUTHORIZING) Stage.IDLE else it.stage) }
    }

    private fun failFalse(msg: String): Boolean {
        fail(msg)
        return false
    }

    enum class AuthOutcome { SUCCESS, FAILED, CANCELLED }

    companion object {
        private const val TAG = "RokidGlassesLink"
        private const val APP_START_TIMEOUT_MS = 15_000L
        private val SN_FIELD = Regex("sn='[^']*'")
    }
}

/** Package names the CXR-L SDK binds to (from client-l manifest <queries>). */
object RokidPackages {
    const val ROKID_AI_APP = "com.rokid.sprite.aiapp"
    const val HI_ROKID = "com.rokid.sprite.global.aiapp"
}

data class RequiredRokidApp(
    /** Mainland "Rokid AI" app installed AND version >= SDK minimum. */
    val rokidAiAppOk: Boolean,
    /** Overseas "Hi Rokid" app installed AND version >= SDK minimum. */
    val hiRokidOk: Boolean,
    val rokidAiAppVersion: String?,
    val hiRokidVersion: String?,
) {
    val anyOk: Boolean get() = rokidAiAppOk || hiRokidOk
}
