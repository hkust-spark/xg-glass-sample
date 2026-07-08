# AI Assistant sample

This standalone Android phone-host sample demonstrates the xg.glass camera-to-vision loop the video-stream API is designed for:

- Connect to the simulator glasses adapter.
- Start `startVideoStream()` with `VideoFrameRateTier.LOW` for live camera awareness.
- On glasses tap (`GlassesEvent.Tap`) or the phone **Ask From Snapshot** button, call `capturePhoto()`.
- While streaming is active, `capturePhoto()` returns the latest stream frame; if streaming is unavailable, the same call degrades to a normal still capture.
- POST the JPEG as a base64 data URL to an OpenAI-compatible `/v1/chat/completions` endpoint.
- Display the answer back through `display()` and emit `ai_assistant: answer <N> chars` to logcat.

The sample depends only on published Maven Central artifacts:

- `io.github.hkust-spark:xgglass-core:0.3.0`
- `io.github.hkust-spark:xgglass-core-android:0.3.0`
- `io.github.hkust-spark:xgglass-device-simulator:0.3.0`

## Configuration

Create `ai_assistant/local.properties` or export the equivalent environment variables. Do not commit real keys.

```properties
ai.baseUrl=http://10.0.2.2:8765/v1
ai.apiKey=mock-key
ai.model=mock-vision
```

Environment alternatives are `AI_BASE_URL`, `AI_API_KEY`, and `AI_MODEL`.

Provider notes:

- OpenAI: set `ai.baseUrl=https://api.openai.com/v1`, `ai.apiKey=<real key>`, and a vision-capable model.
- DashScope: use the OpenAI-compatible endpoint, commonly `https://dashscope.aliyuncs.com/compatible-mode/v1`, with a vision-capable DashScope model.
- Ollama: use its OpenAI-compatible endpoint, commonly `http://10.0.2.2:11434/v1` from the Android Emulator, and an image-capable local model.

If the key, base URL, or model is missing, the app shows and logs a clear configuration message and does not crash.

## Build

```bash
cd ai_assistant
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

## Hardware-free run

The project includes `xg-glass.yaml`, so the CLI can use the same simulator flow as generated projects:

```bash
cd ai_assistant
xg-glass run --sim --local_video /path/to/sample.mp4
```

The CLI patches `BuildConfig.XG_SIM_VIDEO_PATH`, pushes the video to `/data/local/tmp/xg_glass_sim_video.mp4`, builds, installs, and launches the app on an Android Emulator. Without `--local_video`, the simulator uses the emulator camera.

For key-free verification, run a local mock server on the host and set `ai.baseUrl=http://10.0.2.2:<port>/v1`. This demo was verified against a mock OpenAI-compatible endpoint; any provider that accepts Chat Completions messages with `image_url` data URLs should work.
