# Rokid HA

Rokid Glasses Founder Edition × Home Assistant: a wearable smart-home interface (status, notifications, menus, control)
built as an Android companion app that drives the glasses through Rokid **CXR-L** CustomView.

> **Current phase: P0 — SDK research and compatibility.**
> The Home Assistant interface is intentionally *not* implemented until CustomView display is verified on the
> target glasses (Glasses_0651, YODAOS-SPRITE 1.27.009).

## Documents

| Document | Content |
| --- | --- |
| [docs/SDK_RESEARCH.md](docs/SDK_RESEARCH.md) | CXR-L / CXR-S / CXR-M architecture, API list, session flow, CustomView JSON schema, permissions, limits, firmware risks, sources |
| [docs/SDK_COMPATIBILITY.md](docs/SDK_COMPATIBILITY.md) | VERIFIED / DOCUMENTED / EXPERIMENTAL / UNSUPPORTED status of every P0 item, build log, 1.0.3 vs 1.0.4 |
| [docs/DEVICE_VERIFICATION_CHECKLIST.md](docs/DEVICE_VERIFICATION_CHECKLIST.md) | Step-by-step on-device test procedure (no ADB needed) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, dependency rules, adapter state machine, future HA / interaction / notification / security design |

## Repository layout

```
app/            P0 verification app (Compose). Later: phone UI + wiring
rokid/          Rokid adapter — the ONLY module that imports com.rokid.* (CXR-L link, CustomView JSON)
domain/         Pure Kotlin ports & models (GlassesDisplayPort, DisplayScreen, GlassesLinkState)
homeassistant/  planned (P2)        data/      planned (P2)        security/   planned (P2/P3)
presentation/   planned (P1): dashboard, quick_control, device_detail, notifications
interaction/    planned: phone (P1), buttons & touch (P5), imu (P6)
docs/  tests/
```

## Build

Requirements: JDK 17+, Android SDK platform 35 (`local.properties` → `sdk.dir=...`), network access to
`https://maven.rokid.com/repository/maven-public/`.

```bash
./gradlew test assembleDebug                              # client-l 1.0.4 (default)
./gradlew -Prokid.cxrl.version=1.0.3 clean assembleDebug  # cross-check with 1.0.3
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. It must run on an **ARM** phone (Rokid native libs are
arm64-v8a / armeabi-v7a only) with **Rokid AI App ≥ 1.9.0** or **Hi Rokid** installed, logged in and paired with the glasses.

## P0 app

One screen: *Authorize → Connect → Open view → Update → Close → Open again*, with live link state, an event log,
human "seen on glasses" confirmations, and **Copy report** for pasting results into `docs/device-runs/`.
The Rokid auth token stays in memory and is never shown or logged by this app.
