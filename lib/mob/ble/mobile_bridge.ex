defmodule Mob.Ble.MobileBridge do
  @moduledoc """
  Implementation of the canonical `Mob.Ble.Bridge` behaviour (defined in
  `lib/mob/ble/bridge.ex`) for the `mob_ble` plugin.

  `MobileBridge` is the production BLE bridge used when the plugin is
  active. It is **never** started by `MobBle.Application`. Callers (the BLE
  transport adapter) obtain it via `Mob.Ble.bridge_module()` and start it
  with the correct `event_target`:

      {:ok, bridge} =
        Mob.Ble.bridge_module().start_link(
          event_target: transport_pid,
          local_name: "my-device",
          config: Application.get_env(:mob_ble, :config, [])
        )

  ## Lifecycle and ownership

  The bridge process is owned by the transport process that called
  `start_link/1`. The transport supplies `event_target` (usually itself)
  so that decoded native events (`{:ble_peer_up, ...}`, `{:ble_frame, ...}`,
  etc.) are delivered for normalization and routing.

  ## Native interaction (when enabled)

  - On `init/1` (when `native?` is true and not running under `Mix.env() == :test`):
    calls `:mob_ble_nif.start_scan/1` and `:mob_ble_nif.start_advertising/2`
    registering `self()` as the owner for callbacks.
  - `send_frame/4` and `broadcast_frame/4` delegate to `:mob_ble_nif.send_ping/3`.
  - Native side delivers events back as `{Mob.Ble.MobileBridge, :bridge_event, json}`.
  - `terminate/2` calls `:mob_ble_nif.stop/1`.

  Events are decoded by the internal v1 JSON bridge protocol
  before forwarding. Malformed payloads are dropped with a warning.

  ## Configuration

  Per-bridge options (`bridge_opts`) take precedence over the deployment
  `config :mob_ble, config: [...]` for `local_name`, `carrier`, `native?`,
  and an optional `:config` override map/keyword.

  Carrier validation (and deployment `validate_config`) happens in `start_link/1`
  before `GenServer.start_link` (both for the top-level `:config` and any
  explicit `:carrier` / `:config` override in `bridge_opts`). `init/1` receives
  already-validated state.

  ## Example — supervision under a transport

  The transport typically includes the bridge as an internal owned process
  (started via `bridge_module.start_link` during the transport's own init).

  See `MobBle.Application` moduledoc for the boot-time config contract and
  the explicit guarantee that this module is not auto-started by the plugin
  application.
  """

  @behaviour Mob.Ble.Bridge

  # NOTE: Mob.Ble.Bridge is the canonical behaviour definition (see
  # lib/mob/ble/bridge.ex). The MeshX transport adapter's `MeshxTransportBLE.Bridge`
  # is a kept-in-sync local copy (for `meshx_transport_ble` consumers only; CONTRACT
  # SYNC marker present in both). No runtime dep exists between the packages.

  use GenServer

  require Logger

  @test_env Mix.env() == :test

  @type state :: %{
          event_target: pid(),
          config: keyword() | map(),
          local_name: binary(),
          native?: boolean()
        }

  @impl true
  def start_link(opts) do
    with :ok <- require_event_target(opts),
         :ok <- validate_start_config(opts) do
      GenServer.start_link(__MODULE__, opts)
    end
  end

  def child_spec(opts) do
    id =
      Keyword.get_lazy(opts, :id, fn ->
        if Keyword.has_key?(opts, :local_name) do
          {__MODULE__, Keyword.fetch!(opts, :local_name)}
        else
          __MODULE__
        end
      end)

    %{
      id: id,
      start: {__MODULE__, :start_link, [opts]},
      restart: :permanent,
      shutdown: 5_000,
      type: :worker
    }
  end

  defp validate_start_config(opts) do
    config = Application.get_env(:mob_ble, :config, [])

    case Mob.Ble.validate_config(config) do
      :ok -> :ok
      err -> raise "mob_ble validate_config failed: #{inspect(err)}"
    end

    validate_carrier!(Keyword.get(opts, :carrier, config_value(config, :carrier)))
  end

  defp require_event_target(opts) do
    if Keyword.has_key?(opts, :event_target) do
      :ok
    else
      {:error, {:missing_required_option, :event_target}}
    end
  end

  @impl true
  def send_frame(bridge, peer_id, frame, opts \\ []) do
    GenServer.call(bridge, {:send_frame, peer_id, frame, opts})
  end

  @impl true
  def broadcast_frame(bridge, frame, opts \\ []) do
    GenServer.call(bridge, {:broadcast_frame, frame, opts})
  end

  @impl true
  def init(opts) do
    event_target = Keyword.fetch!(opts, :event_target)
    config = Keyword.get(opts, :config, Application.get_env(:mob_ble, :config, []))
    local_name = local_name(opts, config)
    native? = native_enabled?(opts, config)

    state = %{event_target: event_target, config: config, local_name: local_name, native?: native?}

    if native? do
      with :ok <- native_call(:start_scan, [self()]),
           :ok <- native_call(:start_advertising, [self(), local_name]) do
        {:ok, state}
      else
        {:error, reason} -> {:stop, {:mob_ble_native_start_failed, reason}}
      end
    else
      {:ok, state}
    end
  end

  @impl true
  def handle_call({:send_frame, peer_id, frame, _opts}, _from, %{native?: true} = state) do
    {:reply, native_call(:send_ping, [self(), to_string(peer_id), frame]), state}
  end

  def handle_call({:send_frame, _peer_id, _frame, _opts}, _from, state) do
    {:reply, :ok, state}
  end

  def handle_call({:broadcast_frame, frame, _opts}, _from, %{native?: true} = state) do
    {:reply, native_call(:send_ping, [self(), "broadcast", frame]), state}
  end

  def handle_call({:broadcast_frame, _frame, _opts}, _from, state) do
    {:reply, :ok, state}
  end

  @impl true
  def handle_info({__MODULE__, :bridge_event, json}, state) do
    Logger.debug("Mob.Ble.MobileBridge received native event: #{inspect(json)}")

    case Mob.Ble.Internal.BridgeProtocol.decode(json) do
      {:ok, event} ->
        send(state.event_target, event)

      {:error, reason} ->
        Logger.warning("Mob.Ble.MobileBridge dropped invalid native event: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  def handle_info(_other, state), do: {:noreply, state}

  @impl true
  def terminate(_reason, %{native?: true}) do
    _ = native_call(:stop, [self()])
    :ok
  end

  def terminate(_reason, _state), do: :ok

  defp local_name(opts, config) do
    opts[:local_name] ||
      config_value(config, :local_name) ||
      "mob-ble"
  end

  defp config_value(config, key) when is_map(config), do: Map.get(config, key) || Map.get(config, to_string(key))

  defp config_value(config, key) when is_list(config), do: Keyword.get(config, key)
  defp config_value(_config, _key), do: nil

  defp native_enabled?(opts, config) do
    Keyword.get(opts, :native?, config_value(config, :native?)) != false and not @test_env
  end

  defp validate_carrier!(nil), do: :ok
  defp validate_carrier!(:mb_gatt), do: :ok

  defp validate_carrier!(carrier) do
    rejected = Enum.find(Mob.Ble.Diagnostics.rejected_carriers(), &(&1.id == carrier))

    reason =
      case rejected do
        %{reason: reason} -> reason
        _ -> :not_validated
      end

    raise Mob.Ble.CarrierRejectedError, carrier: carrier, reason: reason
  end

  defp native_call(function, args) do
    apply(:mob_ble_nif, function, args)
  rescue
    error in UndefinedFunctionError -> {:error, {:nif_unavailable, error.module, error.function}}
    error in ErlangError -> {:error, Map.get(error, :original, error)}
  catch
    :exit, reason -> {:error, reason}
  end
end
