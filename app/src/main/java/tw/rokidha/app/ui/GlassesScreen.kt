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
import tw.rokidha.domain.glasses.GlassesInput
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.presentation.dashboard.DashboardRenderer
import tw.rokidha.presentation.interaction.InteractionView
import androidx.compose.material3.RadioButton
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

    /** Phone quick action through the shared dispatcher; progress is shown from [commandStatus]. */
    fun run(action: QuickAction) {
        viewModelScope.launch { c.runQuickAction(action) }
    }

    val commandStatus = c.dispatcher.status
    val interaction = c.interaction.view
    private val _control = MutableStateFlow(c.glassesControlEnabled)
    val control = _control.asStateFlow()
    private val _suppress = MutableStateFlow(c.suppressRokidAssistant)
    val suppress = _suppress.asStateFlow()

    fun setControl(on: Boolean) { c.glassesControlEnabled = on; _control.value = on }
    fun setSuppress(on: Boolean) { c.suppressRokidAssistant = on; _suppress.value = on }
    private val _aiKey = MutableStateFlow(c.aiKeyInput)
    val aiKey = _aiKey.asStateFlow()
    fun setAiKey(on: Boolean) { c.aiKeyInput = on; _aiKey.value = on }
    private val _mode = MutableStateFlow(c.glassesMode)
    val mode = _mode.asStateFlow()
    fun setMode(m: RokidGlassesLink.SessionMode) { c.glassesMode = m; _mode.value = m }
    fun activeMode() = c.glasses.activeSessionMode
    val glassesAppInstalled = c.glasses.glassesAppInstalled
    val installState = c.glasses.installState
    val logLines = c.eventLog.lines
    val lastGlassesEvent = c.glasses.lastGlassesEvent
    fun installGlassesApp() { c.installGlassesApp() }
    fun openGlassesApp() { c.glasses.openGlassesApp() }
    fun selectTarget(id: String) = c.interaction.selectTarget(id)
    fun simulate(kind: GlassesInput.Kind) = c.simulateInput(kind)

    /** Same wording as on the glasses. */
    fun describe(v: InteractionView?, entities: Map<String, HaEntity>, live: Boolean): String =
        v?.let { renderer.interactionText(it, entities, live) } ?: "Glasses control is off"
    private val renderer = DashboardRenderer()
    val entities = c.haManager.entities

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
    val bg by vm.background.collectAsStateWithLifecycle()
    val iv by vm.interaction.collectAsStateWithLifecycle()
    val control by vm.control.collectAsStateWithLifecycle()
    val suppress by vm.suppress.collectAsStateWithLifecycle()
    val aiKey by vm.aiKey.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val installed by vm.glassesAppInstalled.collectAsStateWithLifecycle()
    val lastEvent by vm.lastGlassesEvent.collectAsStateWithLifecycle()
    val install by vm.installState.collectAsStateWithLifecycle()
    val logLines by vm.logLines.collectAsStateWithLifecycle()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(install) {
        while (install is RokidGlassesLink.InstallState.Transferring) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val status by vm.commandStatus.collectAsStateWithLifecycle()
    val ents by vm.entities.collectAsStateWithLifecycle()
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
        Text("Glasses mode (applies at next Connect)", fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == RokidGlassesLink.SessionMode.CUSTOM_VIEW, { vm.setMode(RokidGlassesLink.SessionMode.CUSTOM_VIEW) })
            Text("CustomView — display only")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == RokidGlassesLink.SessionMode.CUSTOM_APP, { vm.setMode(RokidGlassesLink.SessionMode.CUSTOM_APP) })
            Text("Glasses app (CXR-S) — display + buttons/touchpad (experimental)")
        }
        if (vm.activeMode() != mode && link.stage != GlassesLinkState.Stage.IDLE && link.stage != GlassesLinkState.Stage.AUTHORIZED) {
            Text("Disconnect and connect again to switch mode.", color = MaterialTheme.colorScheme.error)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.connectGlasses(context as Activity) }, enabled = link.stage != GlassesLinkState.Stage.AUTHORIZING && !link.linkReady) {
                Text("Connect glasses")
            }
            OutlinedButton(onClick = vm::disconnectGlasses) { Text("Disconnect glasses") }
        }
        if (vm.activeMode() == RokidGlassesLink.SessionMode.CUSTOM_APP && link.linkReady) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Glasses app: " + when (installed) { true -> "installed"; false -> "not installed"; null -> "checking…" })
                    Text("Last glasses input: ${lastEvent ?: "none yet"}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    when (val st = install) {
                        is RokidGlassesLink.InstallState.Transferring -> {
                            val secs = (nowMs - st.startedAtMs).coerceAtLeast(0) / 1000
                            Text("Installing: sending ${st.bytes / 1024} KB to the glasses through the Rokid app… ${secs}s", fontWeight = FontWeight.Bold)
                            Text(
                                "Keep the glasses on and near the phone. This can take a minute or more. " +
                                    if (secs > 180) "No answer from the Rokid app after 3 min — see the log below." else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        RokidGlassesLink.InstallState.Installed -> Text("Installed ✓ — starting it on the glasses")
                        is RokidGlassesLink.InstallState.Failed -> Text("Install failed: ${st.reason}", color = MaterialTheme.colorScheme.error)
                        RokidGlassesLink.InstallState.Idle -> Unit
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = vm::installGlassesApp,
                            enabled = install !is RokidGlassesLink.InstallState.Transferring,
                        ) { Text(if (installed == true) "Reinstall glasses app" else "Install glasses app") }
                        OutlinedButton(onClick = vm::openGlassesApp, enabled = installed == true) { Text("Open on glasses") }
                    }
                }
            }
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

        Text("Glasses control", fontWeight = FontWeight.Bold)
        Text(
            "Glasses input arms the target below; a second input within 5 s sends it. Glasses-app mode: " +
                "temple click / two-finger tap = select, two-finger swipe = next / previous, long press = back. " +
                "Not yet verified on Founder Edition firmware 1.27.009.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(control, vm::setControl)
            Text("  Allow glasses input to control Home Assistant", Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(aiKey, vm::setAiKey, enabled = control)
            Text("  Also use AI key (CXR-L; did not work in first test)", Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(suppress, vm::setSuppress, enabled = control && aiKey)
            Text("  Ask Rokid app not to start its assistant (undocumented API)", Modifier.padding(start = 8.dp))
        }
        if (control) {
            Text("Glasses target", fontWeight = FontWeight.Bold)
            sel.quickActions.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(iv?.target?.id == a.id, { vm.selectTarget(a.id) })
                    Text("${a.label} · ${ents[a.entityId]?.friendlyName ?: a.entityId}")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Text(vm.describe(iv, ents, ha is HaConnectionState.Connected), Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
            }
            Text("Input simulator — SIMULATED, not glasses hardware", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { vm.simulate(GlassesInput.Kind.PRIMARY) }) { Text("AI key") }
                OutlinedButton(onClick = { vm.simulate(GlassesInput.Kind.PREVIOUS) }) { Text("Prev") }
                OutlinedButton(onClick = { vm.simulate(GlassesInput.Kind.NEXT) }) { Text("Next") }
                OutlinedButton(onClick = { vm.simulate(GlassesInput.Kind.SELECT) }) { Text("Select") }
                OutlinedButton(onClick = { vm.simulate(GlassesInput.Kind.BACK) }) { Text("Back") }
            }
        }

        Text("Quick actions (phone)", fontWeight = FontWeight.Bold)
        if (sel.quickActions.isEmpty()) Text("None configured. Add them in the Entities tab.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sel.quickActions.forEach { a ->
                OutlinedButton(onClick = { if (a.requiresConfirmation) confirm = a else vm.run(a) }) {
                    Text("${a.label} · ${a.entityId.substringAfter('.')}")
                }
            }
        }
        status?.let { Text("Last command: ${it::class.simpleName} · ${it.action.label} ${it.action.entityId}" + when (it) {
            is tw.rokidha.presentation.interaction.CommandStatus.Failed -> " — ${it.reason}"
            is tw.rokidha.presentation.interaction.CommandStatus.Rejected -> " — ${it.reason}"
            is tw.rokidha.presentation.interaction.CommandStatus.Unavailable -> " — ${it.reason}"
            is tw.rokidha.presentation.interaction.CommandStatus.Confirmed -> " — HA state ${it.state}"
            else -> ""
        }) }

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

        Text("Recent log (newest first)", fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                logLines.takeLast(15).asReversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                if (logLines.isEmpty()) Text("(empty)")
            }
        }
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
