package io.legado.app.help.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import io.legado.app.constant.PreferKey
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx

/**
 * Generate "multi-reader" style comments for a chapter using an OpenAI-compatible endpoint.
 *
 * Notes:
 * - Intentionally on-demand only (no background generation).
 * - Keep output JSON so UI can render a thread-like list.
 */
object AiChapterCommenter {

    data class AiComment(
        @SerializedName("name")
        val name: String,
        @SerializedName("tone")
        val tone: String? = null,
        @SerializedName("reply_to")
        val replyTo: Int? = null,
        @SerializedName("content")
        val content: String
    )

    data class AiCommentResult(
        @SerializedName("comments")
        val comments: List<AiComment> = emptyList()
    )

    class NotEnabledException : IllegalStateException("AI chapter comments disabled")
    class NotConfiguredException : IllegalStateException("AI chapter comments not configured")

    const val DEFAULT_COMMENT_COUNT = 10
    const val MIN_COMMENT_COUNT = 1
    const val MAX_COMMENT_COUNT = 50

    fun isEnabled(): Boolean = appCtx.getPrefBoolean(PreferKey.aiChapterCommentEnabled, false)

    fun getCommentCount(): Int {
        return appCtx.getPrefInt(
            PreferKey.aiChapterCommentCount,
            DEFAULT_COMMENT_COUNT
        ).coerceIn(MIN_COMMENT_COUNT, MAX_COMMENT_COUNT)
    }

    fun loadConfigOrThrow(): Config {
        if (!isEnabled()) throw NotEnabledException()
        val baseUrl = appCtx.getPrefString(PreferKey.aiChapterCommentBaseUrl)?.trim().orEmpty()
        val apiKey = appCtx.getPrefString(PreferKey.aiChapterCommentApiKey)?.trim().orEmpty()
        val model = appCtx.getPrefString(PreferKey.aiChapterCommentModel)?.trim().orEmpty()
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            throw NotConfiguredException()
        }
        return Config(baseUrl = baseUrl, apiKey = apiKey, model = model)
    }

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    private data class ChatMessage(
        @SerializedName("role")
        val role: String,
        @SerializedName("content")
        val content: String
    )

    private data class ResponseFormat(
        @SerializedName("type")
        val type: String
    )

    private data class ChatCompletionRequest(
        @SerializedName("model")
        val model: String,
        @SerializedName("messages")
        val messages: List<ChatMessage>,
        @SerializedName("temperature")
        val temperature: Double = 0.9,
        @SerializedName("max_tokens")
        val maxTokens: Int = 10_000,
        @SerializedName("response_format")
        val responseFormat: ResponseFormat? = null,
    )

    suspend fun generateComments(
        config: Config,
        bookName: String,
        chapterTitle: String,
        chapterIndex: Int,
        chapterText: String,
    ): AiCommentResult {
        val trimmed = chapterText.trim()
        val maxChars = 12_000
        val excerpt = if (trimmed.length <= maxChars) trimmed else trimmed.substring(0, maxChars)
        val commentCount = getCommentCount()
        val replyCount = when {
            commentCount >= 6 -> 3
            commentCount >= 3 -> 1
            else -> 0
        }
        val replyRule = if (replyCount > 0) {
            "2) 至少 $replyCount 条是对其他评论的回复（reply_to 指向被回复评论在数组中的下标，从 0 开始）。"
        } else {
            "2) 评论数量较少时 reply_to 可以为 null。"
        }

        val sys = """
            你将扮演“多个不同的读者”，为一章小说内容生成评论区讨论。
            请严格输出 JSON 对象，格式如下：
            {
              "comments": [
                {"name":"昵称","tone":"吹捧/吐槽/理性分析/阴谋论/磕CP/玩梗/挑刺/路人","reply_to":null或整数,"content":"评论正文"}
              ]
            }
            规则：
            1) 生成 $commentCount 条评论，昵称要彼此不同，有明显性格差异。
            $replyRule
            3) 观点要有冲突：有人吹捧、有人吐槽、有人分析、有人阴阳怪气、有人玩梗。
            4) 不要剧透后续章节，只讨论“本章内容”与合理猜测。
            5) 用中文输出，内容要像真实读者，不要出现“作为AI”之类字眼。
        """.trimIndent()

        val user = """
            书名：$bookName
            章节：第${chapterIndex + 1}章 $chapterTitle
            章节正文（可能为截断片段）：
            $excerpt
        """.trimIndent()

        val req = ChatCompletionRequest(
            model = config.model,
            messages = listOf(
                ChatMessage("system", sys),
                ChatMessage("user", user)
            )
        )

        // Prefer JSON mode (response_format=json_object) when supported.
        // If the provider rejects the field, retry once without it for compatibility.
        val responseBody = kotlin.runCatching {
            postChatCompletions(config, req.copy(responseFormat = ResponseFormat("json_object")))
        }.recoverCatching { e ->
            if (isResponseFormatUnsupported(e)) {
                postChatCompletions(config, req)
            } else {
                throw e
            }
        }.getOrThrow()

        val content = extractAssistantContent(responseBody)
        if (content.isBlank()) {
            // Some providers may return an empty content even when HTTP 200 (e.g. JSON mode edge cases).
            throw IllegalStateException("LLM 返回空内容（content 为空），请更换模型或稍后重试。")
        }
        val assistantJson = parseJsonObjectLenient(content)
        return kotlin.runCatching { GSON.fromJson(assistantJson, AiCommentResult::class.java) }
            .getOrElse {
                // Fallback: if parsing fails, wrap as single comment.
                AiCommentResult(
                    comments = listOf(
                        AiComment(
                            name = "路人甲",
                            tone = "理性分析",
                            replyTo = null,
                            content = content.trim()
                        )
                    )
                )
            }
    }

    private suspend fun postChatCompletions(config: Config, req: ChatCompletionRequest): String {
        val url = buildChatCompletionsUrl(config.baseUrl)
        // Build the JSON body as UTF-8 bytes to avoid providers that are strict about "Content-Type".
        val reqJson = GSON.toJson(req)
        val body = reqJson.toByteArray(Charsets.UTF_8)
            .toRequestBody("application/json".toMediaType())
        val resp = okHttpClient.newCallStrResponse {
            url(url)
            addHeader("Authorization", "Bearer ${config.apiKey}")
            post(body)
        }

        val responseBody = resp.body ?: ""
        // Some gateways may return HTTP 200 with an {"error": ...} payload. Treat that as an error.
        extractErrorMessage(responseBody)?.let { msg ->
            // Only treat it as an error when the top-level payload actually looks like an error envelope.
            // If it's not an error envelope, extractErrorMessage() will return null.
            if (looksLikeErrorEnvelope(responseBody)) {
                throw IllegalStateException(msg)
            }
        }

        if (!resp.isSuccessful()) {
            val msg = extractErrorMessage(responseBody)
            throw IllegalStateException(msg ?: "HTTP ${resp.code()}: ${resp.message()}")
        }
        if (responseBody.isBlank()) throw IllegalStateException("Empty response body")
        return responseBody
    }

    private fun looksLikeErrorEnvelope(body: String): Boolean {
        return kotlin.runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            root.has("error")
        }.getOrDefault(false)
    }

    private fun isResponseFormatUnsupported(e: Throwable): Boolean {
        val msg = e.message?.lowercase().orEmpty()
        if (!msg.contains("response_format")) return false
        return msg.contains("unrecognized") ||
                msg.contains("unknown parameter") ||
                msg.contains("extra fields not permitted") ||
                msg.contains("not supported")
    }

    private fun buildChatCompletionsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return ""

        // If user pasted a full endpoint (ends with /chat/completions), keep it as-is.
        val noSlash = trimmed.removeSuffix("/")
        if (noSlash.endsWith("/chat/completions")) {
            return noSlash
        }

        var b = noSlash
        if (!b.contains("/v1")) {
            b += "/v1"
        }
        b = b.removeSuffix("/")
        return "$b/chat/completions"
    }

    private fun extractAssistantContent(responseBody: String): String {
        val root = JsonParser.parseString(responseBody).asJsonObject
        val choices = root.getAsJsonArray("choices")
        val first = if (choices != null && choices.size() > 0) choices[0].asJsonObject else null
        val message = first?.getAsJsonObject("message")
        return message?.get("content")?.asString ?: responseBody
    }

    private fun extractErrorMessage(body: String): String? {
        return kotlin.runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            val err = root.getAsJsonObject("error") ?: return@runCatching null
            err.get("message")?.asString
        }.getOrNull()
            ?: body.trim().lineSequence().firstOrNull()?.take(200)
    }

    /**
     * Some models may prepend/append extra text. Try to extract the first JSON object.
     */
    private fun parseJsonObjectLenient(content: String): String {
        val trimmed = content.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val sub = trimmed.substring(start, end + 1)
            // Validate JSON.
            val obj = JsonParser.parseString(sub)
            if (obj is JsonObject) return sub
            if (obj.isJsonObject) return sub
        }
        // As last resort, return a JSON wrapper.
        return GSON.toJson(
            AiCommentResult(
                comments = listOf(
                    AiComment(name = "路人甲", tone = "吐槽", replyTo = null, content = trimmed)
                )
            )
        )
    }
}
