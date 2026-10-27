package eu.darken.capod.profiles.core

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import testhelpers.datastore.FakeDataStoreValue

class DeviceProfilesRepoSelectedTargetTest : BaseTest() {
    private val addressA = "AA:BB:CC:DD:EE:FF"
    private val addressB = "11:22:33:44:55:66"

    private fun createRepo(initial: List<DeviceProfile>, scope: CoroutineScope): DeviceProfilesRepo {
        val settings = mockk<DeviceProfilesSettings> {
            every { profiles } returns FakeDataStoreValue(DeviceProfilesContainer(initial)).mock
            every { defaultProfileCreated } returns FakeDataStoreValue(true).mock
            every { reactionMigrationDone } returns FakeDataStoreValue(true).mock
            every { hadLegacyReactionData } returns FakeDataStoreValue(false).mock
        }
        return DeviceProfilesRepo(
            scope = scope,
            context = mockk(relaxed = true),
            generalSettings = mockk(relaxed = true),
            settings = settings,
            deviceStateCache = mockk(relaxed = true),
            batteryDrainStore = mockk(relaxed = true),
            json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
    }

    @Test
    fun `address removal restoration and profile re-add each change the selected target token`() = runTest2 {
        val original = AppleDeviceProfile(id = "p", label = "Pods", address = addressA)
        val repo = createRepo(listOf(original), this)
        val first = repo.selectedTarget("p")
        first.address shouldBe addressA

        repo.updateProfile(original.copy(address = addressB))
        val changed = repo.selectedTarget("p")
        changed.address shouldBe addressB
        (changed.revision > first.revision) shouldBe true

        repo.updateProfile(original.copy(address = null))
        val removedAddress = repo.selectedTarget("p")
        removedAddress.address shouldBe null
        (removedAddress.revision > changed.revision) shouldBe true

        repo.updateAppleProfile("p") { it.copy(address = addressA) }
        val restored = repo.selectedTarget("p")
        restored.address shouldBe addressA
        (restored.revision > removedAddress.revision) shouldBe true

        repo.removeProfile("p")
        val removedProfile = repo.selectedTarget("p")
        removedProfile.address shouldBe null
        (removedProfile.revision > restored.revision) shouldBe true

        repo.addProfile(original)
        val readded = repo.selectedTarget("p")
        readded.address shouldBe addressA
        (readded.revision > removedProfile.revision) shouldBe true
        (repo.selectionChanges.first() >= readded.revision) shouldBe true
    }

    @Test
    fun `label updates and reorder keep the selected target token`() = runTest2 {
        val first = AppleDeviceProfile(id = "p", label = "Pods", address = addressA)
        val second = AppleDeviceProfile(id = "q", label = "Other", address = addressB)
        val repo = createRepo(listOf(first, second), this)
        val original = repo.selectedTarget("p")

        repo.updateProfile(first.copy(label = "Renamed"))
        repo.selectedTarget("p") shouldBe original
        repo.updateAppleProfile("p") { it.copy(label = "Again") }
        repo.selectedTarget("p") shouldBe original
        repo.reorderProfilesById(listOf("q", "p"))
        repo.selectedTarget("p") shouldBe original
        repo.selectionChanges.first() shouldBe original.revision
    }

    @Test
    fun `clear and same-id re-add advance token even with the same final address`() = runTest2 {
        val original = AppleDeviceProfile(id = "p", label = "Pods", address = addressA)
        val repo = createRepo(listOf(original), this)
        val before = repo.selectedTarget("p")

        repo.clear()
        val cleared = repo.selectedTarget("p")
        cleared.address shouldBe null
        (cleared.revision > before.revision) shouldBe true
        repo.addProfile(original)
        val after = repo.selectedTarget("p")
        after.address shouldBe addressA
        (after.revision > cleared.revision) shouldBe true
    }
}
