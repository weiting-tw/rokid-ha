# homeassistant/ (`:homeassistant`, Kotlin/JVM)

Home Assistant client used by the Android app. No Android and no `com.rokid.*` dependencies.

| Package | Content |
| --- | --- |
| `config` | `HaServerUrl` (URL normalisation, https-for-remote policy, no downgrade), `AccessToken` (redacted `toString`), `HaConfig` |
| `rest` | `HaRestClient`: `/api/`, `/api/config`, `/api/states`, `/api/states/{id}`, `/api/services/{domain}/{service}` |
| `ws` | `HaWebSocketConnector` / `HaWsSession`: auth, commands, `subscribe_events` |
| `connection` | `HaConnectionManager` (reconnect + backoff, entity cache, areas, notifications, no command replay), `Backoff` |
| `setup` | `HaConnectionTester` (REST + WebSocket validation before saving) |
| `discovery` | `HaDiscovery` port, `ZeroconfParser` for `_home-assistant._tcp` TXT records |
| `credentials` | `CredentialStore` over `SecretCipher` + `KeyValueStore` ports (Android implementations in `:data`) |
| `selection` | `GlassesSelection`, `ServiceCatalog`, `ServiceCallGuard` (allowlist), `SelectionStore` |
| `notifications` | `HaNotification` (priority parsing from the notification helper entity) |

Tests: `src/test` against `FakeHomeAssistant` (`src/testFixtures`, MockWebServer REST + WebSocket). Run `./gradlew :homeassistant:test`.
