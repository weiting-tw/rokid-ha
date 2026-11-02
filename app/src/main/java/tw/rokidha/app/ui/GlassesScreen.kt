package tw.rokidha.app.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tw.rokidha.app.RokidHaApplication
import tw.rokidha.app.p0.P0VerificationActivity
import tw.rokidha.app.service.HaConnectionService
import tw.rokidha.domain.glasses.GlassesLinkState
import tw.rokidha.homeassistant.HaException
import tw.rokidha.homeassistant.selection.QuickAction
import tw.rokidha.rokid.link.RokidGlassesLink

class GlassesViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RokidHaApplication).container
    val link = c.glasses.state
    val deviceInfo = c.glasses.deviceInfo
    val preview = c.bridge.lastScreen
    val autoShow = c.bridge.autoShow
    val selection = c.selection
    val haState = c.haManager.state
    val notifications = c.notifications.all
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _background = MutableStateFlow(c.keepConnectedInBackground)
    val background = _background.asStateFlow()

    /**
     * Glasses connection: Rokid authorization (UI hosted by the Rokid AI App / Hi Rokid) → connect.
     * Only the Rokid token is involved here; the Home Assistant token is never passed to Rokid.
     */
    fun connectGlasses(activity: Activity) {
        if (c.glasses.hasToken) {
            c.glasses.connect()
            return
        }
        c.glasses.requestAuthorization(activity, ROKID_AUTH_REQUEST)
        // If authorization returned synchronously, connect right away; otherwise onAuthResult does it.
        if (c.glasses.hasToken) c.glasses.connect()
    }

    fun onAuthResult(resultCode: Int, data: Intent?) {
        if (c.glasses.handleAuthorizationResult(resultCode, data) == RokidGlassesLink.AuthOutcome.SUCCESS) c.glasses.connect()
    }

    fun disconnectGlasses() = c.glasses.disconnect()

    fun setAutoShow(on: Boolean) {
        autoShow.value = on
        if (!on) c.glasses.hide()
    }

    fun setBackground(context: android.content.Context, on: Boolean) {
        c.keepConnectedInBackground = on
        _background.value = on
        if (on) HaConnectionService.start(context) else HaConnectionService.stop(context)
    }

    fun run(action: QuickAction) {
        viewModelScope.launch {
            _message.value = try {
                c.runQuickAction(action)
                "${action.label}: sent"
            } catch (e: HaException) {
                e.userMessage // NotConnected: nothing is queued, the user can tap again later
            }
        }
    }

    fun dismiss(id: String) = c.notifications.dismiss(id)

    companion object {
        const val ROKID_AUTH_REQUEST = 0x5245
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlassesScreen(vm: GlassesViewModel = viewModel()) {
    val context = LocalContext.current
    val link by vm.link.collectAsStateWithLifecycle()
    val info by vm.deviceInfo.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val auto by vm.autoShow.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    val ha by vm.haState.collectAsStateWithLifecycle()
    val notes by vm.notifications.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val bg by vm.background.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<QuickAction?>(null) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Glasses", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Rokid link: ${link.stage}", fontWeight = FontWeight.Bold)
                Text("Rokid app service: ${link.transportConnected} · glasses BT: ${link.glassesConnected} · view open: ${link.displayOpen}")
                info?.let { Text("${it.deviceName ?: "Glasses"} · fw ${it.systemVersion ?: "?"} · battery ${it.batteryLevel ?: "?"}%") }
                link.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Home Assistant: ${ha.describe().lineSequence().first()}")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.connectGlasses(context as Activity) }, enabled = link.stage != GlassesLinkState.Stage.AUTHORIZING && !link.linkReady) {
                Text("Connect glasses")
            }
            OutlinedButton(onClick = vm::disconnectGlasses) { Text("Disconnect glasses") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(auto, vm::setAutoShow)
            Text("  Show dashboard automatically", Modifier.padding(start = 8.dp))
        }

        Text("On the glasses now", fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val lines = preview?.lines
                if (lines == null || !link.displayOpen) Text("(nothing shown)")
                else lines.forEach { Text(it.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            }
        }

        Text("Quick actions", fontWeight = FontWeight.Bold)
        Text(
            "Rokid CustomView has no input events, so actions are triggered here on the phone (glasses buttons: future CXR-S work).",
            style = MaterialTheme.typography.bodySmall,
        )
        if (sel.quickActions.isEmpty()) Text("None configured. Add them in the Entities tab.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sel.quickActions.forEach { a ->
                OutlinedButton(onClick = { if (a.requiresConfirmation) confirm = a else vm.run(a) }) {
                    Text("${a.label} · ${a.entityId.substringAfter('.')}")
                }
            }
        }
        msg?.let { Text(it) }

        Text("Notifications", fontWeight = FontWeight.Bold)
        if (notes.isEmpty()) Text("None. HA automations write to ${sel.notificationEntityId}.", style = MaterialTheme.typography.bodySmall)
        notes.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("[${n.priority}] ${listOfNotNull(n.title, n.message).joinToString(": ")}", Modifier.weight(1f))
                TextButton(onClick = { vm.dismiss(n.id) }) { Text("Dismiss") }
            }
        }

        Text("Background", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(bg, { on ->
                if (on && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    (context as Activity).requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                vm.setBackground(context, on)
            })
            Text("  Keep connected in background (foreground service)", Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
            Text("Battery optimization settings")
        }
        OutlinedButton(onClick = {
            vm.setAutoShow(false)
            context.startActivity(Intent(context, P0VerificationActivity::class.java))
        }) { Text("SDK diagnostics (P0)") }
    }

    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirm: ${a.label}") },
            text = { Text("${a.entityId} → ${a.domain}.${a.service}") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.run(a) }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
