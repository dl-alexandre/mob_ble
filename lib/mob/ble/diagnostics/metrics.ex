defmodule Mob.Ble.Diagnostics.Metrics do
  @moduledoc """
  Pure accumulator for bridge diagnostics.

  It records peer discovery counts, RSSI buckets, frame counters, error
  counters, and lightweight GATT quality samples. The accumulator is plain data
  so it can be embedded in `Mob.Ble.SelfTest`, a future diagnostics coordinator,
  or a host application's own telemetry process.
  """

  @type peer_id :: binary()
  @type monotonic_ms :: integer()
  @type rssi :: integer()

  @type peer_stats :: %{
          first_seen_ms: monotonic_ms(),
          last_seen_ms: monotonic_ms(),
          discovery_count: pos_integer(),
          duplicate_count: non_neg_integer(),
          rssi_samples: [rssi()],
          carrier: term()
        }

  @type t :: %{
          peers: %{optional(peer_id()) => peer_stats()},
          rssi_histogram: %{optional(binary()) => non_neg_integer()},
          frames: non_neg_integer(),
          errors: %{optional(term()) => non_neg_integer()},
          connections: [map()]
        }

  @doc "Returns an empty metrics accumulator."
  @spec new() :: t()
  def new do
    %{
      peers: %{},
      rssi_histogram: %{},
      frames: 0,
      errors: %{},
      connections: []
    }
  end

  @doc "Observes a canonical bridge event."
  @spec observe_event(t(), tuple(), monotonic_ms()) :: t()
  def observe_event(metrics, event, now_ms \\ System.monotonic_time(:millisecond))

  def observe_event(metrics, {:ble_peer_up, peer_id, metadata}, now_ms)
      when is_binary(peer_id) and is_map(metadata) do
    rssi = read_rssi(metadata)

    carrier =
      Map.get(metadata, "carrier") || Map.get(metadata, :carrier) ||
        Map.get(metadata, "advertisement")

    metrics
    |> update_peer(peer_id, now_ms, rssi, carrier)
    |> update_rssi_histogram(rssi)
  end

  def observe_event(metrics, {:ble_peer_down, peer_id}, now_ms) when is_binary(peer_id) do
    update_peer(metrics, peer_id, now_ms, nil, nil)
  end

  def observe_event(metrics, {:ble_frame, _peer_id, frame}, _now_ms) when is_binary(frame) do
    Map.update!(metrics, :frames, &(&1 + 1))
  end

  def observe_event(metrics, _event, _now_ms), do: metrics

  @doc "Records an error reason using the public error taxonomy."
  @spec observe_error(t(), Mob.Ble.Error.operation(), term()) :: t()
  def observe_error(metrics, operation, reason) do
    error = Mob.Ble.Error.classify(operation, reason)
    Map.update!(metrics, :errors, &Map.update(&1, error.category, 1, fn count -> count + 1 end))
  end

  @doc "Records a GATT/connection quality sample."
  @spec observe_connection(t(), peer_id(), map()) :: t()
  def observe_connection(metrics, peer_id, sample) when is_binary(peer_id) and is_map(sample) do
    entry =
      sample
      |> Map.put_new(:peer_id, peer_id)
      |> Map.put_new(:observed_at_ms, System.monotonic_time(:millisecond))

    Map.update!(metrics, :connections, &[entry | &1])
  end

  @doc "Returns a compact operator-facing summary."
  @spec summary(t()) :: map()
  def summary(metrics) do
    %{
      peer_count: map_size(metrics.peers),
      discovery_count:
        metrics.peers |> Map.values() |> Enum.map(& &1.discovery_count) |> Enum.sum(),
      duplicate_count:
        metrics.peers |> Map.values() |> Enum.map(& &1.duplicate_count) |> Enum.sum(),
      rssi_histogram: metrics.rssi_histogram,
      frames: metrics.frames,
      errors: metrics.errors,
      connection_samples: length(metrics.connections)
    }
  end

  defp update_peer(metrics, peer_id, now_ms, rssi, carrier) do
    update_in(metrics, [:peers], fn peers ->
      Map.update(
        peers,
        peer_id,
        %{
          first_seen_ms: now_ms,
          last_seen_ms: now_ms,
          discovery_count: 1,
          duplicate_count: 0,
          rssi_samples: rssi_samples(rssi),
          carrier: carrier
        },
        fn peer ->
          %{
            peer
            | last_seen_ms: now_ms,
              discovery_count: peer.discovery_count + 1,
              duplicate_count: peer.duplicate_count + 1,
              rssi_samples: rssi_samples(rssi) ++ peer.rssi_samples,
              carrier: carrier || peer.carrier
          }
        end
      )
    end)
  end

  defp update_rssi_histogram(metrics, nil), do: metrics

  defp update_rssi_histogram(metrics, rssi) do
    bucket = rssi_bucket(rssi)
    Map.update!(metrics, :rssi_histogram, &Map.update(&1, bucket, 1, fn count -> count + 1 end))
  end

  defp read_rssi(%{"rssi" => rssi}) when is_integer(rssi), do: rssi
  defp read_rssi(%{rssi: rssi}) when is_integer(rssi), do: rssi
  defp read_rssi(_metadata), do: nil

  defp rssi_samples(nil), do: []
  defp rssi_samples(rssi), do: [rssi]

  defp rssi_bucket(rssi) when rssi < -90, do: "<-90"
  defp rssi_bucket(rssi) when rssi < -80, do: "-90..-81"
  defp rssi_bucket(rssi) when rssi < -70, do: "-80..-71"
  defp rssi_bucket(rssi) when rssi < -60, do: "-70..-61"
  defp rssi_bucket(_rssi), do: ">=-60"
end
