# Cameo

An edge-bar app launcher for Nothing Phone that opens up to 10 apps as floating pop-up windows.

**See it in action:** https://m0n0t0ne0ne.github.io/cameo/

## Features

- A slim bar on the screen edge that expands into up to 10 app shortcuts
- Opens each app as a Nothing OS pop-up window instead of full screen
- With Shizuku: brings a backgrounded app back as a pop-up, clears Nothing's floating bubbles and
  closes apps
- Icon packs, fonts and bar styling in the settings screen
- Starts again after a reboot

## Requirements

- A Nothing Phone on Nothing OS with the pop-up view feature, Android 12 (API 31) or newer
- [Shizuku](https://shizuku.rikka.app/) running, with Cameo allowed to use it, for task management
- Cameo's accessibility service turned on under Settings › Accessibility › Installed apps. It is
  used only to draw the bar on top of other apps; it does not read the screen. Without it, Cameo
  falls back to an overlay with a notification.

## Build

You need JDK 17+ and an Android SDK with platform 36. Put the SDK path in `local.properties`
(`sdk.dir=...`), or let Android Studio create it.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
