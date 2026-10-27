package eu.darken.capod.common.upgrade.core

import eu.darken.capod.common.datastore.DataStoreValue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class PodBridgeFreeAccessTest : BaseTest() {
    @Test
    fun `a new installation needs no sponsor record to use every feature`() = runTest {
        val repo = FreeAccessRepo()
        val access = repo.upgradeInfo.first()
        access.isPro shouldBe true
        access.isSettled shouldBe true
        access.error shouldBe null
    }
}
