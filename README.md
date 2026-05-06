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

### Device

Commands:
- Clear Plate
- Light on/off
- Pause/Resume/Stop print

States:
- health
- mqttStatus

State for each printer:
- printer1Connected
- printer1CurrentPrint
- printer1Name
- printer1Progress
- printer1RemainingTime
- printer1State

Capabilities
- Initialize
- Refresh
- Switch

## Installation

1. In Hubitat → Drivers Code, click the "+ Add driver" button and paste contents of `bambuddy-printers.groovy` and click Save
2. In the Devices section, create a new Virtual Device, choose driver `BamBuddy Printers`
3. After creation, in your new device's preferences, set the host of the BamBuddy instance, an API Token with these permissions:
  * Read Status
  * Manage Queue
  * Control Printer
4. Optionally add your MQTT server details (host:port, user/pass, topic prefix from BamBuddy settings)
5. Turn on Debug Logging and check the logs, turn off when everything is working
