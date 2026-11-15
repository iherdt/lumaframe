# Releasing Lumaframe

How to cut a signed release APK and publish it on GitHub. End users install that APK via the
[Install & User Guide](INSTALL.md).

## 1. Create a release keystore (once, keep it forever)

Generate a signing key. **Back it up** and keep the passwords safe — if you lose it you can't
ship signed updates that install over previous versions.

```bash
keytool -genkeypair -v \
  -keystore lumaframe-release.jks \
  -alias lumaframe \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Lumaframe, O=Lumaframe, C=US"
# you'll be prompted for the keystore + key passwords
```

Do **not** commit `lumaframe-release.jks` — `*.jks`/`*.keystore`/`keystore.properties` are git-ignored.

## 2. Build a signed release locally (optional)

Create `keystore.properties` in the repo root (git-ignored):

```properties
storeFile=/absolute/path/to/lumaframe-release.jks
storePassword=your-keystore-password
keyAlias=lumaframe
keyPassword=your-key-password
```

Then:

```bash
./gradlew :app:assembleRelease         # JDK 17–21
# -> app/build/outputs/apk/release/app-release.apk  (signed)
```

The same values can be supplied as environment variables instead of the file (these take
precedence, and are what CI uses): `LUMAFRAME_KEYSTORE` (path), `LUMAFRAME_KEYSTORE_PASSWORD`,
`LUMAFRAME_KEY_ALIAS`, `LUMAFRAME_KEY_PASSWORD`. If no keystore is configured, `assembleRelease` still
builds but the APK is **unsigned**.

Verify a build is signed:

```bash
"$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

## 3. Configure CI signing (once)

The release workflow (`.github/workflows/release.yml`) builds and publishes the APK on tag
pushes. Add these **repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `LUMAFRAME_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `LUMAFRAME_KEYSTORE_PASSWORD` | keystore password |
| `LUMAFRAME_KEY_ALIAS` | key alias (e.g. `frame`) |
| `LUMAFRAME_KEY_PASSWORD` | key password |

Encode the keystore for the first secret:

```bash
base64 -i lumaframe-release.jks | pbcopy      # macOS (clipboard)
# base64 -w0 lumaframe-release.jks            # Linux (stdout)
```

## 4. Cut a release

1. Bump the version in `app/build.gradle.kts`:
   - `versionCode` — increment by 1 every release (ordering for updates).
   - `versionName` — human label, e.g. `1.1`.
2. Commit, then tag and push:
   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```
3. The **Release APK** workflow builds the signed APK and publishes a GitHub Release with
   `Lumaframe-v1.1.0.apk` (and its `.sha256`) attached, plus auto-generated notes.

You can also trigger the workflow manually (Actions → *Release APK* → *Run workflow*); manual
runs build and upload a workflow artifact but only **tag pushes** publish a public Release.

## Notes

- Builds target **API 29** for the Portal and are distributed by sideload, not Google Play, so
  the Play "target a recent API level" lint error is disabled for release builds in
  `app/build.gradle.kts`.
- The release APK is **not** `debuggable` and is signed with your stable key, so users can update
  cleanly from one version to the next.
