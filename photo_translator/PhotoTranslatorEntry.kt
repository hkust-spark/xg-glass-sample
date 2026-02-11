package com.example.phototranslator.logic

import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntrySimple
import com.universalglasses.appcontract.UniversalCommand
import com.universalglasses.core.DisplayOptions
import com.aallam.openai.api.chat.*
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import java.util.Base64

class PhotoTranslatorEntry : UniversalAppEntrySimple {
    override val id: String = "photo_translator_demo"
    override val displayName: String = "Photo Translator Demo"
    // Replace the placeholder below with your own OpenAI API key via a secure method
    private val openAI = OpenAI("YOUR_OPENAI_API_KEY_HERE")

    override fun commands(): List<UniversalCommand> {
        val translate = object : UniversalCommand {
            override val id: String = "photo_translator"
            override val title: String = "Photo Translator"
            override suspend fun run(ctx: UniversalAppContext): Result<Unit> {
                val img = ctx.client.capturePhoto().getOrThrow()
                val b64 = Base64.getEncoder().encodeToString(img.jpegBytes)
                val req = chatCompletionRequest {
                    model = ModelId("gpt-4o-mini")
                    messages { user { content { text("Translate the text in this image to Chinese. Output only the result."); image("data:image/jpeg;base64,$b64") } } }
                }
                val text = openAI.chatCompletion(req).choices.firstOrNull()?.message?.content.orEmpty().ifBlank { "No text" }
                return ctx.client.display(text, DisplayOptions())
            }
        }
        return listOf(translate)
    }
}


