package tw.rokidha.data

import android.content.Context
import tw.rokidha.homeassistant.credentials.KeyValueStore

/**
 * Private SharedPreferences. Holds the server URL, the *encrypted* token and the glasses
 * selection. App backup is disabled in the manifest so nothing is copied off the device.
 */
class SharedPrefsKeyValueStore(context: Context, name: String = "rokid_ha") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun remove(vararg keys: String) {
        prefs.edit().apply { keys.forEach { remove(it) } }.commit()
    }
}
