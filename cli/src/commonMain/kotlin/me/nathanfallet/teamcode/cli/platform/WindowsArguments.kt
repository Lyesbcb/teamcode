package me.nathanfallet.teamcode.cli.platform

/** Windows CRT argv encoding. No cmd.exe, shell expansion or interpretation of metacharacters. */
internal fun quoteWindowsArgument(value: String): String {
    require('\u0000' !in value)
    return buildString {
        append('"')
        var slashes = 0
        for (character in value) when (character) {
            '\\' -> slashes++
            '"' -> { repeat(slashes * 2 + 1) { append('\\') }; append('"'); slashes = 0 }
            else -> { repeat(slashes) { append('\\') }; slashes = 0; append(character) }
        }
        repeat(slashes * 2) { append('\\') }
        append('"')
    }
}
