defmodule Mob.Ble.ErrorTest do
  use ExUnit.Case, async: true

  alias Mob.Ble.Backoff
  alias Mob.Ble.Error

  test "classifies configuration errors as non-retryable" do
    error = Error.classify(:start_scan, {:missing_required_option, :event_target})

    assert error.category == :configuration
    refute error.retryable?
    refute Error.backoff?(error)
  end

  test "classifies protocol errors as non-retryable" do
    error = Error.classify(:read, {:unsupported_wire_version, 99})

    assert error.category == :protocol
    refute error.retryable?
  end

  test "classifies busy and timeout errors as backoff retryable" do
    assert %Error{category: :backoff, retryable?: true} = Error.classify(:connect, :timeout)
    assert %Error{category: :backoff, retryable?: true} = Error.classify(:write, :busy)
  end

  test "allows callers to override category and attach metadata" do
    error = Error.classify(:read, :platform_denied, category: :platform, metadata: %{api: 28})

    assert error.category == :platform
    assert error.metadata == %{api: 28}
    refute error.retryable?
  end

  test "backoff policy is bounded by attempts and max delay" do
    policy = Backoff.new(base_ms: 100, max_ms: 250, max_attempts: 3)

    assert Backoff.next(policy, 1) == {:retry, 100}
    assert Backoff.next(policy, 2) == {:retry, 200}
    assert Backoff.next(policy, 3) == {:retry, 250}
    assert Backoff.next(policy, 4) == :halt
  end

  test "backoff rejects invalid policy bounds" do
    assert_raise ArgumentError, fn -> Backoff.new(base_ms: 0) end
  end
end
