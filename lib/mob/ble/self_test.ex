defmodule Mob.Ble.SelfTest do
  @moduledoc """
  Headless on-device BLE bring-up probe for `Mob.Ble`.

  Started on demand by plugin consumers (e.g. `MOB_BLE_SELFTEST=1`) to
  verify the production NIF + BridgeProtocol path without UI:

    1. `:mob_ble_nif.start_scan/1` + `:mob_ble_nif.start_advertising/2`
       with this process as the owner pid.
    2. Receives `{Mob.Ble.MobileBridge, :bridge_event, json}` (or
       `{__MODULE__, :bridge_event, json}` when started standalone),
       decodes via the internal bridge protocol decoder, and logs the
       canonical transport event under the `MobBleSelfTest` tag.

  Two devices each running this probe should log each other's
  advertisements — that is the on-device two-node smoke check.

  This is the plugin's lean, dependency-free self-test. Application-layer
  self-tests with richer event semantics (per-message dedup, peer tagging,
  envelope counters) stay in the consuming app.
  """

  use GenServer

  alias Mob.Ble.Internal.BridgeProtocol

  require Logger

  @heartbeat_ms 5_000
  @test_env Mix.env() == :test

  @spec start_link(keyword()) :: GenServer.on_start()
  def start_link(opts \\ []) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @impl true
  def init(opts) do
    state = %{
      local_name: Keyword.get(opts, :local_name, "mob-ble-selftest"),
      tag: Keyword.get(opts, :tag, "MobBleSelfTest"),
      native?: Keyword.get(opts, :native?, not @test_env),
      events: 0,
      peers_up: MapSet.new(),
      peers_down: MapSet.new(),
      frames: 0
    }

    {:ok, state, {:continue, :start_ble}}
  end

  @impl true
  def handle_continue(:start_ble, %{native?: false} = state) do
    Logger.info("#{state.tag}: native disabled — passive event-forward mode")
    Process.send_after(self(), :heartbeat, @heartbeat_ms)
    {:noreply, state}
  end

  def handle_continue(:start_ble, state) do
    scan = safe_call(fn -> :mob_ble_nif.start_scan(self()) end)
    adv = safe_call(fn -> :mob_ble_nif.start_advertising(self(), state.local_name) end)

    Logger.info(
      "#{state.tag}: start_scan=#{inspect(scan)} start_advertising=#{inspect(adv)} local_name=#{state.local_name}"
    )

    Process.send_after(self(), :heartbeat, @heartbeat_ms)
    {:noreply, state}
  end

  @impl true
  def handle_info(:heartbeat, state) do
    Logger.info(
      "#{state.tag}: HEARTBEAT events=#{state.events} peers_up=#{MapSet.size(state.peers_up)} " <>
        "peers_down=#{MapSet.size(state.peers_down)} frames=#{state.frames}"
    )

    Process.send_after(self(), :heartbeat, @heartbeat_ms)
    {:noreply, state}
  end

  def handle_info({source, :bridge_event, json}, state) when source in [__MODULE__, Mob.Ble.MobileBridge] do
    state = %{state | events: state.events + 1}

    state =
      case BridgeProtocol.decode(json) do
        {:ok, {:ble_peer_up, peer_id, _metadata}} ->
          log_first_seen(state.tag, :peer_up, peer_id, state.peers_up)
          %{state | peers_up: MapSet.put(state.peers_up, peer_id)}

        {:ok, {:ble_peer_down, peer_id}} ->
          log_first_seen(state.tag, :peer_down, peer_id, state.peers_down)
          %{state | peers_down: MapSet.put(state.peers_down, peer_id)}

        {:ok, {:ble_frame, peer_id, frame}} ->
          Logger.debug("#{state.tag}: frame peer=#{peer_id} bytes=#{byte_size(frame)}")
          %{state | frames: state.frames + 1}

        {:error, reason} ->
          Logger.warning("#{state.tag}: dropped bridge_event #{inspect(reason)}")
          state
      end

    {:noreply, state}
  end

  def handle_info(other, state) do
    Logger.debug("#{state.tag}: unexpected message #{inspect(other)}")
    {:noreply, state}
  end

  @doc "Snapshot of counters for tests and operator inspection."
  @spec stats(pid() | GenServer.name()) :: map()
  def stats(server \\ __MODULE__), do: GenServer.call(server, :stats)

  @impl true
  def handle_call(:stats, _from, state) do
    {:reply,
     %{
       events: state.events,
       peers_up: MapSet.size(state.peers_up),
       peers_down: MapSet.size(state.peers_down),
       frames: state.frames
     }, state}
  end

  defp log_first_seen(tag, kind, id, set) do
    if !MapSet.member?(set, id) do
      Logger.info("#{tag}: FIRST #{kind} id=#{id}")
    end
  end

  defp safe_call(fun) do
    fun.()
  rescue
    error in UndefinedFunctionError -> {:error, {:nif_unavailable, error.module, error.function}}
    error in ErlangError -> {:error, error.original}
  catch
    :exit, reason -> {:error, reason}
  end
end
