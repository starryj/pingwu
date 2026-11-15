package com.pingwu.app.network

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多件一起判时，要拿它去比对的其中一件。
 *
 * [coverJpeg] 是登记照（裁好的那一块）；没登记过就是 null，只能按名字和数量看。
 */
data class JudgeTarget(
    val name: String,
    val count: Int,
    val coverJpeg: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 最小可用的 OpenAI 兼容客户端：只做两件事——测试连通性、让视觉模型看一张照片。
 * 不引第三方 HTTP 库，避免给 App 加体积和权限。
 */
class OnlineVisionClient {

    /**
     * 测试连接顺手测「能不能看图」：发一张蓝色小图让它说颜色。
     * 只发文字测不出这件事——有些模型收图会静默忽略，文字照样回「可以」。
     */
    suspend fun testConnection(config: AiConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base64 = Base64.encodeToString(swatchJpeg(), Base64.NO_WRAP)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", TEST_PROMPT))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64")),
                )
            val body = JSONObject()
                .put("model", config.model)
                .put("max_tokens", 400)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ))
            applyThinking(body, config)
            val answer = post(config, body.toString()).trim()
            if (answer.isBlank()) {
                throw IllegalStateException("模型没有返回内容（可能没看完图就把话用完了）")
            }
            if (!TEST_ANSWER.containsMatchIn(answer.lowercase())) {
                throw IllegalStateException("连通了，但它没认出测试图里的颜色（回了：${answer.take(40)}），这个模型可能不收图")
            }
            "连接成功 · 能看图"
        }
    }

    suspend fun describePhoto(config: AiConfig, jpeg: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", PROMPT))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64")),
                )
            val body = JSONObject()
                .put("model", config.model)
                .put("max_tokens", 220)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ))
            applyThinking(body, config)
            post(config, body.toString())
        }
    }

    /**
     * 让在线模型判断「这张照片算不算本次完成」。
     * 只有开启在线识别、且配置齐全时才会走这里；关闭时仍由本机模拟判定。
     */
    suspend fun judgePhoto(
        config: AiConfig,
        jpeg: ByteArray,
        itemName: String,
        countPerDose: Int,
        coverJpeg: ByteArray? = null,
    ): Result<AiVerdict> = withContext(Dispatchers.IO) {
        runCatching {
            val prompt = JUDGE_PROMPT
                .replace("{item}", itemName)
                .replace("{count}", countPerDose.toString())
                .replace(
                    "{hasCover}",
                    if (coverJpeg == null) {
                        "这次只有一张现场照片，没有登记照，只能按画面内容和数量判断。"
                    } else {
                        "第 1 张是刚拍的现场照，第 2 张是这件东西登记时的照片。"
                    },
                )
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(imagePart(jpeg))
            if (coverJpeg != null) {
                content
                    .put(JSONObject().put("type", "text").put("text", "第 2 张：登记时的照片"))
                    .put(imagePart(coverJpeg))
            }
            val body = JSONObject()
                .put("model", config.model)
                // 推理型模型会先花 token 思考，预算太小会把回答挤没（只剩 reasoning），所以给足。
                .put("max_tokens", 1200)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ))
            applyThinking(body, config)
            val raw = post(config, body.toString())
            AiVerdictParser.parse(raw)
                ?: throw IllegalStateException("模型没有按格式回答：${raw.take(120)}")
        }
    }

    /**
     * 一次拍好几件：现场一张 + 每件的登记照，让模型逐件判断有没有出现。
     *
     * 只在在线模式、且用户勾了 ≥2 件时走这里；单件仍走 [judgePhoto]，
     * 那条提示词（主体要对得上登记照主体）是单独调过的，别合并。
     */
    suspend fun judgeMany(
        config: AiConfig,
        sceneJpeg: ByteArray,
        targets: List<JudgeTarget>,
    ): Result<List<TargetVerdict>> = withContext(Dispatchers.IO) {
        runCatching {
            require(targets.isNotEmpty()) { "没有要判定的东西" }
            val list = targets.withIndex().joinToString("；") { (i, t) ->
                "第 ${i + 1} 件：名字备注「${t.name}」，要看到 ${t.count} 件"
            }
            val prompt = MULTI_JUDGE_PROMPT
                .replace("{n}", targets.size.toString())
                .replace("{list}", list)
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "text").put("text", "第 1 张：刚拍的现场照"))
                .put(imagePart(sceneJpeg))
            targets.forEachIndexed { i, target ->
                content.put(
                    JSONObject().put("type", "text").put(
                        "text",
                        if (target.coverJpeg == null) {
                            "第 ${i + 1} 件「${target.name}」：没有登记照，只能按名字和数量看"
                        } else {
                            "第 ${i + 1} 件「${target.name}」登记时的样子"
                        },
                    ),
                )
                target.coverJpeg?.let { content.put(imagePart(it)) }
            }
            val body = JSONObject()
                .put("model", config.model)
                // 一次判好几件，输出比单件长，预算给足。
                .put("max_tokens", 1600)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", content),
                ))
            applyThinking(body, config)
            val raw = post(config, body.toString())
            MultiVerdictParser.parse(raw, targets.size)
                ?: throw IllegalStateException("模型没有按格式回答：${raw.take(120)}")
        }
    }

    private fun imagePart(jpeg: ByteArray): JSONObject {
        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))
    }

    /**
     * 识物不需要思考链：默认带 `thinking: {"type":"disabled"}`。
     * 实测 DeepSeek：deepseek-flash 默认会思考（completion 288 / reasoning 246 token、2.3s），
     * 关掉后 completion 44、0.9s；deepseek-chat 本来就不思考。
     */
    private fun applyThinking(body: JSONObject, config: AiConfig) {
        if (config.disableThinking) {
            body.put("thinking", JSONObject().put("type", "disabled"))
        }
    }

    private fun post(config: AiConfig, body: String): String {
        if (!config.disableThinking) return postOnce(config, body)
        return try {
            postOnce(config, body)
        } catch (e: IllegalStateException) {
            val msg = e.message.orEmpty()
            val rejected = msg.startsWith("HTTP 4") &&
                (msg.contains("thinking", ignoreCase = true) ||
                    msg.contains("unknown", ignoreCase = true) ||
                    msg.contains("unrecognized", ignoreCase = true) ||
                    msg.contains("invalid", ignoreCase = true))
            if (!rejected) throw e
            // 有的 OpenAI 兼容服务不认 thinking 字段：去掉再试一次，别让整条判定挂掉。
            Log.w(TAG, "服务商不认 thinking 参数，去掉后重试：${msg.take(120)}")
            val stripped = JSONObject(body).apply { remove("thinking") }.toString()
            postOnce(config, stripped)
        }
    }

    private fun postOnce(config: AiConfig, body: String): String {
        val endpoint = endpointOf(config.baseUrl)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.let { s ->
                BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { it.readText() }
            } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code：${raw.take(240)}")
            }
            return parseContent(raw)
        } finally {
            conn.disconnect()
        }
    }

    private fun endpointOf(baseUrl: String): String {
        val url = baseUrl.trim().trimEnd('/')
        return if (url.endsWith("/chat/completions")) url else "$url/chat/completions"
    }

    private fun parseContent(raw: String): String {
        val json = JSONObject(raw)
        val choices = json.optJSONArray("choices") ?: throw IllegalStateException("返回里没有 choices")
        if (choices.length() == 0) throw IllegalStateException("返回的 choices 为空")
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw IllegalStateException("返回里没有 message")
        val content = when (val c = message.opt("content")) {
            is String -> c
            is JSONArray -> buildString {
                for (i in 0 until c.length()) {
                    val part = c.optJSONObject(i) ?: continue
                    append(part.optString("text", ""))
                }
            }
            else -> ""
        }
        // 推理型模型（deepseek-flash 等）可能把话全用在自己思考里，正式回答是空的；
        // 这时把 reasoning 拿出来，至少能让上层解析出 JSON 或给出可读的错因。
        return content.trim().ifBlank { message.optString("reasoning_content", "").trim() }
    }

    /**
     * 造一张 96×96 的测试图：蓝底 + 白圆点。
     * 用纯色块有些模型会当成无效图，加个形状稳妥些。
     */
    private fun swatchJpeg(): ByteArray {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(38, 96, 214))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.27f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val TAG = "PwAi"

        const val PROMPT =
            "这是用户按提醒拍摄的一张物品照片。请用两三句中文回答：" +
                "1) 画面里主要是什么物品、大概几个、什么颜色；" +
                "2) 是否像登记的这件物品（只描述外观，不给使用建议）；" +
                "3) 照片是否足够清楚。不要输出任何医疗建议。"

        const val TEST_PROMPT = "这张小图是什么颜色？只回复颜色，一个词。"

        val TEST_ANSWER = Regex("蓝|blue")

        /**
         * 判定 prompt。要点（实测过，别随手改回「按物品名判断」）：
         * - 用户起的名字常常不准（有人把「相机+小盒」叫成「香水」），所以名字只当备注；
         * - 唯一依据是登记照：取其中「最中央、最大、最清楚」的那件当登记对象；
         * - 现场照要对得上「主体」，角落里出现登记照里的背景物不算；
         * - 登记照本身最好只拍一件——登记时点选裁图就是为了保证这一点。
         */
        const val JUDGE_PROMPT =
            "你在帮一个人核对一张照片能不能算作「凭物」。\n" +
                "背景：用户为某件事登记了一件东西，到点时拍一张照片作为凭证。{hasCover}\n" +
                "用户给这件东西起的名字是「{item}」，要拍 {count} 件。这个名字只是随手写的，可能不准确、甚至完全说错，不要把它当作判断依据。\n\n" +
                "请以第 2 张登记照为唯一依据，完成三件事：\n" +
                "1. 先看第 2 张：用户登记时想拍的是哪一件东西？如果画面里有好几样东西，取「最靠画面中央、最大、最清楚」的那一件作为登记对象；其他东西（背景里的手机、相机、桌面等）一律当背景忽略。\n" +
                "2. 再看第 1 张：画面里最主要的东西是什么？有没有出现和第 2 张登记对象是同一件、或同一类的东西（换角度、换光线、远近不同、略有差异但明显是同一种东西，都算）。\n" +
                "3. 关键：必须是「现场照的主体」对得上「登记照的主体」。如果现场照的主体是别的东西（比如一把勺子、一只杯子、空桌面），哪怕画面角落里碰巧有登记照里出现过的背景物，也算没对上。\n\n" +
                "只输出一个 JSON 对象，不要解释、不要 Markdown：\n" +
                "{\"registered\":\"第2张里的登记对象，10字以内\",\"seen\":\"第1张里的主要内容，10字以内\",\"pass\":true 或 false,\"count\":整数,\"confidence\":\"high|medium|low\",\"reason\":\"不超过30字的中文原因\"}\n\n" +
                "判 pass=false：现场照主体对不上登记对象；画面里只有登记照出现过的背景物；照片糊到无法辨认；数量差距明显（差两件以上）；这一张只有一张图且看不出是什么。\n" +
                "判 pass=true：现场照主体就是（或明显是同类）登记对象，数量大致对得上。\n" +
                "count 只数第 1 张里登记对象的数量。reason 直接说两只照片里各看到了什么，不要替用户圆场。不要给建议。"

        /**
         * 多件一起拍的判定 prompt。
         *
         * 和单件那条的区别：这里没有「唯一主体」可用——画面里本来就该有几件东西，
         * 所以改成「逐件问有没有出现」，并且明确说「别的登记物出现在背景里不算」，
         * 防止像单件时代那样「画面里有相机就算过」。
         */
        const val MULTI_JUDGE_PROMPT =
            "你在帮一个人核对一张照片能不能一次算作 {n} 件东西的凭物。\n" +
                "第 1 张是刚拍的现场照；后面依次是每一件东西登记时的照片。\n" +
                "要核对的有：{list}\n" +
                "这些名字都是用户随手写的备注，可能不准确、甚至完全说错，不要拿名字当判断依据，以登记照为准。\n\n" +
                "请一件一件判断：现场照里有没有出现这一件东西（换角度、换光线、远近不同、明显是同一种东西，都算）。\n" +
                "注意：\n" +
                "- 现场照里本来就会同时有好几件登记物，它们互相出现在画面里是正常的；但某一件自己没出现，就不能算过。\n" +
                "- 如果某件东西在登记照里是主体、现场照里却只剩一个角落里模糊的影子或完全看不到，算没出现。\n" +
                "- 没有登记照的那件，只能按现场画面和名字大致判断，看不清就判 false，并在 reason 里说明。\n" +
                "- 「X 第 2 件」这种是同一个物品登记的另一块样子：它和前一块各自独立判断，都要各自真的出现在画面里才算过。\n" +
                "- 照片糊到无法辨认时，全部判 false。\n\n" +
                "只输出一个 JSON 对象，不要解释、不要 Markdown：\n" +
                "{\"results\":[{\"index\":1,\"pass\":true,\"count\":1,\"reason\":\"不超过20字\"},{\"index\":2,\"pass\":false,\"count\":0,\"reason\":\"没看到它\"}]}\n" +
                "results 里每件都要有一条，index 从 1 开始，顺序和上面「要核对的有」一致。reason 直接说看到了什么或没看到什么。不要给建议。"
    }
}
