package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.cli.platform.quoteWindowsArgument
import kotlin.test.*

class WindowsArgumentsTest {
    @Test fun encodesEmptyArgumentsQuotesAndTrailingBackslashes() {
        assertEquals("\"\"", quoteWindowsArgument(""))
        assertEquals("\"two words\"", quoteWindowsArgument("two words"))
        assertEquals("\"a\\\"b\"", quoteWindowsArgument("a\"b"))
        assertEquals("\"C:\\path with spaces\\\\\"", quoteWindowsArgument("C:\\path with spaces\\"))
        assertEquals("\"& | < > %PATH% !var!\"", quoteWindowsArgument("& | < > %PATH% !var!"))
        assertFailsWith<IllegalArgumentException> { quoteWindowsArgument("a\u0000b") }
    }
}
