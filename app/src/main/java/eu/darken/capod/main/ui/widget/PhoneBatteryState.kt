package eu.darken.capod.main.ui.widget

/** A visible phone entry can have unknown capacity while charging status remains known. */
data class PhoneBatteryState(val percent: Int?, val charging: Boolean)
