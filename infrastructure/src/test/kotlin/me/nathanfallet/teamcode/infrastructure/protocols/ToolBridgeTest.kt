package me.nathanfallet.teamcode.infrastructure.protocols

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.protocols.stream.ResponsesStreamEncoder
import kotlin.test.*

class ToolBridgeTest {
    private val codec = ConversationCodec()
    private fun body(value: String) = Json.parseToJsonElement(value).jsonObject
    private val declarations = body("""{"input":[{"type":"additional_tools","role":"developer","id":"tools-1","tools":[{"type":"namespace","name":"functions","description":"Execute local tasks.","tools":[{"type":"custom","name":"exec","description":"Execute the supplied code.","format":{"type":"text"}},{"type":"function","name":"wait","parameters":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]},"strict":false}]},{"type":"namespace","name":"other","tools":[{"type":"function","name":"wait","parameters":{"type":"object"}}]}]},{"role":"user","content":"Run the tests."}],"reasoning":{"effort":"none","context":"all_turns"},"text":{"verbosity":"low"},"client_metadata":{"session_id":"synthetic"}}""")

    @Test fun developerToolDeclarationsAndHistoryKeepDistinctStableIdentities() {
        val conversation = codec.decode(declarations, RESPONSES)
        assertEquals(3, conversation.tools.size)
        assertEquals(1, conversation.turns.size)
        val encoded = codec.encode(conversation, MESSAGES, "claude-test")
        val names = encoded["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(3, names.toSet().size)
        assertTrue(names.all { Regex("[a-zA-Z0-9_-]{1,64}").matches(it) })
        assertEquals(names, codec.encode(codec.decode(declarations, RESPONSES), MESSAGES, "claude-other")["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(encoded["system"]!!.jsonPrimitive.content.contains("verbosity: low"))
        assertEquals(JsonPrimitive("disabled"), encoded["thinking"]!!.jsonObject["type"])
        val code = "text(\"Café 🌍\\n\");\n"
        val history = declarations["input"]!!.jsonArray + listOf(
            buildJsonObject { put("type", "custom_tool_call"); put("name", "exec"); put("namespace", "functions"); put("call_id", "custom-1"); put("input", code) },
            body("""{"type":"custom_tool_call_output","call_id":"custom-1","output":"tool finished"}"""),
            body("""{"type":"function_call","name":"wait","namespace":"other","call_id":"function-1","arguments":"{}"}"""),
            body("""{"type":"function_call_output","call_id":"function-1","output":"done"}"""),
        )
        val next = codec.decode(JsonObject(declarations + ("input" to JsonArray(history))), RESPONSES)
        val forwarded = codec.encode(next, MESSAGES, "claude-test")["messages"]!!.jsonArray
        val call = forwarded[1].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals(names[0], call["name"]!!.jsonPrimitive.content)
        assertEquals("custom-1", call["id"]!!.jsonPrimitive.content)
        assertEquals(code, call["input"]!!.jsonObject["input"]!!.jsonPrimitive.content)
        val responseRoundTrip = codec.decode(codec.encode(next, RESPONSES, "gpt-test"), RESPONSES)
        assertEquals(next.turns, responseRoundTrip.turns)
        assertEquals(next.tools, responseRoundTrip.tools)
        val selected = codec.decode(JsonObject(declarations + ("tool_choice" to body("""{"type":"custom","name":"exec","namespace":"functions"}"""))), RESPONSES)
        assertEquals(names[0], codec.encode(selected, MESSAGES, "claude-test")["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun customStreamUnwrapsValidatedTextAndRestoresNamespaceWithoutLeakingJsonWrapper() {
        val tools = codec.decode(declarations, RESPONSES).tools
        val encoder = ResponsesStreamEncoder("gpt-test", tools)
        val parser = SseFrames()
        fun emit(event: StreamEvent) = encoder.accept(event).flatMap(parser::accept)
        emit(StreamEvent.Start("source"))
        val start = emit(StreamEvent.ToolStart(0, "call_1", tools[0].wireName())).single()["item"]!!.jsonObject
        assertEquals("custom_tool_call", start["type"]!!.jsonPrimitive.content)
        assertEquals("functions", start["namespace"]!!.jsonPrimitive.content)
        assertEquals("exec", start["name"]!!.jsonPrimitive.content)
        val original = "x".repeat(4095) + "🌍\ntext(\"Café\");"
        val wrapped = buildJsonObject { put("input", original) }.toString()
        wrapped.chunked(7).forEach { assertTrue(emit(StreamEvent.Delta(0, it)).isEmpty()) }
        val ended = emit(StreamEvent.End(0))
        val deltas = ended.filter { it["type"] == JsonPrimitive("response.custom_tool_call_input.delta") }
        assertEquals(original, deltas.joinToString("") { it["delta"]!!.jsonPrimitive.content })
        assertFalse(ended.any { it["type"] == JsonPrimitive("response.function_call_arguments.delta") })
        emit(StreamEvent.ToolStart(1, "call_2", tools[2].wireName()))
        emit(StreamEvent.Delta(1, "{}")); emit(StreamEvent.End(1))
        val output = emit(StreamEvent.Finish(StopReason.TOOL_USE, TokenUsage(1, 2))).single()["response"]!!.jsonObject["output"]!!.jsonArray
        assertEquals(original, output[0].jsonObject["input"]!!.jsonPrimitive.content)
        assertEquals("call_1", output[0].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call", output[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("other", output[1].jsonObject["namespace"]!!.jsonPrimitive.content)
        assertEquals("wait", output[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun validatedNonemptyTextGrammarIsSupportedButArbitraryGrammarsAreRejected() {
        assertEquals(1, textMinimumLength(ToolGrammar("lark", CODE_MODE_TEXT_GRAMMAR)))
        assertEquals(1, textMinimumLength(ToolGrammar("regex", "[\\s\\S]+")))
        assertEquals(0, textMinimumLength(null))
        for (grammar in listOf(ToolGrammar("lark", "start: \"only-this\""), ToolGrammar("regex", "(a+)+$"))) {
            assertEquals("translation_unsupported", assertFailsWith<TeamFailure> { textInputSchema(grammar) }.code)
        }
    }

    @Test fun malformedOrUnknownCustomCallsFailWithoutPublishingExecutableInput() = runTest {
        val tool = ToolDefinition("execute", null, "{}", null, "functions", ToolInputFormat.TEXT, ToolGrammar("lark", CODE_MODE_TEXT_GRAMMAR))
        for (arguments in listOf("{}", """{"input":null}""", """{"input":""}""", """{"input":"text(1)","extra":true}""", "{broken", """{"input":"\ud800"}""", """{"input":"\udc00"}""")) {
            val frames = listOf(
                body("""{"type":"message_start","message":{"id":"test","usage":{"input_tokens":1,"output_tokens":0}}}"""),
                buildJsonObject { put("type", "content_block_start"); put("index", 0); putJsonObject("content_block") { put("type", "tool_use"); put("id", "call"); put("name", tool.wireName()); put("input", JsonObject(emptyMap())) } },
                buildJsonObject { put("type", "content_block_delta"); put("index", 0); putJsonObject("delta") { put("type", "input_json_delta"); put("partial_json", arguments) } },
                body("""{"type":"content_block_stop","index":0}"""),
            )
            val seen = mutableListOf<JsonObject>()
            val parser = SseFrames()
            assertFailsWith<TeamFailure> {
                StreamTranslation().translate(frames.map { "data: $it\n\n".toByteArray() }.asFlow(), MESSAGES, RESPONSES, "gpt-test", listOf(tool)).collect { seen += parser.accept(it) }
            }
            assertEquals("response.failed", seen.last()["type"]!!.jsonPrimitive.content)
            assertFalse(seen.any { it["type"] in listOf(JsonPrimitive("response.custom_tool_call_input.delta"), JsonPrimitive("response.output_item.done"), JsonPrimitive("response.completed")) })
        }
        val encoder = ResponsesStreamEncoder("gpt-test", listOf(tool))
        assertFailsWith<TeamFailure> { encoder.accept(StreamEvent.ToolStart(0, "call", "not-advertised")) }
    }

    @Test fun conflictingToolsAndUntrustedDeclarationsCannotChangeTheToolRegistry() {
        val tool = codec.decode(declarations, RESPONSES).tools.first()
        assertFailsWith<TeamFailure> { ToolBridge(listOf(tool, ToolDefinition(tool.wireName(), null, "{}", false))) }
        for (payload in listOf(
            """{"input":[{"type":"additional_tools","role":"user","tools":[]}]}""",
            """{"input":[],"tools":[{"type":"function","name":"same","parameters":{}},{"type":"custom","name":"same"}]}""",
            """{"input":[],"reasoning":{"effort":"low","context":"all_turns"}}""",
            """{"input":[],"text":{"verbosity":"unknown"}}""",
            """{"input":[],"tools":[{"type":"function","name":"exec","parameters":{}}],"tool_choice":{"type":"custom","name":"exec"}}""",
        )) assertFailsWith<TeamFailure> { codec.decode(body(payload), RESPONSES) }
    }
    @Test fun emptyReturnedTextMetadataSurvivesReplayButActualProbabilitiesAreNotDiscarded() {
        for (metadata in listOf("[]", "null")) {
            val request = body("""{"input":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Checking.","annotations":[],"logprobs":$metadata}]}]}""")
            assertEquals(listOf(Part.Text("Checking.")), codec.decode(request, RESPONSES).turns.single().content)
        }
        for (metadata in listOf("[{}]", "{}", "false")) {
            val request = body("""{"input":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Checking.","logprobs":$metadata}]}]}""")
            assertFailsWith<TeamFailure> { codec.decode(request, RESPONSES) }
        }
    }
}
