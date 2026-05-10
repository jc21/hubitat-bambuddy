/**
 * BamBuddy Printers — Hubitat Driver
 *
 * Monitors and controls Bambu Lab 3D printers via a BamBuddy instance.
 * Supports both REST polling and optional MQTT live updates.
 *
 * API Token Permissions Required:
 *   • Read Status
 *   • Manage Queue
 *   • Control Printer
 */
metadata {
    definition(
        name:        "BamBuddy Printers",
        namespace:   "jc21",
        author:      "Jamie Curnow",
        description: "Monitor and control Bambu Lab printers via BamBuddy https://bambuddy.cool"
    ) {
        capability "Refresh"
        capability "Initialize"
        capability "Switch"

        command "refreshData"
        command "clearPlate",    [[name: "Printer ID*", type: "NUMBER", description: "Printer ID to clear plate on"]]
        command "stopPrint",     [[name: "Printer ID*", type: "NUMBER", description: "Printer ID to stop"]]
        command "pausePrint",    [[name: "Printer ID*", type: "NUMBER", description: "Printer ID to pause"]]
        command "resumePrint",   [[name: "Printer ID*", type: "NUMBER", description: "Printer ID to resume"]]
        command "lightOn",       [[name: "Printer ID*", type: "NUMBER", description: "Printer ID to turn chamber light on"]]
        command "lightOff",      [[name: "Printer ID*", type: "NUMBER", description: "Printer ID to turn chamber light off"]]
        command "connectMqtt"
        command "disconnectMqtt"

        // Current States
        attribute "health",     "string"
        attribute "mqttStatus", "string"

        // Per-printer states are set dynamically as camelCase, e.g.:
        //   printer1Name, printer1Connected, printer1State,
        //   printer1CurrentPrint, printer1Progress, printer1RemainingTime
        //
        // State Variables (visible under "State Variables"):
        //   state.printers — List of [{id, name}, ...] from last REST poll
    }

    preferences {
        input name: "bambuddyHost",
              type: "text",
              title: "BamBuddy Host",
              description: "hostname/IP:port or full URL — e.g. https://bambuddy.example.com",
              required: true

        input name: "apiKey",
              type: "password",
              title: "API Token",
              description: "Sent as Bearer token — Required permissions: Read Status, Manage Queue, Control Printer",
              required: true

        input name: "refreshInterval",
              type: "enum",
              title: "REST Poll Interval",
              options: [
                  "Disabled": "Disabled",
                  "30":       "30 seconds",
                  "60":       "1 minute",
                  "120":      "2 minutes",
                  "300":      "5 minutes",
                  "600":      "10 minutes"
              ],
              defaultValue: "60",
              required: true

        input name: "mqttBroker",
              type: "text",
              title: "MQTT Broker (optional)",
              description: "hostname:port — e.g. 192.168.0.10:1883. Leave blank to disable.",
              required: false

        input name: "mqttTopicPrefix",
              type: "text",
              title: "MQTT Topic Prefix",
              description: "Subscribes to {prefix}/printers/#",
              defaultValue: "bambuddy",
              required: false

        input name: "mqttUsername",
              type: "text",
              title: "MQTT Username (optional)",
              description: "Leave blank if your broker does not require authentication.",
              required: false

        input name: "mqttPassword",
              type: "password",
              title: "MQTT Password (optional)",
              required: false

        input name: "logEnable",
              type: "bool",
              title: "Enable debug logging",
              defaultValue: false
    }
}

// ── Lifecycle ──────────────────────────────────────────────────────────────

def installed() {
    log.info "${device.displayName}: installed"
    initialize()
}

def updated() {
    log.info "${device.displayName}: preferences updated"
    unschedule()
    try { interfaces.mqtt.disconnect() } catch (ignored) {}
    initialize()
}

def initialize() {
    scheduleRefresh()
    refreshData()
    if (settings.mqttBroker?.trim()) {
        connectMqtt()
    } else {
        sendEvent(name: "mqttStatus", value: "disabled")
    }
}

// ── Scheduling ─────────────────────────────────────────────────────────────

private scheduleRefresh() {
    if (!settings.refreshInterval || settings.refreshInterval == "Disabled") return

    switch (settings.refreshInterval.toInteger()) {
        case 30:  schedule("0/30 * * * * ?", "refreshData"); break
        case 60:  runEvery1Minute("refreshData");             break
        case 120: schedule("0 0/2 * * * ?",  "refreshData"); break
        case 300: runEvery5Minutes("refreshData");            break
        case 600: runEvery10Minutes("refreshData");           break
        default:  runEvery1Minute("refreshData")
    }

    if (logEnable) log.debug "Auto-refresh scheduled every ${settings.refreshInterval}s"
}

// ── Capability: Refresh ────────────────────────────────────────────────────

def refresh() {
    refreshData()
}

// ── Commands ───────────────────────────────────────────────────────────────

def refreshData() {
    if (!validateSettings()) return
    def healthParams   = buildParams("/health/")
    def printersParams = buildParams("/api/v1/printers/")
    if (logEnable) {
        log.debug "REQ GET ${healthParams.uri} headers=${healthParams.headers}"
        log.debug "REQ GET ${printersParams.uri} headers=${printersParams.headers}"
    }
    asynchttpGet("healthCallback",   healthParams)
    asynchttpGet("printersCallback", printersParams)
}

def clearPlate(printerId)  { asyncPrinterPost(printerId, "clear-plate",   null) }
def stopPrint(printerId)   { asyncPrinterPost(printerId, "print/stop",   null) }
def pausePrint(printerId)  { asyncPrinterPost(printerId, "print/pause",  null) }
def resumePrint(printerId) { asyncPrinterPost(printerId, "print/resume", null) }
def lightOn(printerId)     { asyncPrinterPost(printerId, "chamber-light", [on: true])  }
def lightOff(printerId)    { asyncPrinterPost(printerId, "chamber-light", [on: false]) }

def on() {
    allPrinterIds().each { id -> lightOn(id) }
    sendEvent(name: "switch", value: "on")
}

def off() {
    allPrinterIds().each { id -> lightOff(id) }
    sendEvent(name: "switch", value: "off")
}

private List allPrinterIds() {
    return (state.printers ?: []).collect { it.id }
}

// ── MQTT ───────────────────────────────────────────────────────────────────

def connectMqtt() {
    def broker = settings.mqttBroker?.trim()
    if (!broker) return
    def brokerUrl = (broker.startsWith("tcp://") || broker.startsWith("ssl://")) ? broker : "tcp://${broker}"
    def mqttUser = settings.mqttUsername?.trim() ?: null
    def mqttPass = settings.mqttPassword?.trim() ?: null
    try {
        interfaces.mqtt.connect(brokerUrl, "hubitat-bambuddy-${device.id}", mqttUser, mqttPass)
        log.info "${device.displayName}: connecting to MQTT broker ${brokerUrl}${mqttUser ? " as ${mqttUser}" : ""}"
    } catch (e) {
        log.error "${device.displayName}: MQTT connect failed — ${e.message}"
        sendEvent(name: "mqttStatus", value: "error: ${e.message}")
        runIn(60, "connectMqtt")
    }
}

def disconnectMqtt() {
    try {
        interfaces.mqtt.disconnect()
        sendEvent(name: "mqttStatus", value: "disconnected")
        log.info "${device.displayName}: MQTT disconnected"
    } catch (e) {
        log.error "${device.displayName}: MQTT disconnect failed — ${e.message}"
    }
}

def mqttClientStatus(String status) {
    log.info "${device.displayName}: MQTT status — ${status}"
    if (status.startsWith("Status: Connection succeeded")) {
        sendEvent(name: "mqttStatus", value: "connected")
        runIn(1, "mqttSubscribe")
    } else if (status.contains("Connection lost") || status.contains("Client is not connected") || status.contains("Connection error")) {
        sendEvent(name: "mqttStatus", value: "disconnected")
        log.warn "${device.displayName}: MQTT disconnected, reconnecting in 30s"
        runIn(30, "connectMqtt")
    }
}

def mqttSubscribe() {
    def prefix = settings.mqttTopicPrefix?.trim() ?: "bambuddy"
    def topic  = "${prefix}/printers/#"
    interfaces.mqtt.subscribe(topic)
    log.info "${device.displayName}: MQTT subscribed to ${topic}"
    runEvery5Minutes("mqttHealthCheck")
}

def mqttHealthCheck() {
    if (!interfaces.mqtt.isConnected()) {
        log.warn "${device.displayName}: MQTT health check — not connected, reconnecting"
        connectMqtt()
    } else {
        if (logEnable) log.debug "MQTT health check — connected"
    }
}

def parse(String description) {
    def mqtt = interfaces.mqtt.parseMessage(description)
    if (logEnable) log.debug "MQTT recv: topic=${mqtt.topic} payload=${mqtt.payload}"

    def msg
    try {
        msg = parseJson(mqtt.payload)
        if (logEnable) log.debug "MQTT parsed OK: keys=${msg.keySet()}"
    } catch (e) {
        log.error "${device.displayName}: failed to parse MQTT payload — ${e.message} — raw='${mqtt.payload}'"
        return
    }

    // Only process status messages (must have printer_id and state)
    if (msg.printer_id == null || msg.state == null) {
        if (logEnable) log.debug "MQTT skipped (not a status message): printer_id=${msg.printer_id} state=${msg.state}"
        return
    }

    if (logEnable) log.debug "MQTT status for printer ${msg.printer_id} (${msg.printer_name}): state=${msg.state} progress=${msg.progress} remaining=${msg.remaining_time} connected=${msg.connected}"

    updatePrinterStates(msg.printer_id, [
        name:           msg.printer_name,
        connected:      msg.connected,
        state:          msg.state,
        current_print:  msg.current_print,
        progress:       msg.progress,
        remaining_time: msg.remaining_time
    ])

    if (logEnable) log.debug "MQTT state update applied for printer ${msg.printer_id}"
}

// ── Async REST Callbacks ───────────────────────────────────────────────────

def healthCallback(resp, data) {
    if (resp.hasError()) {
        def msg = "error (HTTP ${resp.getStatus()}): ${resp.getErrorMessage()}"
        log.error "${device.displayName}: health check failed — HTTP ${resp.getStatus()} ${resp.getErrorMessage()}"
        sendEvent(name: "health", value: msg)
        return
    }
    if (logEnable) log.debug "RESP GET /health -> [${resp.getStatus()}] body='${resp.data}'"
    sendEvent(name: "health", value: resp.getStatus() == 200 ? "ok" : "error (HTTP ${resp.getStatus()})")
}

def printersCallback(resp, data) {
    if (resp.hasError()) {
        log.error "${device.displayName}: printers fetch failed — HTTP ${resp.getStatus()} ${resp.getErrorMessage()}"
        return
    }
    def rawBody = resp.data
    if (logEnable) log.debug "RESP GET /api/v1/printers -> [${resp.getStatus()}] bytes=${rawBody?.length()} body='${rawBody}'"
    if (resp.getStatus() != 200) {
        log.warn "${device.displayName}: /api/v1/printers returned HTTP ${resp.getStatus()}"
        return
    }
    def parsed
    try {
        parsed = parseJson(rawBody)
    } catch (Exception e) {
        if (rawBody?.startsWith("<")) {
            log.error "${device.displayName}: /api/v1/printers returned HTML — wrong host/port?"
        } else {
            log.error "${device.displayName}: failed to parse /api/v1/printers — ${e.message}"
        }
        return
    }

    def list = parsed.collect { p -> [id: p.id, name: p.name] }
    state.printers = list
    if (logEnable) log.debug "Printers: ${list.collect { "${it.id}:${it.name}" }.join(", ")}"

    list.each { p ->
        def sp = buildParams("/api/v1/printers/${p.id}/status")
        if (logEnable) log.debug "REQ GET ${sp.uri} headers=${sp.headers}"
        asynchttpGet("printerStatusCallback", sp, [printerId: p.id])
    }
}

def printerStatusCallback(resp, data) {
    def printerId = data?.printerId
    if (resp.hasError()) {
        log.error "${device.displayName}: status fetch failed for printer ${printerId} — HTTP ${resp.getStatus()} ${resp.getErrorMessage()}"
        return
    }
    def rawBody = resp.data
    if (logEnable) log.debug "RESP GET /api/v1/printers/${printerId}/status -> [${resp.getStatus()}] bytes=${rawBody?.length()} body='${rawBody}'"
    if (resp.getStatus() != 200) {
        log.warn "${device.displayName}: status for printer ${printerId} returned HTTP ${resp.getStatus()}"
        return
    }
    def d
    try {
        d = parseJson(rawBody)
    } catch (Exception e) {
        if (rawBody?.startsWith("<")) {
            log.error "${device.displayName}: printer ${printerId} status returned HTML — wrong host/port?"
        } else {
            log.error "${device.displayName}: failed to parse printer ${printerId} status — ${e.message}"
        }
        return
    }

    updatePrinterStates(printerId, [
        name:           d.name,
        connected:      d.connected,
        state:          d.state,
        current_print:  d.current_print,
        progress:       d.progress,
        remaining_time: d.remaining_time
    ])
}

def printerActionCallback(resp, data) {
    def printerId = data?.printerId
    def action    = data?.action
    if (resp.hasError()) {
        log.error "${device.displayName}: '${action}' failed for printer ${printerId} — HTTP ${resp.getStatus()} ${resp.getErrorMessage()}"
        return
    }
    if (logEnable) log.debug "RESP POST /api/v1/printers/${printerId}/${action} -> [${resp.getStatus()}] ${resp.data}"
    if (resp.getStatus() in [200, 201, 204]) {
        log.info "${device.displayName}: '${action}' sent to printer ${printerId}"
    } else {
        log.warn "${device.displayName}: '${action}' for printer ${printerId} returned HTTP ${resp.getStatus()}"
    }
}

// ── Shared State Update ────────────────────────────────────────────────────

private updatePrinterStates(printerId, Map d) {
    def pfx = "printer${printerId}"
    sendIfChanged("${pfx}Name",          safeStr(d.name))
    sendIfChanged("${pfx}Connected",     safeStr(d.connected))
    sendIfChanged("${pfx}State",         safeStr(d.state))
    sendIfChanged("${pfx}CurrentPrint",  safeStr(d.current_print))
    sendIfChanged("${pfx}Progress",      safeStr(d.progress))
    sendIfChanged("${pfx}RemainingTime", safeStr(d.remaining_time))

    if (logEnable) log.debug "Printer ${printerId}: state=${d.state}, progress=${d.progress}, remaining=${d.remaining_time}"
}

private sendIfChanged(String name, String value) {
    if (state."_last_${name}" != value) {
        state."_last_${name}" = value
        sendEvent(name: name, value: value)
    }
}

// ── HTTP Helpers ───────────────────────────────────────────────────────────

private asyncPrinterPost(printerId, String action, Map body) {
    if (!validateSettings()) return
    def params = buildParams("/api/v1/printers/${printerId}/${action}")
    if (body != null) {
        params.contentType = "application/json"
        params.body        = groovy.json.JsonOutput.toJson(body)
    }
    if (logEnable) log.debug "REQ POST ${params.uri} headers=${params.headers} body=${params.body}"
    asynchttpPost("printerActionCallback", params, [printerId: printerId, action: action])
}

private Map buildParams(String path) {
    return [
        uri:        "${baseUrl()}${path}",
        headers:    ["Authorization": "Bearer ${settings.apiKey}", "Accept": "application/json"],
        textParser: true,
        timeout:    15
    ]
}

private String baseUrl() {
    def host = settings.bambuddyHost?.trim() ?: ""
    return host.startsWith("http") ? host : "http://${host}"
}

// ── Utilities ──────────────────────────────────────────────────────────────

private boolean validateSettings() {
    if (!settings.bambuddyHost) { log.warn "${device.displayName}: host not configured";    return false }
    if (!settings.apiKey)       { log.warn "${device.displayName}: API token not configured"; return false }
    return true
}

private String safeStr(val) { val != null ? val.toString() : "" }
