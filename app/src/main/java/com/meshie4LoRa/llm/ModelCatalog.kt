package com.meshie4LoRa.llm

import com.meshie4LoRa.data.ModelEntity
import com.meshie4LoRa.data.ModelSource

/** Curated starter models; sizes and SHA-256 verified against Hugging Face on 2026-11-07. */
object ModelCatalog {
    private fun hf(repo: String, file: String) = "https://huggingface.co/$repo/resolve/main/$file"

    val entries: List<ModelEntity> = listOf(
        catalog("qwen3.5-0.8b-q4km", "Qwen3.5 0.8B", "Tiny and fast, weak personality. Hybrid model: can't reuse its prompt cache, so decisions are slower.",
            hf("unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf"), 532_517_120,
            "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"),
        catalog("llama3.2-1b-q4km", "Llama 3.2 1B Instruct", "Recommended for screen-off use: fast decisions with prompt caching.",
            hf("bartowski/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"), 807_694_464,
            "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83"),
        catalog("qwen3.5-2b-q4km", "Qwen3.5 2B", "Smarter than 1B. Hybrid model: no prompt-cache reuse, slow decisions.",
            hf("unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_K_M.gguf"), 1_280_835_840,
            "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"),
        catalog("llama3.2-3b-q4km", "Llama 3.2 3B Instruct", "Best personality that's still usable screen-off (~3 s decisions, slow replies).",
            hf("bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf"), 2_019_377_696,
            "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff"),
        catalog("qwen3-4b-2507-q4km", "Qwen3 4B Instruct 2507", "Strong non-thinking instruct model.",
            hf("unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"), 2_497_281_120,
            "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597"),
        catalog("qwen3.5-4b-q4km", "Qwen3.5 4B", "Newest Qwen at 4B; may emit thinking blocks.",
            hf("unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf"), 2_740_937_888,
            "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4"),
        catalog("gemma4-e2b-q4km", "Gemma 4 E2B", "Google's on-device model, chatty and friendly.",
            hf("unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf"), 3_106_738_272,
            "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8"),
        catalog("gemma4-e4b-q4km", "Gemma 4 E4B", "Best quality here, but tight on 8 GB RAM.",
            hf("unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf"), 4_977_171_584,
            "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87"),

        // Abliterated (refusal-direction removed) variants: no "I can't help with that" in the chat.
        catalog("llama3.2-1b-abl-q4km", "Llama 3.2 1B Abliterated", "Uncensored 1B. Fast screen-off decisions, won't refuse silly requests.",
            hf("mradermacher/Llama-3.2-1B-Instruct-abliterated-GGUF", "Llama-3.2-1B-Instruct-abliterated.Q4_K_M.gguf"), 955_445_792,
            "68336c25367576f49b29f01ee9034658b51bbded6a7f215e934d997de96301e0"),
        catalog("gemma3-1b-abl-q4km", "Gemma 3 1B Abliterated", "Uncensored Gemma 3 1B.",
            hf("bartowski/huihui-ai_gemma-3-1b-it-abliterated-GGUF", "huihui-ai_gemma-3-1b-it-abliterated-Q4_K_M.gguf"), 806_058_592,
            "0760a54504d7529daf65f2a5de0692e773313685f50dd7f7eece2dae0dc28338"),
        // Dolphin 3.0: fine-tuned to follow the system prompt instead of refusing (verified 2026-11-08).
        catalog("dolphin3-llama3.2-1b-q4km", "Dolphin 3.0 Llama 3.2 1B", "Uncensored fine-tune of Llama 3.2 1B; follows the persona instead of refusing.",
            hf("bartowski/Dolphin3.0-Llama3.2-1B-GGUF", "Dolphin3.0-Llama3.2-1B-Q4_K_M.gguf"), 807_697_440,
            "7ed39ee0638e18d3e47bf12e60e917c792ca5332606a72bd1882ab1f62a13a7a"),
        catalog("dolphin3-qwen2.5-1.5b-q4km", "Dolphin 3.0 Qwen2.5 1.5B", "Uncensored Qwen2.5 1.5B fine-tune; a bit smarter than 1B, still fast.",
            hf("bartowski/Dolphin3.0-Qwen2.5-1.5B-GGUF", "Dolphin3.0-Qwen2.5-1.5B-Q4_K_M.gguf"), 986_051_648,
            "7caa630a60c8831a509e2663e1761355fa24bcf6ccc03e3cc767e5b5747a3be5"),
        catalog("llama3.2-1b-uncensored-q4km", "Llama 3.2 1B Uncensored", "Fine-tuned (not abliterated) to drop refusals.",
            hf("mradermacher/Llama-3.2-1B-Instruct-Uncensored-GGUF", "Llama-3.2-1B-Instruct-Uncensored.Q4_K_M.gguf"), 807_691_520,
            "d886cf5e4af6f9130902a52e29e8085b3ca03e47f380360fbd1d7c72f454eb95"),
        catalog("hermes3-llama3.2-3b-q4km", "Hermes 3 Llama 3.2 3B", "Role-play / persona specialist from Nous Research. Slow screen-off.",
            hf("bartowski/Hermes-3-Llama-3.2-3B-GGUF", "Hermes-3-Llama-3.2-3B-Q4_K_M.gguf"), 2_019_374_176,
            "2e220a14ba4328fee38cf36c2c068261560f999fadb5725ce5c6d977cb5126b5"),
        catalog("dolphin3-llama3.2-3b-q4km", "Dolphin 3.0 Llama 3.2 3B", "Uncensored 3B; smarter Dolphin. Slow screen-off.",
            hf("bartowski/Dolphin3.0-Llama3.2-3B-GGUF", "Dolphin3.0-Llama3.2-3B-Q4_K_M.gguf"), 2_019_382_400,
            "5d6d02eeefa1ab5dbf23f97afdf5c2c95ad3d946dc3b6e9ab72e6c1637d54177"),
        catalog("llama3.2-3b-abl-q4km", "Llama 3.2 3B Abliterated", "Uncensored 3B. Best personality, slow screen-off.",
            hf("QuantFactory/Llama-3.2-3B-Instruct-abliterated-GGUF", "Llama-3.2-3B-Instruct-abliterated.Q4_K_M.gguf"), 2_019_377_472,
            "e1f2b428e316d8bd1cb5ca13e9649329e299d951fbcee6b3cd0e9f1d646c9fa7"),
        catalog("qwen3-4b-2507-abl-q4km", "Qwen3 4B 2507 Abliterated", "Uncensored Qwen3 4B. Smart but slow.",
            hf("mradermacher/Huihui-Qwen3-4B-Instruct-2507-abliterated-GGUF", "Huihui-Qwen3-4B-Instruct-2507-abliterated.Q4_K_M.gguf"), 2_497_281_312,
            "b1324e2f1bd2e0610d591f54f93e38d90b187f69d1f215f4cace349eade678d7"),
    )

    private fun catalog(id: String, name: String, desc: String, url: String, size: Long, sha256: String) = ModelEntity(
        id = id,
        name = name,
        description = desc,
        url = url,
        fileName = url.substringAfterLast('/'),
        sizeBytes = size,
        sha256 = sha256,
        source = ModelSource.CATALOG,
    )
}
