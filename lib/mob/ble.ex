defmodule Mob.Ble do
  @moduledoc """
  Production BLE transport plugin for `mob`.

  This package owns the validated BLE carrier (MB legacy beacon + GATT fetch)
  and the native Android scanner / iOS responder that implement it. It
  provides the canonical implementation of the `Mob.Ble.Bridge` behaviour
  for BLE.

  ## Public surface

    * `carrier/0` — the active, validated carrier id.
    * `validate_config/1` — called by plugin activation and the bridge on
      `start_link`; raises `Mob.Ble.CarrierRejectedError` for any rejected
      `:carrier` value so misconfigurations fail fast.
    * `assert_carrier!/1` — same enforcement, callable directly.
    * `Mob.Ble.Diagnostics` — opaque view of carrier policy.
    * `Mob.Ble.MobileBridge` — implementation of the canonical `Mob.Ble.Bridge`
      behaviour (defined in `lib/mob/ble/bridge.ex`; see also the sync copy
      under `meshx_transport_ble` for the MeshX transport adapter).

  Carrier selection is intentionally opaque. Callers do not pick a carrier;
  the package picks the one with hardware evidence and raises
  `Mob.Ble.CarrierRejectedError` for anything else.

  ## Native initialization note
  On Android the JNI class cache (`mob_ble_cache_class`) is populated by the
  generated plugin dispatcher during `JNI_OnLoad` before any NIF calls. The
  bridge and NIF surfaces tolerate a "not yet cached" state on first use
  (lazy).
  """

  alias Mob.Ble.Internal.CarrierDecision

  @type carrier :: :mb_gatt
  @type config :: keyword() | map()
  @type validation_error :: {:error, {:invalid_config, atom(), term()}}

  @doc "Returns the active, validated carrier id."
  @spec carrier() :: carrier()
  def carrier, do: CarrierDecision.active()

  @doc "Returns the bridge module to use when this plugin is active."
  @spec bridge_module() :: module()
  def bridge_module, do: Mob.Ble.MobileBridge

  @doc "Transitional alias for `bridge_module/0`."
  @spec default_bridge() :: module()
  def default_bridge, do: bridge_module()

  @doc """
  Asserts the given carrier is the active, validated one. Raises
  `Mob.Ble.CarrierRejectedError` otherwise.

  Used by the mobile bridge startup path and `validate_config/1` so the
  rejected-carrier policy is enforced at startup, not at first send.
  """
  @spec assert_carrier!(atom()) :: :ok | no_return()
  def assert_carrier!(carrier_id) when is_atom(carrier_id) do
    case CarrierDecision.check(carrier_id) do
      :ok ->
        :ok

      {:rejected, %{reason: reason, diagnostics: diag}} ->
        raise Mob.Ble.CarrierRejectedError,
          carrier: carrier_id,
          reason: reason,
          diagnostics: diag
    end
  end

  @doc """
  Validates deployment-wide configuration.

  Supported keys (`config :mob_ble, config: [...]`):

    * `:carrier`        — must be `Mob.Ble.carrier/0` if set; any other value
                          raises `Mob.Ble.CarrierRejectedError`.
    * `:evidence_mode`  — `:production` or `:diagnostic` (default `:production`).
    * `:log_level`      — atom passed to `Logger`.
    * `:diagnostics`    — boolean toggle for self-test hooks.

  Unknown keys are tolerated (forward-compat); type-mismatched known keys
  return `{:error, {:invalid_config, key, value}}`. The `:carrier` key is
  the only one that raises — a rejected carrier is a programmer error, not
  a config typo we should swallow.
  """
  @spec validate_config(config()) :: :ok | validation_error()
  def validate_config(config) when is_list(config) or is_map(config) do
    cfg = Map.new(Enum.to_list(config))

    with :ok <- check_carrier(cfg),
         :ok <- check_evidence_mode(cfg),
         :ok <- check_log_level(cfg) do
      check_diagnostics(cfg)
    end
  end

  defp check_carrier(%{carrier: c}) when is_atom(c), do: assert_carrier!(c)
  defp check_carrier(_), do: :ok

  defp check_evidence_mode(%{evidence_mode: m}) when m in [:production, :diagnostic], do: :ok

  defp check_evidence_mode(%{evidence_mode: m}),
    do: {:error, {:invalid_config, :evidence_mode, m}}

  defp check_evidence_mode(_), do: :ok

  defp check_log_level(%{log_level: l}) when is_atom(l), do: :ok
  defp check_log_level(%{log_level: l}), do: {:error, {:invalid_config, :log_level, l}}
  defp check_log_level(_), do: :ok

  defp check_diagnostics(%{diagnostics: d}) when is_boolean(d), do: :ok
  defp check_diagnostics(%{diagnostics: d}), do: {:error, {:invalid_config, :diagnostics, d}}
  defp check_diagnostics(_), do: :ok
end
