defmodule Mob.Ble.Diagnostics do
  @moduledoc """
  Read-only view of `Mob.Ble`'s carrier policy.

  This module exists so operators and release self-tests can inspect the
  active carrier, rejected carriers, and the read-only evidence snapshot
  without depending on private carrier-decision modules.

  Not for runtime branching — carrier choice is fixed by `Mob.Ble.carrier/0`.
  """

  alias Mob.Ble.Internal.CarrierDecision

  @doc """
  Returns the active carrier id (the one with hardware evidence).
  """
  @spec carrier() :: atom()
  def carrier, do: Mob.Ble.carrier()

  @doc """
  Returns the full read-only carrier decision snapshot.

  The snapshot is diagnostic data, not a runtime branching API. Carrier
  selection remains fixed by `Mob.Ble.carrier/0`.

  Note: the `evidence` and `notes` fields contain historical paths and
  references (some legacy internal identifiers from prior extraction).
  These are immutable records of validation; the active carrier and policy
  are clean.
  """
  @spec snapshot() :: map()
  def snapshot, do: CarrierDecision.snapshot()

  @doc """
  Returns the list of rejected carriers with a one-line reason each.

  Each entry is `%{id: atom(), reason: binary()}`. The reason is the
  short "why," not the full evidence chain.
  """
  @spec rejected_carriers() :: [%{id: atom(), reason: binary()}]
  def rejected_carriers do
    CarrierDecision.carriers()
    |> Enum.filter(&(&1.status == :rejected))
    |> Enum.map(fn c -> %{id: c.id, reason: List.first(c.notes) || ""} end)
  end
end
