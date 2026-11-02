# Android background operation

## Strategy

| Situation | What the app does |
| --- | --- |
| App process starts (launch, or system restart of the service) | `RokidHaApplication.onCreate` → loads Keystore-encrypted credentials → `HaConnectionManager.start()` (automatic reconnection after app restart). |
| App in foreground | WebSocket connected; OkHttp sends a WebSocket ping every 30 s, a missing pong closes the socket and triggers reconnect. |
| Connection lost | Exponential backoff with jitter: 1 s, 2 s, 4 s … max 60 s. After every reconnect: re-auth, resubscribe `state_changed`, full `get_states` resync. |
| Network comes back | `ConnectivityManager` default-network callback → `retryNow()` (skips the backoff wait). |
| App returns to foreground | `ProcessLifecycleOwner` `ON_START` → `retryNow()` / reconnect if stopped. |
| Token invalid / expired / revoked | Stops retrying (`CredentialsRejected`); UI asks for a new token. Retrying cannot fix it. |
| Commands while offline | Rejected immediately. Never queued, never replayed after reconnect. |
| "Keep connected in background" ON | Foreground service `HaConnectionService` (type `connectedDevice`) with an ongoing notification keeps the process at foreground priority. |
| "Keep connected in background" OFF | No service. While the app is in the background the connection lasts only as long as Android keeps the process running and its network unrestricted. |

## Platform limitations (do not assume the connection survives)

- **Doze / App Standby**: without a foreground service, network access of a background app is
  deferred; the WebSocket will drop and reconnect when the app is used again.
- **Process death**: Android may kill the process at any time (memory pressure, vendor battery
  managers such as those on Xiaomi/Huawei/OPPO/Samsung). The service is `START_STICKY`; whether
  the system actually restarts it is device-dependent.
- **Foreground service start restrictions (Android 12+)**: the service is only started while the
  app is visible (toggle or `MainActivity.onStart`). It is not started at boot.
- **Android 14+**: `connectedDevice` type requires `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus one of
  a set of prerequisite permissions; the app declares `CHANGE_NETWORK_STATE` (normal permission).
- **Android 13+**: the ongoing notification needs `POST_NOTIFICATIONS`; requested when the toggle is
  enabled. If denied the service still runs but its notification is hidden.
- **Battery optimization**: the app does not request the restricted
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission. The Glasses tab opens the system battery
  optimization list so the user can exempt the app manually.
- **Rokid side**: the CXR-L link is a binding to the Rokid AI App / Hi Rokid service. Whether that
  app keeps the glasses connection and our binding alive while both apps are in the background is
  **UNKNOWN** (not documented, not yet tested on the device). The Rokid auth token is kept in memory
  only, so after a process restart the glasses must be re-connected from the Glasses tab (the SDK
  may return authorization synchronously if previously granted — DOCUMENTED, unverified).
