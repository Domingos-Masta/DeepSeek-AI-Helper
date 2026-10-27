package com.deepseek.plugin.api

import com.deepseek.plugin.settings.DeepSeekSettings
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Image parsing client supporting multiple providers.
 *
 * Selects the image parsing provider based on settings.imageParsingModel:
 * - "agnes"   → uses the Agnes API (reuses the Agnes key)
 * - "stepfun"  → uses the StepFun API (uses a dedicated StepFun key)
 * - "nvidia"  → uses the NVIDIA API (reuses the NVIDIA key)
 *
 * Parses an image into a text description, then injects it into the Chat context.
 */
class StepFunApiClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    /** HTTP client using the shared connection pool */
    private val client get() = HttpClientProvider.stepFunClient

    /** Tracks the currently executing HTTP Call for forced cancellation */
    private val currentCall = AtomicReference<Call?>(null)

    /**
     * Forcefully cancels the currently executing image parsing request (if any).
     * OkHttp's [Call.cancel] causes [Call.execute] to throw IOException("Canceled") immediately.
     */
    fun cancelCurrentCall() {
        currentCall.getAndSet(null)?.cancel()
    }

    // ── Provider configuration ──

    private data class ProviderConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val displayName: String
    )

    private fun resolveProvider(settings: DeepSeekSettings): ProviderConfig {
        return when (settings.imageParsingModel) {
            "stepfun" -> ProviderConfig(
                baseUrl = "https://api.stepfun.com/v1",
                apiKey = settings.stepFunApiKey,
                model = "step-1o-turbo-vision",
                displayName = "StepFun"
            )
            "nvidia" -> ProviderConfig(
                baseUrl = settings.nvidiaBaseUrl.trimEnd('/'),
                apiKey = settings.nvidiaApiKey,
                model = "meta/llama-4-maverick-17b-128e-instruct",
                displayName = "NVIDIA Llama Vision"
            )
            else -> ProviderConfig(  // "agnes" (default)
                baseUrl = settings.agnesBaseUrl.trimEnd('/'),
                apiKey = settings.agnesApiKey,
                model = settings.agnesModel.ifBlank { "agnes-2.5-flash" },
                displayName = "Agnes Image 2.1 Flash"
            )
        }
    }

    // ── Request/response models ──

    data class ContentPart(
        val type: String,  // "text" | "image_url"
        val text: String? = null,
        @SerializedName("image_url") val imageUrl: ImageUrl? = null
    )

    data class ImageUrl(
        val url: String  // base64 data URI or HTTP URL
    )

    data class VisionMessage(
        val role: String,
        val content: List<ContentPart>
    )

    data class VisionRequest(
        val model: String,
        val messages: List<VisionMessage>,
        @SerializedName("max_tokens") val maxTokens: Int = 4096,
        val stream: Boolean = false
    )

    data class VisionChoice(
        val index: Int,
        val message: VisionResponseMessage?,
        @SerializedName("finish_reason") val finishReason: String? = null
    )

    data class VisionResponseMessage(
        val role: String? = null,
        val content: String? = null
    )

    data class VisionResponse(
        val id: String? = null,
        val `object`: String? = null,
        val created: Long? = null,
        val model: String? = null,
        val choices: List<VisionChoice>? = null,
        val usage: Usage? = null
    )

    // ── Public methods ──

    /**
     * Parses a single image file and returns a text description.
     *
     * @param imagePath Absolute path to the image file
     * @param prompt    Optional prompt; by default the model is asked to describe the image in detail
     * @return Text description of the image, or an error message on failure
     */
    fun parseImage(imagePath: String, prompt: String = "Describe the content of this image in detail"): Result<String> {
        val settings = DeepSeekSettings.instance
        val provider = resolveProvider(settings)

        if (provider.apiKey.isBlank()) {
            val providerField = when (settings.imageParsingModel) {
                "stepfun" -> "StepFun API Key"
                "nvidia" -> "NVIDIA API Key"
                else -> "API Key (Agnes section)"
            }
            return Result.failure(IOException(
                "${provider.displayName} API key is not configured; set it in $providerField under Settings → Tools → DeepSeek AI"
            ))
        }

        // H3: Image parsing rate-limit check (max 10 per minute)
        if (!HttpClientProvider.stepFunRateLimiter.tryAcquire()) {
            return Result.failure(RateLimitException("Image parsing is too frequent, please try again later"))
        }

        // Read the image and convert it to a base64 data URI
        val dataUri = readImageAsBase64(imagePath)
            ?: return Result.failure(IOException("Unable to read image file: $imagePath"))

        val request = VisionRequest(
            model = provider.model,
            messages = listOf(
                VisionMessage(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = prompt),
                        ContentPart(type = "image_url", imageUrl = ImageUrl(url = dataUri))
                    )
                )
            ),
            maxTokens = 4096
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)
        val httpRequest = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val call = client.newCall(httpRequest)
        currentCall.set(call)
        return try {
            val result = call.execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = tryParseError(responseBody, response.code)
                    Result.failure(IOException("${provider.displayName} API error ${response.code}: $errMsg"))
                } else {
                    val visionResp = gson.fromJson(responseBody, VisionResponse::class.java)
                    val content = visionResp.choices?.firstOrNull()?.message?.content ?: ""
                    if (content.isBlank()) {
                        Result.failure(IOException("${provider.displayName} returned empty content"))
                    } else {
                        Result.success(content.trim())
                    }
                }
            }
            result
        } catch (e: IOException) {
            Result.failure(e)
        } finally {
            currentCall.set(null)
        }
    }

    /**
     * Parses multiple images in batch and returns a list of descriptions, one per image.
     * Each image calls the API independently.
     */
    fun parseImages(imagePaths: List<String>): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        for (path in imagePaths) {
            val fileName = Paths.get(path).fileName.toString()
            val result = parseImage(path)
            val description = result.getOrElse { e -> "[Image parsing failed: ${e.message}]" }
            results.add(fileName to description)
        }
        return results
    }

    // ── Internal methods ──

    /**
     * Reads an image file and converts it to a base64 data URI string.
     * Supports common formats such as PNG, JPEG, GIF, BMP, WEBP.
     */
    private fun readImageAsBase64(imagePath: String): String? {
        return try {
            val bytes = Files.readAllBytes(Paths.get(imagePath))
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val mimeType = detectMimeType(imagePath)
            "data:$mimeType;base64,$base64"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Detects the MIME type from the file extension.
     */
    private fun detectMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "image/png" // default
        }
    }

    private fun tryParseError(body: String, code: Int): String {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            json.getAsJsonObject("error")?.get("message")?.asString ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
    }
}
