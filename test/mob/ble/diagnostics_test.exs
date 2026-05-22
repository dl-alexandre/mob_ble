defmodule Mob.Ble.DiagnosticsTest do
  use ExUnit.Case, async: true

  alias Mob.Ble.Diagnostics

  test "carrier/0 returns the active validated carrier" do
    assert Diagnostics.carrier() == :mb_gatt
    assert Diagnostics.carrier() == Mob.Ble.carrier()
  end

  test "snapshot/0 exposes the stable public carrier decision snapshot" do
    snapshot = Diagnostics.snapshot()

    assert snapshot.decision_version == 2
    assert snapshot.active_carrier == :mb_gatt
    assert snapshot.active_carrier == Diagnostics.carrier()
    assert is_list(snapshot.carriers)
    assert Enum.any?(snapshot.carriers, &(&1.id == :mb_gatt))
    assert Enum.any?(snapshot.carriers, &(&1.status == :rejected))
    assert is_list(snapshot.blocked_claims)
    assert is_map(snapshot.hybrid_test_scaffolding)
    assert is_list(snapshot.upstream_issues)
  end

  test "rejected_carriers/0 lists known-rejected carriers with a short reason" do
    rejected = Diagnostics.rejected_carriers()

    ids = Enum.map(rejected, & &1.id)
    assert :service_data_beacon_ref in ids
    assert :local_name_encoded_beacon_ref in ids

    for entry <- rejected do
      assert is_atom(entry.id)
      assert is_binary(entry.reason)
      assert entry.reason != ""
    end
  end

  test "snapshot carriers include both validated and rejected entries" do
    snap = Diagnostics.snapshot()
    carriers = snap.carriers
    assert Enum.any?(carriers, fn c -> c.status == :hardware_validated end)
    assert Enum.any?(carriers, fn c -> c.status == :rejected end)
    assert snap.decision_version >= 2
  end
end
