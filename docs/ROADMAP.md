# mob_ble Hardening Roadmap

This roadmap captures the post-0.1 work that makes `mob_ble` easier to
operate, debug, and integrate in production applications.

## Diagnostics and Observability

- Add RSSI histograms per peer and rolling RSSI summaries.
- Track peer discovery stats: first seen, last seen, discovery count, duplicate
  beacon count, and observed carrier.
- Track connection quality metrics for GATT fetches: connect latency, service
  discovery latency, write/read latency, MTU, terminal status, and disconnect
  reason.
- Expose diagnostics through a stable Elixir API and optional structured log
  events.
- Keep diagnostic mode opt-in so normal production logging stays quiet.

## Error Taxonomy and Retry Policy

- Replace broad bridge error atoms with a documented error taxonomy.
- Classify errors by caller action:
  - retryable transient errors;
  - retryable after backoff;
  - platform permission or capability errors;
  - protocol or payload validation errors;
  - permanent configuration errors.
- Add bounded retry and backoff helpers for scanner startup, advertiser startup,
  GATT connect, service discovery, MFQ writes, and MFR reads.
- Include retry attempt, next delay, peer id, and operation in diagnostic events.

## Public API Static Analysis

- Add typespecs for the public `Mob.Ble` API, bridge callbacks, self-test API,
  diagnostics API, and public error structs.
- Keep internal native protocol types private unless they are intentionally
  exposed through diagnostics.
- Run Dialyzer on the standalone package and keep new warnings at zero.
- Add CI checks for format, test, `mix hex.build`, and Dialyzer once the package
  repo is fully wired.

## Example Integration

- Add an `examples/` folder with a small host app that starts `mob_ble`, selects
  `Mob.Ble.bridge_module/0`, and wires bridge events into a minimal transport
  process.
- Keep the example independent from MeshX so pure `mob + mob_ble` users can
  copy it without pulling umbrella dependencies.
- Include an optional native/device launch note for Android and iOS.

## Performance and Power Notes

- Document expected foreground scanning and advertising behavior.
- Capture baseline power measurements for idle scan, active advertise, beacon
  observe, GATT fetch, and repeated failed connect attempts.
- Record tunable values: scan mode, duplicate filtering, beacon interval,
  connect timeout, retry limits, and backoff ceilings.
- Add guidance for keeping diagnostic mode off in production unless collecting
  evidence.

## Suggested Order

1. Public API typespecs and Dialyzer baseline.
2. Error taxonomy and retry/backoff helpers.
3. Diagnostics event schema and peer metrics.
4. Example integration app.
5. Performance and power measurement notes.
