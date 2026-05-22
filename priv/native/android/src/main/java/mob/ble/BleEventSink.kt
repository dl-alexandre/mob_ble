package mob.ble

import android.util.Log

/**
 * Where the Android transport delivers v1 wire-format events.
 *
 * Two implementations ship today:
 *   * `LogcatEventSink` — visible-during-bringup sink. Useful before
 *     BEAM-on-Android lands; events show up in `adb logcat`.
 *   * `InMemoryEventSink` — used by unit tests and the future fake bridge.
 *
 * The production NIF sink (forwarding maps to an Elixir owner pid) will
 * land alongside the BEAM-on-Android bring-up PR. Its name will be
 * `BeamEventSink` and it will not change the contract — it just calls
 * `accept(event.toWireMap())`.
 */
fun interface BleEventSink {
    fun accept(event: BleEvent)
}

class LogcatEventSink(private val tag: String = "MobBle") : BleEventSink {
    override fun accept(event: BleEvent) {
        Log.i(tag, event.toJsonObject().toString())
    }
}

class InMemoryEventSink : BleEventSink {
    private val _events = mutableListOf<BleEvent>()
    val events: List<BleEvent> get() = synchronized(_events) { _events.toList() }

    override fun accept(event: BleEvent) {
        synchronized(_events) { _events.add(event) }
    }

    fun clear() = synchronized(_events) { _events.clear() }
}
