# Hubitat Driver for [BamBuddy](https://bambuddy.cool)

A custom Hubitat Elevation driver for monitoring and controlling your
Bambu 3D printers through BamBuddy directly from your home automation platform.

My main use case for this is to have a physical button on my printer that
fires the Clear Plate command in BamBuddy. However it's the basis to do
so much more. PR's are welcome :)

If you don't want to use BamBuddy but you came here looking for something more
native with your Bambu printer, check out
[jonnyborbs Bambu Lab 3D Printer](https://github.com/jonnyborbs/hubitat-bambu-printers) driver.

## Features

- Connects to your BamBuddy instance via REST API
- Optionally connects to a MQTT broker if you've got one setup
- Automatically creates a child device for each printer discovered via the API
- Automatically creates a child device for each AMS unit attached to a printer

### Parent Device (BamBuddy Printers)

Commands:
- Refresh Data
- Connect/Disconnect MQTT

States:
- health
- mqttStatus

Capabilities
- Initialize
- Refresh

### Child Device (BamBuddy Printer)

One is created automatically per printer discovered via the API.

Commands:
- Clear Plate
- Pause/Resume/Stop print
- On/Off (Connected smart plug for printer)
- Light On/Off (chamber light)

States:
- printerName
- connected
- state
- currentPrint
- progress
- remainingTime
- light
- hmsError
- filamentLoaded, loadedFilamentType, loadedFilamentColor, loadedFilamentSource —
  the filament currently loaded into the nozzle, resolved from `tray_now`
  against every possible source: any attached AMS unit's trays, or the
  external/vase-mode spool. `loadedFilamentSource` is `"ams<N>"`,
  `"external"`, or `"none"`.

Capabilities
- Refresh
- Switch

### Grandchild Device (BamBuddy AMS)

One is created automatically per AMS unit reported for a printer (in the
`ams` array of the printer status, via REST poll or MQTT). Not created at
all for printers with no AMS attached.

States:
- humidity, temperature, isAmsHt
- serialNumber, swVer, moduleType
- dryTime, dryStatus, drySubStatus, dryTargetTemp, dryFilament
- filamentLoaded, loadedFilamentType, loadedFilamentColor — the tray
  currently loaded into the nozzle from this AMS unit, if any. Derived from
  the printer's `tray_now`/`active_extruder`/`ams_extruder_map` fields, so
  at most one AMS unit on a printer reports a loaded filament at a time.
- Per tray (one set of attributes per tray slot reported, named
  `tray<N><Field>`): `tray0Color`, `tray0Type`, `tray0SubBrand`,
  `tray0IdName`, `tray0InfoIdx`, `tray0Remain`, `tray0K`, `tray0CaliIdx`,
  `tray0TagUid`, `tray0Uuid`, `tray0NozzleTempMin`, `tray0NozzleTempMax`,
  `tray0DryingTemp`, `tray0DryingTime`, `tray0State` (and so on for each
  tray index reported)

Capabilities
- Refresh (delegates up to the printer device, then the BamBuddy hub device)

## Installation

1. In Hubitat → Drivers Code, click the "+ Add driver" button and paste contents of `bambuddy.groovy` and click Save
2. Repeat for `bambuddy-printer.groovy` — this is the child driver used for each discovered printer
3. Repeat for `bambuddy-ams.groovy` — this is the grandchild driver used for each AMS unit attached to a printer
4. In the Devices section, create a new Virtual Device, choose driver `BamBuddy Printers`
5. After creation, in your new device's preferences, set the host of the BamBuddy instance, an API Token with these permissions:
  * Read Status
  * Manage Queue
  * Control Printer
6. Optionally add your MQTT server details (host:port, user/pass, topic prefix from BamBuddy settings)
7. Turn on Debug Logging and check the logs, turn off when everything is working
8. A child device will be created automatically for each printer BamBuddy reports — use these for per-printer commands and states
9. If a printer has an AMS attached, a child device of that printer will be created automatically for each AMS unit reported — use these for filament/tray status
