# Rokid HA

Rokid Glasses (Founder Edition) × Home Assistant: a wearable smart-home display driven by an
Android companion app.

```
Rokid Glasses ──CXR-L──▶ Android Companion App ──HTTPS/WSS──▶ Home Assistant
 (display only)          ├─ HA configuration (setup screen)      (source of truth:
                         ├─ HA discovery (mDNS)                   entities, automations,
                         ├─ Credential manager (Keystore)         scripts, scenes)
                         ├─ REST + WebSocket clients
                         └─ Glasses UI bridge (dashboard)
```

- **All configuration happens on the phone.** The glasses never ask for a URL, IP, username,
  password or token, and never receive one.
- **Home Assistant stays the automation engine.** The app reads states and forwards service calls
  the user explicitly allowed; it contains no automation logic.

## Status

| Area | Status |
| --- | --- |
| HA setup screen, manual URL, token, test/save/disconnect/reconnect | Implemented, unit-tested |
| Keystore-encrypted token storage | Implemented; JVM tests + instrumented test (device only) |
| REST + WebSocket client, reconnect/backoff, entity cache | Implemented, tested against a fake HA server |
| mDNS discovery (`_home-assistant._tcp`) | Implemented (Android NsdManager); parser unit-tested; **real-network behaviour untested** |
| Entity selection, quick actions, service allowlist | Implemented, unit-tested |
| Glasses dashboard sync (auto-open, live updates, offline state, notifications) | Implemented and tested with a fake display; **on-glasses rendering is DOCUMENTED, not VERIFIED** |
| Controls from the glasses themselves | **Glasses app (CXR-S)** with temple button / touchpad input — implemented, installed from the phone, **not yet verified on the glasses**. The CXR-L AI-key route did not work in the first test and is only a diagnostic option. See [docs/GLASSES_CONTROL.md](docs/GLASSES_CONTROL.md) |
| QR pairing | Design only — [docs/QR_PAIRING.md](docs/QR_PAIRING.md) |
| Background operation | Foreground-service option + reconnect hooks — [docs/BACKGROUND_OPERATION.md](docs/BACKGROUND_OPERATION.md) |

Hardware status uses VERIFIED / DOCUMENTED / EXPERIMENTAL / UNKNOWN; see
[docs/SDK_COMPATIBILITY.md](docs/SDK_COMPATIBILITY.md). **Nothing glasses-related is VERIFIED on the
target device yet.**

---

## 1. Home Assistant preparation

- Home Assistant reachable from the phone: on the same LAN (`http://homeassistant.local:8123`,
  `http://192.168.x.x:8123`) or remotely over **HTTPS** (Nabu Casa, reverse proxy, VPN).
- Remote URLs must be `https://`. Plain `http://` is accepted only for local/private addresses
  (RFC 1918, `.local`, `.lan`, `.home.arpa`, single-label names, `100.64.0.0/10` e.g. Tailscale).
  The app never falls back from https to http.
- The `zeroconf` integration (enabled by default via `default_config`) is needed for automatic discovery.
- For notifications on the glasses, create a text helper (Settings → Devices & services → Helpers →
  Text) with entity id `input_text.rokid_ha_notification`, max length 255 (see §9).

## 2. Create a dedicated Home Assistant account

Settings → People → **Add person** → enable "Allow person to login", e.g. user `rokid-glasses`:

- **Do not** make it an administrator.
- Use it only for this app, so its token can be revoked without affecting anything else.

Why non-admin: least privilege. Note that Home Assistant has **no per-entity permissions** — any
user's token can read all states and call services through the API. The app's allowlist limits what
*this app* will call; it does not limit what a stolen token could do. Protect the phone and revoke
the token if the phone is lost.

## 3. Generate a Long-Lived Access Token

Log in to Home Assistant **as the dedicated user** → click the user name (Profile) → **Security** tab
→ *Long-lived access tokens* → **Create token** → name it (e.g. "Rokid phone") → copy it.
Home Assistant shows it only once. To revoke it later, delete it on the same page.

## 4. Connect through the Android app

1. Install the APK (§12 CI artifacts or §11 Build) and open **Rokid HA** → tab **Home Assistant**.
2. Tap **Discover Home Assistant** and pick a URL, or type it into **Server URL**.
3. Paste the token (**Paste token** also clears the clipboard afterwards).
4. **Test connection** — checks `GET /api/`, `GET /api/config` and WebSocket authentication.
5. **Save & connect** — tests again, then stores the URL and the encrypted token and connects.
6. **Reconnect** forces a new connection attempt; **Disconnect** deletes URL, token and the encryption key.

Status messages tell you what is wrong: unreachable server, untrusted certificate, token rejected
(invalid/expired/revoked — create a new one), not a Home Assistant URL, etc.
The setup screen blocks screenshots and screen recording while it is shown.

## 5. Automatic discovery and manual fallback

Discovery uses mDNS/DNS-SD (`_home-assistant._tcp`) and lists every instance found with its candidate
URLs (internal URL, host address, external URL). It **often finds nothing** when the phone and Home
Assistant are on different VLANs/subnets, on guest Wi-Fi with client isolation, connected through
most VPNs, or when the router filters multicast. Manual URL entry always works and never requires
discovery to succeed first.

## 6. Connect the Rokid Glasses

Requirements: ARM Android phone, Android 12+, **Rokid AI App ≥ 1.9.0** (mainland) or **Hi Rokid**
(overseas) installed, logged in and paired with the glasses.

1. Tab **Glasses** → **Connect glasses**. The Rokid app asks you to authorize *Rokid HA*.
2. When the Rokid link is ready the dashboard opens on the glasses automatically
   ("Show dashboard automatically"): HA status, your selected entities, latest alert, last update time.
3. Tab **Entities**: choose up to 6 entities (grouped by area or domain, filterable) and the quick
   actions allowed for each. Nothing is shown on the glasses until you select it.
4. Quick action buttons on the Glasses tab call the allowlisted services; locks and covers ask for
   confirmation first.
5. **Glasses control with the glasses app (experimental, CXR-S):** Glasses tab → *Glasses mode* =
   *Glasses app (CXR-S)* → *Disconnect* / *Connect glasses* → *Install glasses app* (the phone
   installs the bundled `rokid-ha-glasses.apk` on the glasses through the Rokid app — no ADB).
   Turn on *Allow glasses input to control Home Assistant* and pick the *Glasses target*.
   On the glasses: **temple click** (or two-finger tap) = select, **two-finger swipe** forward/back =
   next/previous target, **long press** = back. Select once to arm (`Confirm … Press again`), select
   again within 5 s to send. The result shows `OK: … is OFF` only after Home Assistant reports the
   new state. Double-click exits the glasses app (system behaviour); *Open on glasses* brings it back.
   The glasses app shows `PHONE DISCONNECTED` when the phone link drops. Not yet verified on the
   Founder Edition — please run docs/DEVICE_VERIFICATION_CHECKLIST.md §G.
   The CXR-L **AI key** option did not work in the first hardware test and is kept only for diagnostics.

If Home Assistant is unreachable, the glasses show `HA OFFLINE · reconnecting`, cached values are
prefixed with `~`, and the footer says `Not live · last data HH:MM:SS`. After *Disconnect* or a
rejected token, values are hidden. **SDK diagnostics (P0)** on the Glasses tab opens the low-level
CustomView test screen.

## 7. Security considerations

- Token stored only encrypted (AES-256-GCM, non-exportable Android Keystore key, StrongBox when
  available); app backup and device transfer are disabled. *Disconnect* deletes the key too.
- Token never logged, never shown after saving, never in exceptions (`AccessToken.toString()` is
  redacted), never sent to the glasses (the glasses bridge has no access to it; covered by a test).
- No credentials in QR codes (QR pairing not implemented; design forbids permanent tokens).
- HTTP redirects are not followed, so the token cannot be forwarded to another host or downgraded to http.
- Only system CAs are trusted; self-signed certificates are rejected (use a proper certificate or
  the local http URL on your LAN).
- Service calls go through an explicit allowlist (quick actions); `alarm_control_panel` and other
  unlisted services cannot be called. The Rokid SDK itself writes *its own* Rokid token to logcat
  (see docs/SDK_RESEARCH.md §8) — be careful when sharing logcat output.

## 8. Troubleshooting

| Symptom | Fix |
| --- | --- |
| Discovery finds nothing | Expected on VLAN/guest Wi-Fi/VPN. Enter the URL manually. Check `zeroconf` is enabled in HA. |
| "Remote servers must use https://" | Use your HTTPS URL, or the LAN IP when at home. |
| "Cannot reach Home Assistant" | Phone on the right Wi-Fi/VPN? HA running? Port (8123) correct? |
| "Secure connection failed" | Certificate not trusted by Android (self-signed). Use a valid certificate. |
| "Access token was rejected" | Token mistyped, deleted or expired: create a new one (§3) and save it. |
| "Unexpected response… is this a Home Assistant URL?" | URL points to a router/other web page. |
| Glasses show nothing | Rokid AI App / Hi Rokid running and glasses connected? Tap *Connect glasses*. See docs/DEVICE_VERIFICATION_CHECKLIST.md. |
| Connection drops in the background | Enable *Keep connected in background* and exempt the app from battery optimization. |
| No notifications | Automation must write to the configured notification entity; repeating the identical text does not change state (see §9). |

## 9. Notifications from Home Assistant

Home Assistant decides *when* to notify. Because non-admin users may only subscribe to a fixed list
of event types, notifications are delivered through a helper entity instead of a custom event:

```yaml
# Text helper: input_text.rokid_ha_notification (max 255)
automation:
  - alias: "Glasses: dishwasher done"
    triggers:
      - trigger: state
        entity_id: sensor.dishwasher_status
        to: "complete"
    actions:
      - action: input_text.set_value
        target:
          entity_id: input_text.rokid_ha_notification
        data:
          # priority|title|message  (priority: high | medium | low)
          value: "medium|Kitchen|Dishwasher completed {{ now().strftime('%H:%M') }}"
```

JSON (`{"title":…,"message":…,"priority":…}`) and plain text (medium priority) are also accepted.
HIGH is shown immediately (15 min), MEDIUM when no HIGH is active (5 min), LOW only on the phone.
Include something that changes (e.g. the time): setting the same text twice is not a state change.

---

## 10. Repository layout

```
app/            Android app: setup / entities / glasses screens, foreground service, P0 diagnostics
homeassistant/  Pure Kotlin HA client: URL policy, REST, WebSocket, connection manager, discovery parser,
                credential store logic, selection + service allowlist  (no Android, no Rokid)
presentation/   Glasses UI bridge + dashboard renderer + notification priorities  (no Android, no Rokid)
data/           Android implementations: Keystore cipher, SharedPreferences, NsdManager discovery
rokid/          Rokid CXR-L adapter (CustomView + CustomApp sessions) — phone side of com.rokid.*
glassesapp/     Glasses app (CXR-S): displays frames, reports button/touchpad input; bundled into the phone APK
glassesproto/   Versioned phone <-> glasses message format and key mapping (pure Kotlin)
domain/         Shared ports (GlassesDisplayPort, DisplayScreen, GlassesLinkState)
docs/           SDK research, compatibility, architecture, background, QR design, device checklist
```

## 11. Build

JDK 17+, Android SDK platform 35 (`local.properties` → `sdk.dir=…` or `ANDROID_HOME`), access to
`https://maven.rokid.com/repository/maven-public/`.

```bash
./gradlew test assembleDebug                         # unit tests + debug APK
./gradlew :rokid:compileDebugKotlin -Prokid.cxrl.version=1.0.3   # SDK cross-check
./gradlew :data:connectedAndroidTest                 # Keystore test, needs a device/emulator
```

The APK runs on ARM phones only (Rokid native libraries are arm64-v8a / armeabi-v7a).

## 12. CI/CD (GitHub Actions)

| Workflow | Trigger | Does |
| --- | --- | --- |
| [`ci.yml`](.github/workflows/ci.yml) | every push (except `v*` tags), pull requests, manual | JDK 17, Gradle cache, unit tests, debug APK, client-l 1.0.3 compile check, uploads the APK |
| [`release.yml`](.github/workflows/release.yml) | `v*` tags; manual run = dry run | tests, release APK, signs it with secrets, verifies the signature, creates a GitHub Release with APK + SHA-256 + notes |

### Download CI artifacts

GitHub → **Actions** → **CI** → pick a run → section **Artifacts** → `rokid-ha-debug-apk`
(zip with `rokid-ha-debug-<sha>.apk` and its `.sha256`). Kept 14 days. With the GitHub CLI:
`gh run download <run-id> -n rokid-ha-debug-apk`. Debug APKs are for testing only.

### Fixed debug signing key (install updates without uninstalling)

Android installs an APK over an existing app only if both are signed with the **same key**. Without
configuration every CI runner signs debug builds with a new random key, so each build must be
uninstalled first (which also deletes the saved HA token). To keep one debug signature:

1. Create a dedicated debug keystore once (standard debug passwords; it is not your release key):
   ```bash
   keytool -genkeypair -v -keystore rokid-ha-debug.keystore -storetype PKCS12 \
     -alias androiddebugkey -storepass android -keypass android \
     -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Rokid HA Debug"
   base64 -w0 rokid-ha-debug.keystore > debug.b64      # macOS: base64 -i rokid-ha-debug.keystore -o debug.b64
   ```
2. GitHub → **Settings → Secrets and variables → Actions → New repository secret**:
   `ANDROID_DEBUG_KEYSTORE_BASE64` = contents of `debug.b64`.
   Only needed if you chose other values: `ANDROID_DEBUG_KEYSTORE_PASSWORD`, `ANDROID_DEBUG_KEY_ALIAS`,
   `ANDROID_DEBUG_KEY_PASSWORD` (defaults: `android` / `androiddebugkey` / `android`).
3. Keep `rokid-ha-debug.keystore` somewhere safe (not in the repository), delete `debug.b64`.
4. Uninstall the old app **once**, then install the next CI build. From then on, CI debug builds
   install as updates. The run summary shows the certificate SHA-256 and whether the fixed key was used.

To build locally with the same signature: `ROKIDHA_DEBUG_KEYSTORE_FILE=/path/rokid-ha-debug.keystore ./gradlew assembleDebug`.
Debug and release builds use different keys and share the same application id, so switching
between a debug and a release build always requires uninstalling.

### Configure Android signing secrets

1. Create a release keystore once, **outside the repository**, and back it up safely (losing it
   means users cannot update the app):
   ```bash
   keytool -genkeypair -v -keystore rokid-ha-release.jks -storetype PKCS12 \
     -alias rokid-ha -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Base64-encode it: `base64 -w0 rokid-ha-release.jks > keystore.b64` (macOS: `base64 -i rokid-ha-release.jks -o keystore.b64`).
3. GitHub → repository **Settings → Secrets and variables → Actions → New repository secret**:

   | Secret | Value |
   | --- | --- |
   | `ANDROID_KEYSTORE_BASE64` | contents of `keystore.b64` |
   | `ANDROID_KEYSTORE_PASSWORD` | keystore password |
   | `ANDROID_KEY_ALIAS` | `rokid-ha` (the alias used above) |
   | `ANDROID_KEY_PASSWORD` | key password (PKCS12: same as the keystore password) |

4. Delete `keystore.b64`. Never commit `*.jks`, `*.keystore`, passwords or HA tokens (`.gitignore`
   blocks keystore files). The keystore only exists on the runner during the build and is deleted afterwards.

Without these secrets `release.yml` still builds, but produces `rokid-ha-<version>-UNSIGNED.apk` as a
workflow artifact only and **does not publish a GitHub Release**.

To sign locally, export `ROKIDHA_KEYSTORE_FILE`, `ROKIDHA_KEYSTORE_PASSWORD`, `ROKIDHA_KEY_ALIAS`,
`ROKIDHA_KEY_PASSWORD` and run `./gradlew assembleRelease`.

### Create a version tag and publish a release

```bash
git checkout main && git pull           # the commit you want to release
# optional: docs/release-notes/v0.2.0.md is prepended to the generated notes
git tag -a v0.2.0 -m "Rokid HA 0.2.0"
git push origin v0.2.0
```

- Tags must be `vX.Y.Z` or `vX.Y.Z-suffix` (e.g. `v0.2.0-rc.1`, published as a pre-release).
- `versionName` = tag without `v`; `versionCode` = the workflow run number (always increasing).
- The release contains `rokid-ha-<version>.apk`, its `.sha256`, and notes (commits since the
  previous tag + build/verification info).
- Dry run without a tag: Actions → **Release** → *Run workflow*.

## 13. Documents

| Document | Content |
| --- | --- |
| [docs/SDK_RESEARCH.md](docs/SDK_RESEARCH.md) | Rokid CXR-L/S/M research, CustomView schema, SDK pitfalls |
| [docs/SDK_COMPATIBILITY.md](docs/SDK_COMPATIBILITY.md) | VERIFIED / DOCUMENTED / EXPERIMENTAL / UNKNOWN status |
| [docs/DEVICE_VERIFICATION_CHECKLIST.md](docs/DEVICE_VERIFICATION_CHECKLIST.md) | On-device test procedure |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, dependency rules, state machines |
| [docs/BACKGROUND_OPERATION.md](docs/BACKGROUND_OPERATION.md) | Background strategy and Android limits |
| [docs/QR_PAIRING.md](docs/QR_PAIRING.md) | Future secure QR pairing design |
| [docs/GLASSES_CONTROL.md](docs/GLASSES_CONTROL.md) | Glasses input investigation, SDK capability matrix, command pipeline, test map |
