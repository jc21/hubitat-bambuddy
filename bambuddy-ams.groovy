/**
 * BamBuddy AMS — Hubitat Driver
 *
 * Represents a single AMS (Automatic Material System) unit attached to a
 * Bambu Lab printer. Created automatically as a child device of a
 * "BamBuddy Printer" device for each AMS unit reported in the printer
 * status payload (REST poll or MQTT). Purely a status display — refresh()
 * delegates up through the printer device to the BamBuddy hub device,
 * which holds the host/API token configuration.
 *
 * Per-tray attributes are named tray<N><Field>, e.g. tray0Type, tray0Color,
 * tray0Remain — one set per tray slot reported for this AMS unit.
 */
import groovy.transform.Field

@Field static final Map TRAY_FIELDS = [
    tray_color:      "Color",
    tray_type:       "Type",
    tray_sub_brands: "SubBrand",
    tray_id_name:    "IdName",
    tray_info_idx:   "InfoIdx",
    remain:          "Remain",
    k:               "K",
    cali_idx:        "CaliIdx",
    tag_uid:         "TagUid",
    tray_uuid:       "Uuid",
    nozzle_temp_min: "NozzleTempMin",
    nozzle_temp_max: "NozzleTempMax",
    drying_temp:     "DryingTemp",
    drying_time:     "DryingTime",
    state:           "State"
]

metadata {
    definition(
        name:        "BamBuddy AMS",
        namespace:   "jc21",
        author:      "Jamie Curnow",
        description: "An AMS unit attached to a Bambu Lab printer, managed via BamBuddy https://bambuddy.cool"
    ) {
        capability "Refresh"

        // Current States — AMS unit
        attribute "humidity",      "string"
        attribute "temperature",   "string"
        attribute "isAmsHt",       "string"
        attribute "serialNumber",  "string"
        attribute "swVer",         "string"
        attribute "dryTime",       "string"
        attribute "dryStatus",     "string"
        attribute "drySubStatus",  "string"
        attribute "dryTargetTemp", "string"
        attribute "dryFilament",   "string"
        attribute "moduleType",    "string"

        // Currently loaded filament — derived from the printer-level
        // tray_now/active_extruder/ams_extruder_map fields, resolved by the
        // parent "BamBuddy Printer" device to the tray currently loaded
        // into the nozzle from this specific AMS unit (if any).
        attribute "filamentLoaded",      "string"
        attribute "loadedFilamentType",  "string"
        attribute "loadedFilamentColor", "string"

        // Current States — per tray (dynamic, created on first use):
        //   tray<N>Color, tray<N>Type, tray<N>SubBrand, tray<N>IdName,
        //   tray<N>InfoIdx, tray<N>Remain, tray<N>K, tray<N>CaliIdx,
        //   tray<N>TagUid, tray<N>Uuid, tray<N>NozzleTempMin,
        //   tray<N>NozzleTempMax, tray<N>DryingTemp, tray<N>DryingTime,
        //   tray<N>State
    }
}

// ── Capability: Refresh ────────────────────────────────────────────────────

def refresh() {
    parent?.refresh()
}

// ── Called by parent (BamBuddy Printer child device) ───────────────────────

def updateFromParent(Map ams, activeTrayId = null) {
    sendIfChanged("humidity",      safeStr(ams.humidity))
    sendIfChanged("temperature",   safeStr(ams.temp))
    sendIfChanged("isAmsHt",       safeStr(ams.is_ams_ht))
    sendIfChanged("serialNumber",  safeStr(ams.serial_number))
    sendIfChanged("swVer",         safeStr(ams.sw_ver))
    sendIfChanged("dryTime",       safeStr(ams.dry_time))
    sendIfChanged("dryStatus",     safeStr(ams.dry_status))
    sendIfChanged("drySubStatus",  safeStr(ams.dry_sub_status))
    sendIfChanged("dryTargetTemp", safeStr(ams.dry_target_temp))
    sendIfChanged("dryFilament",   safeStr(ams.dry_filament))
    sendIfChanged("moduleType",    safeStr(ams.module_type))

    updateTrays(ams.tray)
    updateLoadedFilament(ams.tray, activeTrayId)
}

private updateTrays(trays) {
    if (trays == null) return
    trays.each { t ->
        def n = t.id
        TRAY_FIELDS.each { jsonKey, suffix ->
            sendIfChanged("tray${n}${suffix}", safeStr(t[jsonKey]))
        }
    }
}

// activeTrayId is the id (within this AMS unit's own tray list) currently
// loaded into the nozzle, or null if this AMS isn't the one feeding the
// nozzle right now (or nothing is loaded at all).
private updateLoadedFilament(trays, activeTrayId) {
    def active = (trays ?: []).find { "${it.id}" == "${activeTrayId}" }
    sendIfChanged("filamentLoaded",      active ? "true" : "false")
    sendIfChanged("loadedFilamentType",  safeStr(active?.tray_type))
    sendIfChanged("loadedFilamentColor", safeStr(active?.tray_color))
}

// ── Utilities ──────────────────────────────────────────────────────────────

private sendIfChanged(String name, String value) {
    if (state."_last_${name}" != value) {
        state."_last_${name}" = value
        sendEvent(name: name, value: value)
    }
}

private String safeStr(val) { val != null ? val.toString() : "" }
