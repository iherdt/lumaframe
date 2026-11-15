# Lumaframe

A photo screensaver for the Meta Portal. It plays your Google Photos or iCloud shared albums, and
photos pushed straight from a phone over Wi-Fi, whenever the Portal is idle, with a clock, captions,
gentle motion and ambient color.

> App id `com.iherdt.lumaframe` · Target: Meta Portal (Android 10, API 29) · Not affiliated with Meta Platforms, Inc.

![Slideshow screensaver](docs/screenshots/01-slideshow.png)

## Where it comes from

Lumaframe is based on [Frame (portal-frame)](https://github.com/Ishtiaqhossain/portal-frame) by
Ishtiaq Hossain, used under the MIT License. The slideshow, the album providers, the settings and
the phone photo drop are that project's work. Lumaframe changes how albums get onto the device:

- **A scanner built for the Portal's camera.** Apps on a Portal+ get one fixed-focus 720p camera
  that ignores zoom and exposure controls, so the scanner works on the picture instead. Frames are
  decoded on a worker thread, about 10 a second instead of 4, each looked at several ways, with
  [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) as the decoder.
- **Reads Google Photos album codes off a phone screen.** Those codes are light dots on a dark
  sheet with ring corners, and a phone screen is so much brighter than the room that the dots
  smear into each other in the camera picture. The scanner shrinks the bright parts back before
  decoding. On frames recorded from a real Portal+ the previous scanner read none; this one reads
  most of the frames in which the code is in view. Hold the phone 15 to 20 cm from the camera and
  turn its brightness down if it does not read.
- **A close look for small, soft codes.** At that distance a module of the code is only three to
  six camera pixels wide. The scanner cuts out the square where the code is, sharpens it, enlarges
  it three times and corrects the glare there in thirds of a pixel, at a few strengths in turn,
  one of them a steep tone curve that keeps only the cores of the dots. On simulated frames
  across distance, screen brightness and blur, replayed on a Portal+, this doubled the frames that
  read (24 of 32 against 12). `tools/scan-sim` makes such frames from a screenshot of a code.
- **A viewfinder that tells you what it sees.** A live picture with a box around the code once it
  is found, and hints to hold still or move closer.
- **Album links from your phone.** The scanner screen shows a QR for your phone. The page it opens
  takes a pasted Google Photos or iCloud album link and adds it to the frame, with no camera
  scanning on the Portal at all.

## Install

The Portal has no app store, so the app is installed once from a computer over USB with `adb`.
The [Install & User Guide](INSTALL.md) walks through it: install the APK, add your album, and turn
Lumaframe on as the screensaver.

Build the APK yourself (see below), or take one from the
[Releases page](https://github.com/iherdt/lumaframe/releases) when a release is published.

Albums can also be set over ADB:

```bash
adb shell am broadcast -n com.iherdt.lumaframe/.ConfigReceiver --es url "https://photos.app.goo.gl/XXXX"
adb shell am broadcast -n com.iherdt.lumaframe/.ConfigReceiver --es remove_url "https://photos.app.goo.gl/XXXX"
```

So can photos off, and what shows meanwhile (`clock_weather`, `clock` or `nothing`):

```bash
adb shell am broadcast -n com.iherdt.lumaframe/.ConfigReceiver --ez photos_off true
adb shell am broadcast -n com.iherdt.lumaframe/.ConfigReceiver --es photos_off_show clock
```

## Features

- **Shared albums**: plays Google Photos or iCloud shared albums; new photos appear automatically.
- **Photos from a phone**: scan the on-screen QR with any phone on the same Wi-Fi, pick photos in
  the browser, and they join the rotation. No app, no account, no cloud.
- **On-device setup**: scan a QR, paste a link, or hand the link over from your phone.
- **Overlays**: clock and weather, photo date captions, shuffle, adjustable timing and transitions.
- **Motion and light**: side-by-side portraits, pan and zoom, auto-enhance, ambient color, night
  dimming and "On This Day" memories, each switchable.
- **Photos off**: one button in setup stops the photos. The screensaver then shows the clock and
  weather, the clock alone, or nothing at all, in which case the Portal's display goes to sleep
  until it is touched.
- **Touch**: swipe to change photo, tap to dismiss, long-press to open setup.

## For developers

All Kotlin: Jetpack Compose settings, Android Views for the slideshow and scanner, built with Gradle.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk, installs as com.iherdt.lumaframe.dev
./gradlew testDebugUnitTest    # scanner decode tests on synthetic frames
./gradlew lintDebug detekt ktlintCheck
```

Requirements: JDK 17 to 21 and an Android SDK (`ANDROID_SDK_ROOT`, or a git-ignored
`local.properties` with `sdk.dir=`). The debug build carries its own app id, so it installs next
to a release build.

See [CONTRIBUTING.md](CONTRIBUTING.md) for layout and conventions, [RELEASING.md](RELEASING.md)
for signed releases, and [CLAUDE.md](CLAUDE.md) for the architecture.

## License and security

Released under the [MIT License](LICENSE). Third-party attributions are in [NOTICE](NOTICE).
[SECURITY.md](SECURITY.md) describes the trust model and how to report issues.
