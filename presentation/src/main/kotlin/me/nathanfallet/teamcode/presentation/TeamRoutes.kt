package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*

val PRINCIPAL = AttributeKey<Principal>("teamcode.principal")
internal val ApplicationCall.principal get() = attributes[PRINCIPAL]
internal fun Member.view() = MemberView(id, username, role.name, disabled, passwordChangeRequired)
internal fun NewMember.view() = CreatedMemberResponse(member.view(), temporaryPassword)
internal fun role(value: String): Role = Role.entries.find { it.name == value } ?: throw TeamFailure("invalid_role", 400, "Unknown role.")
internal suspend inline fun <reified T> ApplicationCall.jsonBody(): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) throw TeamFailure("invalid_body", 400, "A JSON body is required.")
    return try { Serialization.json.decodeFromString<T>(receiveChannel().readBounded(32_768).decodeToString()) }
    catch (_: IllegalArgumentException) { throw TeamFailure("invalid_body", 400, "Invalid request body.") }
}
/** A fixed global bound prevents unauthenticated password hashing and database work from growing without limit. */
internal class LoginThrottle {
    private var window = 0L
    private var attempts = 0
    @Synchronized fun admit() {
        val now = System.currentTimeMillis() / 60_000
        if (now != window) { window = now; attempts = 0 }
        if (++attempts > 120) throw TeamFailure("login_rate_limit", 429, "Too many login attempts. Try again in a minute.")
    }
}
internal fun Route.teamRoutes(team: TeamService) {
    val throttle = LoginThrottle()
    post("/api/v1/auth/login") {
        throttle.admit()
        val input = call.jsonBody<LoginRequest>()
        val session = team.login(input.username, input.password, input.device)
        call.respond(LoginResponse(session.token, session.expiresAt, session.principal.member.view()))
    }
    get("/api/v1/auth/me") { call.respond(call.principal.member.view()) }
    post("/api/v1/auth/logout") { team.logout(call.principal); call.respond(SuccessResponse()) }
    post("/api/v1/auth/password") {
        val input = call.jsonBody<PasswordRequest>()
        team.changePassword(call.principal, input.currentPassword, input.newPassword)
        call.respond(SuccessResponse())
    }
    get("/api/v1/members") { call.respond(team.members(call.principal).map { it.view() }) }
    post("/api/v1/members") {
        val input = call.jsonBody<CreateMemberRequest>()
        call.respond(HttpStatusCode.Created, team.createMember(call.principal, input.username, role(input.role)).view())
    }
    patch("/api/v1/members/{id}") {
        val input = call.jsonBody<UpdateMemberRequest>()
        call.respond(team.updateMember(call.principal, call.parameters["id"]!!, role(input.role), input.disabled).view())
    }
    post("/api/v1/members/{id}/reset-password") { call.respond(team.resetPassword(call.principal, call.parameters["id"]!!).view()) }
}
