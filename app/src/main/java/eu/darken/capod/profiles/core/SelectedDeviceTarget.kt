package eu.darken.capod.profiles.core

import eu.darken.capod.common.bluetooth.BluetoothAddress

/** Saved paired address plus a process-local generation that changes when its selection changes. */
data class SelectedDeviceTarget(val address: BluetoothAddress?, val revision: Long)
