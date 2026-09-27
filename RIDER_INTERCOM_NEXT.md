# Rider Intercom - Next

## Android 11 Bluetooth audio routing

Android 12+ exposes selectable communication devices through `AudioManager.availableCommunicationDevices`.
Android 11 and lower use the legacy Bluetooth Headset/HFP + Bluetooth SCO APIs.

The Android 11 UI therefore lists connected classic Bluetooth Headset profile devices separately and uses the global legacy SCO route when one is selected.

The audio service continues to run in the foreground while the screen is off/backgrounded and stops when the app task is removed from Recents.
