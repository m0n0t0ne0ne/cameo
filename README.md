# Cameo

An edge-bar app launcher for Nothing Phone that opens up to 10 apps as floating pop-up windows.

**See it in action:** https://m0n0t0ne0ne.github.io/cameo/

## Features

- A slim bar on the screen edge that expands into up to 10 app shortcuts
- Opens each app as a Nothing OS pop-up window instead of full screen
- With Shizuku: brings a backgrounded app back as a pop-up, clears Nothing's floating bubbles and
  closes apps
- Icon packs, fonts and bar styling in the settings screen
- Reorder the dock by dragging; swipe an app left to remove it, with Undo
- Starts again after a reboot

## Requirements

- A Nothing Phone on Nothing OS with the pop-up view feature, Android 12 (API 31) or newer
- [Shizuku](https://shizuku.rikka.app/) running, with Cameo allowed to use it, for task management
- Cameo's accessibility service turned on under Settings › Accessibility › Installed apps. It is
  used only to draw the bar on top of other apps; it does not read the screen. Without it, Cameo
  falls back to an overlay with a notification.

## Install

1. On your phone, download `Cameo.apk` from the [latest release](https://github.com/m0n0t0ne0ne/cameo/releases/latest) and open it. Allow your browser to install apps if Android asks.
2. Open Cameo, pick up to ten apps and tap Done.
3. Turn on Settings › Accessibility › Installed apps › Cameo. If the switch is greyed out ("Restricted
   setting"), go to Settings › Apps › Cameo › ⋮ › Allow restricted settings first.
4. Optional: start [Shizuku](https://shizuku.rikka.app/) with Wireless debugging, then tap Allow in
   Cameo's Shizuku section. Start Shizuku again after each restart. With Shizuku connected, the
   Remove notifications button under Bar mode turns on Cameo's accessibility service for you.

The [setup guide](https://m0n0t0ne0ne.github.io/cameo/#setup) explains every switch.

## Build

You need JDK 17+ and an Android SDK with platform 36. Put the SDK path in `local.properties`
(`sdk.dir=...`), or let Android Studio create it. Release builds are signed with the key named in
`~/.android/cameo-release.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`),
which isn't in this repository. Without that file the release APK comes out unsigned; use
`./gradlew assembleDebug` for a debug build instead.

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
