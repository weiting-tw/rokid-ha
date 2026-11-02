# data/ (`:data`, Android library)

Android implementations of `:homeassistant` ports:

| Class | Port | Notes |
| --- | --- | --- |
| `KeystoreSecretCipher` | `SecretCipher` | AES-256-GCM, non-exportable Android Keystore key (StrongBox if available), deleted on Disconnect |
| `SharedPrefsKeyValueStore` | `KeyValueStore` | Private prefs; backup disabled by the app manifest |
| `NsdHaDiscovery` | `HaDiscovery` | NsdManager mDNS/DNS-SD for `_home-assistant._tcp`; API 34+ `ServiceInfoCallback`, older `resolveService` |

Instrumented test (needs a device/emulator, not run in CI): `./gradlew :data:connectedAndroidTest`.
