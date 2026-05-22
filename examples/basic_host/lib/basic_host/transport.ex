defmodule BasicHost.Transport do
  @moduledoc """
  Minimal host transport that owns a `Mob.Ble` bridge process.

  It starts the bridge with itself as `:event_target`, then handles the
  canonical `{:ble_peer_up, ...}`, `{:ble_peer_down, ...}`, and
  `{:ble_frame, ...}` messages emitted by `Mob.Ble.MobileBridge`.
  """

  use GenServer

  @type state :: %{
          bridge: pid(),
          bridge_mod: module(),
          peers: MapSet.t(binary())
        }

  @spec start_link(keyword()) :: GenServer.on_start()
  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @impl true
  def init(opts) do
    bridge_mod = Keyword.get(opts, :bridge, MobBle.bridge_module())

    bridge_opts = [
      event_target: self(),
      local_name: Keyword.get(opts, :local_name, "mob-ble-example"),
      native?: Keyword.get(opts, :native?, false)
    ]

    {:ok, bridge} = bridge_mod.start_link(bridge_opts)
    {:ok, %{bridge: bridge, bridge_mod: bridge_mod, peers: MapSet.new()}}
  end

  @impl true
  def handle_info({:ble_peer_up, peer_id, info}, state) do
    IO.puts("Peer connected: #{inspect(peer_id)} #{inspect(info)}")
    {:noreply, %{state | peers: MapSet.put(state.peers, peer_id)}}
  end

  def handle_info({:ble_peer_down, peer_id}, state) do
    IO.puts("Peer disconnected: #{inspect(peer_id)}")
    {:noreply, %{state | peers: MapSet.delete(state.peers, peer_id)}}
  end

  def handle_info({:ble_frame, peer_id, frame}, state) do
    IO.puts("Frame from #{inspect(peer_id)}: #{inspect(frame)}")
    {:noreply, state}
  end

  @doc "Returns the currently known peer ids."
  @spec peers(pid() | GenServer.name()) :: [binary()]
  def peers(server \\ __MODULE__) do
    GenServer.call(server, :peers)
  end

  @impl true
  def handle_call(:peers, _from, state) do
    {:reply, MapSet.to_list(state.peers), state}
  end
end
