# Glasses → Phone → Home Assistant control

Stage report, 2026-11-02. Target: Rokid Glasses Founder Edition, YODAOS-SPRITE, firmware
`1.27.009-20261014-150203`, phone SDK CXR-L (`client-l` 1.0.4, also compiles with 1.0.3).

**Bottom line**

- CXR-L delivers exactly **one** glasses input to a phone app: the AI key / wake callback. It is
  documented as a callback, but **not** as a control input, and it is **not verified on the glasses**.
- The complete phone-side pipeline is implemented and passes **simulated** end-to-end tests
  (fake glasses input → dispatcher → fake HA server → state update → fake glasses display).
- Glasses-side buttons and touchpad are documented **only for a CXR-S glasses CustomApp**. That
  would need a second APK on the glasses and is not implemented (needs your approval).
- **Nothing in this document has been verified on the actual glasses.** Hardware results: none yet (§7).

Labels: **VERIFIED** (tested on the user's Founder Edition) · **DOCUMENTED** (official Rokid docs) ·
**EXPERIMENTAL** (binary/sample/unofficial evidence only) · **UNKNOWN** (not established) ·
**SIMULATED** (passed tests with mocks/fakes).

---

## 1. Audit of the implementation before this stage (commit `14851f3`)

| Question | Finding (source) |
| --- | --- |
| How is CustomView created / updated? | `GlassesDashboardBridge.apply()` (presentation) builds a `DisplayScreen` via `DashboardRenderer.render()` and calls `RokidGlassesLink.show()` → `DisplayScreenRenderer.render()` → `CustomViewValidator.validateTree()` → `CXRLink.customViewOpen(json)`. Later changes go through `RokidGlassesLink.updateText()` → `CustomViewUpdate.toJson()` → `CXRLink.customViewUpdate(json)`, sending only changed lines. Layout change → `customViewClose()` then re-open. |
| Any glasses input received? | **No.** `RokidGlassesLink.linkCallback` only logged `onGlassAiAssistStart`, `onGlassAiAssistStop`, `onGlassAiInterrupt`. `ICustomViewCbk` has no input callbacks (opened/updated/closed/iconsSent/error). |
| Button / touch / voice / gesture callbacks? | None registered. Audio stream / custom command callbacks were not set. |
| Can the app trigger HA services? | **Yes, from the phone UI only:** `GlassesScreen` quick-action button → `AppContainer.runQuickAction()` → `ServiceCallGuard.check()` → `HaConnectionManager.callService()` (WebSocket `call_service`). |
| Action dispatcher / command abstraction? | **No.** Allowlist check + direct call; no status tracking, no duplicate protection, no confirmation of the resulting state. |
| Does the display stay active during HA updates? | **Yes (by design, SIMULATED):** WebSocket `state_changed` → `HaConnectionManager.entities` → bridge diff → `customViewUpdate`. Whether system scenes or the Rokid assistant close/pause the view on the glasses is **UNKNOWN** (V-12d/e in the device checklist). |

## 2. SDK capability matrix

### A. Glasses physical button / AI key

| Capability | Status | Evidence |
| --- | --- | --- |
| Temple button click / double / long / down / up delivered to a **CXR-L phone app** | **UNKNOWN → not available** | No such callback in the CXR-L 1.0.4 API or docs. Button actions exist only as glasses-side system broadcasts. |
| Same events on the **glasses** (`com.android.action.ACTION_SPRITE_BUTTON_*`) | DOCUMENTED | Official CXR-L doc "眼镜端按键与系统广播", sample `KeyReceiver.kt`. Received by a glasses-side app only. Double-click is the system "exit" and cannot be intercepted. |
| AI key / wake → `ICXRLinkCbk.onGlassAiAssistStart()` on the phone | DOCUMENTED (callback); EXPERIMENTAL (as control input) | Doc lists it as "唤醒词激活/停止" (wake activation). Bytecode: fired from `IAiEventCallback.onAiKeyDown`. One event per activation; `onAiKeyUp` is **not** forwarded, so no short/long press distinction. Likely also fired by the spoken wake word. |
| `onGlassAiInterrupt(Boolean)` | DOCUMENTED (callback) | Doc: "AI 打断事件（可用于触发录音等）"; the official sample uses it to start recording. Exact trigger conditions are not documented. |
| `setInterruptAiWake(Boolean)` (stop the Rokid assistant from handling the key) | EXPERIMENTAL | Present in the 1.0.3/1.0.4 binaries, **absent from the docs**; the sample never calls it. Effect unknown. |
| Events while CustomView is open | UNKNOWN | 1.0.4 bytecode moves the session to "pause (SESSION_AI_START)" on AI key down → the Rokid assistant probably takes over the display. |
| Available on Founder Edition / fw 1.27.009 | UNKNOWN | No firmware compatibility table exists. |

### B. Voice

| Capability | Status | Evidence |
| --- | --- | --- |
| Rokid assistant speech results delivered to a CXR-L app | UNKNOWN → not available | No API in CXR-L 1.0.4 or the docs. |
| Raw microphone PCM (16 kHz mono 16-bit) streamed to the phone | DOCUMENTED | `startAudioStream(1)` + `IAudioStreamCbk`; requires `GlassPermission.MICROPHONE` and an open CustomView or CustomApp session. |
| Speech recognition of that PCM | Not provided by Rokid | Would need a phone-side ASR engine (on-device or cloud). Not implemented; privacy and latency trade-offs. |
| Custom voice commands registered with the Rokid assistant | UNKNOWN | Not in CXR-L or CXR-S docs. (The separate AIUI / JSAR agent platform exists, but it is a different architecture; not evaluated.) |

### C. Touchpad / gestures

| Capability | Status | Evidence |
| --- | --- | --- |
| CustomView interactive callbacks (tap, focus, click) | Not supported (per docs) | `ICustomViewCbk` and the JSON schema have no input fields. |
| Two-finger tap / double tap / swipe forward / back, settings key | DOCUMENTED (glasses-side only) | `ACTION_TWO_FINGER_*`, `ACTION_SETTINGS_KEY` system broadcasts, received by a glasses CustomApp. |
| Single-finger swipe | UNKNOWN | Not in the documented broadcast list. |
| Touchpad long press | DOCUMENTED (glasses-side) as `ACTION_AI_START` | The CXR-S doc describes `AI_START` as "touchpad long pressed". On the phone this appears to be the same AI key event as in A (not verified). |

### D. CustomApp / CXR-S alternative

| Question | Answer | Status |
| --- | --- | --- |
| Can CXR-S run on Founder Edition? | Official docs target "Rokid Glasses / YodaOS-Sprite"; no per-model or per-firmware list. | UNKNOWN |
| Glasses ↔ phone channel | `CXRServiceBridge.subscribe/sendMessage(Caps)` on the glasses; `sendCustomCmd` / `ICustomCmdCbk.onCustomCmdResult` on the phone. Bidirectional. | DOCUMENTED |
| Works with the existing Companion App? | Yes: the phone stays the bridge (CXR-L `CUSTOMAPP` session). | DOCUMENTED |
| Separate APK needed? | **Yes**, a glasses-side APK integrating `cxr-service-bridge`. The phone can install it with `appUploadAndInstall` (no ADB); developing and debugging it needs glasses ADB, enabled through the Rokid app, plus a dedicated dev cable that is not in the box. | DOCUMENTED |
| Architecture impact | CXR-L session type is fixed per link: a `CUSTOMAPP` session cannot use CustomView. The glasses app would draw its own UI (normal Android views) and the phone would send display data over `sendCustomCmd`. HA connection, token, allowlist and dispatcher stay on the phone. | DOCUMENTED |
| CXR-M needed? | **No.** CXR-M is an alternative phone SDK that talks to the glasses directly; licensing terms are not public (SDK_RESEARCH.md §9). | — |

## 3. Founder Edition compatibility assessment

Every hardware fact is UNKNOWN until tested on the glasses. Main risks:

- **R1. Unclear meaning of the AI key event.** It may fire on the wake word and start the Rokid assistant, which can cover our view. → Two-step confirmation; optional `setInterruptAiWake` test.
- **R2. View paused or closed during AI interaction.** The bridge re-opens the dashboard automatically when it is closed (behaviour of the existing bridge).
- **R3. CXR-S support on firmware 1.27.009 is untested.** Hardware access for development (ADB + dev cable) may be the real blocker.

## 4. Recommended input method and rationale

1. **Now: CXR-L AI key as a single "PRIMARY" input** (implemented, opt-in, EXPERIMENTAL).
   - It is the only glasses-originated signal that reaches the phone without new SDKs, without a second APK, and without changing the architecture.
   - It has only one event and may be triggered accidentally (wake word). So a press only **arms** the selected target; a **second press within 5 s** sends the command.
   - The target is chosen on the phone, or with Next/Prev on future multi-button inputs.
2. **If (1) is unusable on the device** (no event, or the assistant takes over), the documented path to real buttons and touchpad is a **CXR-S glasses CustomApp**. This needs your approval: a second APK, the CUSTOMAPP session instead of CustomView, and ADB/dev-cable access. The phone-side pipeline built here already accepts `NEXT / PREVIOUS / SELECT / BACK` from any source, so only an input adapter and a glasses UI would be added.
3. **Voice** (phone-side ASR of streamed PCM) is possible per the docs but heavier and privacy-sensitive. Not recommended as the first path.

## 5. Implemented pipeline

```
Glasses input ──▶ RokidGlassesLink.inputs (CXRL_AI_KEY)        ┐
Phone simulator ─▶ AppContainer.simulateInput (PHONE_SIMULATOR) ┤──▶ GlassesInteractionController
                                                                ┘      │ InputGate: duplicate / debounce 400 ms / stale > 2 s
                                                                       │ arm → confirm within 5 s (cleared on HA or glasses disconnect)
Phone quick-action buttons ────────────────────────────────────────────┤
                                                                       ▼
                                   ActionDispatcher (single gate for every command)
                                     ServiceCallGuard allowlist → HA connected? → entity present/available?
                                     → not already in flight → call_service (WebSocket, never queued)
                                     → wait for confirming state_changed (10 s) / HA result for scripts
                                                                       ▼
          CommandStatus: Sending · AwaitingState · Confirmed · Accepted · Unconfirmed · Rejected · Unavailable · Failed
                                                                       ▼
                         GlassesDashboardBridge → "action" line + "> " cursor on the target entity → customViewUpdate
```

| Component | File |
| --- | --- |
| `GlassesInput`, `GlassesInputSource` (protocol-neutral) | `domain/.../glasses/GlassesInput.kt` |
| AI key → input events, `setAssistantInterrupt` | `rokid/.../link/RokidGlassesLink.kt` |
| `ServiceCaller` (implemented by `HaConnectionManager`) | `homeassistant/.../connection/ServiceCaller.kt` |
| `ActionDispatcher`, `CommandStatus`, `ExpectedState` | `presentation/.../interaction/` |
| `InputGate`, `GlassesInteractionController`, `InteractionView` | `presentation/.../interaction/` |
| Glasses text for each status | `DashboardRenderer.interactionText()` |
| Wiring, settings, simulator | `app/.../AppContainer.kt`, `app/.../ui/GlassesScreen.kt` |

Display wording keeps the states apart:

| Situation | Glasses line |
| --- | --- |
| Idle | `Press: Off · Living Light` |
| Armed | `Confirm Off · Living Light? Press again` |
| Pending | `Sending Off · Living Light…` → `Waiting for HA: Living Light…` |
| Success (state seen) | `OK: Living Light is OFF` |
| Success (script) | `OK: Run · Movie Mode (HA accepted)` |
| No confirming state | `Not confirmed: Living Light is ON` |
| Failure | `Failed: <reason>` / `Blocked: <reason>` / `Unavailable: <reason>` / `Controls paused: HA offline` |

Success is never shown just because a command was sent.

Security: commands use only the phone-side allowlist (quick actions ⊂ service catalog ⊂ selected
entities). Glasses events carry no entity or service, only `PRIMARY/NEXT/…`. The token never leaves
the phone. Glasses control is off by default.

## 6. Tests (all SIMULATED)

`presentation/src/test/.../interaction/CommandPipelineTest.kt` (15 tests). The real
`HaConnectionManager` runs against `FakeHomeAssistant` (WebSocket); input and display are fakes.

| # | Requirement | Test |
| --- | --- | --- |
| 1 | Valid input → correct authorized action | `confirmed glasses input runs the authorized action…`, `stateless script is reported as accepted…` |
| 2 | Unauthorized entity rejected | `unauthorized entity is rejected and nothing is sent` |
| 3 | Invalid service rejected | `invalid or unlisted service is rejected` |
| 4 | Duplicate event not executed twice | `duplicate and bouncing events never execute twice`, `concurrent commands for the same entity…` |
| 5 | HA disconnected → failure | `HA disconnected reports failure and sends nothing` |
| 6 | Service failure displayed | `HA service failure is shown on the glasses`, `unavailable entity is reported…` |
| 7 | HA state update refreshes glasses | test 1 + `state changes made elsewhere in HA refresh the glasses` |
| 8 | Glasses disconnect keeps HA sync | `losing the glasses does not stop HA synchronisation` |
| 9 | No stale replay after reconnect | `a press from before a reconnect cannot complete a command`, `stale events are dropped`, `arming times out without sending` |
| 10 | Existing functionality intact | All earlier tests still pass (`./gradlew test`, CI) |

The timing-sensitive suite was run 8 times in a row without failures.

## 7. Hardware verification

**Not performed.** I have no access to the phone or the glasses. The procedure is in
[DEVICE_VERIFICATION_CHECKLIST.md](DEVICE_VERIFICATION_CHECKLIST.md) §F. Until it is run, the
end-to-end loop on real glasses is **UNKNOWN**.

## 8. Known limitations and next steps

- Single input only: the AI key cannot distinguish short/long presses, and navigation between
  targets happens on the phone (or via the simulator).
- The wake word may also trigger input; two-step confirmation reduces but does not eliminate accidental arming.
- The Rokid assistant may cover the dashboard when the key is pressed (UNKNOWN). The
  `setInterruptAiWake` toggle is an undocumented experiment and is off by default.
- Next steps:
  1. Run checklist §F on the Founder Edition and record results.
  2. If the AI key works, keep it as the PoC path. If not, decide on the CXR-S CustomApp (needs approval, dev cable, ADB).
  3. Only then expand the glasses UI (entity navigation screens, device detail).
