package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class ConversationCodecTest {
    private val codec = ConversationCodec()
    private fun body(value: String) = Json.parseToJsonElement(value).jsonObject
    @Test fun toolConversationKeepsIdsArgumentsResultsAndOrderAcrossProtocols() {
        val original = body("""{"model":"claude-haiku-test","stream":true,"max_tokens":100,"system":"Be precise.","messages":[{"role":"user","content":"Calculate."},{"role":"assistant","content":[{"type":"text","text":"Checking."},{"type":"tool_use","id":"call_1","name":"calculate","input":{"x":2}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"4"}]}],"tools":[{"name":"calculate","description":"Square x","input_schema":{"type":"object","properties":{"x":{"type":"integer"}}}}],"tool_choice":{"type":"auto"}}""")
        val semantic = codec.decode(original, MESSAGES)
        val encoded = codec.encode(semantic, RESPONSES, "gpt-6-luna")
        val roundtrip = codec.encode(codec.decode(encoded, RESPONSES), MESSAGES, "claude-haiku-test")
        assertEquals(JsonArray(original["tools"]!!.jsonArray.map { JsonObject(it.jsonObject + ("strict" to JsonPrimitive(false))) }), roundtrip["tools"])
        assertEquals(original["max_tokens"], roundtrip["max_tokens"])
        val turns = codec.decode(roundtrip, MESSAGES).turns
        assertEquals(semantic.turns, turns)
        val input = encoded["input"]!!.jsonArray
        assertEquals("function_call", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", input[3].jsonObject["call_id"]!!.jsonPrimitive.content)
    }
    @Test fun explicitlyDisabledReasoningKeepsItsMeaning() {
        val source = body("""{"messages":[],"thinking":{"type":"disabled"}}""")
        val encoded = codec.encode(codec.decode(source, MESSAGES), RESPONSES, "gpt-6-luna")
        assertEquals("none", encoded["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val back = codec.encode(codec.decode(encoded, RESPONSES), MESSAGES, "claude-test")
        assertEquals(source["thinking"], back["thinking"])
        assertNull(back["output_config"])
    }
    @Test fun claudeCodeDefaultSamplingCanCrossToCodexButCustomSamplingCannot() {
        val request = body("""{"model":"claude-haiku-4-5-20251001","stream":true,"max_tokens":32000,"temperature":1,"thinking":{"type":"disabled"},"messages":[{"role":"user","content":"Run the tests."}],"tools":[{"name":"Bash","description":"Run a command","input_schema":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}}]}""")
        for (source in listOf(request, JsonObject(request + ("top_p" to JsonPrimitive(1.0))))) {
            val encoded = codec.encode(codec.decode(source, MESSAGES), RESPONSES, "gpt-6-luna")
            assertNull(encoded["temperature"])
            assertNull(encoded["top_p"])
            assertEquals("none", encoded["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals(codec.decode(request, MESSAGES).tools, codec.decode(encoded, RESPONSES).tools)
        }
        for (field in listOf("temperature", "top_p")) {
            val changed = JsonObject(request + (field to JsonPrimitive(0.5)))
            assertEquals("translation_unsupported", assertFailsWith<TeamFailure> { codec.encode(codec.decode(changed, MESSAGES), RESPONSES, "gpt-6-luna") }.code)
        }
    }
    @Test fun imageBytesArePreservedWithoutFetchingAnyDestination() {
        val original = body("""{"messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}}]}]}""")
        val semantic = codec.decode(original, MESSAGES)
        val responses = codec.encode(semantic, RESPONSES, "gpt-6-luna")
        val roundtrip = codec.decode(responses, RESPONSES)
        assertEquals(semantic.turns, roundtrip.turns)
    }
    @Test fun optionalClaudeToolArgumentsDoNotBecomeRequiredThroughResponsesDefaults() {
        val request = body("""{"messages":[],"tools":[{"name":"Read","input_schema":{"type":"object","properties":{"file_path":{"type":"string"},"offset":{"type":"integer"},"pages":{"type":"string"}},"required":["file_path"]}}]}""")
        val original = request["tools"]!!.jsonArray.single().jsonObject
        val encoded = codec.encode(codec.decode(request, MESSAGES), RESPONSES, "gpt-6-luna")["tools"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(false), encoded["strict"])
        assertEquals(original["input_schema"], encoded["parameters"])
        val explicit = JsonObject(request + ("tools" to JsonArray(listOf(JsonObject(original + ("strict" to JsonPrimitive(true)))))))
        val strict = codec.encode(codec.decode(explicit, MESSAGES), RESPONSES, "gpt-6-luna")["tools"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(true), strict["strict"])
    }
    @Test fun failedClaudeToolsRetainTheirErrorStateAndOriginalContentInResponses() {
        val request = body("""{"messages":[{"role":"assistant","content":[{"type":"tool_use","id":"failed-read","name":"Read","input":{"path":"missing.py"}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"failed-read","is_error":true,"content":[{"type":"text","text":"File not found."},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGVsbG8="}}]}]}]}""")
        val encoded = codec.encode(codec.decode(request, MESSAGES), RESPONSES, "gpt-6-luna")
        val result = encoded["input"]!!.jsonArray.last().jsonObject
        assertEquals("function_call_output", result["type"]!!.jsonPrimitive.content)
        assertEquals("failed-read", result["call_id"]!!.jsonPrimitive.content)
        val output = result["output"]!!.jsonArray
        assertTrue(output[0].jsonObject["text"]!!.jsonPrimitive.content.contains("is_error=true"))
        assertEquals("File not found.", output[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,aGVsbG8=", output[2].jsonObject["image_url"]!!.jsonPrimitive.content)
        assertEquals(listOf(Part.Text("[TeamCode tool result: is_error=true. Tool execution failed.]"), Part.Text("File not found."), Part.Image("data:image/png;base64,aGVsbG8=")),
            (codec.decode(encoded, RESPONSES).turns.last().content.single() as Part.ToolResult).content)
    }
    @Test fun providerSpecificStateAndUnsupportedControlsAreRejected() {
        val examples = listOf(
            MESSAGES to """{"messages":[],"thinking":{"type":"enabled","budget_tokens":2048}}""",
            MESSAGES to """{"messages":[{"role":"assistant","content":[{"type":"thinking","thinking":"opaque","signature":"secret-signature"}]}]}""",
            RESPONSES to """{"input":[{"type":"reasoning","encrypted_content":"opaque"}]}""",
            RESPONSES to """{"input":[],"previous_response_id":"response-1"}""",
            RESPONSES to """{"input":[],"tools":[{"type":"web_search"}]}""",
        )
        examples.forEach { (protocol, value) ->
            val error = assertFailsWith<TeamFailure> { codec.decode(body(value), protocol) }
            assertEquals("translation_unsupported", error.code)
            assertFalse(error.message.orEmpty().contains("secret-signature"))
        }
    }
    @Test fun patternsResolveOrderedExplicitTargetsWithoutRecursiveAliases() {
        val rule = RouteRule("opus-fallback", "claude-opus-*", listOf(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")))
        assertEquals(listOf(RouteTarget("anthropic", "claude-opus-test"), RouteTarget("codex", "gpt-6-astra")), ResolveRouteUseCase().resolve(listOf(rule), "claude-opus-test", MESSAGES))
        assertEquals(listOf(RouteTarget("codex", "gpt-6-luna")), ResolveRouteUseCase().resolve(listOf(rule), "gpt-6-luna", RESPONSES))
    }
}
