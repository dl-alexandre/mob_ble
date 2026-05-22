defmodule Mob.Ble.Internal.BridgeProtocol do
  @moduledoc false
  # Decodes v1 bridge-event JSON payloads (Android NIF / iOS responder) into
  # the canonical transport event tuples:
  #
  #     {:ble_peer_up, peer_id, metadata}
  #     {:ble_peer_down, peer_id}
  #     {:ble_frame, peer_id, frame}
  #
  # Supports the full set of tags emitted by native (advertisement_received,
  # received_message with envelope/payload, device_discovered, error, status,
  # received_message_beacon) for contract parity. Unknown/malformed are
  # returned as error for the bridge to log/drop. The wire shape is stable
  # so the same Kotlin/Swift emitters work unchanged. Self-contained on
  # purpose — mob_ble does not depend on the application layer.
  #
  # Security: event tags are matched against a fixed allowlist; frames are
  # required to be binary (Base64-decoded if present); unknown tags become
  # `{:error, ...}` rather than silent drops so bridge contract violations
  # surface immediately.

  @wire_version 1

  @type transport_event ::
          {:ble_peer_up, peer_id :: binary(), metadata :: map()}
          | {:ble_peer_down, peer_id :: binary()}
          | {:ble_frame, peer_id :: binary(), frame :: binary()}

  @doc """
  Decode a raw bridge_event payload — either a JSON binary from the NIF or
  an already-decoded v1 map — into a transport event tuple.
  """
  @spec decode(binary() | map()) :: {:ok, transport_event()} | {:error, term()}
  def decode(payload) when is_binary(payload) do
    case JSON.decode(payload) do
      {:ok, map} when is_map(map) -> decode(map)
      {:ok, other} -> {:error, {:unrecognized_bridge_payload, other}}
      {:error, reason} -> {:error, {:invalid_bridge_json, reason}}
    end
  end

  def decode(%{"v" => @wire_version, "event" => tag} = msg) when is_binary(tag) do
    decode_v1(tag, msg)
  end

  def decode(%{v: @wire_version, event: tag} = msg) when is_binary(tag) do
    decode_v1(tag, stringify_keys(msg))
  end

  def decode(%{"v" => v}), do: {:error, {:unsupported_wire_version, v}}
  def decode(%{v: v}), do: {:error, {:unsupported_wire_version, v}}
  def decode(other), do: {:error, {:unrecognized_bridge_payload, other}}

  # ---- v1 tag dispatch (fixed allowlist) -----------------------------------

  defp decode_v1("peer_up", %{"peer_id" => peer_id} = msg) when is_binary(peer_id) do
    metadata = Map.get(msg, "metadata", %{})

    if is_map(metadata) do
      {:ok, {:ble_peer_up, peer_id, metadata}}
    else
      {:error, {:invalid_metadata, metadata}}
    end
  end

  defp decode_v1("peer_down", %{"peer_id" => peer_id}) when is_binary(peer_id) do
    {:ok, {:ble_peer_down, peer_id}}
  end

  defp decode_v1("frame", %{"peer_id" => peer_id, "frame" => frame})
       when is_binary(peer_id) and is_binary(frame) do
    {:ok, {:ble_frame, peer_id, decode_frame(frame)}}
  end

  # Friendlier alias used by some bridge emitters; treated as a frame event.
  defp decode_v1("received_message", %{"peer_id" => peer_id, "payload" => payload})
       when is_binary(peer_id) and is_binary(payload) do
    {:ok, {:ble_frame, peer_id, decode_frame(payload)}}
  end

  # Support real native shapes from Android BleEvent.toJsonObject and iOS emitters:
  # - "received_message" uses "sender_peer_id" (or "peer_id" / "received_device_id" fallback) + "envelope" (or "payload")
  # - This ensures full MX envelopes (the :mb_gatt carrier) are delivered as {:ble_frame, peer_id, data}
  defp decode_v1("received_message", msg) when is_map(msg) do
    peer_id =
      Map.get(msg, "peer_id") || Map.get(msg, "sender_peer_id") ||
        Map.get(msg, "received_device_id")

    envelope_or_payload = Map.get(msg, "envelope") || Map.get(msg, "payload")

    if is_binary(peer_id) and is_binary(envelope_or_payload) do
      {:ok, {:ble_frame, peer_id, decode_frame(envelope_or_payload)}}
    else
      {:error, {:missing_required_fields, "received_message"}}
    end
  end

  # Real native advertisement events (from Android BleEvent + iOS parity) map to peer lifecycle
  defp decode_v1("advertisement_received", %{"device_id" => dev} = msg) when is_binary(dev) do
    meta = Map.take(msg, ["rssi", "advertisement", "observed_at_ms"])
    {:ok, {:ble_peer_up, dev, meta}}
  end

  defp decode_v1("device_discovered", %{"device_id" => dev} = msg) when is_binary(dev) do
    meta = Map.take(msg, ["rssi", "advertisement", "observed_at_ms"])
    {:ok, {:ble_peer_up, dev, meta}}
  end

  # received_message_beacon (iOS/Android cue path) -> peer_up for discovery
  defp decode_v1("received_message_beacon", %{"received_device_id" => dev} = msg)
       when is_binary(dev) do
    meta =
      Map.take(msg, [
        "rssi",
        "beacon_version",
        "envelope_version",
        "payload_kind",
        "message_id_hash",
        "sender_peer_id_hash"
      ])

    {:ok, {:ble_peer_up, dev, meta}}
  end

  # Error and status events are tolerated (logged + dropped at bridge layer)
  defp decode_v1("error", msg) when is_map(msg), do: {:error, {:native_error, msg}}
  defp decode_v1("status", msg) when is_map(msg), do: {:error, {:native_status, msg}}

  defp decode_v1(tag, _msg)
       when tag in ~w(peer_up peer_down frame received_message advertisement_received device_discovered error status received_message_beacon) do
    {:error, {:missing_required_fields, tag}}
  end

  defp decode_v1(tag, _msg), do: {:error, {:unknown_event_tag, tag}}

  # ---- helpers -------------------------------------------------------------

  @spec decode_frame(binary()) :: binary()
  # Bridges send frames either raw-binary (rare; JSON-unsafe) or Base64.
  # Accept both: try Base64 first; fall back to the literal binary if decode
  # fails so non-Base64 emitters keep working during the transition.
  # The function is infallible (always returns binary) so callers in
  # decode_v1 embed the result directly without error arms.
  defp decode_frame(s) when is_binary(s) do
    case Base.decode64(s) do
      {:ok, bin} -> bin
      :error -> s
    end
  end

  defp stringify_keys(map) when is_map(map) do
    Map.new(map, fn
      {k, v} when is_atom(k) -> {Atom.to_string(k), v}
      {k, v} -> {k, v}
    end)
  end
end
