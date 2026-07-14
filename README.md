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

Capabilities
- Refresh
- Switch

## Installation

1. In Hubitat → Drivers Code, click the "+ Add driver" button and paste contents of `bambuddy.groovy` and click Save
2. Repeat for `bambuddy-printer.groovy` — this is the child driver used for each discovered printer
3. In the Devices section, create a new Virtual Device, choose driver `BamBuddy Printers`
4. After creation, in your new device's preferences, set the host of the BamBuddy instance, an API Token with these permissions:
  * Read Status
  * Manage Queue
  * Control Printer
5. Optionally add your MQTT server details (host:port, user/pass, topic prefix from BamBuddy settings)
6. Turn on Debug Logging and check the logs, turn off when everything is working
7. A child device will be created automatically for each printer BamBuddy reports — use these for per-printer commands and states
