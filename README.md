# xg-glass-sample

This directory contains a set of **sample apps built with the xg.glass SDK**, to help developers quickly understand:

- How to use the unified APIs across different smart glasses
- How to build, install, and run a working glasses app from a **single Kotlin entry file**

These samples target xg.glass SDK `0.2.0`.

If you're new to the SDK, start with the main documentation (see [**developer guide**](https://xg.glass/developer-guide/)).

## Examples

| Example | What it demonstrates | Run command |
| --- | --- | --- |
| `photo_translator` | Capture a photo, call an OpenAI-compatible vision model, and display the translation. | `xg-glass run photo_translator/PhotoTranslatorEntry.kt --sdk /path/to/xg-glass-sdk` |
| `exam_solver` | Auto-capture loop with streaming AI answers and conversation memory. | `xg-glass run exam_solver/ExamSolverEntry.kt --sdk /path/to/xg-glass-sdk` |
| `teleprompter` | Simulator-runnable display teleprompter with streaming/paged display degradation and tap/long-press controls. | `xg-glass run teleprompter/TeleprompterEntry.kt --sim --sdk /path/to/xg-glass-sdk` |
| `voice_notes` | Simulator-runnable microphone capture with transcription when AI settings are configured, otherwise an honest audio summary. | `xg-glass run voice_notes/VoiceNotesEntry.kt --sim --sdk /path/to/xg-glass-sdk` |

## Prerequisites

Install the CLI and clone the SDK checkout once:

```bash
pip install xg-glass
git clone https://github.com/hkust-spark/xg-glass-sdk
```

The SDK checkout is needed when the PyPI-installed CLI runs a single Kotlin entry file. In the examples below, replace `/path/to/xg-glass-sdk` with your checkout path. If you are running from inside an SDK checkout, the same commands still work without `--sdk`.

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
xg-glass run PhotoTranslatorEntry.kt --sdk /path/to/xg-glass-sdk
```

Notes:

- `xg-glass` is installed with `pip install xg-glass`; for PyPI installs, pass `--sdk /path/to/xg-glass-sdk` when running a single `.kt` entry.
- Before running, replace `YOUR_OPENAI_API_KEY_HERE` in `PhotoTranslatorEntry.kt` with your own key (this is a placeholder; for real apps, inject secrets securely).

### Core logic (you can build this app in ~10 lines)

In `PhotoTranslatorEntry.kt`, the core logic that implements **capture → translate → display** is essentially just the snippet below (you only need ~10 lines like this to build the full app):

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
