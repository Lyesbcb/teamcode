package me.nathanfallet.teamcode.infrastructure.protocols.stream

import java.util.UUID
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.routing.*

interface StreamEncoder {
    fun accept(event: StreamEvent): List<ByteArray>
}

internal abstract class BaseStreamEncoder(protected val model: String, prefix: String) : StreamEncoder {
    protected val id = prefix + UUID.randomUUID().toString().replace("-", "")
    private var terminated = false
    private var bytes = 0L
    protected val blocks = linkedMapOf<Int, Block>()
    protected data class Block(val index: Int, val callId: String? = null, val name: String? = null, val content: StringBuilder = StringBuilder(), var closed: Boolean = false)
    final override fun accept(event: StreamEvent): List<ByteArray> {
        if (terminated) return emptyList()
        val values = encode(event)
        if (event is StreamEvent.Finish || event == StreamEvent.Failure) terminated = true
        return values.map { value -> "event: ${value.required("type")}\ndata: $value\n\n".toByteArray() }
    }
    protected open fun event(type: String, fields: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("type", type); fields() }
    protected abstract fun encode(e: StreamEvent): List<JsonObject>
    protected fun addBlock(index: Int, call: String? = null, name: String? = null): Block {
        if (index in blocks || blocks.size >= 256) unsupportedStream()
        return Block(blocks.size, call, name).also { blocks[index] = it }
    }
    protected fun append(e: StreamEvent.Delta): Block {
        val b = blocks[e.index]?.takeIf { !it.closed } ?: unsupportedStream()
        bytes += e.value.toByteArray().size
        if (bytes > 8 * 1024 * 1024) unsupportedStream()
        b.content.append(e.value); return b
    }
    protected fun close(index: Int): Block = (blocks[index]?.takeIf { !it.closed } ?: unsupportedStream()).also {
        if (it.callId != null && it.content.isEmpty()) it.content.append("{}")
        if (it.callId != null) try { if (Json.parseToJsonElement(it.content.toString()) !is JsonObject) unsupportedStream() } catch (_: IllegalArgumentException) { unsupportedStream() }
        it.closed = true
    }
}
