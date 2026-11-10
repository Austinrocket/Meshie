package com.meshie4LoRa.llm

fun interface TokenCallback {
    /** Receives complete UTF-8 chunks; return false to stop generating. */
    fun onToken(bytes: ByteArray): Boolean
}

/** Thin JNI surface over llama.cpp (see app/src/main/cpp/llama_jni.cpp). Not thread-safe except [cancel]. */
object LlamaNative {
    init {
        System.loadLibrary("meshchan_llm")
        init()
    }

    @JvmStatic private external fun init()
    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int, repack: Boolean): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun info(handle: Long): String
    @JvmStatic external fun applyTemplate(handle: Long, roles: Array<String>, contents: Array<ByteArray>): ByteArray
    @JvmStatic external fun generate(
        handle: Long,
        prompt: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        seed: Int,
        grammar: ByteArray?,
        negativePrompt: ByteArray?,
        cfgScale: Float,
        callback: TokenCallback,
    ): Boolean
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun lastStats(handle: Long): DoubleArray
}
