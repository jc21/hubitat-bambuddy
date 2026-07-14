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
 *
 * A "BamBuddy AMS" child device is created automatically beneath this
 * device for each AMS unit reported in the printer status payload (REST
 * poll or MQTT). AMS status updates are forwarded to those child devices.
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

        // Currently loaded filament — resolved from tray_now against every
        // known source (each AMS unit's trays, plus the external/vase-mode
        // spool) since filament can be loaded from any of them, or none.
        attribute "filamentLoaded",       "string"
        attribute "loadedFilamentType",   "string"
        attribute "loadedFilamentColor",  "string"
        attribute "loadedFilamentSource", "string"
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

    // Cache the fields needed to work out which tray is actually loaded —
    // they're reported alongside "ams" but cached independently in state
    // so a later update carrying "ams" without them (e.g. a partial MQTT
    // tick) still has the last known values to work with.
    if (d.containsKey("tray_now"))         state.trayNow        = d.tray_now
    if (d.containsKey("active_extruder"))  state.activeExtruder = d.active_extruder
    if (d.containsKey("ams_extruder_map")) state.amsExtruderMap = d.ams_extruder_map
    if (d.containsKey("vt_tray"))          state.vtTray         = d.vt_tray
    if (d.containsKey("ams"))              state.amsList        = d.ams

    // Same reasoning applies to ams — only present when the source payload
    // actually carried AMS data.
    if (d.containsKey("ams")) updateAmsChildren(d.ams)

    updateLoadedFilament()
}

def updatePlugState(String rawState) {
    sendIfChanged("switch", safeStr(rawState).toLowerCase())
}

// ── Loaded Filament (printer-level) ─────────────────────────────────────────
//
// tray_now identifies the loaded tray, but its id is only unambiguous once
// you know the source: it's a fixed id (254) for the external/vase-mode
// spool (vt_tray), or relative to whichever AMS unit is actually feeding
// the nozzle (see computeActiveAmsId) for AMS-loaded filament.

private updateLoadedFilament() {
    def trayId = normalizeTrayNow(state.trayNow)
    if (trayId == null) {
        setLoadedFilament(false, null, "none")
        return
    }

    def vt = (state.vtTray ?: []).find { "${it.id}" == "${trayId}" }
    if (vt) {
        setLoadedFilament(true, vt, "external")
        return
    }

    def activeAmsId = computeActiveAmsId()
    def ams         = (state.amsList ?: []).find { "${it.id}" == "${activeAmsId}" }
    def tray        = (ams?.tray ?: []).find { "${it.id}" == "${trayId}" }
    if (tray) {
        setLoadedFilament(true, tray, "ams${activeAmsId}")
        return
    }

    // tray_now points at a tray we haven't seen data for yet (e.g. an AMS
    // whose status hasn't arrived on this device in this cycle).
    setLoadedFilament(false, null, "none")
}

private setLoadedFilament(boolean loaded, tray, String source) {
    sendIfChanged("filamentLoaded",      loaded ? "true" : "false")
    sendIfChanged("loadedFilamentType",  safeStr(tray?.tray_type))
    sendIfChanged("loadedFilamentColor", safeStr(tray?.tray_color))
    sendIfChanged("loadedFilamentSource", source)
}

// ── AMS Child Device Management ─────────────────────────────────────────────

private updateAmsChildren(List amsList) {
    if (amsList == null) return
    def activeAmsId  = computeActiveAmsId()
    def activeTrayId = normalizeTrayNow(state.trayNow)
    amsList.each { ams ->
        def child        = ensureAmsChildDevice(ams.id)
        def isActiveUnit = (activeAmsId != null && "${activeAmsId}" == "${ams.id}")
        child?.updateFromParent(ams, isActiveUnit ? activeTrayId : null)
    }
}

// active_extruder selects an entry in ams_extruder_map (extruder -> AMS id)
// to determine which AMS unit is actually feeding the nozzle right now.
private computeActiveAmsId() {
    def extruderMap    = state.amsExtruderMap
    def activeExtruder = state.activeExtruder
    if (extruderMap == null || activeExtruder == null) return null
    return extruderMap["${activeExtruder}"]
}

// tray_now uses sentinel values (-1 / 255) to mean "no tray loaded".
private normalizeTrayNow(raw) {
    if (raw == null) return null
    def s = "${raw}"
    return (s == "-1" || s == "255") ? null : raw
}

private ensureAmsChildDevice(amsId) {
    def dni   = amsChildDni(amsId)
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("jc21", "BamBuddy AMS", dni, [
                name:  "BamBuddy AMS",
                label: "${device.displayName} AMS ${amsId}",
                data:  [printerId: printerId(), amsId: "${amsId}"]
            ])
            log.info "${device.displayName}: created AMS child device ${amsId}"
        } catch (e) {
            log.error "${device.displayName}: failed to create AMS child device ${amsId} — ${e.message}"
        }
    }
    return child
}

private String amsChildDni(amsId) {
    return "${device.deviceNetworkId}-ams${amsId}"
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
