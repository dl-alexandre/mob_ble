defmodule Mob.Ble.Error do
  @moduledoc """
  Public error taxonomy for bridge and native BLE operations.

  The taxonomy is intentionally small and caller-oriented: each category tells
  a host whether retrying makes sense, whether backoff is required, or whether
  the problem is configuration/platform/protocol related.
  """

  @type category ::
          :transient
          | :backoff
          | :platform
          | :permission
          | :protocol
          | :configuration
          | :permanent

  @type operation ::
          :start_scan
          | :start_advertising
          | :send_frame
          | :broadcast_frame
          | :connect
          | :service_discovery
          | :write
          | :read
          | atom()

  @type reason :: term()

  @type t :: %__MODULE__{
          category: category(),
          operation: operation(),
          reason: reason(),
          retryable?: boolean(),
          retry_after_ms: non_neg_integer() | nil,
          metadata: map()
        }

  @enforce_keys [:category, :operation, :reason, :retryable?, :metadata]
  defstruct [:category, :operation, :reason, :retryable?, :retry_after_ms, :metadata]

  @doc "Normalizes arbitrary bridge/native error reasons into `Mob.Ble.Error`."
  @spec classify(operation(), reason(), keyword()) :: t()
  def classify(operation, reason, opts \\ []) when is_atom(operation) do
    category = Keyword.get(opts, :category, infer_category(reason))
    retry_after_ms = Keyword.get(opts, :retry_after_ms)

    %__MODULE__{
      category: category,
      operation: operation,
      reason: reason,
      retryable?: retryable?(category),
      retry_after_ms: retry_after_ms,
      metadata: Map.new(Keyword.get(opts, :metadata, %{}))
    }
  end

  @doc "Returns true when this category can be retried by a caller."
  @spec retryable?(category()) :: boolean()
  def retryable?(category) when category in [:transient, :backoff], do: true
  def retryable?(_category), do: false

  @doc "Returns true when retrying should wait for a backoff delay."
  @spec backoff?(category() | t()) :: boolean()
  def backoff?(%__MODULE__{category: category}), do: backoff?(category)
  def backoff?(category), do: category == :backoff

  @spec infer_category(reason()) :: category()
  defp infer_category({:missing_required_option, _key}), do: :configuration
  defp infer_category({:invalid_config, _key, _value}), do: :configuration
  defp infer_category({:invalid_bridge_json, _reason}), do: :protocol
  defp infer_category({:unsupported_wire_version, _version}), do: :protocol
  defp infer_category({:unknown_event_tag, _tag}), do: :protocol
  defp infer_category({:missing_required_fields, _tag}), do: :protocol
  defp infer_category({:native_error, %{"permission" => _}}), do: :permission
  defp infer_category({:native_error, %{"platform" => _}}), do: :platform
  defp infer_category({:nif_unavailable, _module, _function}), do: :platform
  defp infer_category(:timeout), do: :backoff
  defp infer_category(:busy), do: :backoff
  defp infer_category(:eagain), do: :backoff
  defp infer_category({:shutdown, _reason}), do: :transient
  defp infer_category(_reason), do: :permanent
end
