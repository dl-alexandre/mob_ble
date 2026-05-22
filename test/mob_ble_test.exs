defmodule MobBleTest do
  use ExUnit.Case, async: true

  test "facade exposes the canonical bridge module" do
    assert MobBle.bridge_module() == Mob.Ble.MobileBridge
    assert MobBle.default_bridge() == MobBle.bridge_module()
  end

  test "facade validates deployment config" do
    assert :ok = MobBle.validate_config(carrier: :mb_gatt)

    assert_raise Mob.Ble.CarrierRejectedError, fn ->
      MobBle.validate_config(carrier: :wifi_direct)
    end
  end
end
