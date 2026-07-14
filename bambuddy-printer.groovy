/**
 * BamBuddy Printer — Hubitat Driver
 *
 * Represents a single Bambu Lab 3D printer. Created automatically as a child
 * device of "BamBuddy Printers" for each printer discovered via the BamBuddy
 * API. All commands delegate to the parent device, which holds the host/API
 * token configuration.
 *
 * The Switch capability (on/off) controls the printer's smart plug.
 * lightOn/lightOff control the chamber light independently.
 */
metadata {
    definition(
        name:        "BamBuddy Printer",
        namespace:   "jc21",
        author:      "Jamie Curnow",
        description: "A single Bambu Lab printer managed via BamBuddy https://bambuddy.cool"
    ) {
        capability "Refresh"
        capability "Switch"

        command "clearPlate"
        command "stopPrint"
        command "pausePrint"
        command "resumePrint"
        command "lightOn"
        command "lightOff"

        // Current States
        attribute "printerName",   "string"
        attribute "connected",     "string"
        attribute "state",         "string"
        attribute "currentPrint",  "string"
        attribute "progress",      "string"
        attribute "remainingTime", "string"
        attribute "light",         "string"
        attribute "hmsError",      "string"
    }
}

// ── Capability: Refresh ────────────────────────────────────────────────────

def refresh() {
    parent?.childRefresh(printerId())
}

// ── Capability: Switch (smart plug) ─────────────────────────────────────────

def on() {
    parent?.childPlugOn(printerId())
}

def off() {
    parent?.childPlugOff(printerId())
}

// ── Commands ───────────────────────────────────────────────────────────────

def clearPlate()  { parent?.childClearPlate(printerId()) }
def stopPrint()   { parent?.childStopPrint(printerId()) }
def pausePrint()  { parent?.childPausePrint(printerId()) }
def resumePrint() { parent?.childResumePrint(printerId()) }

def lightOn() {
    parent?.childLightOn(printerId())
    sendEvent(name: "light", value: "on")
}

def lightOff() {
    parent?.childLightOff(printerId())
    sendEvent(name: "light", value: "off")
}

// ── Called by parent ───────────────────────────────────────────────────────

def updateFromParent(Map d) {
    sendIfChanged("printerName",   safeStr(d.name))
    sendIfChanged("connected",     safeStr(d.connected))
    sendIfChanged("state",         safeStr(d.state))
    sendIfChanged("currentPrint",  safeStr(d.current_print))
    sendIfChanged("progress",      safeStr(d.progress))
    sendIfChanged("remainingTime", safeStr(d.remaining_time))

    // "light" is reported by both MQTT and REST status payloads
    if (d.light != null) sendIfChanged("light", d.light ? "on" : "off")

    // hms_errors is only present on REST /status responses — MQTT payloads
    // omit the key entirely, so only touch this attribute when it was
    // actually provided (avoids clobbering the last known value every
    // ~1s from MQTT ticks that don't carry HMS data).
    if (d.containsKey("hms_errors")) sendIfChanged("hmsError", formatHmsErrors(d.hms_errors))
}

def updatePlugState(String rawState) {
    sendIfChanged("switch", safeStr(rawState).toLowerCase())
}

// ── Utilities ──────────────────────────────────────────────────────────────

private String printerId() {
    return getDataValue("printerId")
}

private sendIfChanged(String name, String value) {
    if (state."_last_${name}" != value) {
        state."_last_${name}" = value
        sendEvent(name: name, value: value)
    }
}

private String safeStr(val) { val != null ? val.toString() : "" }

private String formatHmsErrors(errors) {
    if (!errors) return "none"
    return errors.collect { "${it.code} (severity ${it.severity})" }.join(", ")
}
