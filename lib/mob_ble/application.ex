defmodule MobBle.Application do
  @moduledoc """
  OTP Application for the `mob_ble` plugin.

  Started automatically when `:mob_ble` is listed in the host app's
  applications list or when the `mob` framework activates the plugin via
  `config :mob, :plugins, [:mob_ble]`.

  ## Configuration

  Deployment configuration is read from the application environment under
  the `:config` key:

      config :mob_ble, config: [
        evidence_mode: :production,   # :production | :diagnostic
        log_level: :info,
        diagnostics: false,
        local_name: "my-app-ble"      # fallback advertising name
      ]

  All values are validated at boot by `Mob.Ble.validate_config/1`. Unknown
  keys are tolerated for forward compatibility. The `:carrier` key, if
  present, must match the single validated carrier or a
  `Mob.Ble.CarrierRejectedError` is raised immediately.

  ## Responsibilities

  - Read `config :mob_ble, config: [...]` and call `Mob.Ble.validate_config/1`
    before any children are started.
  - Start an internal supervision tree (currently empty; the placeholder
    `MobBle.Supervisor` name is registered for future NIF owner / diagnostics
    coordinator children).
  - **Never** start `Mob.Ble.MobileBridge`. The bridge (the `Mob.Ble.Bridge`
    behaviour implementation — see `apps/mob_ble/lib/mob/ble/bridge.ex` for the
    canonical rich contract) is always started on-demand by the BLE
    transport layer using `Mob.Ble.bridge_module().start_link(bridge_opts)`.
    The transport supplies the authoritative `event_target` so events are
    routed correctly. This design guarantees the lifecycle/ownership
    contract required by the transport.

  ## Example — direct invocation (primarily for tests)

      Application.put_env(:mob_ble, :config, [evidence_mode: :diagnostic])
      {:ok, sup} = MobBle.Application.start(:normal, [])
      # ... later
      Supervisor.stop(sup)

  ## Native notes

  The real NIF loading for the static NIF case is handled by the `mob`
  driver bootstrap (static symbol registration). Dynamic fallback uses the
  conventional `:mob_ble_nif` module name declared in `src/mob_ble_nif.erl`.
  """

  use Application

  require Logger

  @impl true
  def start(_type, _args) do
    config = Application.get_env(:mob_ble, :config, [])
    Logger.info("mob_ble starting with config: #{inspect(config)}")

    case Mob.Ble.validate_config(config) do
      :ok -> :ok
      err -> raise "mob_ble validate_config failed: #{inspect(err)}"
    end

    children = [
      # Placeholder for future NIF loader / owner registrar / diagnostics
      # coordinator. Currently empty because the MobileBridge lifecycle
      # must be owned by the caller of `Mob.Ble.bridge_module().start_link/1`
      # (see moduledoc). This is the critical ownership invariant.
    ]

    opts = [strategy: :one_for_one, name: MobBle.Supervisor]
    Supervisor.start_link(children, opts)
  end

  @impl true
  def stop(_state) do
    Logger.info("mob_ble stopping")
    :ok
  end
end
