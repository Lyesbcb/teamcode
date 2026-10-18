package me.nathanfallet.teamcode.domain.policy

/** Glob matching with bounded O(pattern length * model length) work, without regex backtracking. */
fun matchesModelPattern(pattern: String, model: String): Boolean {
    val value = model.lowercase()
    var previous = BooleanArray(value.length + 1).also { it[0] = true }
    for (character in pattern.lowercase()) {
        val next = BooleanArray(value.length + 1)
        if (character == '*') next[0] = previous[0]
        for (index in 1..value.length) {
            next[index] = if (character == '*') previous[index] || next[index - 1]
                else previous[index - 1] && value[index - 1] == character
        }
        previous = next
    }
    return previous[value.length]
}
