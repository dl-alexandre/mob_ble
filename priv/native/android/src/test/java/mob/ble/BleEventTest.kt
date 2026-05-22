package mob.ble

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleEventTest {

    @Test fun `device_discovered emits canonical v1 wire map`() {
        val e = BleEvent.DeviceDiscovered(
            deviceId = "AA:BB:CC:DD:EE:01",
            rssi = -55,
            advertisement = byteArrayOf(0x02, 0x01, 0x06),
            observedAtMs = 12345L
        )
        val m = e.toWireMap()
        assertEquals(1, m["v"])
        assertEquals("device_discovered", m["event"])
        assertEquals("AA:BB:CC:DD:EE:01", m["device_id"])
        assertEquals(-55, m["rssi"])
        assertEquals(12345L, m["observed_at_ms"])
        assertTrue(m["advertisement"] is ByteArray)
        assertEquals(setOf("v", "event", "device_id", "rssi", "advertisement", "observed_at_ms"),
            m.keys)
    }

    @Test fun `advertisement_received emits canonical v1 wire map`() {
        val e = BleEvent.AdvertisementReceived(
            deviceId = "AA:BB:CC:DD:EE:02",
            rssi = -70,
            advertisement = byteArrayOf(),
            observedAtMs = 99L
        )
        val m = e.toWireMap()
        assertEquals("advertisement_received", m["event"])
        assertEquals(1, m["v"])
    }

    @Test fun `error emits closed-taxonomy kind and never invents fields`() {
        val e = BleEvent.Error(
            kind = BleEvent.Companion.ErrorKind.SCAN_FAILED,
            detail = "scan failed (code=3)"
        )
        val m = e.toWireMap()
        assertEquals("error", m["event"])
        assertEquals("scan_failed", m["kind"])
        assertEquals("scan failed (code=3)", m["detail"])
        assertNull(m["device_id"])
    }

    @Test fun `error with device_id includes the key`() {
        val e = BleEvent.Error(
            kind = BleEvent.Companion.ErrorKind.GATT_ERROR,
            detail = "x",
            deviceId = "AA:BB"
        )
        val m = e.toWireMap()
        assertEquals("AA:BB", m["device_id"])
    }

    @Test fun `JSON form base64-encodes advertisement bytes`() {
        val e = BleEvent.DeviceDiscovered(
            deviceId = "X",
            rssi = -1,
            advertisement = byteArrayOf(0x01, 0x02, 0x03),
            observedAtMs = 0
        )
        val json: JSONObject = e.toJsonObject()
        assertEquals("device_discovered", json.getString("event"))
        // base64 of {0x01,0x02,0x03} == "AQID"
        assertEquals("AQID", json.getString("advertisement"))
    }

    @Test fun `received_message JSON preserves canonical fields and binary payloads`() {
        val e = BleEvent.ReceivedMessage(
            messageId = ByteArray(16) { if (it == 15) 1 else 0 },
            senderPeerId = "mob-alpha",
            recipientPeerId = "mob-beta",
            receivedDeviceId = "AA:BB",
            receivedAt = 12345L,
            rssi = -61,
            envelope = byteArrayOf('M'.code.toByte(), 'X'.code.toByte()),
            rawTransportMetadata = BleEvent.ReceivedMessage.RawTransportMetadata(
                transport = "ble_advertisement",
                sourceEvent = "advertisement_received",
                receivedDeviceId = "AA:BB",
                advertisement = byteArrayOf(1, 2, 3),
                messagePayload = byteArrayOf('M'.code.toByte(), 'X'.code.toByte()),
                manufacturerData = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 'M'.code.toByte(), 'X'.code.toByte()),
                companyIdentifier = 65535,
                adType = 255
            )
        )

        val json = e.toJsonObject()

        assertEquals(1, json.getInt("v"))
        assertEquals("received_message", json.getString("event"))
        assertEquals("AAAAAAAAAAAAAAAAAAAAAQ==", json.getString("message_id"))
        assertEquals("mob-alpha", json.getString("sender_peer_id"))
        assertEquals("mob-beta", json.getString("recipient_peer_id"))
        assertEquals("AA:BB", json.getString("received_device_id"))
        assertEquals(12345L, json.getLong("received_at"))
        assertEquals(-61, json.getInt("rssi"))
        assertEquals("TVg=", json.getString("envelope"))

        val raw = json.getJSONObject("raw_transport_metadata")
        assertEquals("ble_advertisement", raw.getString("transport"))
        assertEquals("advertisement_received", raw.getString("source_event"))
        assertEquals("AA:BB", raw.getString("received_device_id"))
        assertEquals("AQID", raw.getString("advertisement"))
        assertEquals("TVg=", raw.getString("message_payload"))
        assertEquals("//9NWA==", raw.getString("manufacturer_data"))
        assertEquals(65535, raw.getInt("company_identifier"))
        assertEquals(255, raw.getInt("ad_type"))
    }

    @Test fun `received_message JSON keeps null recipient key for broadcasts`() {
        val e = BleEvent.ReceivedMessage(
            messageId = ByteArray(16),
            senderPeerId = "mob-alpha",
            recipientPeerId = null,
            receivedDeviceId = "AA:BB",
            receivedAt = 12345L,
            rssi = -61,
            envelope = byteArrayOf('M'.code.toByte(), 'X'.code.toByte()),
            rawTransportMetadata = BleEvent.ReceivedMessage.RawTransportMetadata(
                transport = "ble_advertisement",
                sourceEvent = "advertisement_received",
                receivedDeviceId = "AA:BB",
                advertisement = byteArrayOf(1, 2, 3),
                messagePayload = byteArrayOf('M'.code.toByte(), 'X'.code.toByte()),
                manufacturerData = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 'M'.code.toByte(), 'X'.code.toByte()),
                companyIdentifier = 65535,
                adType = 255
            )
        )

        val json = e.toJsonObject()

        assertTrue(json.has("recipient_peer_id"))
        assertTrue(json.isNull("recipient_peer_id"))
    }
}
