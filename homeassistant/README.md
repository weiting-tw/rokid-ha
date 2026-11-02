# homeassistant/ (planned, P2)

Home Assistant client: REST (`/api/`, `/api/states`, `/api/services/{domain}/{service}`) and WebSocket
(`/api/websocket`: auth, get_states, subscribe_events / subscribe_entities, call_service), automatic reconnect with
re-subscribe, local entity cache. **Never replays pending commands after reconnect.**

Must not depend on any `com.rokid.*` class. Not implemented until P0 CustomView is verified on the device.
See [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) §5.1.
