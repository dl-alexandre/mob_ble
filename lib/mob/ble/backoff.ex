defmodule Mob.Ble.Backoff do
  @moduledoc """
  Bounded exponential backoff helper for bridge retry loops.

  The module is pure and does not sleep. Bridge/native callers use `next/2` to
  decide whether another attempt is allowed and how long the caller should wait.
  """

  @type t :: %__MODULE__{
          base_ms: pos_integer(),
          max_ms: pos_integer(),
          max_attempts: pos_integer(),
          jitter_ms: non_neg_integer()
        }

  @enforce_keys [:base_ms, :max_ms, :max_attempts, :jitter_ms]
  defstruct base_ms: 100, max_ms: 5_000, max_attempts: 5, jitter_ms: 0

  @doc "Builds a backoff policy, validating bounds."
  @spec new(keyword()) :: t()
  def new(opts \\ []) do
    policy = %__MODULE__{
      base_ms: Keyword.get(opts, :base_ms, 100),
      max_ms: Keyword.get(opts, :max_ms, 5_000),
      max_attempts: Keyword.get(opts, :max_attempts, 5),
      jitter_ms: Keyword.get(opts, :jitter_ms, 0)
    }

    if policy.base_ms <= 0 or policy.max_ms <= 0 or policy.max_attempts <= 0 or
         policy.jitter_ms < 0 do
      raise ArgumentError, "invalid backoff policy: #{inspect(policy)}"
    end

    policy
  end

  @doc """
  Returns `{:retry, delay_ms}` for a 1-based attempt, or `:halt`.
  """
  @spec next(t(), pos_integer()) :: {:retry, non_neg_integer()} | :halt
  def next(%__MODULE__{} = policy, attempt) when is_integer(attempt) and attempt > 0 do
    if attempt > policy.max_attempts do
      :halt
    else
      delay =
        policy.base_ms
        |> Kernel.*(Integer.pow(2, attempt - 1))
        |> min(policy.max_ms)
        |> add_jitter(policy.jitter_ms)

      {:retry, delay}
    end
  end

  defp add_jitter(delay, 0), do: delay
  defp add_jitter(delay, jitter_ms), do: delay + :rand.uniform(jitter_ms + 1) - 1
end
