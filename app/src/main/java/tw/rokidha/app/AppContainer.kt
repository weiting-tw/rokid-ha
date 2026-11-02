package tw.rokidha.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tw.rokidha.app.p0.EventLog
import tw.rokidha.data.KeystoreSecretCipher
import tw.rokidha.data.NsdHaDiscovery
import tw.rokidha.data.SharedPrefsKeyValueStore
import tw.rokidha.homeassistant.HaHttp
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.connection.HaConnectionManager
import tw.rokidha.homeassistant.credentials.CredentialStore
import tw.rokidha.homeassistant.selection.GlassesSelection
import tw.rokidha.homeassistant.selection.QuickAction
import tw.rokidha.homeassistant.selection.SelectionStore
import tw.rokidha.homeassistant.selection.ServiceCallGuard
import tw.rokidha.homeassistant.setup.HaConnectionTester
import tw.rokidha.homeassistant.ws.HaWebSocketConnector
import tw.rokidha.presentation.GlassesDashboardBridge
import tw.rokidha.presentation.notifications.NotificationCenter
import tw.rokidha.rokid.link.RokidGlassesLink

/**
 * Manual dependency wiring (one instance per process).
 *
 *   Rokid Glasses ⇄ RokidGlassesLink (CXR-L) ⇄ GlassesDashboardBridge ⇄ HaConnectionManager ⇄ Home Assistant
 *
 * Credentials live in [credentialStore] / [haManager] only. The glasses side ([bridge],
 * [glasses]) receives entity states and status text, never the URL or token.
 */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val eventLog = EventLog()

    private val kv = SharedPrefsKeyValueStore(app, "rokid_ha")
    val credentialStore = CredentialStore(KeystoreSecretCipher(), kv)
    private val selectionStore = SelectionStore(kv)

    private val http = HaHttp.newClient()
    val tester = HaConnectionTester(http)
    val discovery = NsdHaDiscovery(app)
    val haManager = HaConnectionManager(HaWebSocketConnector(http), scope)
    val notifications = NotificationCenter()

    private val _selection = MutableStateFlow(selectionStore.load())
    val selection: StateFlow<GlassesSelection> = _selection.asStateFlow()
    private val guard = ServiceCallGuard { _selection.value }

    private val _setupMessage = MutableStateFlow<String?>(null)
    /** E.g. "stored credentials could not be read". */
    val setupMessage: StateFlow<String?> = _setupMessage.asStateFlow()

    val glasses: RokidGlassesLink by lazy { RokidGlassesLink(app, logger = eventLog::add) }

    val bridge: GlassesDashboardBridge by lazy {
        GlassesDashboardBridge(
            display = glasses,
            haState = haManager.state,
            entities = haManager.entities,
            lastSyncMs = haManager.lastSyncMs,
            selection = selection,
            notifications = notifications.all,
            noticeFor = notifications::forGlasses,
        )
    }

    private val prefs = app.getSharedPreferences("rokid_ha_settings", Context.MODE_PRIVATE)
    var keepConnectedInBackground: Boolean
        get() = prefs.getBoolean("keep_connected", false)
        set(v) { prefs.edit().putBoolean("keep_connected", v).apply() }

    /** Called from Application.onCreate: restores the connection after an app restart. */
    fun startup() {
        haManager.notificationEntityId = _selection.value.notificationEntityId
        scope.launch { haManager.notifications.collect(notifications::add) }
        bridge.start(scope)
        connectStored()
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = haManager.retryNow()
            },
        )
    }

    /** (Re)connects with stored credentials, if any. */
    fun connectStored(): Boolean = when (val r = credentialStore.load()) {
        is CredentialStore.LoadResult.Ok -> { haManager.start(r.config); true }
        is CredentialStore.LoadResult.Unreadable -> { _setupMessage.value = r.reason; haManager.reset(); false }
        CredentialStore.LoadResult.None -> false
    }

    /** Persists validated credentials (encrypted) and connects. */
    fun saveAndConnect(config: HaConfig) {
        credentialStore.save(config)
        _setupMessage.value = null
        haManager.start(config)
    }

    /** "Disconnect": stop, delete URL + token + encryption key, forget cached data. */
    fun disconnectAndForget() {
        haManager.reset()
        credentialStore.clear()
        notifications.dismissAll()
    }

    fun updateSelection(s: GlassesSelection) {
        _selection.value = s
        selectionStore.save(s)
        haManager.notificationEntityId = s.notificationEntityId
    }

    /** Runs an allowlisted quick action. Confirmation for sensitive actions is done by the UI first. */
    suspend fun runQuickAction(action: QuickAction) {
        val allowed = guard.check(action.entityId, action.service)
        haManager.callService(allowed.domain, allowed.service, allowed.entityId)
    }
}
