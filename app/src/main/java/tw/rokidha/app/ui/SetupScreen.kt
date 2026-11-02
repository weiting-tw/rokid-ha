package tw.rokidha.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.rokidha.app.RokidHaApplication
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.credentials.CredentialStore
import tw.rokidha.homeassistant.discovery.DiscoveredHaInstance
import tw.rokidha.homeassistant.discovery.ZeroconfParser
import tw.rokidha.homeassistant.setup.HaConnectionTester

data class SetupUi(
    val url: String = "",
    /** Only what the user is typing right now; never pre-filled from storage. */
    val token: String = "",
    val discovering: Boolean = false,
    val discovered: List<DiscoveredHaInstance> = emptyList(),
    val discoveryNote: String? = null,
    val busy: Boolean = false,
    val result: String? = null,
    val error: String? = null,
)

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RokidHaApplication).container
    private val _ui = MutableStateFlow(SetupUi(url = storedUrl() ?: ""))
    val ui: StateFlow<SetupUi> = _ui.asStateFlow()
    val connection = c.haManager.state
    val setupMessage = c.setupMessage
    private var discoveryJob: Job? = null

    val hasStoredCredentials: Boolean get() = c.credentialStore.hasCredentials()

    private fun storedUrl(): String? = (c.credentialStore.load() as? CredentialStore.LoadResult.Ok)?.config?.url?.toString()

    fun setUrl(v: String) = _ui.update { it.copy(url = v, error = null, result = null) }
    fun setToken(v: String) = _ui.update { it.copy(token = v, error = null, result = null) }

    /** Pastes the token from the clipboard and then clears the clipboard so it does not linger. */
    fun pasteToken(context: Context) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotBlank()) {
            setToken(text.trim())
            runCatching { cm.clearPrimaryClip() }
        }
    }

    fun discover() {
        discoveryJob?.cancel()
        _ui.update { it.copy(discovering = true, discovered = emptyList(), discoveryNote = null) }
        discoveryJob = viewModelScope.launch {
            try {
                c.discovery.discover().collect { found -> _ui.update { it.copy(discovered = ZeroconfParser.merge(it.discovered, found)) } }
            } catch (e: Exception) {
                _ui.update { it.copy(discoveryNote = "Discovery failed: ${e.message}") }
            }
            _ui.update {
                it.copy(
                    discovering = false,
                    discoveryNote = it.discoveryNote ?: if (it.discovered.isEmpty()) {
                        "No Home Assistant found. Discovery does not work across VLANs, guest/isolated Wi-Fi or most VPNs — enter the URL manually."
                    } else null,
                )
            }
        }
    }

    fun pick(url: HaServerUrl) = setUrl(url.toString())

    private fun buildConfig(): HaConfig? {
        val s = _ui.value
        val url = when (val r = HaServerUrl.parse(s.url)) {
            is HaServerUrl.ParseResult.Ok -> r.url
            is HaServerUrl.ParseResult.Invalid -> { _ui.update { it.copy(error = r.reason) }; return null }
        }
        val token = AccessToken.parse(s.token)
            ?: (c.credentialStore.load() as? CredentialStore.LoadResult.Ok)?.config?.token // keep stored token when only the URL changes
            ?: run { _ui.update { it.copy(error = "Paste a Long-Lived Access Token.") }; return null }
        return HaConfig(url, token)
    }

    /** Test only; does not save. */
    fun test() = run(save = false)

    /** Test, then save encrypted and connect. */
    fun save() = run(save = true)

    private fun run(save: Boolean) {
        val cfg = buildConfig() ?: return
        _ui.update { it.copy(busy = true, error = null, result = null) }
        viewModelScope.launch {
            when (val r = c.tester.test(cfg)) {
                is HaConnectionTester.Result.Ok -> {
                    if (save) c.saveAndConnect(cfg)
                    _ui.update {
                        it.copy(
                            busy = false,
                            url = cfg.url.toString(),
                            token = if (save) "" else it.token,
                            result = "OK: ${r.locationName ?: "Home Assistant"} ${r.version ?: ""}".trim() + if (save) " — saved" else "",
                        )
                    }
                }
                is HaConnectionTester.Result.Failed -> _ui.update { it.copy(busy = false, error = r.error.userMessage) }
            }
        }
    }

    fun reconnect() {
        if (!c.connectStored()) _ui.update { it.copy(error = "No saved configuration.") }
    }

    fun disconnectAndForget() {
        c.disconnectAndForget()
        _ui.value = SetupUi()
    }
}

fun HaConnectionState.describe(): String = when (this) {
    HaConnectionState.NotConfigured -> "Not configured"
    HaConnectionState.Stopped -> "Disconnected"
    is HaConnectionState.Connecting -> "Connecting… (attempt $attempt)"
    is HaConnectionState.Connected -> "Connected · Home Assistant ${haVersion ?: ""}".trim()
    is HaConnectionState.Retrying -> "Offline: $reason\nRetrying automatically (attempt $attempt)"
    is HaConnectionState.CredentialsRejected -> "Login rejected: $reason\nCreate a new token in Home Assistant and save it here."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: SetupViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val msg by vm.setupMessage.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Home Assistant", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Status", fontWeight = FontWeight.Bold)
                Text(conn.describe())
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        Text("1. Find your server", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::discover, enabled = !ui.discovering) { Text("Discover Home Assistant") }
            if (ui.discovering) CircularProgressIndicator(Modifier.padding(4.dp))
        }
        ui.discovered.forEach { inst ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${inst.displayName} ${inst.version ?: ""}", fontWeight = FontWeight.Bold)
                    inst.candidates.forEach { url ->
                        Text("→ $url", Modifier.fillMaxWidth().clickable { vm.pick(url) }.padding(vertical = 6.dp))
                    }
                    if (inst.candidates.isEmpty()) Text("No usable URL advertised; enter it manually.")
                }
            }
        }
        ui.discoveryNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        OutlinedTextField(
            value = ui.url, onValueChange = vm::setUrl, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Server URL") }, placeholder = { Text("http://homeassistant.local:8123") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )

        Text("2. Access token", fontWeight = FontWeight.Bold)
        Text(
            "Profile → Security → Long-lived access tokens in Home Assistant (use a dedicated, non-admin user)." +
                if (vm.hasStoredCredentials) "\nA token is already stored securely; leave empty to keep it." else "",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = ui.token, onValueChange = vm::setToken, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Long-Lived Access Token") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
        OutlinedButton(onClick = { vm.pasteToken(context) }) { Text("Paste token (clears clipboard)") }

        Text("3. Connect", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::test, enabled = !ui.busy) { Text("Test connection") }
            Button(onClick = vm::save, enabled = !ui.busy) { Text("Save & connect") }
            OutlinedButton(onClick = vm::reconnect, enabled = !ui.busy && vm.hasStoredCredentials) { Text("Reconnect") }
            OutlinedButton(onClick = { confirmForget = true }, enabled = vm.hasStoredCredentials || conn != HaConnectionState.NotConfigured) {
                Text("Disconnect")
            }
        }
        if (ui.busy) CircularProgressIndicator()
        ui.result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Disconnect and delete credentials?") },
            text = { Text("The server URL, the encrypted token and its encryption key are deleted from this phone. The glasses will show 'not set up'.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; vm.disconnectAndForget() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
