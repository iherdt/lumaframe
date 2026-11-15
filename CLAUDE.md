# CLAUDE.md

Guidance for AI assistants (and humans) working in this repo.

## What this is

**Lumaframe** (repo: Lumaframe, app id `com.iherdt.lumaframe`) — an Android slideshow /
screensaver for the **Meta Portal** (Android 10 / API 29) that shows Google Photos and iCloud
shared albums, plus photos pushed from a phone over Wi‑Fi (the "AirDrop for Portal" drop). App is
100% Kotlin — Jetpack Compose for the settings screens, Android Views for the slideshow/scanner.
No cloud backend; the only server is an on-device, LAN-only photo-drop HTTP server (see Architecture).

## Build & run

```bash
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Requires **JDK 17–21** (a newer/GraalVM JDK can fail AGP's `JdkImageTransform`; set `JAVA_HOME`)
  and an Android SDK (`ANDROID_SDK_ROOT`, or `local.properties` with
  `sdk.dir=` — git-ignored, never commit it).
- Versions are pinned in `app/build.gradle.kts` (compileSdk 36, minSdk 28, targetSdk 29).
- Set/clear the album for testing without rebuilding:
  ```bash
  adb shell am broadcast -n com.iherdt.lumaframe/.ConfigReceiver --es url "https://photos.app.goo.gl/XXXX"
  adb shell am broadcast -n com.iherdt.lumaframe/.ConfigReceiver --es url ""   # clear
  ```
- See README for the screensaver-install commands.

> `build.sh` is a **legacy** dependency-free pipeline (Java only). It predates the Compose
> migration and will NOT build the current app. Use Gradle.

## Architecture

- **`FrameDreamService`** — the registered screensaver; a thin trampoline that launches
  `SlideshowComposeActivity` and finishes. (Portal's ambient manager kills interactive in-dream
  windows, so the slideshow runs as a normal foreground Activity instead — see the README
  "trampoline" note.)
- **`SlideshowComposeActivity` + `SlideshowController`** (Kotlin) — the actual full-screen
  slideshow and the dream target. The Activity is Compose (`setContent`) and hosts the
  view-based `SlideshowController` via `AndroidView` (the controller's crossfade / Ken Burns
  `ValueAnimator` / `Canvas` shimmer are imperative custom animation, best kept as Views); the
  Activity owns the album fetch/cache/refresh + night-dimming logic. Features: crossfade,
  clock/weather overlay, captions, Ken Burns, face-aware framing, ambient color, auto-enhance,
  night dimming, smart shuffle, On This Day, portrait pairing.
  **Photos off** (`photos_off`, the button in Settings) keeps that Activity as the screensaver
  but shows its clock-only screen instead of photos: clock and weather, clock alone, or nothing
  (`photos_off_show`). With nothing to show the Activity drops `FLAG_KEEP_SCREEN_ON`, and
  `FrameDreamService` marks its dream not screen-bright and, once asleep, ends without opening
  anything; measured on a Portal+, the system then puts the display to sleep instead of waking
  it (a dream window that keeps the screen on makes it wake). Waking lands on the black screen.
- **`SettingsActivity`** (Kotlin/Compose) — the home-icon ("Lumaframe") setup/settings screen: a
  two-column "Set up" / "Customize" layout (tuning grouped into collapsible sections). Hands off to
  `PhotosActivity` (scanner / manual link entry) and `UploadsActivity` (phone-pushed photos grid),
  and shows the phone-drop QR.
- **`PhotosActivity`** (Kotlin, Android Views) — camera QR scanner + manual entry (ZXing, vendored jar).
  The scanner's pieces: **`QrCamera`** (Camera2 luma stream, frames copied off the camera thread
  and decoded on a worker), **`QrSearch`** in `QrDecode.kt` (what to look at in each frame),
  **`QrFilters`** (the picture operations: cut out, enlarge, sharpen, `shrinkBright`, `curve`),
  **`QrNative`** (the decoder: zxing-cpp through its Android AAR, whole grey frames in, text and
  position out) and **`ViewfinderView`** (live picture with a box around a located code). Portals
  whose camera needs the legacy API keep the old Camera path, chosen per device by `QrTuning`.
  Measured on a Portal+ with real recorded frames:
  - Apps get one fixed-focus 720p camera that ignores zoom, exposure compensation and metering
    regions; the 12 MP sensor is reserved for preinstalled apps. Do not add camera steering.
  - A phone screen is far brighter than the room the camera exposes for, so the light dots of a
    Google Photos album code bloom into each other. `QrSearch.scanWhole` therefore reads each
    frame sharpened with the bright parts shrunk (`shrinkBright`), plus the plain frame, plus one
    rotating extra look. Java ZXing read none of those frames in any form (its finder cannot see
    the bloomed ring corners); it remains only as the fallback path `scanPatches` (with `QrGray`)
    for JVM tests and for a device where the native library does not load.
  - A phone at 10 to 20 cm puts the code at three to six camera pixels per module, so a pixel of
    bloom is a large part of a module. Every frame therefore also gets a *close look*: the square
    around the tracked code (else the middle of the frame) sharpened, enlarged three times and
    shrunk there in thirds of a pixel, the strength rotating, one turn in five with the tone
    `curve` instead of sharpening. On simulated frames this look alone read every frame the
    whole-frame looks read and more; replayed on a Portal+ the scanner went from 12 to 24 of 32
    frames at about 90 ms a frame instead of 60. Tried on the same frames and not worth their
    weight: the WeChat detector with its super-resolution network (one extra frame in 300),
    Richardson-Lucy deconvolution, top-hat and local contrast filters, bicubic instead of
    bilinear enlarging, and a tighter square enlarged four times.
  - Change the plan only against frames, recorded ones where possible: `adb shell am broadcast -n
    com.iherdt.lumaframe/.ConfigReceiver --ez scan_debug true` makes the scanner save about one
    frame a second to the app's external files folder; copy frames into its `replay` subfolder and
    start `.PhotosActivity --ez replay true` to run them through the search on the device (results
    in logcat as `QR replay`). Switch `scan_debug` off afterwards; the frames are pictures of the room.
    Without recorded frames, `tools/scan-sim/scansim.py` makes stand-ins from a screenshot of a
    code (distance, screen brightness above what the sensor holds, lens blur, noise): `score`
    compares the looks on a desk, `frames` writes PGMs for the same replay. It is a model, not
    the camera: it agrees with the recorded frames on what fails and why, not on exact strengths,
    so keep the whole-frame looks that read recorded frames.
  `QrSearchTest` covers the plan and the fallback's decode floor on synthetic frames; keep it passing.
- **`PhotoProvider` / `PhotoSources`** — provider abstraction + registry. `PhotoSources.matches(url)`
  / `fetch(url)` route a link to the right provider and return a shared `Album` (title + slides).
  Add a new provider by implementing `PhotoProvider` and listing it in `PhotoSources`.
- **`GooglePhotosSource`** — provider that scrapes the public shared-album page (the Library API is gone).
- **`ApplePhotosSource`** — provider for public iCloud Shared Albums via Apple's `sharedstreams`
  web API (`webstream` → `webasseturls`, handling the 330 partition redirect).
- **`ImageLoader`** — background decode + disk/memory image cache. Also decodes locally-pushed
  photos (absolute `filesDir/uploads` paths) and applies their EXIF orientation (androidx).
- **`AlbumCache`** — shared persistence of the fetched photo list + title in `SharedPreferences`,
  used by both the slideshow and the settings preview.
- **Add photos from a phone ("AirDrop for Portal")** — a phone on the LAN scans an on-screen QR,
  its browser posts photos to the frame, and they persist in the slideshow rotation:
  - **`DropServerService`** — foreground service owning the LAN server (started from the slideshow,
    Settings, and on boot); broadcasts `ACTION_UPLOAD` (package-scoped) on each upload.
  - **`LocalDropServer`** — embedded HTTP server (raw `ServerSocket` + hand-rolled multipart, no HTTP
    lib): serves the mobile upload page (`GET /`) and accepts `POST /upload`, plus
    `POST /album` for a pasted album link (validated through `PhotoSources`, same token gate). **Plaintext + LAN-only
    by design**, token-gated, image-validated (magic bytes), size/connection-capped.
  - **`LocalUploads`** — persistent `filesDir/uploads` store (the "keep") with count/byte caps.
  - **`DropAuth`** (per-install token carried in the QR), **`DropServerStatus`** (bound port + LAN
    URL), **`QrCodes`** (ZXing QR encode).
  - **`UploadsActivity`** (Compose) — full-screen `LazyVerticalGrid` to view/delete pushed photos;
    reached from `SettingsActivity`'s "Manage photos".
- **`ConfigReceiver`** — `SharedPreferences` keys + the exported ADB config receiver.
- **`Ui` (Views) / `Theme.kt` (Compose)** — the shared Portal dark palette + Inter typography.

## Origin

Lumaframe is based on Frame (`Ishtiaqhossain/portal-frame`, MIT). Keep the original copyright line
in `LICENSE` and the credit in `NOTICE` and the README.

## Conventions & gotchas

- All Kotlin — no Java. Match surrounding style: idiomatic Kotlin + Android Views for the
  view-based pieces, idiomatic Compose for new UI. Keep the Portal palette (`Ui` / `PortalColors`).
- **Outbound networking is HTTPS-only** and size-capped; album fetch and image download reject
  non-HTTPS URLs (`network_security_config.xml`). Don't loosen this. The inbound photo-drop server
  (`LocalDropServer`) is the deliberate exception: plaintext + LAN-only + token-gated **by design**
  (it's a server socket, which the network-security config doesn't govern). See `SECURITY.md`.
- **Static analysis runs in CI** (`lintDebug` + `detekt` + `ktlint` on every push/PR): new findings
  fail, existing ones are grandfathered by baselines (`app/detekt-baseline.xml`,
  `app/config/ktlint/baseline.xml`; detekt config at `config/detekt/detekt.yml`). Run
  `./gradlew lintDebug detekt ktlintCheck` and `./gradlew ktlintFormat` before pushing.
- The scraper regexes in `GooglePhotosSource` are intentionally tight and anchored to
  `lh3.googleusercontent.com`; it's unofficial and must fail closed (fall back to bundled samples).
- `ConfigReceiver` is exported (for ADB) but validates the album URL — keep that validation.
- Do **not** commit secrets, `local.properties`, keystores, or build output (see `.gitignore`).
- The codebase is fully Kotlin and every Activity is Compose (`setContent`). `SettingsActivity`
  is native Compose; `SlideshowComposeActivity` (the live dream target) is Compose hosting the
  imperative `SlideshowController` View stack via `AndroidView`, and `PhotosActivity` (scanner)
  is still a view-based screen. `SlideshowController`/`Ui` remain Android Views by design (custom
  animation / canvas drawing); that's the intended end state, not a pending migration.
