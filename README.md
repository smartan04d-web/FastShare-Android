# FastShare (Android 10+)

Offline Android-to-Android file transfer using a manually enabled Wi-Fi hotspot. Java / Android Studio / Gradle.

## Build
1. Open this folder in Android Studio (JDK 17; Android SDK 35).
2. Allow Gradle sync to download Android Gradle Plugin and libraries.
3. Build > Build APK(s); install the debug APK on both Android phones.
4. For command-line builds, use a locally installed Gradle 8.9 (`gradle assembleDebug`) or generate a wrapper with `gradle wrapper --gradle-version 8.9` first. This ZIP does not contain a Gradle wrapper JAR.

## Transfer
1. Receiver: enable **mobile hotspot** in Android Settings (mobile data can remain OFF). Open FastShare and tap RECEIVE.
2. Sender: connect to receiver's hotspot in Android Wi-Fi settings. No internet is required; ignore "No internet" warning.
3. Sender: choose a file, scan the receiver's QR, then tap Send. If QR isn't available, manually enter the receiver hotspot IPv4 address shown in the app.
4. Keep both apps in foreground and screens awake until transfer completes. The receiver saves verified files to **Downloads**.

## Limitations / security
- This is an MVP, not a production-hardened file-sharing app. Anyone on the hotspot who knows the IP/port can send files to the receiver while it is listening. Use a strong hotspot password and only accept trusted peers. QR contains a local IP, not an authentication secret.
- TCP stream is not end-to-end encrypted at application layer; WPA2/WPA3 hotspot security protects the wireless link.
- Android hotspot IP discovery varies by manufacturer; some devices show a non-hotspot interface. Verify the IP or use manual entry.
- One incoming transfer at a time; no automatic resume/background service or multi-file folder transfer.
- Android media provider may rename duplicates. Unknown-size document providers cannot be sent.
- Network timeouts apply to idle operations; huge slow transfers may need longer timeouts.
- Camera access is requested by the scanner library when scanning.
- No internet is used by the app for transfers, but initial Gradle dependency download needs internet on the development computer.
