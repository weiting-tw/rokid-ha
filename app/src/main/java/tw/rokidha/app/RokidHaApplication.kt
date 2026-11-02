package tw.rokidha.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import tw.rokidha.app.p0.EventLog
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.rokid.link.RokidGlassesLink

class RokidHaApplication : Application() {
    lateinit var container: AppContainer
        private set

    // Kept for the P0 diagnostics screen.
    val eventLog: EventLog get() = container.eventLog
    val glasses: RokidGlassesLink get() = container.glasses

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startup()
        // App returns to the foreground: reconnect immediately instead of waiting for backoff.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                when (container.haManager.state.value) {
                    HaConnectionState.Stopped -> container.connectStored()
                    is HaConnectionState.Retrying -> container.haManager.retryNow()
                    else -> Unit
                }
            }
        })
    }
}
