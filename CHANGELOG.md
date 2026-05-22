# Changelog

## 0.1.0 (2026-05-19)

- **Bridge migration complete (Phases 1+2)**: Canonical `Mob.Ble.Bridge` behaviour
  (with full moduledoc, 3 callbacks, and documented inbound `{:ble_peer_up,...}`,
  `{:ble_peer_down,...}`, `{:ble_frame,...}` event contract) now lives here as the
  authoritative definition. `Mob.Ble.MobileBridge` implements it. CONTRACT-SYNC
  copy retained under `MeshxTransportBLE.Bridge` (for `meshx_transport_ble` consumers only).
- **Self-contained for `mob` ecosystem**: Zero runtime `meshx_*` dependencies.
  `mix hex.build` succeeds cleanly. Independently publishable on Hex.pm as a
  first-class `mob` plugin (no MeshX packages required for pure `mob + mob_ble` users).
- Added `LICENSE`, `CHANGELOG.md`; polished `mix.exs` metadata (description,
  links, files list, docs config) for publication hygiene. No plugin-owned
  MeshX prose (only unavoidable compat module identifiers + CONTRACT SYNC paths remain in comments/docs).
- `Mob.Ble.SelfTest` (plugin-owned, `native?: true` on device) for headless
  on-device bring-up when the `mob_ble` path is active (avoids NIF contention
  with legacy paths).
- Carrier decision logic, v1 `BridgeProtocol` decoder, Android Kotlin + JNI,
  iOS Swift sources (including vendored protocols), plugin manifest
  (`priv/mob_plugin.exs`), and `MobBle.Application` lifecycle all owned here.
- Full backward compatibility preserved: legacy `MOB_BLE_TRANSPORT=0` path in
  consumers continues to work unchanged; no behaviour or wire-format changes.
- See `docs/mob_ble_bridge_migration.md`, `Mob.Ble.Bridge` moduledoc, and
  `README.md` for the complete contract and cutover guidance.
- `mix test apps/mob_ble` passes (test isolation fixes prevent auto-start
  conflicts in application lifecycle tests).

### Phase 3 (Release Coordination & Publication Prep, 2026-05-19)
- Enhanced `CHANGELOG.md` + root `CHANGELOG.md` for cutover.
- Full cutover announcement + on-device validation commands + evidence
  collection template drafted (`docs/releases/mob_ble_phase3_cutover_announcement.md`).
- Android/iOS `MOB_BLE_*` launch forwarding parity (MainActivity + AppDelegate)
  + dedicated launch script + CONTRIBUTING refresh for validation of default path.
- `mix hex.build` verified clean; pre-publish checklist + exact `mix hex.publish`
  commands produced (see Implementation Summary).
- Audit / migration docs + upstream checklist updated with Phase 3 status.
- Zero extraneous MeshX prose in plugin-owned sources; full legacy `MOB_BLE_TRANSPORT=0`
  backward compat preserved.

(The 0.1.0 release marks the package as production-ready post-extraction and
migration. Phase 3 delivers the coordinated release artifacts and on-device
validation enablement. Publish with `mix hex.publish` from `apps/mob_ble/`.)

