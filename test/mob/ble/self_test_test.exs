defmodule Mob.Ble.SelfTestTest do
  use ExUnit.Case, async: false

  alias Mob.Ble.MobileBridge
  alias Mob.Ble.SelfTest

  setup do
    {:ok, pid} = SelfTest.start_link(name: :"selftest-#{System.unique_integer([:positive])}")
    on_exit(fn -> if Process.alive?(pid), do: GenServer.stop(pid) end)
    {:ok, pid: pid}
  end

  test "counts decoded peer_up / peer_down / frame events", %{pid: pid} do
    send(pid, {MobileBridge, :bridge_event, ~s({"v":1,"event":"peer_up","peer_id":"p-1"})})

    send(
      pid,
      {MobileBridge, :bridge_event, ~s({"v":1,"event":"peer_down","peer_id":"p-1"})}
    )

    frame = Base.encode64(<<1, 2, 3>>)

    send(
      pid,
      {MobileBridge, :bridge_event,
       ~s({"v":1,"event":"frame","peer_id":"p-1","frame":"#{frame}"})}
    )

    # let the GenServer process the cast-style messages
    stats = SelfTest.stats(pid)
    assert stats.events == 3
    assert stats.peers_up == 1
    assert stats.peers_down == 1
    assert stats.frames == 1
  end

  test "malformed payloads are dropped without crashing", %{pid: pid} do
    send(pid, {MobileBridge, :bridge_event, "garbage"})
    stats = SelfTest.stats(pid)
    assert stats.events == 1
    assert stats.peers_up == 0
    assert stats.frames == 0
  end
end
