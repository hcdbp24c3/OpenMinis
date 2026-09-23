package com.openminis.app.data.model

data class LLMMessage(
    val role: Role,
    val content: String,
    val imageParts: List<ImagePart> = emptyList(),
    /**
     * Inline audio attachments for audio-input models (GH#67). Kept as the
     * caller's base64 string — payloads can be megabytes and every consumer
     * (OpenAI input_audio blocks) wants base64 back, so decode/re-encode
     * would be pure churn. Mirrors iOS `LLMMessage.audios`.
     */
    val audioParts: List<AudioPart> = emptyList(),
    val contentParts: List<AgentContentPart> = emptyList(),
    /**
     * Persisted DB row id this AgentMessage corresponds to, when applicable.
     * Mirrors iOS `AgentMessage.dbMessageId`. Used by compact logic to
     * resolve marker boundaries against the in-memory history without
     * relying on positional indexes (which break across reloads). `null`
     * for synthesized messages (e.g. injected `<context-summary>` heads).
     */
    val dbMessageId: String? = null,
    /**
     * Captured reasoning_content from the originating assistant turn
     * (DeepSeek V4 / Kimi / GLM / etc. — chat-completions interleaved
     * reasoning models). Mirrors iOS `AgentMessage.reasoningContent`. Only
     * used when echoing assistant history back to the model on a subsequent
     * turn — DeepSeek V4 rejects history where any assistant turn lacks
     * reasoning_content once `thinking` is enabled.
     */
    val reasoningContent: String? = null,
    /**
     * [T-android-responses-reasoning-echo] Encrypted reasoning items captured
     * from a prior Responses-API turn (`reasoning.encrypted_content`), to be
     * replayed at the head of this assistant turn on the next request.
     * In-memory only (mirrors iOS `AgentMessage.reasoningEcho` — not persisted
     * to DB). `null` for user turns / pre-fix history / non-Responses providers.
     */
    val reasoningEcho: ReasoningEcho? = null,
) {
    enum class Role(val value: String) {
        USER("user"),
        ASSISTANT("assistant"),
    }

    data class ImagePart(
        val data: ByteArray,
        val mimeType: String,
        /**
         * iSH-visible linux path the bytes were originally persisted to, if
         * any. Used by [ImageBudget.planRequestBudget] to emit an
         * agent-readable text placeholder (`[image elided… original at <path>;
         * re-fetch with read_image]`) instead of the bytes when the request
         * payload exceeds the per-request image budget. `null` for images
         * that were never offloaded (extremely rare; spillover writer in
         * ImageBudget handles those at drop time).
         *
         * Backward-compatible: existing call sites default to `null`; old
         * persisted history that round-trips through new code stays valid.
         */
        val linuxPath: String? = null,
        /**
         * [T-android-vision-group / GH#182] Text a provider substitutes for the
         * pixels when the target model has NO native image input (the T264
         * placeholder path). Seeded by ChatViewModel ONLY when a Vision Group is
         * configured: it names the image path and instructs the model to call
         * read_image, so a text-only model routes the image through the Vision
         * Group instead of being told "I can't see it". Null → provider emits its
         * default "does not support vision input" literal (current behaviour when
         * no Vision Group is set).
         */
        val noVisionPlaceholder: String? = null,
    )

    /** Mirrors iOS `LLMMessage.AudioAttachment` (GH#67). */
    data class AudioPart(
        val format: String,     // e.g. "wav", "mp3" (OpenAI input_audio.format)
        val base64Data: String, // base64-encoded audio bytes
    )
}

/**
 * [T-android-responses-reasoning-echo] Captured encrypted reasoning from a
 * prior Responses-API assistant turn, replayed on the next request so the
 * model retains its chain of thought across turns (`store:false` means the
 * server does not keep it for us).
 *
 * Mirrors iOS `ReasoningEcho` (AgentProvider.swift). Tagged with the producing
 * model so cross-model switches strip the payload — encrypted_content is
 * model-specific and 400-inducing on a different model id.
 */
data class ReasoningEcho(
    /** Stable provider-family tag; matches `OpenAIProvider.responsesAPIProviderKind`. */
    val providerKind: String,
    /** Concrete model id the payload was encrypted for (e.g. "gpt-5.5"). */
    val modelId: String,
    /** Reasoning items in original emission order — must be replayed in order. */
    val items: List<Item>,
) {
    sealed class Item {
        /** OpenAI Responses API reasoning item. */
        data class OpenAIReasoning(
            val id: String,
            val encryptedContent: String?,
            /** Summary text blocks; empty list still emits `"summary": []`. */
            val summary: List<String>,
            /**
             * Plaintext reasoning blocks from `content[]`
             * (`type=="reasoning_text"`) — DeepSeek-shaped items carry these
             * instead of `encrypted_content`. Field name is `reasoningText`;
             * the wire name stays `content[]`.
             */
            val reasoningText: List<String> = emptyList(),
        ) : Item()
    }
}

data class LLMResponse(
    val text: String,
    val stopReason: String?,
    val usage: LLMUsage?,
    val mediaAttachments: List<LLMMediaAttachment> = emptyList(),
)

/** Mirrors iOS LLMMediaAttachment (LLMTypes.swift). */
data class LLMMediaAttachment(
    val type: MediaType,
    val mimeType: String,
    val data: ByteArray,
) {
    enum class MediaType(val value: String) {
        IMAGE("image"),
        AUDIO("audio"),
        VIDEO("video"),
    }
}
