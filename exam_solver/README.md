# xg-glass-release

`example.apk` connects Rokid glasses to an Android phone: it captures photos, syncs them to the phone, runs AI via your configured API endpoint, and displays the result on the glasses.

---

### What you need

- An Android phone
- Your Rokid glasses
- Wi‑Fi and Location enabled on the phone (required for the glasses ↔ phone connection and image sync)
- AI API credentials (Base URL / API key / model; configure in **AI settings** below)

---

### Install & open

- Install the provided release APK on your phone.
- Open AIGlass from your app list.

---

### First-time setup (AI settings)

Before starting, open **AI settings** (tap to expand/collapse) and enter:
- Base URL: an OpenAI-compatible `v1/chat/completions` endpoint (e.g. `https://api.poe.com/v1/chat/completions`)
- Model: e.g. `GPT-5.2`
- API Key

Tap Apply to save. Settings are stored on the phone, so you usually only do this once.

---

### Normal usage flow

Use this order: Connect → Apply (AI settings) → Start.

What each button does:
- Connect: Connects the phone to the glasses (for syncing photos and sending text to the glasses display).
- Start: Starts the automatic loop: capture → sync → AI → display on glasses.
- Stop: Stops the automatic loop.

Changing AI settings while running:
- Best practice: tap Stop, then Apply, then Start again.

---

### What you will see

- On the phone: a running log (capture/sync/AI status).
- On the glasses:
   - For valid AI results, displayed as: `<question_id>: (<explanation>) **<answer>**`
   - For invalid requests: displayed as `invalid request: <reason>`; the app retries sooner after invalid responses.

---

### Frequently Asked Questions (FAQ)

1. The app can't connect to the glasses
   - Fold the glasses, then press the button on the glasses **three times** to re-pair the glasses with the app.
2. The app shows "WIFI_CONNECT_FAILED"
   - First, disconnect and reconnect the glasses. If the issue persists, try the following:
   - **Try this first (recommended):**
      - Keep **Wi‑Fi enabled** but **do not connect** to any Wi‑Fi network (this allows the Wi‑Fi P2P connection to be established successfully).
      - Use **cellular data** to access the remote AI.
   - Other workaround:
      - Enable Developer mode on the phone.
      - Tap Connect to initiate the Bluetooth connection.
      - While the app is retrying, open the phone Settings → WLAN Direct / Wi‑Fi Direct and manually connect to the glasses.
      - Return to the app; the connection should succeed.
3. The app shows "AI Failed" or timeout
   - Many API calls require a VPN in restricted regions (e.g., mainland China).
4. The app/glasses shows "text blurred or too small"
   - Try to adjust the position and/or tilt of the glasses. You can use the photo preview in the app to help with alignment.
5. Other errors or issues:
   - Please submit an issue on GitHub! We will fix it very soon.

---

### Privacy note

Captured images are synced to your phone for AI processing.

The app saves captured images and generated answers locally (app storage) for operation/debugging. You can remove them by clearing the app’s data or uninstalling the app.

Depending on your AI provider, images and prompts may be sent to your configured API server. Use a provider you trust.
