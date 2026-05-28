package mob.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.security.SecureRandom

/**
 * JNI bridge between the statically-linked `mob_ble_nif` (c_src/mob_ble_nif.c)
 * and the Kotlin BLE transport (`RealBleBridge` + `BleDispatcher`).
 *
 * Direction of calls:
 *
 *   NIF  → Kotlin : `startScan` / `startAdvertising` / `stop` / `sendToPeer`
 *                   (the NIF resolves these via GetStaticMethodID and
 *                   invokes them with CallStaticBooleanMethod).
 *   Kotlin → NIF  : `nativeDeliverEvent` — the BeamEventSink forwards each
 *                   `BleEvent` as its v1 wire-format JSON; the NIF wraps it
 *                   in `{Mob.Ble.MobileBridge, :bridge_event, json}`
 *                   and sends it to the owner pid.
 *
 * `sendFullMxEnvelope` / `stopFullMxResponder` are deliberately
 * Kotlin-only (no JNI binding yet). They're the dev-mode opt-in for
 * full-MX-envelope dispatch via GATT fetch; the JNI surface stays
 * MB-only until the BEAM transport policy explicitly asks for the new
 * mode. See `docs/BLE_BRIDGE.md` § "iOS production receive capabilities".
 *
 * `init/1` must be called once (from MainActivity.onCreate) to supply an
 * application Context — `RealBleBridge` / `BleDispatcher` are created lazily
 * on first use so a BLE-less host/unit context never touches the adapter.
 *
 * Error policy: every failure path on this surface surfaces a canonical
 * `BleEvent.Error` through the sink so the BEAM sees
 * `%Mob.Ble.Events.Error{kind, detail}` instead of having the
 * exception swallowed by the Kotlin try/catch. The boolean return value
 * remains the synchronous accept/reject signal for the NIF caller.
 */
object MobBleNative {
    private const val TAG = "MobBleNative"

    @Volatile private var appContext: Context? = null
    @Volatile private var bridge: RealBleBridge? = null
    @Volatile private var dispatcher: BleDispatcher? = null
    @Volatile private var fetchResponder: MobFetchGatt? = null
    @Volatile private var fetchOnBeaconEnabled: Boolean = false
    @Volatile private var selftestSendEnabled: Boolean = true
    private val random = SecureRandom()

    /** Kotlin → NIF. Implemented in c_src/mob_ble_nif.c. */
    @JvmStatic
    private external fun nativeDeliverEvent(json: String)

    /** Called once from MainActivity.onCreate, before the BEAM starts. */
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "init: application context set")
    }

    @JvmStatic
    fun setFetchOnBeaconEnabled(enabled: Boolean) {
        fetchOnBeaconEnabled = enabled
        bridge = null
        Log.i(TAG, "setFetchOnBeaconEnabled=$enabled")
    }

    @JvmStatic
    fun setSelftestSendEnabled(enabled: Boolean) {
        selftestSendEnabled = enabled
        Log.i(TAG, "setSelftestSendEnabled=$enabled")
    }

    // Every BleEvent the scanner/advertiser/dispatcher emits is forwarded
    // to the BEAM as its canonical v1 wire JSON — the same shape the Elixir
    // BridgeProtocol decoder already understands.
    private val sink = BleEventSink { event ->
        try {
            nativeDeliverEvent(event.toJsonObject().toString())
        } catch (t: Throwable) {
            Log.e(TAG, "nativeDeliverEvent failed", t)
        }
    }

    private fun emitBridgeError(kind: String, detail: String) {
        Log.w(TAG, "bridge error $kind: $detail")
        try {
            sink.accept(BleEvent.Error(kind = kind, detail = detail))
        } catch (t: Throwable) {
            // Never let the error surface throw — if even the error pipe
            // is broken, log and continue rather than escalate.
            Log.e(TAG, "emitBridgeError failed: $kind", t)
        }
    }

    private fun contextOrNull(): Context? {
        val ctx = appContext
        if (ctx == null) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "MobBleNative.init(context) was not called before a BLE command"
            )
        }
        return ctx
    }

    @Synchronized
    private fun ensureBridgeOrNull(): RealBleBridge? {
        bridge?.let { return it }
        val ctx = contextOrNull() ?: return null
        return try {
            RealBleBridge(ctx, sink, fetchOnBeaconEnabled).also { bridge = it }
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "RealBleBridge construction failed: ${t.message ?: t.javaClass.simpleName}"
            )
            null
        }
    }

    @Synchronized
    private fun ensureDispatcherOrNull(): BleDispatcher? {
        dispatcher?.let { return it }
        val ctx = contextOrNull() ?: return null
        return try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter
            if (adapter == null) {
                emitBridgeError(
                    BleEvent.Companion.ErrorKind.BLUETOOTH_OFF,
                    "BluetoothManager.adapter is null (BT unsupported or BluetoothManager service unavailable)"
                )
                return null
            }
            BleDispatcher(adapter, sink).also { dispatcher = it }
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "BleDispatcher construction failed: ${t.message ?: t.javaClass.simpleName}"
            )
            null
        }
    }

    // The Mob peer id this device advertises under — derived from the
    // MOB_NODE_SUFFIX the launcher set, matching the local name fed to
    // start_advertising. Used as the envelope sender_peer_id.
    private fun localName(): String =
        "mob-" + (System.getenv("MOB_NODE_SUFFIX")?.takeIf { it.isNotBlank() } ?: "dev")

    // ── NIF → Kotlin commands. Return true = accepted, false = rejected. ──────

    @JvmStatic
    fun startScan(): Boolean {
        val b = ensureBridgeOrNull() ?: return false
        return try {
            b.startScan()
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.SCAN_FAILED,
                "startScan threw: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
    }

    /**
     * RT-01 lever 2a: re-register the active scan from the *current* process
     * context. When invoked from the foreground service (after startForeground),
     * Android attributes the scan to a foreground app, which is exempt from the
     * screen-off background-scan suspension that otherwise freezes locked
     * receive. Stops then restarts so the registration is rebound even if the
     * BEAM session already started scanning from background importance.
     */
    @JvmStatic
    fun restartScanFromForeground(): Boolean {
        val b = ensureBridgeOrNull() ?: return false
        return try {
            b.stopScan()
            b.startScan()
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.SCAN_FAILED,
                "restartScanFromForeground threw: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
    }

    @JvmStatic
    fun startAdvertising(localName: String): Boolean {
        if (localName.isBlank()) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.ADVERTISE_FAILED,
                "startAdvertising rejected: localName is blank"
            )
            return false
        }
        val b = ensureBridgeOrNull() ?: return false
        return try {
            b.startAdvertising(localName)
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.ADVERTISE_FAILED,
                "startAdvertising threw: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
    }

    @JvmStatic
    fun stop(): Boolean {
        return try {
            bridge?.let {
                it.stopScan()
                it.stopAdvertising()
            }
            true
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "stop threw: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
    }

    /**
     * Doze / adapter-cycle hook. MainActivity registers a
     * BroadcastReceiver for `BluetoothAdapter.ACTION_STATE_CHANGED` and
     * forwards the new state here. The bridge tracks scan/advertise
     * *intent* across radio off/on cycles and auto-replays it when the
     * adapter comes back, so the BEAM observes a brief Error event
     * instead of the whole BLE plane disappearing until a manual restart.
     */
    @JvmStatic
    fun onBluetoothStateChanged(state: Int) {
        try {
            bridge?.onBluetoothStateChanged(state)
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "onBluetoothStateChanged threw: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    /**
     * Dev-mode full-MX-envelope send.
     *
     * Wraps `payload` in a v1 `MobMessageEnvelope` (broadcast),
     * starts a `MobFetchGatt` responder serving that envelope (so
     * peers can pull it via GATT — see `docs/BLE_BRIDGE.md` for why
     * iOS needs this), and dispatches a 22-byte MB legacy beacon
     * cueing peers to fetch.
     *
     * Default `sendToPeer/2` continues to dispatch MB-only, which is
     * what the shipping app should keep doing — extended advertising
     * is not universally receivable across the fleet. This method is
     * the deliberate opt-in for cross-platform validation builds and
     * the `MXFullEnvelopeSmokeTest` instrumented test. It is not
     * (yet) wired through the JNI surface; callers are limited to
     * Kotlin code (MainActivity dev hooks, instrumented tests).
     *
     * The responder remains advertising the connectable fetch service
     * indefinitely until `stopFullMxResponder/0` is called. Calling
     * this method again with a different envelope tears down the
     * previous responder and starts a fresh one.
     *
     * Returns `true` if both the responder started AND the MB beacon
     * dispatch was accepted by the radio. A `false` return leaves no
     * responder running.
     */
    @JvmStatic
    fun sendFullMxEnvelope(peerId: String, payload: ByteArray): Boolean {
        val ctx = contextOrNull() ?: return false
        val disp = ensureDispatcherOrNull() ?: return false
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter
        if (adapter == null) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.BLUETOOTH_OFF,
                "sendFullMxEnvelope: BluetoothAdapter unavailable"
            )
            return false
        }

        val target = peerId.ifBlank { "broadcast" }
        val messageId = ByteArray(16).also { random.nextBytes(it) }
        val envelope = try {
            MobMessageEnvelope.buildV1(
                messageId = messageId,
                senderPeerId = localName(),
                recipientPeerId = null,
                createdAtMs = System.currentTimeMillis(),
                ttl = 1,
                payloadType = "TX",
                payload = payload
            )
        } catch (e: IllegalArgumentException) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "sendFullMxEnvelope: envelope build rejected: ${e.message ?: "invalid argument"}"
            )
            return false
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "sendFullMxEnvelope: envelope build threw: ${t.message ?: t.javaClass.simpleName}"
            )
            return false
        }

        // Tear down any previous responder before starting a new one.
        // MobFetchGatt only serves one envelope at a time; starting a
        // second responder over an old one would race the advertise
        // session and confuse fetch clients still mid-handshake.
        synchronized(this) {
            fetchResponder?.stopResponder()
            fetchResponder = null
        }

        val responder = MobFetchGatt(ctx, adapter)
        val responderStarted = try {
            responder.startResponder(envelope = envelope, responderPeerId = localName())
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "sendFullMxEnvelope: responder start threw: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
        if (!responderStarted) {
            // startResponder already logged the failure reason via its
            // own event sink; surface a structured rejection too.
            emitBridgeError(
                BleEvent.Companion.ErrorKind.ADVERTISE_FAILED,
                "sendFullMxEnvelope: MobFetchGatt responder failed to start"
            )
            return false
        }
        fetchResponder = responder

        return try {
            val result = disp.dispatch(
                attemptId = "mx-${System.currentTimeMillis()}",
                messageId = messageId,
                targetPeerId = target,
                targetDeviceIds = listOf(target),
                payload = envelope,
                forceLegacyBeacon = true
            )
            val accepted = result.kind == BleDispatcher.BleDispatchResult.Kind.DISPATCHED
            Log.i(TAG, "sendFullMxEnvelope($target, ${payload.size}B) -> ${result.kind} reason=${result.reason}")
            if (!accepted) {
                // Beacon dispatch failed → tear down the responder so
                // we don't leave it advertising for a message no peer
                // was cued to fetch.
                responder.stopResponder()
                fetchResponder = null
            }
            accepted
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "sendFullMxEnvelope: dispatch threw: ${t.message ?: t.javaClass.simpleName}"
            )
            responder.stopResponder()
            fetchResponder = null
            false
        }
    }

    /**
     * Tears down any GATT-fetch responder started by
     * [sendFullMxEnvelope]. Safe to call when nothing is running.
     */
    @JvmStatic
    fun stopFullMxResponder(): Boolean {
        val responder = synchronized(this) {
            val r = fetchResponder
            fetchResponder = null
            r
        } ?: return true

        return try {
            responder.stopResponder()
            true
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "stopFullMxResponder threw: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
    }

    /**
     * Test/diagnostic accessor for the active responder's success counters.
     * Public (not `internal`) so instrumented tests in the consuming app's
     * `:app` module — not `:mob_ble_android` — can reach it; module-internal
     * visibility is invisible across that boundary. Returns the public
     * `MobFetchGatt`; not intended for production callers.
     */
    @JvmStatic
    fun activeResponder(): MobFetchGatt? = fetchResponder

    /**
     * Public send entry point — called from the JNI bridge.
     *
     * Routes based on `MobBleConfig.useFullMxEnvelopes`:
     *
     *   - false (production / default debug builds): dispatch a 22-byte
     *     MB legacy beacon. Fleet-safe — every device including API 28
     *     hardware can both send and receive it. Full-payload retrieval
     *     is a separate concern (GATT fetch on a per-peer basis).
     *   - true (debug builds opting in with `MOB_BLE_FULL_MX_SEND=true`):
     *     dispatch the full MX envelope via [sendFullMxEnvelope], which
     *     pairs the MB beacon cue with a connectable GATT fetch
     *     responder serving the envelope. The same config flag also
     *     installs Android's scanner-side MB-cue -> GATT-fetch
     *     coordinator in RealBleBridge.
     */
    @JvmStatic
    fun sendToPeer(peerId: String, payload: ByteArray): Boolean {
        if (!selftestSendEnabled) {
            Log.i(TAG, "sendToPeer rejected: self-test send disabled by runtime flag")
            return false
        }

        return if (MobBleConfig.useFullMxEnvelopes) {
            sendFullMxEnvelope(peerId, payload)
        } else {
            sendMbBeaconOnly(peerId, payload)
        }
    }

    /**
     * MB legacy beacon path — the fleet-safe default. Builds a v1
     * `MobMessageEnvelope` (broadcast, `recipientPeerId = null`) and
     * dispatches it through `BleDispatcher.dispatch(..., forceLegacyBeacon = true)`.
     *
     * The 22-byte beacon carries a Mob message reference (message-id
     * hash + sender hash) that every device can scan, regardless of
     * BLE 5 extended-advertising support. Peers decode it via
     * `MobMessageAdvertisement.decodeScanRecord` into a
     * `received_message_beacon` event.
     */
    private fun sendMbBeaconOnly(peerId: String, payload: ByteArray): Boolean {
        val disp = ensureDispatcherOrNull() ?: return false
        val target = peerId.ifBlank { "broadcast" }

        val envelope = try {
            val messageId = ByteArray(16).also { random.nextBytes(it) }
            MobMessageEnvelope.buildV1(
                messageId = messageId,
                senderPeerId = localName(),
                recipientPeerId = null,
                createdAtMs = System.currentTimeMillis(),
                ttl = 1,
                payloadType = "TX",
                payload = payload
            ) to messageId
        } catch (e: IllegalArgumentException) {
            // buildV1's require() preconditions: peer-id / payload-type
            // size limits, ttl range, payload size limit. Surface them
            // as a structured rejection rather than a silent false.
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "envelope build rejected: ${e.message ?: "invalid argument"}"
            )
            return false
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "envelope build threw: ${t.message ?: t.javaClass.simpleName}"
            )
            return false
        }

        val (envelopeBytes, messageId) = envelope

        return try {
            val result = disp.dispatch(
                attemptId = "selftest-${System.currentTimeMillis()}",
                messageId = messageId,
                targetPeerId = target,
                targetDeviceIds = listOf(target),
                payload = envelopeBytes,
                forceLegacyBeacon = true
            )
            Log.i(TAG, "sendToPeer($target, ${payload.size}B) -> ${result.kind} reason=${result.reason}")
            result.kind == BleDispatcher.BleDispatchResult.Kind.DISPATCHED
        } catch (t: Throwable) {
            emitBridgeError(
                BleEvent.Companion.ErrorKind.UNKNOWN,
                "dispatch threw: ${t.message ?: t.javaClass.simpleName}"
            )
            false
        }
    }
}
