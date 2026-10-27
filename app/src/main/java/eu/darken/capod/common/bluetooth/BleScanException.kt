package eu.darken.capod.common.bluetooth

/** A failed registration has no more results; its owner must release and retry it. */
class BleScanException(val errorCode: Int) : IllegalStateException("BLE scan failed (code=$errorCode)")
