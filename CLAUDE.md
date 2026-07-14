# BamBuddy Hubitat Driver

Hubitat Groovy driver for monitoring and controlling Bambu Lab 3D printers via a [BamBuddy](https://github.com/jc21/bambuddy) instance.

## Project

- **Driver file:** `bambuddy-printer-manager.groovy`
- **Namespace:** `jc21` / **Author:** Jamie Curnow
- **BamBuddy** is Jamie's own open-source project — he knows the API

## BamBuddy API

- **Auth:** `Authorization: Bearer <token>` header (NOT `X-API-Key`)
- **Token format:** `bb_xxxxxxxxxxxx`
- **Base URL:** user-configured, e.g. `https://bambuddy.example.com`
- **Trailing slashes:** required on list endpoints (e.g. `/api/v1/printers/`) but NOT on nested resource endpoints (e.g. `/api/v1/printers/1/status`)

### Endpoints used

| Method | Path | Notes |
|--------|------|-------|
| GET | `/health/` | Returns `{"status":"healthy"}` — no auth needed |
| GET | `/api/v1/printers/` | Returns array of printer objects |
| GET | `/api/v1/printers/{id}/status` | No trailing slash |
| POST | `/api/v1/printers/{id}/clear-plate` | No body needed |
| POST | `/api/v1/printers/{id}/print/stop` | No body needed |
| POST | `/api/v1/printers/{id}/print/pause` | No body needed |
| POST | `/api/v1/printers/{id}/print/resume` | No body needed |
| POST | `/api/v1/printers/{id}/chamber-light?on={value}` | Query param, not body — `value` is `True`/`False` |
| GET | `/api/v1/smart-plugs/by-printer/{printer_id}` | Returns the smart plug object (`id`) controlling this printer |
| POST | `/api/v1/smart-plugs/{plug_id}/control` | Body: `{"action": "on"/"off"}` |

### MQTT

- **Topic:** `{prefix}/printers/#` (wildcard, e.g. `bambuddy/printers/#`)
- **Status messages** have `printer_id` and `state` fields — use these to identify them
- **Topic format seen in practice:** `bambuddy/printers/{serial}/status`

## Hubitat Driver Specifics

### HTTP

- **Never use synchronous `httpGet`/`httpPost`** — they cause `StackOverflowError` in Hubitat's sandbox
- **Always use `asynchttpGet` / `asynchttpPost`** with named callback methods
- **Callbacks** must be `def` (not `private`) — Hubitat calls them by reflection
- **Passing context to callbacks:** use the third `data` argument: `asynchttpGet("cb", params, [printerId: id])`
- **Response body:** use `textParser: true` in params, then `resp.data` is a String. Use Hubitat's built-in `parseJson(resp.data)` — do NOT use `new groovy.json.JsonSlurper().parseText()` as it fails in the sandbox
- **`contentType: "application/json"` in params** (not in headers) controls response auto-parsing — but `textParser: true` + `parseJson()` is more reliable
- **Error handling:** `resp.hasError()` catches 4xx/5xx; always log `resp.getStatus()` not just `resp.getErrorMessage()`
- **Do not make `asynchttpGet` calls inside another async response handler** — keep all HTTP calls at the top level

### State

- **`sendEvent(name:, value:)`** → appears in "Current States" on the device page
- **`state.foo = ...`** → appears in "State Variables" — use for data collections like the printer list
- **Dynamic attributes** (not declared in metadata) work fine with `sendEvent` — Hubitat creates them on first use
- **Attribute naming:** use camelCase — e.g. `printer1Name`, `printer1Connected`, `printer1State`, `printer1CurrentPrint`, `printer1Progress`, `printer1RemainingTime`

### MQTT

- **Interface:** `interfaces.mqtt.connect(brokerUrl, clientId, username, password)`
- **Broker URL format:** `tcp://host:port` or `ssl://host:port`
- **DO NOT call `interfaces.mqtt.subscribe()` inside `mqttClientStatus()`** — it silently fails
- **Correct pattern:** in `mqttClientStatus` on success, use `runIn(1, "mqttSubscribe")` to defer the subscribe call
- **Incoming messages** are delivered to `def parse(String description)` — use `interfaces.mqtt.parseMessage(description)` to get `{topic, payload}`
- **`log.debug` is filtered** by Hubitat's hub log level — use `log.info` when you need logs to always appear (e.g. during debugging), then revert to `log.debug` behind `logEnable`
- **Keepalive drops:** connections can silently drop without triggering `mqttClientStatus` — use `runEvery5Minutes("mqttHealthCheck")` and check `interfaces.mqtt.isConnected()`
- **`mqttClientStatus` string:** starts with `"Status: Connection succeeded"` on connect; contains `"Connection lost"` on drop

### Scheduling

- Hubitat supports Quartz cron with seconds: `schedule("0/30 * * * * ?", "method")`
- Built-in helpers: `runEvery1Minute`, `runEvery5Minutes`, `runEvery10Minutes`, etc.
- Always call `unschedule()` in `updated()` before rescheduling
- `runIn(seconds, "method")` for one-shot delayed execution

### Capabilities

- `capability "Refresh"` → requires `refresh()` method
- `capability "Initialize"` → adds Initialize button in device UI, requires `initialize()` method
- `capability "Switch"` → requires `on()` and `off()` methods; update `switch` attribute with `sendEvent(name: "switch", value: "on"/"off")`

## Common Pitfalls Encountered

1. **StackOverflow on HTTP** — always async, never sync
2. **`resp.json` accessor throws `JsonException`** when body is empty/non-JSON — use `textParser: true` + `parseJson(resp.data)` in a try-catch instead
3. **HTML response body** (4131 bytes) means wrong host/port — the server is returning a SPA's `index.html`
4. **Trailing slash sensitivity** — `/api/v1/printers/` needs slash, `/api/v1/printers/1/status` does not
5. **MQTT subscribe in status callback silently fails** — always defer with `runIn(1, ...)`
6. **`log.debug` invisible** when hub log level is set to Info — use `log.info` for critical debugging, revert after
7. **API returns 404 (not 401) for bad/missing auth** on some endpoints — don't assume 401 means "no auth"
