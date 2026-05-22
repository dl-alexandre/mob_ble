# Basic Host Example

This example shows the minimal shape a host transport uses to own the
`mob_ble` bridge.

```elixir
defmodule BasicHost.Transport do
  use GenServer

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts)
  end

  @impl true
  def init(opts) do
    bridge_mod = Keyword.get(opts, :bridge, Mob.Ble.bridge_module())

    bridge_opts = [
      event_target: self(),
      local_name: Keyword.get(opts, :local_name, "basic-host"),
      native?: Keyword.get(opts, :native?, false)
    ]

    {:ok, bridge} = bridge_mod.start_link(bridge_opts)
    {:ok, %{bridge: bridge, bridge_mod: bridge_mod, peers: MapSet.new()}}
  end

  @impl true
  def handle_info({:ble_peer_up, peer_id, metadata}, state) do
    IO.inspect({:peer_up, peer_id, metadata})
    {:noreply, %{state | peers: MapSet.put(state.peers, peer_id)}}
  end

  def handle_info({:ble_peer_down, peer_id}, state) do
    IO.inspect({:peer_down, peer_id})
    {:noreply, %{state | peers: MapSet.delete(state.peers, peer_id)}}
  end

  def handle_info({:ble_frame, peer_id, frame}, state) do
    IO.inspect({:frame, peer_id, byte_size(frame)})
    {:noreply, state}
  end
end
```

Run in a host application with:

```elixir
{:ok, _pid} =
  BasicHost.Transport.start_link(
    bridge: Mob.Ble.bridge_module(),
    local_name: "basic-host",
    native?: false
  )
```

Set `native?: true` only in a real mobile build where the `mob_ble` native
sources and NIF are linked by the host app.
