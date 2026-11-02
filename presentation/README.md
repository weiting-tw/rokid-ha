# presentation/ (`:presentation`, Kotlin/JVM)

Glasses UI bridge. Turns Home Assistant state into protocol-neutral `DisplayScreen`s and pushes them
through `GlassesDisplayPort` (implemented by the Rokid adapter). It never sees the HA URL or token.

| Class | Role |
| --- | --- |
| `GlassesDashboardBridge` | Auto-opens the dashboard when the glasses link is ready; sends only changed lines; re-opens on layout change |
| `dashboard.DashboardRenderer` | Screen A (Home Dashboard): HA status, selected entities, alert, freshness footer; offline/stale handling |
| `dashboard.EntityFormatter` | Short value text per domain (`ON`, `26.0 °C`, `OPEN`, `COOL 26°`, `UNAVAILABLE`) |
| `notifications.NotificationCenter` | HIGH / MEDIUM / LOW priority, expiry, dismissal; LOW never shown on the glasses |

Screens B–E (quick control, device detail, notification, confirmation on the glasses) are not
implemented: they need verified CustomView behaviour and glasses-side input (CXR-S).
Tests: `./gradlew :presentation:test`.
