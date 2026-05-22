defmodule Mob.Ble.Internal.BridgeProtocolTest do
  use ExUnit.Case, async: true

  alias Mob.Ble.Internal.BridgeProtocol

  describe "decode/1 — v1 wire format" do
    test "peer_up with metadata map" do
      json = ~s({"v":1,"event":"peer_up","peer_id":"abc","metadata":{"rssi":-42}})
      assert {:ok, {:ble_peer_up, "abc", %{"rssi" => -42}}} = BridgeProtocol.decode(json)
    end

    test "peer_up tolerates missing metadata (defaults to empty map)" do
      assert {:ok, {:ble_peer_up, "abc", %{}}} =
               BridgeProtocol.decode(%{"v" => 1, "event" => "peer_up", "peer_id" => "abc"})
    end

    test "peer_down" do
      json = ~s({"v":1,"event":"peer_down","peer_id":"abc"})
      assert {:ok, {:ble_peer_down, "abc"}} = BridgeProtocol.decode(json)
    end

    test "frame with base64-encoded payload" do
      frame_bytes = <<1, 2, 3, 4, 5>>
      b64 = Base.encode64(frame_bytes)
      json = ~s({"v":1,"event":"frame","peer_id":"abc","frame":"#{b64}"})
      assert {:ok, {:ble_frame, "abc", ^frame_bytes}} = BridgeProtocol.decode(json)
    end

    test "frame falls back to raw binary when not valid base64" do
      json = ~s({"v":1,"event":"frame","peer_id":"abc","frame":"not-base64!"})
      assert {:ok, {:ble_frame, "abc", "not-base64!"}} = BridgeProtocol.decode(json)
    end

    test "received_message alias maps to ble_frame" do
      payload = Base.encode64(<<9, 9, 9>>)
      json = ~s({"v":1,"event":"received_message","peer_id":"p","payload":"#{payload}"})
      assert {:ok, {:ble_frame, "p", <<9, 9, 9>>}} = BridgeProtocol.decode(json)
    end

    test "received_message with envelope key (Android/iOS real shape) maps to ble_frame" do
      env = Base.encode64(<<1, 2, 3, 4>>)
      json = ~s({"v":1,"event":"received_message","sender_peer_id":"p2","envelope":"#{env}"})
      assert {:ok, {:ble_frame, "p2", <<1, 2, 3, 4>>}} = BridgeProtocol.decode(json)
    end

    test "received_message using received_device_id fallback (real iOS shape) maps to ble_frame" do
      env = Base.encode64(<<5, 6, 7>>)

      json =
        ~s({"v":1,"event":"received_message","received_device_id":"dev3","envelope":"#{env}"})

      assert {:ok, {:ble_frame, "dev3", <<5, 6, 7>>}} = BridgeProtocol.decode(json)
    end

    test "advertisement_received maps to ble_peer_up (native contract)" do
      json =
        ~s({"v":1,"event":"advertisement_received","device_id":"dev-42","rssi":-61,"advertisement":"YWJj"})

      assert {:ok, {:ble_peer_up, "dev-42", %{"rssi" => -61}}} = BridgeProtocol.decode(json)
    end

    test "device_discovered maps to ble_peer_up" do
      json = ~s({"v":1,"event":"device_discovered","device_id":"d1","rssi":-70})
      assert {:ok, {:ble_peer_up, "d1", %{"rssi" => -70}}} = BridgeProtocol.decode(json)
    end

    test "error and status tags return structured errors (not crash)" do
      assert {:error, {:native_error, _}} =
               BridgeProtocol.decode(~s({"v":1,"event":"error","kind":"nif"}))

      assert {:error, {:native_status, _}} =
               BridgeProtocol.decode(~s({"v":1,"event":"status","detail":"scanning"}))
    end

    test "received_message_beacon tolerated (future mapping)" do
      json = ~s({"v":1,"event":"received_message_beacon","peer_id":"b1"})

      assert {:error, {:missing_required_fields, "received_message_beacon"}} =
               BridgeProtocol.decode(json)
    end
  end

  describe "decode/1 — errors" do
    test "unsupported wire version" do
      assert {:error, {:unsupported_wire_version, 2}} =
               BridgeProtocol.decode(~s({"v":2,"event":"peer_up","peer_id":"x"}))
    end

    test "unknown event tag" do
      assert {:error, {:unknown_event_tag, "mystery"}} =
               BridgeProtocol.decode(~s({"v":1,"event":"mystery","peer_id":"x"}))
    end

    test "invalid JSON" do
      assert {:error, {:invalid_bridge_json, _}} = BridgeProtocol.decode("not json")
    end

    test "missing required field" do
      assert {:error, {:missing_required_fields, "peer_up"}} =
               BridgeProtocol.decode(~s({"v":1,"event":"peer_up"}))
    end

    test "non-map JSON payload" do
      assert {:error, {:unrecognized_bridge_payload, _}} = BridgeProtocol.decode("[]")
    end
  end
end
