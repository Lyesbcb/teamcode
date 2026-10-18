package me.nathanfallet.teamcode.domain.routing

/** Input excludes cached input; output includes reasoning tokens. */
data class TokenUsage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheCreation: Long = 0)
sealed interface StreamEvent {
    data class Start(val id: String) : StreamEvent
    data class TextStart(val index: Int) : StreamEvent
    data class ToolStart(val index: Int, val id: String, val name: String) : StreamEvent
    data class Delta(val index: Int, val value: String) : StreamEvent
    data class End(val index: Int) : StreamEvent
    data class Finish(val reason: StopReason, val usage: TokenUsage) : StreamEvent
    data object Failure : StreamEvent
}
enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS }
