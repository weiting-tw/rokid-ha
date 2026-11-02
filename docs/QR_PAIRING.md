# QR pairing — design (P6, not implemented)

Status: **design only**. The initial release does not include QR pairing; configuration is done by
entering the URL (or picking a discovered server) and pasting a Long-Lived Access Token on the phone.

## What QR pairing is (and is not)

- It is a way to move **non-secret setup data plus a short-lived, single-use pairing code** from a
  trusted screen (e.g. a Home Assistant dashboard card or the HA admin's phone) to this Android app.
- The **phone** scans the code with its camera. The glasses are **not** assumed to scan QR codes:
  CXR-L only offers `takePhoto` (JPEG) during an open CustomView/CustomApp session and has no QR
  decoding; whether it could be used for scanning is **UNKNOWN** and is not planned.

## Hard requirements

1. A QR code never contains a permanent Home Assistant access token (or any password).
2. Pairing credentials are single-use and expire (proposal: 5 minutes).
3. The app validates every field before use (URL policy of `HaServerUrl`, code format, expiry).
4. Unused credentials expire server-side; a used code cannot be reused.
5. Nothing from the QR payload is logged.

## Proposed protocol

Home Assistant has no built-in "pair a device with a one-time code" endpoint for long-lived tokens,
so a secure implementation needs a server-side component. Two options:

| Option | How | Trade-off |
| --- | --- | --- |
| A. Official OAuth2 (preferred) | QR carries only `{"v":1,"url":"https://…","name":"Home"}`. The app runs HA's standard **IndieAuth/OAuth2 authorization-code flow** in a browser tab (`/auth/authorize` → `/auth/token`), the user logs in and approves, the app receives a refresh token bound to its client id. | No custom server code; user still logs in once. Requires implementing refresh-token handling instead of long-lived tokens. |
| B. Custom pairing integration | A small HA custom integration creates a one-time code (≥128-bit random, 5 min TTL) shown as `{"v":1,"url":…,"code":…}`. The app POSTs the code over HTTPS to the integration, which returns a **new** token for a dedicated user and invalidates the code. | Must be written, reviewed and maintained as HA custom code; rate limiting and audit logging required. |

Validation in the app (both options): version field, URL passes `HaServerUrl.parse` (https required
for remote hosts), payload size limit, code format/expiry, and a confirmation screen showing the
server name/URL before anything is sent.

## Why not now

Long-lived tokens pasted on the phone already meet the security requirements (Keystore-encrypted,
never sent to the glasses). QR pairing is a convenience feature and does not block the release.
