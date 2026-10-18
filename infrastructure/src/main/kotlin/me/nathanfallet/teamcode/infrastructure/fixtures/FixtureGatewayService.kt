package me.nathanfallet.teamcode.infrastructure.fixtures

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.domain.gateway.*

/** Synthetic transport fixture. Never authenticates to or calls an actual model provider. */
class FixtureGatewayService(private val chunkDelayMillis: Long = 20) : GatewayService {
    private val active = AtomicInteger()
    private val completed = AtomicLong()

    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
        active.incrementAndGet()
        try {
            receive(GenerationHead(200, "text/event-stream"), flow {
                for (event in EVENTS) {
                    emit(event.encodeToByteArray())
                    delay(chunkDelayMillis)
                }
            })
            completed.incrementAndGet()
        } finally { active.decrementAndGet() }
    }

    override fun status() = GatewaySnapshot("fixture", active.get(), completed.get())

    companion object {
        val EVENTS = listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_fixture\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"teamcode-fixture\",\"content\":[],\"stop_reason\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Synthetic TeamCode stream. No provider was called.\"}}\n\n",
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        )
    }
}
