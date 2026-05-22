#!/usr/bin/env bash
set -euo pipefail

duration_seconds="${DURATION_SECONDS:-300}"
scenario="${SCENARIO:-idle_scan}"
out_dir="${OUT_DIR:-power-captures/$(date -u +%Y%m%dT%H%M%SZ)-${scenario}}"

mkdir -p "$out_dir"

cat > "$out_dir/metadata.md" <<EOF
# mob_ble Power Baseline

- scenario: ${scenario}
- duration_seconds: ${duration_seconds}
- captured_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)
- device_model:
- os_version:
- ble_api_or_controller:
- scan_mode:
- duplicate_filtering:
- advertising_interval:
- local_name:
- connect_timeout:
- retry_limit:
- backoff_policy:
- battery_start:
- battery_end:
- notes:
EOF

cat > "$out_dir/operator-checklist.md" <<EOF
# Operator Checklist

1. Record device model, OS version, and battery percentage in metadata.md.
2. Start the host app with the intended mob_ble scenario.
3. Keep the device awake and in the documented foreground/background state.
4. Capture logs for ${duration_seconds} seconds.
5. Record battery_end and attach logs in this directory.
6. Summarize discovery count, duplicate count, RSSI histogram, connection
   latency, terminal status, and retry/backoff events from diagnostics.
EOF

echo "Created ${out_dir}"
