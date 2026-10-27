package eu.darken.capod.reaction.core.conversation

import eu.darken.capod.profiles.core.DeviceProfile
import eu.darken.capod.profiles.core.DeviceProfilesContainer
import eu.darken.capod.profiles.core.DeviceProfilesRepo
import eu.darken.capod.profiles.core.DeviceProfilesSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import testhelpers.datastore.FakeDataStoreValue

/** Real repository with the existing in-memory DataStore adapter; writes and revisions stay real. */
internal fun conversationTestProfiles(
    initial: List<DeviceProfile>,
    scope: CoroutineScope,
    store: FakeDataStoreValue<DeviceProfilesContainer> = FakeDataStoreValue(DeviceProfilesContainer(initial)),
): DeviceProfilesRepo {
    val settings = mockk<DeviceProfilesSettings> {
        every { profiles } returns store.mock
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
        json = Json { ignoreUnknownKeys = true },
    )
}
