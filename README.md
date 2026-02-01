# xg-glass-sample

This directory contains a set of **sample apps built with the xg.glass SDK**, to help developers quickly understand:

- How to use the unified APIs across different smart glasses
- How to build, install, and run a working glasses app from a **single Kotlin entry file**

If you're new to the SDK, start with the main documentation (see [**developer guide**](https://xg.glass/developer-guide/)).

---

## photo_translator (Photo Translator)

Location: `xg-glass-sample/photo_translator`

This sample demonstrates a minimal end-to-end flow: **capture photo → LLM translate → display on glasses**.

- Capture a photo from the glasses camera
- Encode the image as base64 and call OpenAI **Chat Completions** for image-text translation
- Display the translated result on the glasses

### Quick run (recommended)

Run the single-file entry directly from this directory:

```bash
cd xg-glass-sample/photo_translator
xg-glass run TranslationEntry.kt
```

Notes:

- `xg-glass` is our CLI. Make sure it’s in your `PATH`. If you're running from the repo root, you can also use `./xg-glass ...`.
- Before running, replace `YOUR_OPENAI_API_KEY_HERE` in `TranslationEntry.kt` with your own key (this is a placeholder; for real apps, inject secrets securely).

### Core logic (you can build this app in ~10 lines)

In `TranslationEntry.kt`, the core logic that implements **capture → translate → display** is essentially just the snippet below (you only need ~10 lines like this to build the full app):

```kotlin
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
```
