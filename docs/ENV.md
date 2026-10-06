# Build Environment — LibrePocket

## 1. JDK

Android Gradle Plugin 8.x must run on **JDK 17 or 21** (this project
uses JDK 21). Install one and export `JAVA_HOME`:

```sh
# Debian/Ubuntu
sudo apt-get install -y openjdk-21-jdk-headless
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java -version   # expect 21.x
```

Gradle itself is bootstrapped through the checked-in wrapper (`gradlew`);
no system Gradle install is required.

## 2. Android SDK

Point the build at your SDK (the wrapper reads `ANDROID_HOME`,
falling back to `ANDROID_SDK_ROOT` and `local.properties`'s `sdk.dir`):

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Required SDK pieces (install once via `sdkmanager`, **no SDK reinstall needed**):

```sh
sdkmanager "platforms;android-36" "platforms;android-37.0" "build-tools;36.0.0"
```

Why these three:

| Piece | Reason |
|-------|--------|
| `platforms;android-37.0` | `compileSdk = 37` (Android 17) |
| `platforms;android-36` | `targetSdk = 36` (Play 2026-08-31 minimum) |
| `build-tools;36.0.0` | `aapt`/dexer used by AGP 8.13 |

Verify:

```sh
ls "$ANDROID_HOME/platforms"   # expect android-36 and android-37.0
./gradlew :app:assemblePlayDebug :app:assembleFossDebug :app:assembleGithubDebug
```

## 3. Permission assertion (three flavors)

After building, confirm each APK carries no high-risk permissions
(locked decision: no SMS / storage-manager / VPN permission in any
flavor; foss/github each add ONLY the accessibility automation service,
default off):

```sh
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
$AAPT dump permissions app/build/outputs/apk/play/debug/app-play-debug.apk
# must NOT list READ_SMS, RECEIVE_SMS, MANAGE_EXTERNAL_STORAGE,
# BIND_ACCESSIBILITY_SERVICE, or BIND_VPN_SERVICE
$AAPT dump permissions app/build/outputs/apk/foss/debug/app-foss-debug.apk
$AAPT dump permissions app/build/outputs/apk/github/debug/app-github-debug.apk
# foss/github MUST also NOT list the above (a11y comes from the flavor
# manifest overlay service, default off — see CAPABILITY_MATRIX §2)
```

Or run both policy gates (mirrors `HardeningPolicy`):

```sh
scripts/play_policy_check.sh app/build/outputs/apk/play/debug/app-play-debug.apk
scripts/play_policy_check.sh --foss app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

## 4. Flavors and signing

| Flavor | `BuildConfig.FLAVOR` | applicationId | Ships via |
|--------|----------------------|---------------|-----------|
| `play` | `play` | `dev.librepocket.agent` | Google Play (AAB) |
| `foss` | `foss` | `dev.librepocket.agent.foss` | F-Droid (F-Droid builds and signs; only that build is official) |
| `github` | `github` | `dev.librepocket.agent.github` | GitHub Releases (developer release key, APK only) |

- Read the flavor at runtime via `BuildConfig.FLAVOR` (`play` / `foss` / `github`); it maps 1:1 to the `Flavor` enum used by capability projection.
- Signing keys are kept separate per channel: Play uses its upload key (Play App Signing holds the final key), F-Droid signs with its own key (hence only the F-Droid-built foss APK is official — a locally built foss APK is functionally identical but unofficial), GitHub APKs are signed with the developer release key (`RELEASE_KEYSTORE_*` secrets in `release.yml`). Never reuse the GitHub/local debug key for the Play upload.
- Release flow publishes ONLY the `github` APK (+ SBOM + SHA256SUMS) to GitHub Releases; Play AAB/APK are built and policy-gated as self-proof, foss APK is policy-checked but distributed via F-Droid (see `.github/workflows/release.yml`, `TRADEMARKS.md`).
