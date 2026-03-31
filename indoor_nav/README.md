# Indoor Wayfinding

This sample demonstrates an **AR glasses indoor navigation assistant** for large venues (hospitals, shopping centers) using the xg.glass SDK:

**Capture visual cues → AI vision analysis → step-by-step navigation on glasses**

---

### What it does

1. **Where Am I** — Capture a photo of your surroundings, AI identifies your current location from visible signs and landmarks.
2. **Navigate To** — Say your destination via voice, capture a photo, AI gives step-by-step walking directions (max 4 steps).
3. **Find Nearest** — Say what you need (e.g. "restroom", "elevator"), capture a photo, AI locates the nearest facility.
4. **Read Signs** — Capture a photo of signs/directories, AI reads, translates, and interprets all visible signage.

### Key features

- **Session memory**: The app remembers previously visited locations and observed context across commands, improving accuracy over time.
- **Voice input**: Destination and search queries are captured via glasses microphone + Whisper transcription.
- **TTS output**: Navigation instructions are spoken aloud on supported devices.
- **Multi-language**: Responds in the same language as the venue signs (defaults to Chinese for venues in China).

### What you need

- An Android phone with smart glasses connected
- AI API credentials (OpenAI-compatible endpoint with vision model support, e.g. `gpt-4o`)
- Whisper support for voice commands (Navigate To, Find Nearest)

---

### Quick run

```bash
cd xg-glass-sample/indoor_nav
xg-glass run IndoorNavEntry.kt
```

Then configure the following in **Settings**:

| Setting | Description |
|---------|-------------|
| API Base URL | OpenAI-compatible endpoint (default: `https://api.openai.com/v1/`) |
| API Key | Your API key |
| Model | Vision-capable model (default: `gpt-4o`) |
| Venue Name / Description | e.g. "Beijing Chaoyang Hospital Building A" |
| Current Floor | e.g. "3F", "B1" |

---

### How it works

```
Glasses Camera ──► Photo capture ──► Base64 encoding
                                          │
Voice Input ──► Whisper transcription ────┤
                                          ▼
                                    AI Vision Chat
                                   (GPT-4o with photo)
                                          │
                                          ▼
                              Glasses Display + TTS
                           (step-by-step directions)
```

### Navigation state

The app maintains a breadcrumb trail of visited locations and accumulated venue context across commands within a session. This helps the AI provide more accurate directions as you explore the venue.
