defmodule MobBle do
  @moduledoc """
  Package-level facade for the `mob_ble` plugin.

  Most callers should use `Mob.Ble` directly. This module exists as a compact
  package entry point for docs, examples, and hosts that prefer the OTP
  application name as their starting namespace.
  """

  @doc "Returns the active, validated BLE carrier."
  @spec carrier() :: Mob.Ble.carrier()
  defdelegate carrier(), to: Mob.Ble

  @doc "Returns the canonical bridge module for transport adapters."
  @spec bridge_module() :: module()
  defdelegate bridge_module(), to: Mob.Ble

  @doc "Transitional alias for `bridge_module/0`."
  @spec default_bridge() :: module()
  defdelegate default_bridge(), to: Mob.Ble

  @doc "Validates deployment config."
  @spec validate_config(Mob.Ble.config()) :: :ok | Mob.Ble.validation_error()
  defdelegate validate_config(config), to: Mob.Ble
end
