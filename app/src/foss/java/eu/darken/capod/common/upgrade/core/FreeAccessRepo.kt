package eu.darken.capod.common.upgrade.core

import eu.darken.capod.common.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** PodBridge has no entitlement lookup, sponsor prerequisite or expiry. */
@Singleton
class FreeAccessRepo @Inject constructor() : UpgradeRepo {
    override val storeSite = "https://github.com/d4rken-org/capod"
    override val upgradeSite = storeSite
    override val betaSite = storeSite
    override val upgradeInfo: Flow<UpgradeRepo.Info> = flowOf(UpgradeRepoFoss.Info(isPro = true))
    override suspend fun refresh() = Unit
}
