package it.noxia.leicaai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class EditResult(
    val jpegBytes: ByteArray,
    val actualCostUsd: Double?,
    val outputSize: String,
    val quality: PhotoQuality,
    val model: String,
    val imageInputTokens: Long?,
    val textInputTokens: Long?,
    val imageOutputTokens: Long?
)

class ImageEditException(
    message: String,
    val retryable: Boolean
) : Exception(message)

enum class PhotoLook(val shortCode: String) {
    COLOR("CL"),
    BLACK_AND_WHITE("BW"),
    PORTRAIT("PT")
}

enum class PhotoQuality(val apiValue: String, val label: String) {
    HIGH("high", "HIGH"),
    MAX("max", "MAX")
}

class OpenAiImageEditor(private val context: Context) {
    private data class CostDetails(
        val actualCostUsd: Double?,
        val imageInputTokens: Long?,
        val textInputTokens: Long?,
        val imageOutputTokens: Long?
    )

    private data class ModelAttempt(
        val model: String,
        val supportsMaxQuality: Boolean
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .build()

    fun enhance(
        source: File,
        apiKey: String,
        look: PhotoLook,
        quality: PhotoQuality,
        capturedAtMillis: Long,
        captureZoneId: String,
        useCaptureTimeContext: Boolean = true,
        onSuccess: (EditResult) -> Unit,
        onError: (String) -> Unit
    ) {
        worker.execute {
            try {
                val result = enhanceBlocking(
                    source,
                    apiKey,
                    look,
                    quality,
                    capturedAtMillis,
                    captureZoneId,
                    useCaptureTimeContext
                )
                mainHandler.post { onSuccess(result) }
            } catch (error: ImageEditException) {
                mainHandler.post { onError(error.message ?: "NXT development failed") }
            }
        }
    }

    fun enhanceBlocking(
        source: File,
        apiKey: String,
        look: PhotoLook,
        quality: PhotoQuality,
        capturedAtMillis: Long,
        captureZoneId: String,
        useCaptureTimeContext: Boolean = true
    ): EditResult {
        var upload: File? = null
        try {
            val prepared = ImageUtils.prepareForUpload(context, source, quality)
            upload = prepared.file
            return executeWithCompatibilityFallback(
                upload = upload,
                apiKey = apiKey,
                prompt = promptFor(
                    look,
                    capturedAtMillis,
                    captureZoneId,
                    useCaptureTimeContext
                ),
                outputSize = prepared.outputSize,
                quality = quality
            )
        } catch (error: Exception) {
            val retryable = when (error) {
                is IOException -> true
                is ApiException -> error.isRetryable()
                else -> false
            }
            val message = when (error) {
                is ApiException -> error.message ?: "NXT service error"
                else -> "NXT development failed: ${error.message ?: "unknown error"}"
            }
            throw ImageEditException(message, retryable)
        } finally {
            upload?.delete()
        }
    }

    fun close() {
        worker.shutdownNow()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        cleanupExecutor.execute {
            try {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            } finally {
                cleanupExecutor.shutdown()
            }
        }
    }

    private fun executeWithCompatibilityFallback(
        upload: File,
        apiKey: String,
        prompt: String,
        outputSize: String,
        quality: PhotoQuality
    ): EditResult {
        val attempts = ArrayDeque<ModelAttempt>().apply { add(PRIMARY_ATTEMPT) }
        val attempted = mutableSetOf<ModelAttempt>()
        var lastError: ApiException? = null

        while (attempts.isNotEmpty()) {
            val attempt = attempts.removeFirst()
            if (!attempted.add(attempt)) continue
            try {
                return executeEdit(upload, apiKey, prompt, outputSize, quality, attempt)
            } catch (error: ApiException) {
                lastError = error
                when {
                    attempt.model == PRIMARY_MODEL && error.allowsModelFallback() -> {
                        Log.w(LOG_TAG, "Primary image model unavailable; using $FALLBACK_MODEL")
                        attempts.addFirst(FALLBACK_ATTEMPT)
                    }

                    else -> throw error
                }
            }
        }
        throw lastError ?: ApiException(0, null, "No image model available")
    }

    private fun executeEdit(
        upload: File,
        apiKey: String,
        prompt: String,
        outputSize: String,
        quality: PhotoQuality,
        attempt: ModelAttempt
    ): EditResult {
        val requestQuality =
            if (quality == PhotoQuality.MAX && !attempt.supportsMaxQuality) "high"
            else quality.apiValue
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", attempt.model)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("quality", requestQuality)
            .addFormDataPart("size", outputSize)
            .addFormDataPart("output_format", "jpeg")
            .addFormDataPart("output_compression", "96")
        bodyBuilder.addFormDataPart(
            "image[]",
            upload.name,
            upload.asRequestBody("image/jpeg".toMediaType())
        )

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(bodyBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (!response.isSuccessful) throw parseApiException(raw, response.code)

            val json = JSONObject(raw)
            val encoded = json.getJSONArray("data")
                .getJSONObject(0)
                .getString("b64_json")
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.isNotEmpty()) { "OpenAI returned an empty image" }
            val cost = calculateCostDetails(json, attempt.model)
            return EditResult(
                jpegBytes = bytes,
                actualCostUsd = cost.actualCostUsd,
                outputSize = outputSize,
                quality = quality,
                model = attempt.model,
                imageInputTokens = cost.imageInputTokens,
                textInputTokens = cost.textInputTokens,
                imageOutputTokens = cost.imageOutputTokens
            )
        }
    }

    private fun parseApiException(raw: String, statusCode: Int): ApiException = try {
        val error = JSONObject(raw).getJSONObject("error")
        ApiException(
            statusCode = statusCode,
            errorCode = error.optString("code").takeIf { it.isNotBlank() },
            message = error.optString("message").takeIf { it.isNotBlank() }
                ?: "OpenAI returned error $statusCode"
        )
    } catch (_: Exception) {
        ApiException(statusCode, null, "OpenAI returned error $statusCode")
    }

    private fun calculateCostDetails(response: JSONObject, model: String): CostDetails {
        val explicitCost = listOf("cost_usd", "total_cost", "cost")
            .firstNotNullOfOrNull { key ->
                response.optDouble(key, Double.NaN)
                    .takeIf { it.isFinite() && it >= 0.0 }
            }
        val usage = response.optJSONObject("usage")
        val inputDetails = usage?.optJSONObject("input_tokens_details")
        val outputDetails = usage?.optJSONObject("output_tokens_details")
        val imageInput = inputDetails?.optLong("image_tokens", -1)
            ?.takeIf { it >= 0 }
        val textInput = inputDetails?.optLong("text_tokens", -1)
            ?.takeIf { it >= 0 }
        val imageOutput = (
            outputDetails?.optLong("image_tokens", -1)
                ?: usage?.optLong("output_tokens", -1)
            )?.takeIf { it >= 0 }

        val imageInputRate = if (model == FALLBACK_MODEL) 4.0 else 8.0
        val textInputRate = if (model == FALLBACK_MODEL) 2.5 else 5.0
        val imageOutputRate = if (model == FALLBACK_MODEL) 15.0 else 30.0
        val calculatedCost = if (
            imageInput != null || textInput != null || imageOutput != null
        ) {
            (imageInput ?: 0L) * imageInputRate / 1_000_000.0 +
                (textInput ?: 0L) * textInputRate / 1_000_000.0 +
                (imageOutput ?: 0L) * imageOutputRate / 1_000_000.0
        } else {
            null
        }
        return CostDetails(
            actualCostUsd = explicitCost ?: calculatedCost,
            imageInputTokens = imageInput,
            textInputTokens = textInput,
            imageOutputTokens = imageOutput
        )
    }

    private fun promptFor(
        look: PhotoLook,
        capturedAtMillis: Long,
        captureZoneId: String,
        useCaptureTimeContext: Boolean
    ): String {
        val selectedLook = when (look) {
            PhotoLook.COLOR -> COLOR_LOOK
            PhotoLook.BLACK_AND_WHITE -> BLACK_AND_WHITE_LOOK
            PhotoLook.PORTRAIT -> PORTRAIT_LOOK
        }
        val timePrompt = if (useCaptureTimeContext) {
            captureTimePrompt(capturedAtMillis, captureZoneId)
        } else {
            IMPORTED_SOURCE_TIME_PROMPT
        }
        return "$SOURCE_LOCKED_PROMPT\n\n$SUBJECT_POLISH_PROMPT\n\n$selectedLook\n\n" +
            "$NIGHT_SCENE_PROMPT\n\n$SCENE_LIGHTING_PROMPT\n\n$timePrompt"
    }

    private fun captureTimePrompt(capturedAtMillis: Long, captureZoneId: String): String {
        val zone = runCatching { ZoneId.of(captureZoneId) }.getOrDefault(ZoneId.systemDefault())
        val capturedAt = Instant.ofEpochMilli(capturedAtMillis).atZone(zone)
        val period = when (capturedAt.hour) {
            in 5..10 -> "MORNING"
            in 11..16 -> "DAYTIME"
            in 17..21 -> "EVENING"
            else -> "NIGHT HOURS"
        }
        val formatted = capturedAt.format(
            DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy, HH:mm", Locale.US)
        )
        val daylightConstraint = when (period) {
            "MORNING" -> "It is morning: do not depict or suggest sunset, evening golden hour or red low-sun light."
            "DAYTIME" -> "It is daytime: do not depict or suggest sunrise, sunset or golden hour."
            else -> "The capture time does not authorize inventing sunrise, sunset or golden hour; use them only when unmistakably present in the source."
        }
        return """
            VERIFIED CAPTURE METADATA — $period.
            The photograph was captured on $formatted, time zone ${zone.id}.
            $daylightConstraint
            Capture time is temporal metadata, not an exposure instruction. Do not use it to
            increase exposure, ambient brightness, fill light or the quantity of light, and do
            not simulate light entering through windows or openings. Keep a genuinely dim
            interior dark and atmospheric, preserving the original relationship between
            highlights, midtones and shadows. Do not turn it into a bright interior merely
            because it was captured in the morning or during daytime.

            Use capture time only to prevent a false temporal reinterpretation. It must not
            neutralize warm lamps or other artificial sources that are genuinely visible. A
            real warm source must remain a local accent, without creating a global amber cast
            or changing the perceived time of day. Existing or physically justified light
            beams may be emphasized, but never by increasing overall ambient illumination or
            globally opening the shadows.
        """.trimIndent()
    }

    private class ApiException(
        val statusCode: Int,
        val errorCode: String?,
        message: String
    ) : Exception(message) {
        fun allowsModelFallback(): Boolean {
            val value = message.orEmpty().lowercase(Locale.US)
            return statusCode == 404 ||
                errorCode == "model_not_found" ||
                value.contains("does not exist") ||
                value.contains("do not have access") ||
                value.contains("not have access") ||
                (value.contains("model") && value.contains("does not support"))
        }

        fun isRetryable(): Boolean = statusCode == 408 ||
            statusCode == 409 ||
            statusCode == 425 ||
            statusCode == 429 ||
            statusCode in 500..599
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/images/edits"
        const val LOG_TAG = "NxtCamAi"
        const val PRIMARY_MODEL = "gpt-image-2.5-sunburst"
        const val FALLBACK_MODEL = "gpt-image-2"
        // Sunburst already prioritizes edit fidelity. Do not add input_fidelity: the live
        // Images API rejects that optional field for this model on the user's API project.
        val PRIMARY_ATTEMPT = ModelAttempt(PRIMARY_MODEL, true)
        val FALLBACK_ATTEMPT = ModelAttempt(FALLBACK_MODEL, false)

        val SOURCE_LOCKED_PROMPT = """
            GOAL — NXT-CAM SIGNATURE, STRONG IMPACT.
            Transform the source into a premium, authored and immediately recognizable editorial
            photograph. The before/after difference must be evident even at thumbnail size: do
            not return a basic automatic correction, do not be timid and do not leave the image
            almost unchanged. Build a strong visual hierarchy, a clear hero subject, sculpted
            light, optical depth, authoritative color, visible photographic grain and a
            professional print finish. It must remain a real photograph, never an illustration,
            synthetic HDR rendering or invented scene.

            REQUIRED ANALYSIS AND TREATMENT.
            Identify the true main subject, depth planes, distance, lighting and scene category.
            Apply every compatible intervention decisively: a refined S-shaped tonal curve, deep
            but structured blacks, smooth highlight roll-off, local dodge and burn, selective
            microcontrast, color separation, precise focal-plane sharpness, organic grain and a
            soft optical vignette. The vignette must be clearly perceptible yet highly feathered
            and centered on the real subject; it must never resemble a uniform black border.

            DEPTH OF FIELD — STRONG, VISIBLE EFFECT.
            • Close portrait, headshot or half body: keep face, eyes, hair and clothing faithful
              and very sharp. Give the background STRONG, creamy, progressive bokeh with the
              visual character of full frame and a fast lens close to wide open. If foreground
              elements exist in front of the subject, blur them with equal conviction. Separation
              must be obvious at a glance.
            • Environmental or full-body portrait: use MEDIUM-STRONG bokeh, retaining enough of
              the location to preserve its character while making the person stand out clearly.
            • Food, product, macro or close object: treat it as a HERO SUBJECT. Keep its shape,
              edges, labels, text, logo and details entirely sharp. Blur the supporting surface
              decisively and progressively outside the narrow contact plane toward both the
              extreme foreground and background. Aim for a wide-aperture advertising photograph.
            • Group, landscape, architecture or wide scene without a separable close subject: do
              not create arbitrary bokeh. Create equal WOW impact through tonal layering, local
              contrast, decisive color, sculpted light and a subtle vignette while keeping all
              important planes legible.

            BOKEH AND EDGE QUALITY.
            Blur must follow real distance from the focal plane, not merely the subject outline.
            Protect hair, eyelashes, glasses, ears, fingers, cutlery, glass, steam and complex
            edges: no halos, double edges, deformation or sticker effect. Preserve contact
            shadows and a thin band of texture around support points. Turn only light sources
            that actually exist into bokeh discs.

            GRAIN AND FINISH.
            Apply genuinely irregular photographic grain that is fine yet CLEARLY VISIBLE,
            especially in midtones and shadows. It should remain perceptible when viewing the
            whole image without obscuring skin, eyes, text or materials. Avoid digital noise,
            repeated patterns, waxy skin, oversharpening and synthetic texture.

            LOCKED CONTENT — HIGH-FIDELITY PRESERVATION.
            Preserve the exact aspect ratio, crop, field of view, perspective, geometry, size
            and position of every element. Preserve each person's identity, face, features,
            expression, anatomy, pose, hair, clothing and complexion. Preserve every object's
            shape, material, branding, labels and text. Do not add, remove, replace, move or
            reconstruct people, animals, objects, furniture, plants, vehicles, windows,
            lettering, logos or details. Do not change weather, time of day or shadow direction.
            Do not invent lights, reflections, textures, backgrounds, haze or off-frame content.
            If a detail is unreadable, leave it unreadable. Change only development, light,
            color, depth of field, vignette, grain and photographic finish, and change those
            decisively.
        """.trimIndent()

        val SUBJECT_POLISH_PROMPT = """
            ADAPTIVE SUBJECT REFINEMENT — LIGHT SUBJECT POLISH.
            Identify the true category of the main subject and apply only the relevant branch
            below. This refinement must be precise and selective, with a perceived intensity of
            about 10–15%. Overall treatment, lighting and bokeh may be strong, but the subject
            itself must remain authentic and identical.

            • PEOPLE: very lightly even facial illumination with local dodge and burn. Reduce
              only oily reflections and unwanted color casts without removing the skin's natural
              luminosity. Enhance eyes, eyebrows, lips and hair with selective microcontrast.
              Keep pores, wrinkles, expression lines, freckles, moles, beard and real texture
              fully visible.
            • ANIMALS: moderately improve the definition of eyes, fur, feathers, muzzle and
              whiskers. Enhance existing color, light and microcontrast without changing breed,
              anatomy, expression, markings or the color of coat, plumage or eyes.
            • FOOD: make existing textures more legible and appetizing, deepen existing colors,
              enhance the natural volume and sheen of ingredients, and separate the dish from its
              context. Do not add ingredients, sauces, garnish, steam, droplets, condensation or
              decoration.
            • PRODUCTS AND OBJECTS: improve material legibility and enhance only reflections that
              already exist. Define metal, glass, fabric, wood and surfaces precisely. Preserve
              shape, geometry, edges, branding, labels, text, color and proportions perfectly.
            • GENERIC SUBJECTS: use local dodge and burn, microcontrast and selective sharpness to
              increase volume and dimensionality without changing shape or content.

            ABSOLUTE SUBJECT-POLISH RESTRICTIONS.
            No smoothed, waxy, plastic or poreless skin. Do not change face, features, body,
            weight, age, expression, complexion or identity. Do not whiten eyes or teeth
            artificially and do not add makeup. Do not alter fur, plumage, animal anatomy,
            shapes, ingredients, materials, labels or writing. Do not invent texture, detail,
            reflection or any part that is unreadable in the source. Enhance only visual
            information that genuinely exists in the photograph.
        """.trimIndent()

        val COLOR_LOOK = """
            SELECTED LOOK: COLOR.
            Apply a STRONG, coherent and recognizable contemporary editorial color signature:
            editorial contrast, authoritative blacks, creamy highlights, rich microcontrast and
            clear color separation without an HDR effect. Color grading must be clearly visible,
            not a neutral correction.

            Preserve the true nature and temperature of all existing colors while making them
            deeper without introducing a cast: full, slightly crimson reds only where red already
            exists; dense blues and cyans; restrained, slightly desaturated greens; natural,
            faithful skin tones. Neutral light must remain neutral. Enhance amber and warmth only
            in areas already unmistakably lit by a warm source in the original. Do not
            automatically create warm/cool separation and do not warm highlights merely to add
            atmosphere. Add subtle reddish halation only at the edges of genuinely intense
            existing sources. Avoid red, orange or yellow casts, teal-orange LUTs, global
            saturation, clipping and an HDR look.

            The final image must have the visual presence of boldly developed editorial
            photography: deep color, volume, light and recognizable grain.
        """.trimIndent()

        val BLACK_AND_WHITE_LOOK = """
            SELECTED LOOK: BLACK AND WHITE.
            Create a bold, immediately recognizable analog black-and-white image: deep structured
            blacks, luminous whites without clipping, rich midtones, strong tonal separation and
            incisive subject microcontrast. No selective color. Use decisive but realistic dodge
            and burn to sculpt only the light that already exists.

            Apply clearly visible organic silver grain, more pronounced than in COLOR, and a soft
            fine-art print vignette. The result must have material presence, tension and
            photographic character without digital noise, posterization or artificial edges.
        """.trimIndent()

        val PORTRAIT_LOOK = """
            SELECTED LOOK: PORTRAIT — NXT-CAM PROFESSIONAL PORTRAIT.
            This profile is intended for human portraits. If no real person is present, do not
            invent people, faces or poses and do not turn the scene into a portrait. Restrict the
            treatment to natural color, refined light and photographic finish.

            STUDIO PRESENCE WITHOUT REBUILDING THE SCENE.
            When a person is present, create the visual presence of a professional studio
            portrait using only existing light, color, depth and detail. Shape facial light
            gently with local dodge and burn, recover eyes from shadow, control oily reflections
            and skin highlights, and enhance eyes, eyebrows, lips and hair with selective
            microcontrast. Preserve the original direction of light and shadows. Do not add
            softboxes, flash, rim light, catchlights, backdrops or any nonexistent source.

            VISIBLE BUT AUTHENTIC BEAUTY RETOUCH.
            Apply a light beauty retouch that is more perceptible than in the other profiles:
            moderately even skin color and luminosity, reducing only temporary minor blemishes,
            localized redness, dark circles and excessive shine. Keep pores, microtexture,
            wrinkles, freckles, moles, scars, beard and expression lines clearly visible. Never
            create smooth, waxy, blurred or plastic skin.

            IDENTITY LOCKED.
            Do not change features, proportions, face or body shape, weight, age, expression,
            pose, complexion, eye color, teeth, nose, lips, jaw, cheekbones or hair. Do not whiten
            eyes or teeth and do not add makeup, eyelashes, hairstyles or details. Every person
            must remain unmistakably the same individual.

            DEPTH AND FINISH.
            When depth separation allows it, apply strong, creamy, progressive background bokeh
            from the visual language of a fast professional lens, protecting hair, glasses, ears
            and complex edges. Do not replace or simplify the background. Use natural color,
            elegant contrast, soft highlights, a light vignette and very fine grain. The face
            must be the protagonist without looking artificial.
        """.trimIndent()

        val NIGHT_SCENE_PROMPT = """
            AUTOMATIC NIGHT CONDITION.
            Analyze the original light. Activate the NIGHT treatment below only when the
            photograph was taken at night, during blue hour, in a genuinely dim interior, or in a
            scene dominated by artificial sources. Ignore this section entirely when the scene is
            daytime, shaded, overcast, merely underexposed, or shows daylight through windows. A
            dark photograph is not automatically a night photograph.

            Build a STRONG cinematic atmosphere exclusively by developing sources and colors that
            already exist. Protect signs, headlights, streetlights, windows and bright points:
            recover color and structure in highlights and avoid white blobs or clipping. Apply
            visible but controlled optical bloom and halation only around genuinely bright
            existing sources, without creating new lights or lens flare.

            Reduce irregular chroma noise before adding photographic grain. Keep blacks very deep
            but not empty, retain readable shadow texture and create smooth transitions into the
            midtones. Use a more visible night vignette to focus attention. Do not simulate false
            daylight exposure and do not brighten the scene uniformly; preserve the authenticity
            of night.

            For COLOR, enhance only the existing contrast between warmer lamps, tungsten or
            sodium light and a cooler night environment or sky. Preserve the real colors of neon
            and signs while controlling saturation. Keep skin believable under mixed lighting
            and do not apply an artificial teal-orange LUT.

            For BLACK AND WHITE, create a monochrome night with authoritative structured blacks,
            luminous midtones, controlled light sources, selective microcontrast and fine silver
            grain that is slightly more present in the shadows.

            In night portraits, food and close subjects, bokeh may be EXTREMELY pronounced under
            the depth rules above. Bokeh discs and halation must derive only from light sources
            that actually exist in the photograph.

            Absolute night restrictions: do not add rain, wet asphalt, reflections, puddles,
            haze, fog, smoke, steam, stars, moon, neon, signs, illuminated windows, headlights,
            vehicles, people or light sources that are not already present. Do not alter the
            direction, shape or position of existing shadows. Do not change the time of day or
            reconstruct detail hidden in absolute black.
        """.trimIndent()

        val SCENE_LIGHTING_PROMPT = """
            FINAL PRIORITY CONSTRAINT — TIME OF DAY AND LIGHT COLOR.
            Preserve exactly the temporal condition visible in the source: morning, full
            daylight, overcast sky, shade, daytime interior, genuine sunrise or sunset, blue hour
            or night. Never turn a morning, daytime, neutral or interior scene into golden hour or
            sunset. This rule overrides every request for atmosphere, cinematic styling,
            sculpted light or visual impact.

            The sky is locked content. Preserve its hue, brightness, clouds, haze, gradient and
            any visible sun exactly as they appear in the original. Do not replace or dramatize
            the sky and do not add sun, horizon glow, golden clouds, or red, orange and pink areas
            that are not already clearly present. Light visible through doors and windows must
            preserve the same time of day and source white balance.

            PRESERVE CINEMATIC LIGHT.
            Light beams, light entering windows and openings, controlled bloom, chiaroscuro and
            atmospheric volume may be made more legible, deep and dramatic, but only by following
            sources, openings and light directions already present or physically evident. These
            effects must inherit the source's real temperature: clean morning light, neutral
            daylight, soft overcast light or the true color of artificial illumination. Never
            automatically color beams, walls, skin, floors or objects red, orange, amber or gold.
            Create drama through luminance, local contrast, depth, grain and microcontrast, never
            by simulating a sunset.

            In BLACK AND WHITE, beams, chiaroscuro and plane separation may be emphasized
            strongly, but preserve sun position, shadow direction and the scene's luminance
            relationships. Do not simulate low sunrise or sunset light. If color temperature is
            uncertain, always favor source fidelity and remove the warm cast without reducing
            tonal impact.

            NEUTRAL COLOR ANCHORS.
            Use genuine white, gray, black and metallic objects as references against global
            color casts. White must stay neutral white; gray must stay gray; aluminum, steel,
            glass and chrome must retain their material color. They may reflect a genuinely
            present warm lamp, but only locally and consistently with that source. Do not turn
            the entire material, background or both sides of the subject amber, orange or gold.

            For products and metallic objects, create volume with luminance gradients, neutral
            reflections, local contrast, microtexture, depth separation and bokeh. Do not add
            warm side lights, golden rim light or sunset illumination to make the image feel
            commercial. The photograph can be highly dramatic while remaining color-faithful
            and free from a global warm cast.
        """.trimIndent()

        val IMPORTED_SOURCE_TIME_PROMPT = """
            IMPORTED PHOTOGRAPH — CAPTURE TIME UNAVAILABLE.
            Do not use the current time, processing date or temporal assumptions to alter light,
            exposure or atmosphere. Infer temporal conditions only from unmistakable evidence
            already visible in the photograph. When uncertain, preserve the source temperature,
            brightness and perceived time exactly. Do not invent sunrise, sunset, golden hour,
            night or light entering through openings.
        """.trimIndent()
    }
}
