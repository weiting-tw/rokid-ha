# tests/

Automated tests live next to the code (Gradle convention):

| Module | Tests | What |
| --- | --- | --- |
| `domain` | `domain/src/test/` | `DisplayScreen` / `GlassesLinkState` invariants |
| `rokid` | `rokid/src/test/` | CustomView JSON serialisation against the official schema examples, validator rules |

Run: `./gradlew test`

Hardware behaviour cannot be unit-tested here. The manual on-device procedure is
[docs/DEVICE_VERIFICATION_CHECKLIST.md](../docs/DEVICE_VERIFICATION_CHECKLIST.md).
