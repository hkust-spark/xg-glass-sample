# Google Ecosystem Sync

This sample demonstrates how to build a **conversation-to-Google-services** pipeline on smart glasses using the xg.glass SDK:

**Record audio → Whisper transcription → LLM extraction → Sync to Google Drive / Calendar / Tasks**

---

### What it does

1. **Listen & Sync** — Record audio from the glasses microphone, transcribe via OpenAI Whisper, extract structured data (memories, tasks, calendar events) with an LLM, and sync everything to Google services.
2. **Sync Text** — Same pipeline but from manually pasted text (no microphone needed).
3. **View Sync Status** — Check the result of the last sync (items created, errors, etc.).

### What you need

- An Android phone with smart glasses connected
- AI API credentials (OpenAI-compatible endpoint with Whisper support)
- A **Google OAuth2 Access Token** with the following scopes:
  - `https://www.googleapis.com/auth/drive.file`
  - `https://www.googleapis.com/auth/calendar`
  - `https://www.googleapis.com/auth/tasks`

You can obtain a token via the [Google OAuth Playground](https://developers.google.com/oauthplayground/).

---

### Quick run

```bash
cd xg-glass-sample/google_sync
xg-glass run GoogleSyncEntry.kt
```

Then configure the following in **Settings**:

| Setting | Description |
|---------|-------------|
| API Base URL | OpenAI-compatible endpoint (default: `https://api.openai.com/v1/`) |
| API Key | Your API key |
| Model | LLM model for extraction (default: `gpt-4o-mini`) |
| Google OAuth2 Access Token | Bearer token with Drive, Calendar, Tasks scopes |
| Listen Duration | How long to record audio in seconds (default: 30) |

---

### How it works

```
Glasses Mic ──► PCM audio ──► WAV encoding ──► Whisper transcription
                                                        │
                                                        ▼
                                                 LLM extraction
                                                   (memories, tasks, events)
                                                        │
                                    ┌───────────────────┼───────────────────┐
                                    ▼                   ▼                   ▼
                              Google Drive        Google Calendar      Google Tasks
                            (transcript doc)     (extracted events)   (action items)
```

### Google services synced

- **Google Drive**: Saves a full transcript document with summary, key memories, action items, and calendar events.
- **Google Calendar**: Creates events with title, description, start/end time, and location.
- **Google Tasks**: Creates action items with title, notes, and optional due date.
