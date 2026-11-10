package com.meshie4LoRa.llm

import android.util.Log
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.random.Random

private const val TAG = "MeshChan.Llm"

data class ChatMessage(val role: String, val content: String)

data class GenParams(
    val maxTokens: Int = 160,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val seed: Int = Random.nextInt(),
    val grammar: String? = null,
    /** Same request without the persona; with [guidance] > 1 the reply is pushed away from it (classifier-free guidance). */
    val negative: List<ChatMessage>? = null,
    val guidance: Float = 1f,
)

data class GenStats(
    val promptTokens: Int,
    val promptMs: Double,
    val genTokens: Int,
    val genMs: Double,
    val reusedTokens: Int = 0,
    val threads: Int = 0,
) {
    val promptTps: Double get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
    val genTps: Double get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
}

data class GenResult(val text: String, val stats: GenStats)

data class ModelInfo(
    val description: String,
    val params: Long,
    val sizeBytes: Long,
    val ctxTrain: Int,
    val ctx: Int,
    val hasTemplate: Boolean,
    val thinkingTemplate: Boolean,
)

sealed interface EngineState {
    data object Unloaded : EngineState
    data class Loading(val modelId: String) : EngineState
    data class Ready(val modelId: String, val info: ModelInfo) : EngineState
    data class Failed(val modelId: String, val message: String) : EngineState
}

/** Owns the single loaded model; all native calls run on one dedicated thread. */
class LlamaEngine {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "llm").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val genMutex = Mutex()
    @Volatile private var handle = 0L

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    suspend fun load(modelId: String, path: String, nCtx: Int, nThreads: Int, repack: Boolean) = genMutex.withLock {
        withContext(dispatcher) {
            freeLocked()
            _state.value = EngineState.Loading(modelId)
            val t0 = System.currentTimeMillis()
            val h = LlamaNative.load(path, nCtx, nThreads, repack)
            if (h == 0L) {
                _state.value = EngineState.Failed(modelId, "Could not load model (unsupported file or out of memory)")
                return@withContext
            }
            handle = h
            val info = parseInfo(LlamaNative.info(h))
            Log.i(TAG, "Loaded $modelId in ${System.currentTimeMillis() - t0} ms: $info")
            _state.value = EngineState.Ready(modelId, info)
        }
    }

    suspend fun unload() = genMutex.withLock {
        withContext(dispatcher) {
            freeLocked()
            _state.value = EngineState.Unloaded
        }
    }

    /** Runs a chat completion; [onText] receives streamed text on the LLM thread. */
    suspend fun chat(messages: List<ChatMessage>, params: GenParams, onText: (String) -> Unit = {}): GenResult = genMutex.withLock {
        withContext(dispatcher) {
            val h = handle
            check(h != 0L) { "No model loaded" }
            val job = currentCoroutineContext()[Job]
            _busy.value = true
            try {
                var prompt = LlamaNative.applyTemplate(
                    h,
                    messages.map { it.role }.toTypedArray(),
                    messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                )
                var negative = params.negative?.takeIf { params.guidance > 1.01f }?.let { neg ->
                    LlamaNative.applyTemplate(h, neg.map { it.role }.toTypedArray(), neg.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray())
                }
                // Same as enable_thinking=false: an empty think block makes reasoning models answer directly.
                if ((_state.value as? EngineState.Ready)?.info?.thinkingTemplate == true) {
                    prompt += "<think>\n\n</think>\n\n".toByteArray(Charsets.UTF_8)
                    negative = negative?.plus("<think>\n\n</think>\n\n".toByteArray(Charsets.UTF_8))
                }
                val out = StringBuilder()
                LlamaNative.generate(
                    h, prompt, params.maxTokens, params.temperature, params.topP, params.topK, params.minP,
                    params.repeatPenalty, params.seed, params.grammar?.toByteArray(Charsets.UTF_8), negative, params.guidance,
                ) { bytes ->
                    val piece = String(bytes, Charsets.UTF_8)
                    out.append(piece)
                    onText(piece)
                    job?.isActive != false
                }
                val s = LlamaNative.lastStats(h)
                GenResult(out.toString(), GenStats(s[0].toInt(), s[1], s[2].toInt(), s[3], s[4].toInt(), s[5].toInt()))
            } finally {
                _busy.value = false
            }
        }
    }

    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)
    }

    /** The exact text the model reads for [messages] (chat template applied, thinking prefill included), or null if no model. */
    suspend fun renderPrompt(messages: List<ChatMessage>): String? = genMutex.withLock {
        withContext(dispatcher) {
            val h = handle
            if (h == 0L) return@withContext null
            var prompt = LlamaNative.applyTemplate(
                h,
                messages.map { it.role }.toTypedArray(),
                messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            ).toString(Charsets.UTF_8)
            if ((_state.value as? EngineState.Ready)?.info?.thinkingTemplate == true) prompt += "<think>\n\n</think>\n\n"
            prompt
        }
    }

    private fun freeLocked() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
    }

    private fun parseInfo(raw: String): ModelInfo {
        val m = raw.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return ModelInfo(
            description = m["desc"].orEmpty(),
            params = m["params"]?.toLongOrNull() ?: 0,
            sizeBytes = m["size"]?.toLongOrNull() ?: 0,
            ctxTrain = m["ctx_train"]?.toIntOrNull() ?: 0,
            ctx = m["ctx"]?.toIntOrNull() ?: 0,
            hasTemplate = m["template"] == "builtin",
            thinkingTemplate = m["think"] == "1",
        )
    }
}
