package com.example.phototranslator.logic

import com.universalglasses.appcontract.AIApiSettings
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.core.DisplayOptions
import com.aallam.openai.api.chat.*
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIHost
import java.util.Base64

class PhotoTranslatorEntry : UniversalAppEntrySimple {
    override val id: String = "photo_translator_demo"
    override val displayName: String = "Photo Translator Demo"

    override fun userSettings(): List<UserSettingField> = AIApiSettings.fields(
        defaultBaseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o-mini",
    )

    override fun commands(): List<UniversalCommand> {
        return listOf(PhotoTranslateCommand())
    }
}

private class PhotoTranslateCommand : UniversalCommand {
    override val id: String = "photo_translator"
    override val title: String = "Photo Translator"

    private var openAI: OpenAI? = null
    private var apiModel: String = ""

    private fun ensureClient(ctx: UniversalAppContext) {
        val baseUrl = AIApiSettings.baseUrl(ctx.settings)
        val apiKey = AIApiSettings.apiKey(ctx.settings)
        apiModel = AIApiSettings.model(ctx.settings)

        require(baseUrl.isNotBlank()) { "API Base URL is not configured. Please fill in Settings and Apply." }
        require(apiKey.isNotBlank()) { "API Key is not configured. Please fill in Settings and Apply." }
        require(apiModel.isNotBlank()) { "Model is not configured. Please fill in Settings and Apply." }

        openAI = OpenAI(
            token = apiKey,
            host = OpenAIHost(baseUrl = baseUrl),
        )
    }

    override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
        try {
            ensureClient(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.log("ERROR: ${e.message}")
            return Result.failure(e)
        }

        val img = ctx.client.capturePhoto().getOrThrow()
        val b64 = Base64.getEncoder().encodeToString(img.jpegBytes)
        val req = chatCompletionRequest {
            model = ModelId(apiModel)
            messages {
                user {
                    content {
                        text("Translate the text in this image to Chinese. Output only the result.")
                        image("data:image/jpeg;base64,$b64")
                    }
                }
            }
        }
        val text = openAI!!.chatCompletion(req)
            .choices.firstOrNull()?.message?.content.orEmpty()
            .ifBlank { "No text" }
        return ctx.client.display(text, DisplayOptions())
    }
}
