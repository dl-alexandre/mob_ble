defmodule Mob.Ble.CarrierRejectedError do
  @moduledoc """
  Raised when a caller asks `Mob.Ble` to use a carrier that has been rejected
  by hardware evidence or platform constraints.

  This is a programmer error, not a runtime condition: there is no recovery
  path other than picking the validated carrier (`Mob.Ble.carrier/0`).
  See `Mob.Ble.Diagnostics.rejected_carriers/0` for the recorded reasons.
  """

  defexception [:carrier, :reason, :diagnostics]

  @type t :: %__MODULE__{
          carrier: atom(),
          reason: binary() | atom(),
          diagnostics: map() | nil
        }

  @impl true
  def message(%__MODULE__{carrier: carrier, reason: reason, diagnostics: diag}) do
    diag_str =
      if diag, do: "\nDiagnostics snapshot: #{inspect(diag)}", else: ""

    """
    Mob.Ble: carrier #{inspect(carrier)} is rejected.

    Reason: #{inspect(reason)}
    #{diag_str}
    The validated carrier is #{inspect(Mob.Ble.carrier())}.
    See Mob.Ble.Diagnostics.rejected_carriers/0 for the full record.
    """
  end
end
