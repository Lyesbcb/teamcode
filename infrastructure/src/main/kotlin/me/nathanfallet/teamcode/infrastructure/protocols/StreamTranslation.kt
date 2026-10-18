package me.nathanfallet.teamcode.infrastructure.protocols

import java.io.ByteArrayOutputStream
import me.nathanfallet.teamcode.infrastructure.protocols.stream.unsupportedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.routing.StreamEvent

class StreamTranslation(private val codecs: ConversationCodec = ConversationCodec()) {
    fun translate(source: Flow<ByteArray>, from: WireProtocol, to: WireProtocol, model: String): Flow<ByteArray> {
        require(from != to)
        return flow {
            val decoder = codecs.decoder(from)
            val encoder = codecs.encoder(to, model)
            val frames = SseFrames()
            // catch observes only upstream/translation failures, never errors thrown by the downstream collector.
            flow {
                source.collect { bytes -> frames.accept(bytes).forEach { value -> decoder.accept(value).forEach { event ->
                    encoder.accept(event).forEach { emit(it) }
                } } }
                if (!decoder.finished) unsupportedStream()
            }.catch { error ->
                if (error is CancellationException) throw error
                encoder.accept(StreamEvent.Failure).forEach { emit(it) }
                throw error
            }.collect { emit(it) }
        }
    }
}

/** Handles split UTF-8 and multiline SSE data; bounds incomplete lines and complete events. */
internal class SseFrames {
    private val line = ByteArrayOutputStream()
    private val data = StringBuilder()
    fun accept(bytes: ByteArray): List<JsonObject> = buildList {
        bytes.forEach { b ->
            if (b == 10.toByte()) {
                val text = line.toString(Charsets.UTF_8).trimEnd('\r')
                line.reset()
                if (text.isEmpty()) {
                    val payload = data.toString().trim(); data.clear()
                    if (payload.isNotEmpty() && payload != "[DONE]") add(parse(payload))
                } else if (text.startsWith("data:")) {
                    data.append(text.removePrefix("data:").removePrefix(" ")).append('\n')
                    if (data.length > LIMIT) unsupportedStream()
                }
            } else {
                if (line.size() >= LIMIT) unsupportedStream()
                line.write(b.toInt())
            }
        }
    }
    private fun parse(value: String) = try { Json.parseToJsonElement(value) as? JsonObject ?: unsupportedStream() } catch (_: IllegalArgumentException) { unsupportedStream() }
    companion object { const val LIMIT = 1_048_576 }
}
