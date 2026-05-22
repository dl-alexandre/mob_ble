defmodule Mob.Ble.Bridge do
  @moduledoc """
  Behaviour for native/mobile BLE bridge implementations (canonical contract
  owned by the `mob_ble` plugin).

  Bridge implementations own platform-specific BLE details: scanning,
  advertising, GATT characteristics, MTU negotiation, background constraints,
  and mobile OS callbacks.

  They communicate with a transport adapter (e.g. `MeshxTransportBLE` when
  using the MeshX BLE transport adapter, or a future `mob`-native transport adapter) through
  a small message contract.

  ## Callbacks (outbound from adapter → bridge)

      @callback start_link(keyword()) :: GenServer.on_start()
      @callback send_frame(pid(), term(), binary(), keyword()) :: :ok | {:error, term()}
      @callback broadcast_frame(pid(), binary(), keyword()) :: :ok | {:error, term()}

  ## Inbound events (bridge → event_target)

  The `event_target` (normally the transport adapter process, supplied via
  `bridge_opts`) must receive:

      {:ble_peer_up, peer_id :: binary(), metadata :: map()}
      {:ble_peer_down, peer_id :: binary()}
      {:ble_frame, peer_id :: binary(), frame :: binary()}

  `MobileBridge` (the production implementation) additionally decodes the
  native v1 JSON bridge protocol before emitting the above tuples.

  See `Mob.Ble.MobileBridge` for the reference implementation and native
  Android/iOS sources for the exact JSON shapes emitted by the platform sides.
  """

  # CONTRACT SYNC: Mob.Ble.Bridge <-> MeshxTransportBLE.Bridge
  # This is the canonical behaviour definition for the mob plugin ecosystem.
  # Any change to callbacks or documented inbound events MUST be mirrored
  # in the copy at apps/meshx_transport_ble/lib/meshx_transport_ble/bridge.ex
  # (and vice-versa) to prevent API drift.
  # Last synchronized: 2026-05-19
  # See docs/mob_ble_bridge_migration.md for full migration rationale and risks.

  @callback start_link(keyword()) :: GenServer.on_start()
  @callback send_frame(pid(), term(), binary(), keyword()) :: :ok | {:error, term()}
  @callback broadcast_frame(pid(), binary(), keyword()) :: :ok | {:error, term()}
end
