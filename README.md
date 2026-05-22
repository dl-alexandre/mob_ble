# mob_ble

Production BLE transport plugin for the `mob` mobile framework.

`mob_ble` provides a hardened, hardware-validated BLE transport for phone-to-phone secure messaging on Android and iOS.

## Carrier Decision

After extensive bidirectional hardware validation, the only currently reliable cross-platform path for full MX envelopes involving iOS is:

**MB legacy beacon cue (22-byte manufacturer data) + GATT fetch (responder)**

Direct full-MX service-data and extended advertising (AUX) paths are blocked by current iOS CoreBluetooth foreground restrictions.

See the linked issues for the full evidence:
- https://github.com/GenericJam/mob_dev/issues/7
- https://github.com/GenericJam/mob_dev/issues/8
- https://github.com/GenericJam/mob/issues/15

## Configuration

`mob_ble` reads its deployment configuration under the `:config` key in the
application environment. This is distinct from per-bridge options passed at
runtime when the transport starts the bridge.

```elixir
# config/config.exs, config/runtime.exs, or equivalent
config :mob_ble, config: [
  # :production (default) or :diagnostic. Affects logging and self-test hooks.
  evidence_mode: :production,

  # Passed through to Logger.
  log_level: :info,

  # Enables Diagnostics self-test / release hooks.
  diagnostics: false,

  # Optional fallback local name used for BLE advertising when not
  # overridden in bridge_opts.
  local_name: "mob-ble"
]
```

### Per-bridge options (`bridge_opts`)

When the BLE transport adapter starts the bridge, it can pass `bridge_opts`.
These take precedence for the lifetime of that bridge instance:

- `:event_target` (required by the transport) — pid that receives `{:ble_*, ...}` tuples
- `:local_name`
- `:carrier` (validated; only the active carrier is accepted)
- `:native?` (force-disable native for tests or simulators)
- `:config` (per-bridge override of the deployment config map)

The deployment `config :mob_ble, config: [...]` acts as the fallback.

Validation (`Mob.Ble.validate_config/1` and carrier assertions) runs both at
`MobBle.Application` boot and again inside `MobileBridge.start_link/1`.

## Plugin Activation

Add the package and list it under the `mob` plugins key:

```elixir
# mix.exs
defp deps do
  [
    {:mob, "~> 0.5"},
    {:mob_ble, "~> 0.1"}
  ]
end

# config/config.exs (or mob.exs / runtime configuration)
config :mob, :plugins, [:mob_ble]

config :mob_ble, config: [
  evidence_mode: :production,
  log_level: :info,
  diagnostics: false
]
```

When the `mob` framework processes the plugin list it ensures `:mob_ble` is
started. This invokes the `MobBle.Application` OTP callback, which:

1. Reads `config :mob_ble, config: [...]`
2. Calls `Mob.Ble.validate_config/1` (fails fast on bad values)
3. Starts the placeholder `MobBle.Supervisor` (empty by design)

## Full Activation + Transport Usage Example

```elixir
# After plugin activation the bridge module is obtained from the public API:
bridge_mod = Mob.Ble.bridge_module()
# => Mob.Ble.MobileBridge

# Later, when attaching the BLE transport (typical host code):
{:ok, ble} =
  # the BLE transport adapter (from the stack)
  SomeTransportBLE.start_link(
    event_target: Router,   # outer target for normalized events
    bridge: bridge_mod,
    bridge_opts: [
      local_name: "my-mob-device-42"
      # (transport internally sets the bridge's event_target to itself)
    ]
  )

:ok = Router.attach_transport(:ble, SomeTransportBLE, ble)
```

The bridge is started by the transport adapter (not by the `mob_ble`
application). This guarantees the correct `event_target` and proper
supervision/linking semantics.

## Runtime Behavior

- `MobBle.Application` intentionally starts **zero** children that are
  `MobileBridge` instances. The supervisor only exists as a named anchor
  for future internal coordinators.
- `Mob.Ble.Error` and `Mob.Ble.Backoff` provide a small public taxonomy and
  bounded retry policy helper for bridge/native operations.
- `Mob.Ble.Diagnostics.Metrics` provides a pure accumulator for RSSI
  histograms, peer discovery stats, frame counts, error categories, and
  GATT/connection quality samples.
- `Mob.Ble.MobileBridge.diagnostics/1` exposes the live bridge metrics snapshot
  gathered from decoded bridge events and native-operation failures.

See `docs/ROADMAP.md`, `docs/PERFORMANCE.md`, and
`examples/basic_host/README.md` for the current hardening plan and integration
shape.
- `MobileBridge` (GenServer) is instantiated exclusively via
  `Mob.Ble.bridge_module().start_link(bridge_opts)`.
- Native NIF calls (`:mob_ble_nif.*`) are skipped in `Mix.env() == :test`
  or when `:native?` is explicitly false. This keeps unit tests hermetic.
- Native events arrive as `{Mob.Ble.MobileBridge, :bridge_event, json}` and
  are decoded by the internal v1 protocol into `{:ble_peer_up, ...}`,
  `{:ble_peer_down, ...}`, `{:ble_frame, ...}` before being sent to the
  `event_target`.
- Carrier policy is enforced at the earliest possible moment
  (`validate_config/1` and `start_link/1`). Only `:mb_gatt` is accepted;
  everything else raises `Mob.Ble.CarrierRejectedError` (with diagnostics
  pointer).
- `terminate/2` on the bridge calls the native stop hook when native mode
  was active.

## Build and Static NIF Notes

- Elixir side: `src/mob_ble_nif.erl` declares the NIF exports and on-load.
- Android: full Kotlin sources + JNI C under `priv/native/android/`
  (including `MobBleNative`, `BleBridge`, scanner/dispatcher, GATT fetch).
- iOS: Swift responder + static NIF registration is provided via the `mob`
  driver tab (see `mob_dev` static-NIF table and driver bootstrap).
- When released as a Hex package the `priv/native` tree carries the
  platform artifacts. The package declares required Android permissions and
  iOS plist usage descriptions in `priv/mob_plugin.exs`.
- The NIF can be loaded statically (preferred on device) or dynamically.

See the `MobBle.Application` and `MobileBridge` moduledocs for more
implementation details.

## Migration from direct BLE

Previously some codebases accessed BLE directly via:

- Hard-coded calls to the NIF module (`:mob_ble_nif` or older internal names)
- Custom `NativeBridge` modules inside the mobile application shell
- Manual carrier selection or beacon/GATT orchestration
- Direct supervision of bridge processes inside the app tree

To migrate:

1. Add `{:mob_ble, "~> 0.1"}` to your deps and list `:mob_ble` in
   `config :mob, :plugins`.
2. Configure deployment settings under `config :mob_ble, config: [...]`
   (see Configuration section above).
3. Replace any direct bridge module reference with
   `Mob.Ble.bridge_module()`.
4. Let the official BLE transport adapter start the bridge (pass
   `bridge: Mob.Ble.bridge_module()` and the required `bridge_opts`).
5. Remove local carrier-decision logic — the package now owns the single
   validated `:mb_gatt` path and raises on any other attempt at startup.
6. Android permissions and iOS plist keys are now declared by the plugin
   manifest; you can usually remove the hand-written equivalents.
7. Tests that previously started native BLE should now rely on the
   automatic `native?: false` behaviour under `Mix.env() == :test`, or
   explicitly pass `native?: false` in bridge_opts.

Benefits after migration:
- Centralized, evidence-backed carrier policy with fast failure.
- Correct lifecycle ownership (transport owns the bridge).
- Consistent event contract and decode path.
- Plugin-driven permissions and native registration.
- Easier future evolution of the carrier without touching host code.

## Related

- `Mob.Ble`, `Mob.Ble.MobileBridge`, `Mob.Ble.Bridge`, `Mob.Ble.Diagnostics` — the public API
  surface. `Mob.Ble.Bridge` is the *canonical* behaviour definition owned by
  this package (rich contract in `lib/mob/ble/bridge.ex`; a CONTRACT-SYNC
  copy exists under `meshx_transport_ble` (for consumers of the MeshX transport adapter only). See
  their moduledocs for examples and `docs/mob_ble_bridge_migration.md`.
- `Mob.Ble.CarrierRejectedError` — the fast-fail exception type.
- `priv/mob_plugin.exs` — plugin manifest (permissions, NIF registration,
  platform bridges).
- Upstream `mob` plugin activation model and the carrier evidence summaries
  exposed through `Mob.Ble.Diagnostics`.

This package is the canonical, production-grade BLE implementation for the
`mob` ecosystem.
