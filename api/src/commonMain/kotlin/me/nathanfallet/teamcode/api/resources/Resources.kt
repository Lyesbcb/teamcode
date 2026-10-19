package me.nathanfallet.teamcode.api.resources

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/capabilities")
class CapabilitiesResource

@Serializable
@Resource("/api/v1/status")
class StatusResource

/** Administrator-only observed history and forecasts for a selected subscription account. */
@Serializable
@Resource("/api/v1/forecasts")
class ForecastsResource(val account: String? = null)

/** Group management requires interactive authentication, including for administrators. */
@Serializable @Resource("/api/v1/groups")
class GroupsResource {
    @Serializable @Resource("{id}")
    class Id(val parent: GroupsResource = GroupsResource(), val id: String) {
        @Serializable @Resource("members/{memberId}")
        class Member(val parent: Id, val memberId: String) {
            @Serializable @Resource("pause")
            class Pause(val parent: Member)
        }
    }
}
