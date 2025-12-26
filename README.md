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

Tap **Apply** to save. Settings are stored on the phone, so you usually only do this once.

---

### Normal usage flow

Use this order:

- Connect
- Apply (AI settings)
- Start

What each button does:

- Connect: Connects the phone to the glasses (for syncing photos and sending text to the glasses display).

- Start: Starts the automatic loop: capture → sync → AI → display on glasses.

- Stop: Stops the automatic loop.

---

### What you will see

- On the phone: a running log (capture/sync/AI status).

- On the glasses:

- For valid AI results, displayed as: `<question_id>: (<explanation>) **<answer>**`

- For invalid requests: displayed as `invalid request: <reason>`; the app retries sooner after invalid responses.

---

### Tips & troubleshooting

- Make sure you tapped Connect first.

- Confirm Wi‑Fi and Location are enabled on the phone.

- AI doesn’t respond:
  - Re-check Base URL / API Key / Model, then tap Apply again.

- Changing AI settings while running:
  - Best practice: tap Stop, then Apply, then Start again.

---

### Frequently Asked Questions

1. The APP cannot connect to the glasses
   - **Solution**: Fold the glasses, press the button on the glasses three times to re-pair the glasses with the APP.
2. The APP shows "WIFI_CONNECT_FAILED"
   - **Solution**: The easiest way to solve this issue is to re-connect the glasses again. If the issue still persists, you might need to:
     - (a) Enable the Developer mode of the phone.
     - (b) "Connect" which initiate the Bluetooth connection
     - (c) During the APP retrying, go to the "WLAN Direct" in the settings of the phone, and manually connect to the glasses.
     - (d) Return to the APP and it should be successful.
3. The APP shows "AI Failed" or timeout
   - **Solution**: Many API calls need to access through the VPN if you're in a restricted region (e.g., mainland China).
4. The APP/glasses shows "text blurred or too small"
   - **Solution**: Try to adjust the position and/or tilt of the glasses. You can view the picture taken by the glasses on the APP for your convenience to adjust.
5. Other errors and issues:
   - **Solution**: Please submit the issue on GitHub! We will fix it very soon.

---

### Privacy note

Captured images are synced to your phone for AI processing.

The app saves captured images and generated answers locally (app storage) for operation/debugging. You can remove them by clearing the app’s data or uninstalling the app.

Depending on your AI provider, images and prompts may be sent to your configured API server. Use a provider you trust.
