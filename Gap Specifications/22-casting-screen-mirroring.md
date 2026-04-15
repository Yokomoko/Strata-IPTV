# 22 - Casting / Screen Mirroring Support

## Current State

The app runs exclusively on Fire Stick and has no casting or mirroring functionality. There is no `CastContext`, `MediaRouter`, or `RemotePlayback` integration. The `build.gradle.kts` does not include the Google Cast SDK or any casting library. The player renders directly to the local `PlayerView` surface.

Fire Stick is itself a casting target (via Amazon's native Miracast/AirPlay support), but the app does not participate in any casting protocol as a sender.

## Gap

While Fire Stick is primarily a receiver device, users may want to:
- Cast from the Fire Stick app to a second TV or Bluetooth audio receiver using HDMI CEC/ARC (already handled by Fire OS natively).
- In the future, use a companion phone app or web interface to control playback or send a stream URL to the Fire Stick app.
- Mirror the Fire Stick output to other devices in multi-room setups.

The primary casting use case (phone-to-Fire Stick) is handled by Fire OS system-level features (e.g. screen mirroring, AirPlay on newer models). The app does not need to implement a casting SDK for the current single-device target.

## User Story

> As a household viewer, I want to send a stream URL from my phone to the Strata TV app on Fire Stick so that I can start watching without navigating the TV interface.

## Acceptance Criteria

1. The app registers a deep-link intent filter that accepts stream URLs (e.g. `strata://play?url=...&title=...&type=live`).
2. A companion feature (web page or QR code) displayed in Settings allows the user to open a stream URL on the Fire Stick by scanning a code on their phone.
3. When a deep-link is received while the app is running, it opens the player with the specified stream.
4. When a deep-link is received while the app is closed, it launches the app and navigates directly to the player.
5. The app exposes a simple local-network REST endpoint (optional, P4) that accepts `POST /play` with a stream URL, enabling home-automation integration (e.g. from a Raspberry Pi or smart home system).

## Technical Approach

1. **Deep-link intent filter**: Add an `<intent-filter>` to the main Activity in `AndroidManifest.xml`:
   ```xml
   <intent-filter>
       <action android:name="android.intent.action.VIEW" />
       <category android:name="android.intent.category.DEFAULT" />
       <data android:scheme="strata" android:host="play" />
   </intent-filter>
   ```
2. **Intent handling**: In the Activity's `onCreate` and `onNewIntent`, parse the deep-link URI and convert it to `PlayerArgs`. Pass to the nav state via a shared `DeepLinkHandler` injected by Hilt.
3. **QR code display**: In the Settings screen, show a QR code encoding the device's local IP + a URL template (e.g. `http://192.168.1.X:8080/play?url={url}`). Use a simple QR code composable library (e.g. `io.github.alexzhirkevich:qrose`).
4. **Local REST endpoint** (optional, P4): Embed a lightweight HTTP server (e.g. Ktor embedded or NanoHTTPD) that accepts `POST /play` with JSON body `{ "url": "...", "title": "...", "type": "live|movie|show" }`. Rate-limited to local network only via IP check. Secured with a PIN displayed in Settings.
5. **Google Cast SDK**: Not recommended for Fire TV (conflicts with Amazon's ecosystem, adds ~2 MB to APK, and Fire OS does not support the Cast framework). Defer unless a phone companion app is built.

## Priority

**P4 -- Future** (Fire Stick is a receiver device; deep-linking covers the main use case; full casting requires a companion app that does not yet exist)

## Effort Estimate

**2-3 days** (deep-link + QR code only; local REST server adds 2-3 more days)
- 0.5 day: deep-link intent filter + URI parsing
- 0.5 day: DeepLinkHandler + nav state integration
- 0.5 day: QR code display in Settings
- 1 day: testing deep-links from phone browser and adb
- (Optional) 2-3 days: Ktor embedded server + PIN security + local network restriction
