package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.TeamFailure

/** Bounds and masking for transcripts and inputs. */
object CloudConversationRules {
    /** Longest message a member may send, in characters. */
    const val MAX_MESSAGE_CHARS = 32_000
    /** Longest string kept in an event; longer strings are cut and marked. */
    const val MAX_EVENT_STRING_CHARS = 16_000
    /** Largest encoded event; a larger one is replaced by a `truncated` marker. */
    const val MAX_EVENT_BYTES = 60_000
    const val MAX_EVENTS_PER_BATCH = 200
    const val MAX_PENDING_INPUTS = 100
    const val MAX_ATTEMPTS = 3
    const val MAX_PAGE = 200
    const val MAX_RUNNER_WAIT_MILLIS = 20_000L
    const val ATTACHMENT_LEASE_MILLIS = 60_000L
    /** A delivery not acknowledged within this time is offered again (the runner deduplicates by input id). */
    const val REDELIVERY_MILLIS = 60_000L
    const val TRUNCATION_MARK = "…[truncated]"
    const val MASK = "[REDACTED]"

    /**
     * Credential shapes masked in every stored string: provider API keys, GitHub tokens, TeamCode credentials,
     * cloud keys, JWTs, bearer and basic authorization values, private keys, and `key=value` assignments of
     * names that look secret.
     */
    private val secrets = listOf(
        // Prefixed shapes start at a word boundary and contain a digit, so `task-management-…` or `etc_…` stay readable.
        Regex("(?<![A-Za-z0-9_-])sk-(?:ant-)?(?=[A-Za-z0-9_-]{0,256}[0-9])[A-Za-z0-9_-]{16,}"),
        Regex("(?<![A-Za-z0-9_])(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        Regex("(?<![A-Za-z0-9_-])tc(?:ex|sk|dv|ss)?_(?=[A-Za-z0-9_-]{0,256}[0-9])[A-Za-z0-9_-]{16,}"),
        Regex("(?:AKIA|ASIA)[A-Z0-9]{16}"),
        Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
        Regex("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
    )
    private val authorization = Regex("(?i)\\b(bearer|basic|token)\\s+([A-Za-z0-9._~+/=-]{12,})")
    /** A name ending in a secret word (`GITHUB_TOKEN`, `"api_key"`, not `max_tokens`) assigned a value of 8 characters or more. */
    private val assignment = Regex("(?i)\\b([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|PASSWD|API_?KEY|PRIVATE_KEY|CREDENTIALS?))(\"?\\s*[=:]\\s*)(\"[^\"]{8,}\"|'[^']{8,}'|[^\\s\"',;]{8,})")
    private val urlCredentials = Regex("(?i)((?<![a-z0-9+.-])[a-z][a-z0-9+.-]{0,31}://)[^\\s/@:]+:[^\\s/@]+@")

    /**
     * A plain word (`configuration`, `Authentication.`) after `token`, `basic` or `bearer` is prose. Anything else is
     * treated as a credential: a digit, a symbol, inner capitals, or letters too long for a word (a passphrase).
     */
    private fun looksLikeCredential(value: String): Boolean {
        val word = value.trimEnd('.')
        return word.length > MAX_WORD || word.any { !it.isLetter() } || word.drop(1).any { it.isUpperCase() }
    }
    private const val MAX_WORD = 16

    /** The kind of each pattern of [secrets], in order, as [detect] names it. */
    private val secretKinds = listOf("provider_api_key", "github_token", "github_token", "teamcode_credential", "aws_access_key", "slack_token", "jwt", "private_key")

    /**
     * The kind of the first credential found in [text], or null: the shapes [mask] hides, plus authorization values
     * and URLs with credentials. Name-based assignments (`API_KEY = "…"`) are not reported: they are ordinary code in a
     * page and say nothing about the value. Used to refuse publishing a file rather than to rewrite it.
     */
    fun detect(text: String): String? {
        secrets.forEachIndexed { index, pattern -> if (pattern.containsMatchIn(text)) return secretKinds[index] }
        if (authorizationValue.findAll(text).any { looksLikeSecretValue(it.groupValues[2]) }) return "authorization_header"
        if (urlCredentials.containsMatchIn(text)) return "url_credentials"
        return null
    }

    /** On one line only: a whole page holds prose (`Basic cross-browser support`) and Markdown headings (`Token` over `-----`). */
    private val authorizationValue = Regex("(?i)\\b(bearer|basic|token)[ \\t]+([A-Za-z0-9._~+/=-]{12,})")

    /**
     * Stricter than [looksLikeCredential], since a hit refuses a whole file: a digit or a base64 or token symbol
     * (`+/=_~`), or mixed case with no word separator (`dXNlcjpwYXNzd29yZA`); hyphenated or dotted words are prose.
     */
    private fun looksLikeSecretValue(value: String): Boolean {
        val word = value.trimEnd('.')
        if (word.any { it.isDigit() || it in "+/=_~" }) return true
        return '-' !in word && '.' !in word && word.any { it.isLowerCase() } && word.drop(1).any { it.isUpperCase() }
    }

    fun mask(text: String): String {
        var masked = text
        for (pattern in secrets) masked = pattern.replace(masked, MASK)
        masked = authorization.replace(masked) { if (looksLikeCredential(it.groupValues[2])) "${it.groupValues[1]} $MASK" else it.value }
        masked = assignment.replace(masked) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        masked = urlCredentials.replace(masked) { "${it.groupValues[1]}$MASK@" }
        return masked
    }

    /** Masks, then cuts a string to [limit] characters with a visible mark. */
    fun bound(text: String, limit: Int = MAX_EVENT_STRING_CHARS): String {
        val masked = mask(text)
        return if (masked.length <= limit) masked else masked.take(limit - TRUNCATION_MARK.length) + TRUNCATION_MARK
    }

    fun validateMessage(text: String) {
        if (text.isBlank() || text.length > MAX_MESSAGE_CHARS || text.any { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' })
            throw TeamFailure("invalid_message", 400, "Send a message of 1–$MAX_MESSAGE_CHARS characters without control characters.")
    }

    /** Request ids are the agent's own (Claude Code uses UUIDs); answers are short: a label index or a sentence each. */
    fun validatePermissionAnswer(answer: PermissionAnswer) {
        if (!isPermissionRequestId(answer.requestId))
            throw TeamFailure("invalid_permission_answer", 400, "Send the requestId of a permission.request event.")
        val texts = listOfNotNull(answer.message) + answer.answers.mapNotNull { it.text }
        if (answer.answers.size > MAX_PERMISSION_ANSWERS || texts.any { it.isBlank() || it.length > MAX_PERMISSION_TEXT_CHARS || it.any { c -> c.isISOControl() && c != '\n' && c != '\t' && c != '\r' } })
            throw TeamFailure("invalid_permission_answer", 400, "Send at most $MAX_PERMISSION_ANSWERS answers and texts of 1–$MAX_PERMISSION_TEXT_CHARS characters.")
        if (answer.answers.isNotEmpty() && !answer.allow) throw TeamFailure("invalid_permission_answer", 400, "Answers go with behavior allow.")
        if (answer.answers.map { it.question }.toSet().size != answer.answers.size || answer.answers.any { it.question !in 0 until MAX_PERMISSION_INDEX })
            throw TeamFailure("invalid_permission_answer", 400, "Answer each question once, by its index (0 to ${MAX_PERMISSION_INDEX - 1}).")
        if (answer.answers.any { (it.options.isEmpty()) == (it.text == null) || it.options.toSet().size != it.options.size || it.options.any { option -> option !in 0 until MAX_PERMISSION_INDEX } })
            throw TeamFailure("invalid_permission_answer", 400, "Answer a question with distinct option indexes (0 to ${MAX_PERMISSION_INDEX - 1}) or with a text, not both.")
    }

    /**
     * Who may answer [request], and whether [answer] fits it. Approving or denying a tool use gated by an `ask` rule
     * is the session owner's or an administrator's decision ([approver]); a question is answered by anyone who may
     * send messages to the session, which the caller already passed. Answers need a question request, and their
     * indexes must exist in it when its input is known.
     */
    fun checkAnswer(request: PermissionRequestRecord, answer: PermissionAnswer, approver: Boolean, currentEpoch: Int = request.epoch) {
        // An execution that ended (a pause, a new pod) took its open requests with it.
        when (if (request.epoch != currentEpoch && request.state == PermissionRequestState.OPEN) PermissionRequestState.ENDED else request.state) {
            PermissionRequestState.OPEN -> Unit
            PermissionRequestState.EXPIRED -> throw TeamFailure("permission_request_expired", 409, "This permission request expired without an answer and was denied.")
            PermissionRequestState.ANSWERED -> throw TeamFailure("permission_request_answered", 409, "This permission request was already answered.")
            PermissionRequestState.ENDED -> throw TeamFailure("permission_request_ended", 409, "The turn of this permission request has ended.")
        }
        // Nobody may approve what nobody could read: an input too large for the transcript can only be denied.
        if (answer.allow && request.inputLeftOut)
            throw TeamFailure("permission_input_unseen", 400, "This request's input was too large to show in the transcript, so it can only be denied.")
        if (!request.question) {
            if (!approver) throw TeamFailure("permission_answer_denied", 403, "Only the session owner or an administrator may approve or deny this tool use.")
            if (answer.answers.isNotEmpty()) throw TeamFailure("invalid_permission_answer", 400, "This request is a tool use, not a question: send no answers.")
            return
        }
        if (answer.allow && answer.answers.isEmpty()) throw TeamFailure("invalid_permission_answer", 400, "Answer at least one question, or deny the request.")
        val questions = request.questions ?: return
        val texts = answer.answers.mapNotNull { questions.getOrNull(it.question)?.text }
        if (texts.toSet().size != texts.size) throw TeamFailure("invalid_permission_answer", 400, "Two of these questions have the same text; answer only one of them.")
        for (item in answer.answers) {
            val shape = questions.getOrNull(item.question) ?: throw TeamFailure("invalid_permission_answer", 400, "The request has ${questions.size} questions, numbered from 0.")
            if (item.options.any { it >= shape.options } || (item.options.size > 1 && !shape.multiSelect))
                throw TeamFailure("invalid_permission_answer", 400, "Question ${item.question} has ${shape.options} options, numbered from 0" + if (shape.multiSelect) "." else ", and takes one.")
        }
    }
    const val MAX_PERMISSION_ANSWERS = 16
    /**
     * Pending permission answers a session holds at most. They have their own bound, outside [MAX_PENDING_INPUTS]: a
     * queue full of messages waiting for the turn to end must never block the answer that lets it end.
     */
    const val MAX_PENDING_PERMISSION_ANSWERS = 16
    const val MAX_PERMISSION_TEXT_CHARS = 4_000
    /** Question and option indexes stay below this bound (Claude Code asks at most 4 questions of 4 options). */
    const val MAX_PERMISSION_INDEX = 64
    /** The longest deadline a team may set for unanswered permission requests: a day. */
    const val MAX_PERMISSION_DEADLINE_MINUTES = 1_440
    /** The `turn` phases that end a turn, and with it every permission request still open in it. */
    val TURN_END_PHASES = setOf("completed", "interrupted", "failed")
    /** Printable ASCII without spaces: the agent chooses the shape (Claude Code: a UUID). */
    private val permissionRequestId = Regex("[\\x21-\\x7E]{1,128}")
    fun isPermissionRequestId(id: String) = permissionRequestId.matches(id)

    fun validatePage(limit: Int, after: Long?, before: Long?) {
        if (limit !in 1..MAX_PAGE) throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and $MAX_PAGE.")
        if (after != null && before != null) throw TeamFailure("invalid_cursor", 400, "Send after or before, not both.")
        if ((after != null && after < 0) || (before != null && before < 1)) throw TeamFailure("invalid_cursor", 400, "Use a non-negative event sequence.")
    }
}
