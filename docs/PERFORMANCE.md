# Performance and Power Notes

These notes are the first measurement plan for `mob_ble`; they are not final
device benchmarks yet.

## Baseline Scenarios

- Idle foreground scanner with no peers nearby.
- Active MB beacon advertising with scanner disabled.
- Scanner plus advertiser enabled with one nearby peer.
- MB beacon observed followed by GATT fetch.
- Repeated failed GATT connect attempts with retry/backoff enabled.

## Metrics to Capture

- Battery percentage before and after each run.
- Wall-clock duration.
- Device model, OS version, BLE controller/API level, and foreground/background
  state.
- Scan mode, duplicate filtering setting, advertising interval, local name,
  connect timeout, retry limit, and backoff policy.
- Discovery count, duplicate count, RSSI histogram, connect latency, service
  discovery latency, write/read latency, terminal status, and disconnect reason.

## Initial Guidance

- Keep `evidence_mode: :production` and `diagnostics: false` in normal app
  builds.
- Enable diagnostic collection only for field evidence or release validation.
- Prefer bounded exponential backoff for connect/read/write retries; do not run
  unbounded tight retry loops on mobile devices.
- Treat direct full-MX AUX advertising as unavailable on tested iOS hardware;
  the validated full-envelope path remains MB beacon cue plus GATT fetch.

## Open Measurement Work

- Capture Android idle scan and active fetch power deltas on API 28 and API 33
  devices.
- Capture iOS foreground responder power deltas while advertising and serving
  GATT fetch.
- Compare short vs. capped backoff policies under failed-connect conditions.
- Publish a small table of measured runs once at least two Android and one iOS
  device have repeatable captures.
