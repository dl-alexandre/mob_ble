# Mob BLE Android Extraction Inventory

This directory is the target home for Android native sources that are being
extracted from the previous Android app source tree.

## Target Skeleton

- `src/main/java/mob/ble/` - Kotlin BLE bridge, scanner, dispatcher, fetch, and wire-format code.
- `src/test/java/mob/ble/` - local JVM tests after package rewrite.
- `src/androidTest/java/mob/ble/` - instrumented Android tests after package rewrite.
- `jni/` - JNI/C bridge sources once NIF and bridge symbols are renamed.
- `assets/` - Android assets owned by the BLE plugin.
- `res/` - Android resources owned by the BLE plugin.
- `gradle/wrapper/` - Gradle wrapper metadata if the plugin needs a standalone Android build harness.

## Main Kotlin Files

Copied into `src/main/java/mob/ble/` with `package mob.ble`:

- `BleAdvertGossipDispatcher.kt`
- `BleAdvertiser.kt`
- `BleBridge.kt`
- `BleDispatcher.kt`
- `BleEvent.kt`
- `BleEventSink.kt`
- `BlePermissions.kt`
- `BleScanner.kt`
- `FakeBleBridge.kt`
- `MobBeaconFetchCoordinator.kt`
- `MobBleNative.kt`
- `MobFetchGatt.kt`
- `MobFetchProtocol.kt`
- `MobMessageAdvertisement.kt`
- `MobMessageEnvelope.kt`
- `PlainGattInteropHarness.kt`

Core files started first:

- `BleScanner.kt`
- `BleDispatcher.kt`

## Main Kotlin Follow-Up Cleanup

- Rename remaining legacy log/event labels where they refer to the old app
  rather than the on-air MB/MX wire format.
- Remove comments that refer to the old app module as ownership rather than
  historical validation context.
- Decide whether `PlainGattInteropHarness.kt` remains plugin-owned or becomes a
  test-only utility.

## Android App-Level Files Still Pending

These are outside the BLE package and need an explicit ownership decision before
moving, because they are app shell / BEAM runtime integration rather than pure
BLE transport code:

- `BeamForegroundService.kt`
- `MainActivity.kt`
- `MobBridge.kt`
- `MobFirebaseService.kt`
- `MobNode.kt`
- `MobScannerActivity.kt`

## JNI / Project Files Still Pending

- `jni/CMakeLists.txt`
- `jni/beam_jni.c`
- `jni/build.zig`
- `AndroidManifest.xml`
- `build.gradle`
- `settings.gradle`
- `gradle.properties`
- Gradle wrapper files
- Android resources under `res/`
- Android assets under `assets/`
- JNI libraries under `jniLibs/` if they become plugin-owned artifacts

## Test Files Still Pending

Local JVM tests:

- `BleAdvertGossipDispatcherTest.kt`
- `BleDispatcherTest.kt`
- `BleEventTest.kt`
- `BleScannerTest.kt`
- `FakeBleBridgeTest.kt`
- `MobBeaconFetchCoordinatorTest.kt`
- `MobFetchProtocolTest.kt`
- `MobMessageAdvertisementTest.kt`
- `MobMessageEnvelopeTest.kt`

Instrumented tests:

- `IOSAuxFullMxAdvertSmokeTest.kt`
- `IOSHybridDirectMxReceiveTest.kt`
- `IOSResponderFetchSmokeTest.kt`
- `MXFullEnvelopeSmokeTest.kt`
