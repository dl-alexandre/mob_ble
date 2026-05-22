defmodule Mob.BleTest do
  use ExUnit.Case, async: false

  alias Mob.Ble.CarrierRejectedError
  alias Mob.Ble.MobileBridge

  defmodule OwnerTransport do
    @moduledoc false
    use GenServer

    def start_link(opts), do: GenServer.start_link(__MODULE__, opts)

    @impl true
    def init(opts) do
      bridge_module = Keyword.fetch!(opts, :bridge)
      bridge_opts = Keyword.get(opts, :bridge_opts, [])
      {:ok, bridge} = bridge_module.start_link(Keyword.put(bridge_opts, :event_target, self()))
      {:ok, %{bridge: bridge, bridge_module: bridge_module, peers: MapSet.new()}}
    end

    @impl true
    def handle_info({:ble_peer_up, peer_id, _metadata}, state) do
      {:noreply, %{state | peers: MapSet.put(state.peers, peer_id)}}
    end

    def handle_info({:ble_peer_down, peer_id}, state) do
      {:noreply, %{state | peers: MapSet.delete(state.peers, peer_id)}}
    end

    def handle_info({:ble_frame, _peer_id, _frame}, state), do: {:noreply, state}
  end

  describe "carrier policy" do
    test "carrier/0 is the validated mb_gatt path" do
      assert Mob.Ble.carrier() == :mb_gatt
    end

    test "assert_carrier!/1 accepts the active carrier" do
      assert :ok = Mob.Ble.assert_carrier!(:mb_gatt)
    end

    test "assert_carrier!/1 raises for a rejected carrier" do
      assert_raise CarrierRejectedError, ~r/service_data_beacon_ref/, fn ->
        Mob.Ble.assert_carrier!(:service_data_beacon_ref)
      end
    end

    test "assert_carrier!/1 raises for an unknown carrier" do
      assert_raise CarrierRejectedError, ~r/unknown carrier/, fn ->
        Mob.Ble.assert_carrier!(:nonsense)
      end
    end
  end

  describe "validate_config/1" do
    test "accepts empty config" do
      assert :ok = Mob.Ble.validate_config([])
      assert :ok = Mob.Ble.validate_config(%{})
    end

    test "accepts the active carrier" do
      assert :ok = Mob.Ble.validate_config(carrier: :mb_gatt)
    end

    test "raises CarrierRejectedError for a rejected carrier in config" do
      assert_raise CarrierRejectedError, fn ->
        Mob.Ble.validate_config(carrier: :local_name_encoded_beacon_ref)
      end
    end

    test "returns {:error, ...} for non-carrier invalid keys" do
      assert {:error, {:invalid_config, :evidence_mode, :bogus}} =
               Mob.Ble.validate_config(evidence_mode: :bogus)

      assert {:error, {:invalid_config, :log_level, "info"}} =
               Mob.Ble.validate_config(log_level: "info")

      assert {:error, {:invalid_config, :diagnostics, "yes"}} =
               Mob.Ble.validate_config(diagnostics: "yes")
    end

    test "tolerates unknown keys" do
      assert :ok = Mob.Ble.validate_config(future_key: :anything)
    end
  end

  # --- Application, lifecycle, plugin simulation (Track A items 1-3) ---
  # These exercise MobBle.Application.start/2 directly (the callback invoked
  # by OTP when the :mob_ble application is started, either explicitly or via
  # plugin activation in config :mob, :plugins). This avoids global app state
  # races while still covering the documented boot path, config reading under
  # :config, and the critical guarantee that the bridge is never started here.

  describe "Application + config (startup, reading config :mob_ble, validation at boot)" do
    setup do
      original = Application.get_env(:mob_ble, :config)

      on_exit(fn ->
        if original == nil do
          Application.delete_env(:mob_ble, :config)
        else
          Application.put_env(:mob_ble, :config, original)
        end
      end)

      :ok
    end

    test "MobBle.Application.start reads config :mob_ble,config and calls validate_config" do
      Application.put_env(:mob_ble, :config,
        evidence_mode: :production,
        log_level: :debug,
        diagnostics: false
      )

      assert {:ok, sup_pid} = MobBle.Application.start(:normal, [])
      assert is_pid(sup_pid)
      assert Process.whereis(MobBle.Supervisor) == sup_pid

      # Critical: empty children; bridge lifecycle is owned externally
      assert Supervisor.which_children(MobBle.Supervisor) == []

      Supervisor.stop(sup_pid)
    end

    test "startup raises on invalid deployment config (validation at boot)" do
      Application.put_env(:mob_ble, :config, evidence_mode: :bogus)

      assert_raise RuntimeError, ~r/mob_ble validate_config failed/, fn ->
        MobBle.Application.start(:normal, [])
      end
    end

    test "accepts :diagnostic evidence_mode and boolean diagnostics" do
      Application.put_env(:mob_ble, :config, evidence_mode: :diagnostic, diagnostics: true)

      {:ok, sup} = MobBle.Application.start(:normal, [])
      assert Supervisor.which_children(MobBle.Supervisor) == []
      Supervisor.stop(sup)
    end
  end

  describe "lifecycle / ownership (bridge not started by Application; owned by transport)" do
    test "Application never starts a MobileBridge process or child" do
      {:ok, sup} = MobBle.Application.start(:normal, [])

      children = Supervisor.which_children(MobBle.Supervisor)
      assert children == []

      Supervisor.stop(sup)
    end

    test "bridge_module() is the correct impl and transport owns its lifecycle via start_link" do
      assert Mob.Ble.bridge_module() == MobileBridge

      {:ok, transport} =
        OwnerTransport.start_link(
          bridge: Mob.Ble.bridge_module(),
          bridge_opts: [event_target: self()]
        )

      state = :sys.get_state(transport)
      bridge = state.bridge
      assert is_pid(bridge)

      # Ownership proof: the transport process (started first) is in the bridge's links
      # (start_link inside the transport adapter's init creates the link)
      {:links, links} = Process.info(bridge, :links) || {:links, []}
      assert transport in links

      # Bridge receives events addressed to transport's internal target
      # (we do not assert forwarding here; see mobile_bridge_test and transport tests)
      GenServer.stop(transport)
    end
  end

  describe "plugin activation simulation (when :mob_ble in config :mob, :plugins)" do
    test "starting the application (as plugin activation would) does not eagerly create bridge" do
      # In a real host, config :mob, :plugins, [:mob_ble] causes the framework
      # to ensure_all_started(:mob_ble) (or include in applications list).
      # We simulate the resulting boot of our Application callback.
      orig_mob_plugins = Application.get_env(:mob, :plugins)
      orig_mob_ble_config = Application.get_env(:mob_ble, :config)

      on_exit(fn ->
        if orig_mob_plugins == nil do
          Application.delete_env(:mob, :plugins)
        else
          Application.put_env(:mob, :plugins, orig_mob_plugins)
        end

        if orig_mob_ble_config == nil do
          Application.delete_env(:mob_ble, :config)
        else
          Application.put_env(:mob_ble, :config, orig_mob_ble_config)
        end
      end)

      Application.put_env(:mob, :plugins, [:mob_ble])
      Application.put_env(:mob_ble, :config, log_level: :info)

      {:ok, sup} = MobBle.Application.start(:normal, [])

      # Still no bridge child — this is the key ownership invariant.
      assert Supervisor.which_children(MobBle.Supervisor) == []

      Supervisor.stop(sup)
    end
  end

  describe "additional config and diagnostics surface coverage" do
    setup do
      orig = Application.get_env(:mob_ble, :config)

      on_exit(fn ->
        if orig == nil do
          Application.delete_env(:mob_ble, :config)
        else
          Application.put_env(:mob_ble, :config, orig)
        end
      end)

      :ok
    end

    test "validate_config with mixed map/list inputs" do
      assert :ok = Mob.Ble.validate_config(%{log_level: :warning, diagnostics: false})
      assert :ok = Mob.Ble.validate_config(evidence_mode: :production)
    end

    test "bridge_module and default_bridge are stable" do
      assert Mob.Ble.bridge_module() == Mob.Ble.default_bridge()
      assert Mob.Ble.bridge_module() == MobileBridge
    end
  end
end
