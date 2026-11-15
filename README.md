# HearAssist

A home-screen widget for real-time hearing assistance. Tap it once and the phone's microphone plays into your wired earphones, with an adjustable equalizer. Tap it again to turn it off.

## Build the APK

**Option A: Android Studio (simplest)**
1. Unzip, then in Android Studio choose **File → Open** and pick the `HearAssist` folder.
2. Let Gradle sync, plug in your phone (with USB debugging on) and press **Run ▶**.

**Option B: no Android Studio (GitHub builds it for you)**
1. Create a new GitHub repository and upload the contents of this folder.
2. Open the repository's **Actions** tab. The *Build APK* workflow runs on its own.
3. When it finishes, download **HearAssist-apk** from the run page, unzip it, copy the `.apk` to your phone and open it. You'll need to allow "Install unknown apps".

**Option C: command line** (Android SDK installed): `./gradlew assembleRelease`

## Using it
1. Open the app once and allow **Microphone** and **Notifications**.
2. Long-press the home screen → **Widgets** → **HearAssist**, and drag it onto the home screen.
3. Plug in wired earphones (3.5 mm jack, or USB-C earphones/adapter), turn media volume **down**, then tap the widget.
4. Change the sound from the app or from the notification. Slider changes apply live.

## How it works
| Part | File |
|---|---|
| Widget (shows ON/OFF, one-tap toggle) | `HearingWidget.kt`, `res/layout/widget_hearing.xml` |
| Invisible tap handler (guarantees Android allows mic start) | `ToggleActivity.kt` |
| Foreground "microphone" service + notification with Turn off | `HearingService.kt` |
| Low-latency mic → earphones loop | `AudioEngine.kt` |
| DSP: 80 Hz rumble filter → boost → 6-band EQ → safety limiter | `SoundProcessor.kt` |
| Settings & presets | `HearingSettings.kt`, `MainActivity.kt` |

**Latency:** runs at the phone's native sample rate and burst size, uses the low-latency output path with a 2-burst buffer, skips the phone's call-style audio processing, and runs on an urgent-priority audio thread. Typical total delay is about 20–40 ms depending on the phone.

**Safety:** it only starts with wired earphones connected and stops immediately if they're unplugged, so the mic never plays through the loudspeaker (which would squeal). A limiter caps the maximum loudness. Bluetooth is deliberately not supported because its delay (150 ms+) makes live listening echo.

*This is a personal listening aid, not a medical hearing aid.*
