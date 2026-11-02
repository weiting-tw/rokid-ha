package tw.rokidha.app.p0

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.rokidha.domain.glasses.GlassesLinkState

/**
 * P0 compatibility test harness. Not the product UI: one screen that walks through the
 * 10 checks of docs/SDK_COMPATIBILITY.md and produces a copyable report (no ADB needed).
 */
class P0VerificationActivity : ComponentActivity() {

    private val vm: P0ViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Diagnostics owns the CustomView while open; the dashboard bridge resumes in MainActivity.onStart.
        (application as tw.rokidha.app.RokidHaApplication).container.bridge.autoShow.value = false
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    P0Screen(
                        vm = vm,
                        onCheckApp = { vm.checkRequiredApp(this) },
                        onAuthorize = { vm.authorize(this) },
                        onCopyReport = ::copyReport,
                    )
                }
            }
        }
    }

    // The CXR-L AuthorizationHelper starts the Rokid app with startActivityForResult(requestCode)
    // internally, so the classic callback is the documented integration point.
    @Deprecated("Required by CXR-L AuthorizationHelper (official docs, 鉴权 chapter)")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == P0ViewModel.AUTH_REQUEST_CODE) vm.onAuthResult(resultCode, data)
    }

    private fun copyReport() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("rokid-ha P0 report", vm.report()))
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun P0Screen(
    vm: P0ViewModel,
    onCheckApp: () -> Unit,
    onAuthorize: () -> Unit,
    onCopyReport: () -> Unit,
) {
    val state by vm.glasses.state.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val required by vm.requiredApp.collectAsStateWithLifecycle()
    val info by vm.glasses.deviceInfo.collectAsStateWithLifecycle()
    val acks by vm.glasses.updateAcks.collectAsStateWithLifecycle()
    val log by vm.log.lines.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { onCheckApp() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Rokid HA · P0 SDK verification", style = MaterialTheme.typography.titleLarge)
            Text("client-l ${vm.glasses.sdkVersion}", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Environment", fontWeight = FontWeight.Bold)
                    Text("Rokid AI App: ${required?.rokidAiAppVersion ?: "not installed"} (SDK check: ${required?.rokidAiAppOk})")
                    Text("Hi Rokid: ${required?.hiRokidVersion ?: "not installed"} (SDK check: ${required?.hiRokidOk})")
                    Text("Glasses: ${info?.deviceName ?: "-"} fw=${info?.systemVersion ?: "-"} battery=${info?.batteryLevel ?: "-"}")
                }
            }
        }
        item { LinkCard(state, acks) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCheckApp) { Text("Re-check app") }
                Button(onClick = onAuthorize, enabled = state.stage != GlassesLinkState.Stage.AUTHORIZING) { Text("1 Authorize") }
                Button(onClick = vm::connect, enabled = vm.glasses.hasToken) { Text("2 Connect") }
                Button(onClick = vm::openTestView, enabled = state.linkReady && !state.displayOpen) { Text("3 Open view") }
                Button(onClick = vm::updateTestView, enabled = state.displayOpen) { Text("4 Update") }
                Button(onClick = { vm.closeView() }, enabled = state.linkReady) { Text("5 Close") }
                OutlinedButton(onClick = vm::queryViewOpen) { Text("isOpen?") }
                OutlinedButton(onClick = vm::refreshInfo) { Text("Device info") }
                OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
                OutlinedButton(onClick = onCopyReport) { Text("Copy report") }
            }
        }
        item { Text("Checks (observed ≠ VERIFIED; record results in SDK_COMPATIBILITY.md)", fontWeight = FontWeight.Bold) }
        items(P0Check.entries) { c ->
            CheckRow(c, checks.getValue(c), onConfirm = { vm.confirm(c, it) })
        }
        item {
            Row {
                Text("Event log", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("(${log.size})")
            }
        }
        items(log.asReversed()) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LinkCard(s: GlassesLinkState, acks: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Link: ${s.stage}", fontWeight = FontWeight.Bold)
            Text("CXR service (onCXRLConnected): ${s.transportConnected}")
            Text("Glasses BT (onGlassBtConnected): ${s.glassesConnected}")
            Text("Link ready: ${s.linkReady}")
            Text("CustomView open: ${s.displayOpen}   update acks: $acks")
            s.lastError?.let { Text("Last error: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun CheckRow(c: P0Check, s: CheckStatus, onConfirm: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            val mark = when {
                s.humanConfirmed == false -> "✗"
                s.observed && (!c.needsHumanConfirmation || s.humanConfirmed == true) -> "✓"
                s.observed -> "…"
                else -> "○"
            }
            Text("$mark ${c.number}. ${c.title}", fontWeight = FontWeight.Bold)
            s.evidence?.let { Text(it, fontSize = 12.sp) }
            if (c.needsHumanConfirmation) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onConfirm(true) }) { Text("Seen on glasses") }
                    OutlinedButton(onClick = { onConfirm(false) }) { Text("Not seen") }
                }
            }
        }
    }
}
