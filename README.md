# control-panel-addon

Quick Settings tile for **USB tethering** (USB 共享网络), an add-on for the Hermes Android client.

- `UsbTetherTileService.kt`: the tile. Root: toggles tethering. No root: opens the system tethering page.
- `UsbTether.kt`: state check (rndis*/usb* interface up) and root helpers (`su -c 'svc usb setFunctions ...'`).
- `ic_usb_tether.xml`: monochrome tile icon.
- `integration/`: the manifest service entry and the English / 中文 strings to add to the host app.

Requires root for toggling. Without root the tile opens the system tethering settings.
Tile must be added by the user in the Quick Settings edit panel.

Status: builds inside the Hermes Android client (assembleDebug). Runtime behaviour on device not yet verified.
