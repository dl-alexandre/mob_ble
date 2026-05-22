package mob.ble

/**
 * Plugin-local runtime flags for the extracted Android BLE package.
 *
 * The in-app source used the host Android build config. The plugin copy
 * cannot assume a host package or generated constants, so keep this decision
 * controlled by process environment until the plugin manifest grows a native
 * build-time flag surface.
 */
internal object MobBleConfig {
    val useFullMxEnvelopes: Boolean
        get() = truthy(System.getenv("MOB_BLE_FULL_MX_SEND"))

    private fun truthy(value: String?): Boolean =
        value != null && value.lowercase() in setOf("1", "true", "yes", "on")
}
