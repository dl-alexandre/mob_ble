%{
  name: :mob_ble,
  mob_version: "~> 0.5",
  plugin_spec_version: 1,
  description:
    "Production-grade BLE transport for mob using MB legacy beacons + GATT fetch. Includes hardened Android scanner, hybrid correlation, and iOS responder.",

  # The custom BLE NIF (mob_ble_nif) - static registration via mob_dev static_nifs + driver tab
  nifs: [
    %{
      # The native module name (matches ERL_NIF_INIT arg); wrapper or direct calls use :mob_ble_nif
      module: :mob_ble_nif,
      native_dir: "priv/native"
    }
  ],
  android: %{
    # Permissions required for production BLE (advertise + scan + GATT)
    permissions: [
      "android.permission.BLUETOOTH_ADVERTISE",
      "android.permission.BLUETOOTH_CONNECT",
      "android.permission.BLUETOOTH_SCAN"
    ],

    # The main Kotlin bridge (populated by parallel Android extraction)
    # NOTE: actual source lives under src/main/java/mob/ble/ (see EXTRACTION_INVENTORY.md)
    bridge_kt: "priv/native/android/src/main/java/mob/ble/BleBridge.kt",

    # JNI / C NIF source (rebranded, no old app symbols)
    jni_source: "priv/native/android/jni/mob_ble_nif.c",

    # Standalone compile/build harness for plugin-owned Android sources.
    # Produces libmob_ble_android.a per ABI; final lib<app>.so packaging is
    # still performed by the app build using generated native-link metadata.
    gradle_project: "priv/native/android",
    manifest: "priv/native/android/src/main/AndroidManifest.xml",
    cmake: "priv/native/android/jni/CMakeLists.txt",
    zig_build: "priv/native/android/jni/build.zig",
    native_archive: "priv/generated/android/{abi}/libmob_ble_android.a",
    jni_onload_hook: "priv/native/android/jni/mob_ble_jni_hooks.c",
    jni_onload_function: "mob_ble_jni_on_load",
    static_nifs: "priv/native/android/jni/static_nifs.list",
    driver_tab_decls: "priv/native/android/jni/driver_tab_decls.cinc",
    driver_tab_entries: "priv/native/android/jni/driver_tab_entries.cinc"
  },
  ios: %{
    # Full iOS extraction complete under the mob_ble plugin (symmetric to Android).
    # - MobBleBridge.swift: sanitized Swift responder.
    # - mob_ble_nif.m: NIF glue with mob_ble_* cdecls + JSON v1 emit_* impls
    #   delivering to {Elixir.Mob.Ble.MobileBridge, :bridge_event, json}.
    # Listed swift_files include the bridge and all support protocol types it
    # references, so Hex consumers do not need to vendor host Swift sources.
    swift_files: [
      "priv/native/ios/BLAKE2s.swift",
      "priv/native/ios/BLE.swift",
      "priv/native/ios/Chunk.swift",
      "priv/native/ios/Fragment.swift",
      "priv/native/ios/Frame.swift",
      "priv/native/ios/MessageAdvertisement.swift",
      "priv/native/ios/MessageAdvertisementObserver.swift",
      "priv/native/ios/MessageEnvelope.swift",
      "priv/native/ios/MobFetchGatt.swift",
      "priv/native/ios/MobFetchGattResponder.swift",
      "priv/native/ios/MobFetchProtocol.swift",
      "priv/native/ios/Noise.swift",
      "priv/native/ios/SecureSession.swift",
      "priv/native/ios/MobBleBridge.swift"
    ],

    # Usage description for Bluetooth permission
    plist_keys: %{
      "NSBluetoothAlwaysUsageDescription" => "Required for secure peer-to-peer messaging via BLE"
    },
    frameworks: ["CoreBluetooth"]
  }
}
