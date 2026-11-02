# security/

Security rules are implemented inside the modules that enforce them:

| Rule | Where |
| --- | --- |
| Token encryption (Android Keystore AES-GCM), deletion on Disconnect | `data/KeystoreSecretCipher`, `homeassistant/credentials/CredentialStore` |
| Token never logged / shown (`AccessToken.toString()` redacted), setup screen `FLAG_SECURE` | `homeassistant/config/AccessToken`, `app/ui/MainActivity` |
| https required for remote hosts, no downgrade, no redirects | `homeassistant/config/HaServerUrl`, `homeassistant/HaHttp` |
| Entity exposure only by explicit selection; service allowlist; confirmation for locks/covers | `homeassistant/selection` |
| Glasses never receive credentials | `presentation/GlassesDashboardBridge` (+ `GlassesCredentialIsolationTest`) |
| AI intent validation | Future (P6): must go through `ServiceCallGuard` |

See README → "Security considerations".
