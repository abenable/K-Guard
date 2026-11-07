package com.tele.k_guard.live

import android.graphics.Bitmap
import android.content.Context
import android.util.Base64
import android.util.Log
import com.tele.k_guard.data.model.AgeProfile
import com.tele.k_guard.data.model.AiConfig
import com.tele.k_guard.data.model.GeminiKeyRotationManager
import com.tele.k_guard.data.model.LlmProviderType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the AI made of one screenshot. */
data class ScreenVerdict(
    val level: Level,
    /** One of K-Guard's categories (see functions/src/policy.ts), or "none". */
    val category: String,
    /** Short explanation for the parent. */
    val reason: String,
    /** Short, kind message for the child. */
    val childMessage: String
) {
    enum class Level { SAFE, CAUTION, HARMFUL }
}

/**
 * Decides whether what is on the child's screen is suitable for their age group.
 *
 * 1. On the phone, always: [NsfwScreenModel] (the model Trawl uses) checks for sexual content. Nothing leaves the phone.
 * 2. Only if a cloud model is configured (`kguard.ai.url` or `kguard.gemini.keys`) and the screen passed step 1: the
 *    self-hosted model (OpenAI-style vision messages), otherwise Gemini, checks the other categories.
 * Returns null when nothing could check the frame; the caller simply tries again with the next one.
 */
class LiveScreenAnalyzer(context: Context) {
    private val local = NsfwScreenModel(context.applicationContext)

    fun analyze(frame: Bitmap, age: AgeProfile, appLabel: String?, jpeg: () -> ByteArray): ScreenVerdict? {
        val p = runCatching { local.score(frame) }
            .onFailure { Log.e(TAG, "On-device screen check failed", it) }
            .getOrNull()
        if (p != null) {
            val level = NsfwScreenModel.verdictFor(p, age)
            if (level != ScreenVerdict.Level.SAFE) return ScreenVerdict(
                level = level,
                category = "sexual",
                reason = "Sexual content on screen" + (appLabel?.let { " in $it" } ?: "") + " (on-device check, ${(p * 100).toInt()}%).",
                childMessage = "this looks like something that isn't meant for you, so K-Guard has covered it."
            )
        }
        if (!AiConfig.cloudConfigured) {
            return if (p == null) null else ScreenVerdict(ScreenVerdict.Level.SAFE, "none", "On-device check passed.", "")
        }
        return analyzeInCloud(jpeg(), age, appLabel)
    }

    fun close() = local.close()

    private fun analyzeInCloud(jpeg: ByteArray, age: AgeProfile, appLabel: String?): ScreenVerdict? {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = prompt(age, appLabel)
        if (AiConfig.provider == LlmProviderType.SELF_HOSTED) {
            runCatching { return selfHosted(prompt, b64) }
                .onFailure { Log.w(TAG, "Self-hosted vision check failed (${it.message}); using Gemini") }
        }
        return runCatching { gemini(prompt, b64) }
            .onFailure { Log.w(TAG, "Gemini vision check failed: ${it.message}") }
            .getOrNull()
    }

    // ------------------------------------------------------------------ Gemini
    private fun gemini(prompt: String, b64: String): ScreenVerdict {
        fun body(model: String) = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("text", prompt)))))
            put("generationConfig", JSONObject()
                .put("temperature", 0.1)
                .put("maxOutputTokens", 300)
                .put("responseMimeType", "application/json")
                .put("responseSchema", SCHEMA)
                .apply { if (supportsNoThinking(model)) put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) })
            // The reviewer must be able to describe harmful screens, so only extreme output is filtered.
            put("safetySettings", JSONArray().apply {
                listOf("HARASSMENT", "HATE_SPEECH", "SEXUALLY_EXPLICIT", "DANGEROUS_CONTENT").forEach {
                    put(JSONObject().put("category", "HARM_CATEGORY_$it").put("threshold", "BLOCK_ONLY_HIGH"))
                }
            })
        }.toString()

        var lastError: IOException? = null
        for (model in MODELS) {
            var keysTried = 0
            while (keysTried < GeminiKeyRotationManager.keyCount) {
                val (code, text) = post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    body(model), mapOf("x-goog-api-key" to GeminiKeyRotationManager.getCurrentKey())
                )
                when {
                    code in 200..299 -> return parseGemini(text)
                    code == 429 || code == 403 || text.contains("RESOURCE_EXHAUSTED") -> {
                        lastError = IOException("HTTP $code"); GeminiKeyRotationManager.rotateKey(); keysTried++
                    }
                    code == 404 -> { lastError = IOException("$model not available"); break }
                    else -> throw IOException("Gemini $model HTTP $code: ${text.take(200)}")
                }
            }
        }
        throw lastError ?: IOException("Gemini could not be reached")
    }

    private fun parseGemini(raw: String): ScreenVerdict {
        val json = JSONObject(raw)
        val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty().isNotBlank()
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val finish = candidate?.optString("finishReason").orEmpty()
        // If the model's own filter refuses even to look at the screen, the screen itself is the problem.
        if (blocked || finish == "SAFETY" || finish == "PROHIBITED_CONTENT") {
            return ScreenVerdict(ScreenVerdict.Level.HARMFUL, "sexual",
                "The screen showed content the AI safety filter refused to process (likely explicit).", DEFAULT_CHILD_MESSAGE)
        }
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: throw IOException("Empty answer")
        val text = buildString { for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty()) }
        return parseVerdict(text)
    }

    // ------------------------------------------------------------------ self-hosted (OpenAI-compatible vision)
    private fun selfHosted(prompt: String, b64: String): ScreenVerdict {
        val base = AiConfig.selfHostedUrl.trimEnd('/')
        val url = when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
        val body = JSONObject().apply {
            put("model", AiConfig.selfHostedModel)
            put("temperature", 0.1)
            put("max_tokens", 300)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "$prompt\n\nReply with only a JSON object with keys verdict, category, reason, childMessage."))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))))))
        }.toString()
        val headers = buildMap {
            put("ngrok-skip-browser-warning", "true")
            if (AiConfig.selfHostedKey.isNotBlank()) put("Authorization", "Bearer ${AiConfig.selfHostedKey}")
        }
        val (code, text) = post(url, body, headers)
        if (code !in 200..299) throw IOException("HTTP $code: ${text.take(200)}")
        val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        return parseVerdict(content)
    }

    // ------------------------------------------------------------------ helpers
    private fun parseVerdict(text: String): ScreenVerdict {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("No JSON in answer")
        val j = JSONObject(text.substring(start, end + 1))
        val level = when (j.optString("verdict").uppercase()) {
            "HARMFUL" -> ScreenVerdict.Level.HARMFUL
            "CAUTION" -> ScreenVerdict.Level.CAUTION
            else -> ScreenVerdict.Level.SAFE
        }
        val category = j.optString("category").lowercase().takeIf { it in CATEGORIES } ?: "none"
        return ScreenVerdict(
            // A "harmful" verdict that names no category is treated as a heads-up only.
            level = if (level == ScreenVerdict.Level.HARMFUL && category == "none") ScreenVerdict.Level.CAUTION else level,
            category = category,
            reason = j.optString("reason").take(300).ifBlank { "Flagged by K-Guard Live." },
            childMessage = j.optString("childMessage").take(200).ifBlank { DEFAULT_CHILD_MESSAGE }
        )
    }

    private fun post(url: String, body: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 20_000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "KGuardLive"
        /** The lite models reject thinkingBudget = 0 (they don't think by default anyway). */
        fun supportsNoThinking(model: String) = !model.contains("lite")
        private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-flash-lite-latest")
        val CATEGORIES = setOf("cyberbullying", "threat", "violence", "sexual", "self_harm", "hate", "drugs", "profanity", "scam", "personal_info")
        const val DEFAULT_CHILD_MESSAGE = "This might not be right for you. If something worries you, talk to your parent."

        private val SCHEMA = JSONObject("""
            {"type":"OBJECT","properties":{
              "verdict":{"type":"STRING","enum":["SAFE","CAUTION","HARMFUL"]},
              "category":{"type":"STRING","enum":["none","cyberbullying","threat","violence","sexual","self_harm","hate","drugs","profanity","scam","personal_info"]},
              "reason":{"type":"STRING"},
              "childMessage":{"type":"STRING"}},
             "required":["verdict","category","reason","childMessage"]}
        """.trimIndent())

        fun prompt(age: AgeProfile, appLabel: String?): String = """
You are K-Guard Live, the safety reviewer in a family safety app. The image is a screenshot of a child's phone screen.
Child's age group: ${age.title} (${age.ageRange}).${appLabel?.let { "\nApp on screen: $it." } ?: ""}

Decide whether what is visible right now is suitable for this age group. Look for:
- sexual content, nudity or suggestive images
- graphic violence, gore, weapons used to hurt
- self-harm or suicide content
- hate speech or hate symbols
- drugs, alcohol, vaping or gambling
- bullying or harassing messages, or threats
- a stranger asking for personal details, photos, secrets or to meet (grooming), or scams/phishing
- strong profanity (matters more for younger children)

Normal screens are SAFE: home screens, app lists, settings, notifications, homework, ordinary games, cartoons, ordinary videos, friendly chats, shopping, maps.
An app's icon or name on its own is never a reason to flag; judge the content actually shown.
Judge only what is clearly visible; never guess. Be stricter for younger children and more lenient for teens.
Answer:
- verdict: SAFE, CAUTION (mildly unsuitable, worth a gentle heads-up) or HARMFUL (clearly unsuitable or a risk to the child; should be hidden)
- category: the best-fitting category, or "none" when SAFE
- reason: one short sentence for the parent describing what was on screen, without graphic detail (max 25 words)
- childMessage: one short, kind sentence for the child, no blame (max 20 words)
""".trim()
    }
}
