# Music Box app on iPhone: what it would take

Nov 25, 2026 · @Syncro

The Music Box app is Android-only and the repo's instructions cannot build an iPhone version. An iOS app would be a separate build, though the connection code could carry over.

## Why the APK won't run on an iPhone

An APK is Android's app format. iPhones only run apps built for iOS, so the APK won't even install.

## What an iPhone version would take

The current build instructions don't help, because almost every step is Android-specific: Android Studio, the Android SDK, adb, USB debugging and APK signing.

| Part | What happens to it |
| --- | --- |
| Connection code | Can probably be reused. It is plain Kotlin with no Android dependencies, so Kotlin Multiplatform can compile it for iOS, tests included |
| Screens | Must be rebuilt, in SwiftUI or by porting to Compose Multiplatform. Expect this to be most of the work |
| Build tools | Entirely different. An iOS build needs a Mac with Xcode, because Apple's tools don't run on Windows |

## Getting it onto other people's iPhones

This is the bigger obstacle. There is no equivalent of handing someone an APK file.

- With a free Apple account, an app installed from your own Mac stops working after 7 days and must be reinstalled.
- Sharing with friends needs a paid Apple Developer membership (US$99 a year), then distribution through TestFlight or the App Store.

**The practical option for iPhone owners:** the Pi runs standard MPD, so any existing iOS MPD client will control it with nothing to build. Rigelian and MPDluxe are two examples; check what is current in the App Store. They connect to the Pi's address on port 6600, the same as the Android app.

## Suggested changes to the build instructions

1. Say near the top that the app is Android-only, and point iPhone users to an existing MPD client, so nobody clones the repo expecting an iOS build.
2. Don't hard-code the build-tools version. The `apksigner` path includes `build-tools\36.0.0`, which will differ on other computers; say to use whichever version folder is there.
3. The Mac and Linux note in section 3 means building the Android app on those computers. The Android-only sentence from point 1 stops it being read as iOS support.
