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

Required SDK pieces (install once via `sdkmanager`, **no full reinstall**):

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
./gradlew :app:assemblePlayDebug :app:assembleFullDebug
```

## 3. Permission assertion (Play flavor)

After building, confirm the Play APK carries no high-risk permissions:

```sh
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
$AAPT dump permissions app/build/outputs/apk/play/debug/app-play-debug.apk
# must NOT list READ_SMS, RECEIVE_SMS, MANAGE_EXTERNAL_STORAGE,
# BIND_ACCESSIBILITY_SERVICE, or BIND_VPN_SERVICE
$AAPT dump permissions app/build/outputs/apk/full/debug/app-full-debug.apk
# MUST list READ_SMS and MANAGE_EXTERNAL_STORAGE
```
