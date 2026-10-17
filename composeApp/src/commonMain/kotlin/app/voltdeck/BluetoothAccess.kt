package app.voltdeck

/** Autorisation Bluetooth du système. Sur iOS, la demande part au premier scan : [granted] y est toujours vrai. */
interface BluetoothAccess {
    fun granted(): Boolean

    fun request(onResult: (Boolean) -> Unit)
}

object AlwaysGrantedBluetooth : BluetoothAccess {
    override fun granted() = true

    override fun request(onResult: (Boolean) -> Unit) = onResult(true)
}
