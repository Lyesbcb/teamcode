package me.nathanfallet.teamcode.api

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.QuotaView
import kotlin.test.*

class QuotaSerializationTest {
    @Test fun unknownResetIsExplicitNullWhileExistingKnownDatesStillDecode() {
        val unknown = QuotaView("account", "five_hour", 0.0, 1000, null)
        val encoded = Serialization.json.encodeToString(unknown)
        assertEquals(JsonNull, Json.parseToJsonElement(encoded).jsonObject["resetsAtMillis"])
        assertEquals(unknown, Serialization.json.decodeFromString<QuotaView>(encoded))
        assertEquals(unknown, Serialization.json.decodeFromString<QuotaView>("""{"accountId":"account","window":"five_hour","utilization":0,"observedAtMillis":1000}"""))
        val known = Serialization.json.decodeFromString<QuotaView>("""{"accountId":"account","window":"five_hour","utilization":0.5,"observedAtMillis":1000,"resetsAtMillis":3600000}""")
        assertEquals(3_600_000L, known.resetsAtMillis)
    }
}
