package tw.rokidha.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tw.rokidha.app.RokidHaApplication
import tw.rokidha.app.service.HaConnectionService

/** Phone UI: Home Assistant setup, entity selection, glasses. All configuration happens here. */
class MainActivity : ComponentActivity() {
    private val glassesVm: GlassesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as RokidHaApplication).container
        // First launch without configuration → open the setup tab.
        val startTab = if (container.credentialStore.hasCredentials()) TAB_GLASSES else TAB_SETUP
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var tab by rememberSaveable { mutableIntStateOf(startTab) }
                // The setup tab may show a token: block screenshots / screen recording / recents preview.
                DisposableEffect(tab) {
                    if (tab == TAB_SETUP) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose { }
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == TAB_SETUP, { tab = TAB_SETUP }, { Icon(Icons.Default.Settings, null) }, label = { Text("Home Assistant") })
                            @Suppress("DEPRECATION")
                            NavigationBarItem(tab == TAB_ENTITIES, { tab = TAB_ENTITIES }, { Icon(Icons.Default.List, null) }, label = { Text("Entities") })
                            NavigationBarItem(tab == TAB_GLASSES, { tab = TAB_GLASSES }, { Icon(Icons.Default.Info, null) }, label = { Text("Glasses") })
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        // Build identity, so testers can confirm which version is installed.
                        Text(
                            "v${tw.rokidha.app.BuildConfig.VERSION_NAME}",
                            Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(end = 8.dp, top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        when (tab) {
                            TAB_SETUP -> SetupScreen()
                            TAB_ENTITIES -> EntitiesScreen()
                            else -> GlassesScreen(glassesVm)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val c = (application as RokidHaApplication).container
        c.bridge.autoShow.value = true
        // Foreground services may only be started while the app is visible.
        if (c.keepConnectedInBackground) HaConnectionService.start(this)
    }

    // CXR-L AuthorizationHelper uses startActivityForResult internally (official docs, 鉴权 chapter).
    @Deprecated("Required by CXR-L AuthorizationHelper")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GlassesViewModel.ROKID_AUTH_REQUEST) glassesVm.onAuthResult(resultCode, data)
    }

    private companion object {
        const val TAB_SETUP = 0
        const val TAB_ENTITIES = 1
        const val TAB_GLASSES = 2
    }
}
