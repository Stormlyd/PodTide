package eu.darken.capod.main.ui.devicesettings.cards

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.darken.capod.pods.core.apple.aap.protocol.AapSetting

/** Keeps the user's unsent EQ draft separate from incoming device reports. */
internal class CustomEqViewModel(initialReport: AapSetting.CustomEq?) {
    private data class AppliedDraft(
        val value: AapSetting.CustomEq,
        val editRevision: Long,
        val confirmationRevision: Long,
    )

    private var editRevision = 0L
    private var applying: AppliedDraft? = null

    var draft by mutableStateOf(initialReport ?: AapSetting.CustomEq(1, 50, 50, 50))
        private set

    var edited by mutableStateOf(false)
        private set

    fun observe(report: AapSetting.CustomEq?, confirmationRevision: Long = 0) {
        val applied = applying
        if (applied != null && confirmationRevision > applied.confirmationRevision && report == applied.value) {
            applying = null
            if (editRevision == applied.editRevision) edited = false
        }
        if (!edited && report != null) draft = report
    }

    /** Captures the exact draft submitted by Apply, before its asynchronous socket write. */
    fun markApplying(confirmationRevision: Long): AapSetting.CustomEq {
        applying = AppliedDraft(draft, editRevision, confirmationRevision)
        edited = true
        return draft
    }

    fun selectCustom(custom: Boolean) {
        draft = draft.copy(state = if (custom) 2 else 1)
        edited = true
        editRevision++
    }

    fun setLow(value: Int) = setBand(value) { draft.copy(low = it) }
    fun setMid(value: Int) = setBand(value) { draft.copy(mid = it) }
    fun setHigh(value: Int) = setBand(value) { draft.copy(high = it) }

    private fun setBand(value: Int, update: (Int) -> AapSetting.CustomEq) {
        require(value in 0..100)
        draft = update(value)
        edited = true
        editRevision++
    }

    fun reset() {
        draft = draft.copy(low = 50, mid = 50, high = 50)
        edited = true
        editRevision++
    }
}
