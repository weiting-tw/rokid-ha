package tw.rokidha.app

import android.app.Application
import tw.rokidha.app.p0.EventLog
import tw.rokidha.rokid.link.RokidGlassesLink

/**
 * Holds the single process-wide [RokidGlassesLink]. Official CXR-L docs require one CXRLink per
 * process, shared by every page, with the link callback registered exactly once.
 */
class RokidHaApplication : Application() {
    val eventLog = EventLog()

    val glasses: RokidGlassesLink by lazy {
        RokidGlassesLink(this, logger = eventLog::add)
    }
}
