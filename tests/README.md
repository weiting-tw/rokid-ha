# tests/

Automated tests live next to the code (Gradle convention). `./gradlew test` runs all JVM tests; CI runs them on every push.

| Requirement | Test |
| --- | --- |
| mDNS discovery | `homeassistant/.../DiscoveryTest` (TXT parsing, candidate ordering, merge) |
| Manual server configuration | `homeassistant/.../ManualConfigurationTest` |
| Successful authentication / REST requests | `RestAndAuthTest` (REST), `WebSocketTest` (WS auth) |
| Invalid token | `RestAndAuthTest`, `WebSocketTest`, `ConnectionManagerTest` (stops retrying, revoked token) |
| Unreachable server | `RestAndAuthTest`, `ConnectionManagerTest` (backoff) |
| WebSocket authentication and subscription | `WebSocketTest`, `ConnectionManagerTest` |
| Reconnection (re-auth, resubscribe, resync, no command replay) | `ConnectionManagerTest` |
| Secure token persistence | `CredentialAndSelectionTest` (AES-GCM, JVM); `data/src/androidTest/.../KeystoreCredentialStoreTest` (real Keystore, device only) |
| Disconnect and credential deletion | `CredentialAndSelectionTest`, `ConnectionManagerTest.reset` |
| Entity selection / allowlist | `CredentialAndSelectionTest` |
| Glasses connection without exposing credentials | `presentation/.../GlassesCredentialIsolationTest` (fake HA → manager → bridge → fake glasses) |
| Glasses dashboard sync, offline state, notification priority | `presentation/.../GlassesDashboardBridgeTest` |
| CustomView JSON / schema | `rokid/.../CustomViewJsonTest` |

The fake Home Assistant (`homeassistant/src/testFixtures/.../FakeHomeAssistant.kt`) serves REST and
WebSocket on MockWebServer and behaves like a non-admin user.

Not covered automatically (needs hardware): real NsdManager discovery, Android Keystore on a device,
Rokid glasses rendering. See [docs/DEVICE_VERIFICATION_CHECKLIST.md](../docs/DEVICE_VERIFICATION_CHECKLIST.md).
