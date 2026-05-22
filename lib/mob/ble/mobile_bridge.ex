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

  alias Mob.Ble.Backoff
  alias Mob.Ble.Diagnostics.Metrics
  alias Mob.Ble.Error

  @test_env Mix.env() == :test

  @type state :: %{
          event_target: pid(),
          config: keyword() | map(),
          local_name: binary(),
          native?: boolean(),
          native_module: module(),
          backoff: Backoff.t(),
          metrics: Metrics.t()
        }

  @spec start_link(keyword()) :: GenServer.on_start()
  @impl true
  def start_link(opts) do
    with :ok <- require_event_target(opts),
         :ok <- validate_start_config(opts) do
      GenServer.start_link(__MODULE__, opts)
    end
  end

  @spec child_spec(keyword()) :: Supervisor.child_spec()
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

  @spec send_frame(pid(), term(), binary(), keyword()) :: :ok | {:error, term()}
  @impl true
  def send_frame(bridge, peer_id, frame, opts \\ []) do
    GenServer.call(bridge, {:send_frame, peer_id, frame, opts})
  end

  @spec broadcast_frame(pid(), binary(), keyword()) :: :ok | {:error, term()}
  @impl true
  def broadcast_frame(bridge, frame, opts \\ []) do
    GenServer.call(bridge, {:broadcast_frame, frame, opts})
  end

  @doc "Returns the bridge diagnostics summary and raw metrics snapshot."
  @spec diagnostics(pid() | GenServer.name()) :: %{summary: map(), metrics: Metrics.t()}
  def diagnostics(bridge) do
    GenServer.call(bridge, :diagnostics)
  end

  @impl true
  def init(opts) do
    event_target = Keyword.fetch!(opts, :event_target)
    config = Keyword.get(opts, :config, Application.get_env(:mob_ble, :config, []))
    local_name = local_name(opts, config)
    native? = native_enabled?(opts, config)
    native_module = Keyword.get(opts, :native_module, :mob_ble_nif)

    backoff = backoff_policy(opts, config)

    state = %{
      event_target: event_target,
      config: config,
      local_name: local_name,
      native?: native?,
      native_module: native_module,
      backoff: backoff,
      metrics: Metrics.new()
    }

    if native? do
      with {:ok, state} <- native_operation(state, :start_scan, [self()]),
           {:ok, state} <- native_operation(state, :start_advertising, [self(), local_name]) do
        {:ok, state}
      else
        {:error, error, state} -> {:stop, {:mob_ble_native_start_failed, error}, state}
      end
    else
      {:ok, state}
    end
  end

  @impl true
  def handle_call({:send_frame, peer_id, frame, _opts}, _from, %{native?: true} = state) do
    case native_operation(state, :send_frame, [self(), to_string(peer_id), frame]) do
      {:ok, state} -> {:reply, :ok, state}
      {:error, error, state} -> {:reply, {:error, error}, state}
    end
  end

  def handle_call({:send_frame, _peer_id, _frame, _opts}, _from, state) do
    {:reply, :ok, state}
  end

  def handle_call({:broadcast_frame, frame, _opts}, _from, %{native?: true} = state) do
    case native_operation(state, :broadcast_frame, [self(), "broadcast", frame]) do
      {:ok, state} -> {:reply, :ok, state}
      {:error, error, state} -> {:reply, {:error, error}, state}
    end
  end

  def handle_call({:broadcast_frame, _frame, _opts}, _from, state) do
    {:reply, :ok, state}
  end

  def handle_call(:diagnostics, _from, state) do
    diagnostics = %{summary: Metrics.summary(state.metrics), metrics: state.metrics}
    {:reply, diagnostics, state}
  end

  @impl true
  def handle_info({__MODULE__, :bridge_event, json}, state) do
    Logger.debug("Mob.Ble.MobileBridge received native event: #{inspect(json)}")

    case Mob.Ble.Internal.BridgeProtocol.decode(json) do
      {:ok, event} ->
        send(state.event_target, event)
        {:noreply, %{state | metrics: Metrics.observe_event(state.metrics, event)}}

      {:error, reason} ->
        error = Error.classify(:bridge_event, reason)
        Logger.warning("Mob.Ble.MobileBridge dropped invalid native event: #{inspect(error)}")

        {:noreply,
         %{state | metrics: Metrics.observe_error(state.metrics, :bridge_event, reason)}}
    end
  end

  def handle_info(_other, state), do: {:noreply, state}

  @impl true
  def terminate(_reason, %{native?: true, native_module: native_module}) do
    _ = native_call(native_module, :stop, [self()])
    :ok
  end

  def terminate(_reason, _state), do: :ok

  defp local_name(opts, config) do
    opts[:local_name] ||
      config_value(config, :local_name) ||
      "mob-ble"
  end

  defp config_value(config, key) when is_map(config),
    do: Map.get(config, key) || Map.get(config, to_string(key))

  defp config_value(config, key) when is_list(config), do: Keyword.get(config, key)
  defp config_value(_config, _key), do: nil

  defp native_enabled?(opts, config) do
    requested? = Keyword.get(opts, :native?, config_value(config, :native?)) != false

    fake_native? =
      Keyword.has_key?(opts, :native_module) and Keyword.get(opts, :native?, true) != false

    fake_native? or (requested? and not @test_env)
  end

  defp backoff_policy(opts, config) do
    opts
    |> Keyword.get(:backoff, config_value(config, :backoff) || [])
    |> Backoff.new()
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

  defp native_operation(state, operation, args) do
    do_native_operation(state, operation, args, 1)
  end

  defp do_native_operation(state, operation, args, attempt) do
    case native_call(state.native_module, native_function(operation), args) do
      :ok ->
        {:ok,
         Metrics.observe_connection(state.metrics, operation_peer(args), %{
           operation: operation,
           attempt: attempt,
           terminal_status: :ok
         })
         |> then(&%{state | metrics: &1})}

      {:error, reason} ->
        error = Error.classify(operation, reason)
        state = %{state | metrics: Metrics.observe_error(state.metrics, operation, reason)}

        case retry_decision(state.backoff, attempt, error) do
          {:retry, delay_ms} ->
            Logger.debug(
              "Mob.Ble.MobileBridge retrying #{operation} after #{delay_ms}ms: #{inspect(error)}"
            )

            Process.sleep(delay_ms)
            do_native_operation(state, operation, args, attempt + 1)

          :halt ->
            Logger.warning("Mob.Ble.MobileBridge native #{operation} failed: #{inspect(error)}")
            {:error, error, state}
        end
    end
  end

  defp native_function(:send_frame), do: :send_ping
  defp native_function(:broadcast_frame), do: :send_ping
  defp native_function(operation), do: operation

  defp retry_decision(backoff, attempt, %Error{retryable?: true}),
    do: Backoff.next(backoff, attempt + 1)

  defp retry_decision(_backoff, _attempt, _error), do: :halt

  defp operation_peer([_owner, peer_id, _frame]) when is_binary(peer_id), do: peer_id
  defp operation_peer(_args), do: "bridge"

  defp native_call(native_module, function, args) do
    apply(native_module, function, args)
  rescue
    error in UndefinedFunctionError -> {:error, {:nif_unavailable, error.module, error.function}}
    error in ErlangError -> {:error, Map.get(error, :original, error)}
  catch
    :exit, reason -> {:error, reason}
  end
end
