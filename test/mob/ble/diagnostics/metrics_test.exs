defmodule Mob.Ble.Diagnostics.MetricsTest do
  use ExUnit.Case, async: true

  alias Mob.Ble.Diagnostics.Metrics

  test "accumulates peer discovery stats and RSSI histogram" do
    metrics =
      Metrics.new()
      |> Metrics.observe_event({:ble_peer_up, "peer-1", %{"rssi" => -83, "carrier" => "mb"}}, 10)
      |> Metrics.observe_event({:ble_peer_up, "peer-1", %{"rssi" => -58}}, 20)

    summary = Metrics.summary(metrics)

    assert summary.peer_count == 1
    assert summary.discovery_count == 2
    assert summary.duplicate_count == 1
    assert summary.rssi_histogram == %{"-90..-81" => 1, ">=-60" => 1}

    peer = metrics.peers["peer-1"]
    assert peer.first_seen_ms == 10
    assert peer.last_seen_ms == 20
    assert peer.rssi_samples == [-58, -83]
    assert peer.carrier == "mb"
  end

  test "counts frames, errors, and connection quality samples" do
    metrics =
      Metrics.new()
      |> Metrics.observe_event({:ble_frame, "peer-1", <<1, 2, 3>>}, 10)
      |> Metrics.observe_error(:connect, :timeout)
      |> Metrics.observe_error(:read, {:unsupported_wire_version, 99})
      |> Metrics.observe_connection("peer-1", %{connect_latency_ms: 42, terminal_status: :ok})

    summary = Metrics.summary(metrics)

    assert summary.frames == 1
    assert summary.errors == %{backoff: 1, protocol: 1}
    assert summary.connection_samples == 1

    assert [%{peer_id: "peer-1", connect_latency_ms: 42, terminal_status: :ok}] =
             metrics.connections
  end
end
