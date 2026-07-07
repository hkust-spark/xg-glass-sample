# Play Feature Delivery sample

This standalone Android project demonstrates keeping a base xg.glass app small while loading the heavy Meta Android adapter through Play Feature Delivery.

The base `:app` depends on the always-on SDK artifacts:

- `io.github.hkust-spark:xgglass-core:0.2.1`
- `io.github.hkust-spark:xgglass-core-android:0.2.1`
- `io.github.hkust-spark:xgglass-app-contract:0.2.1`
- `io.github.hkust-spark:xgglass-device-even:0.2.1`
- `io.github.hkust-spark:xgglass-device-simulator:0.2.1`

The on-demand `:feature:meta` module depends on `io.github.hkust-spark:xgglass-device-meta:0.2.1`. The base app never imports Meta classes directly; it requests the `meta` split with `SplitInstallManager`, calls `SplitCompat.install(...)`, then instantiates `com.xgglass.device.meta.MetaWearablesGlassesClient` by reflection.

Frame is intentionally not included here. `xgglass-device-frame-embedded` is not on public Maven and only resolves through the SDK source/CLI composite build; see the Frame section in the SDK's `docs/play-feature-delivery.md`.

## Token requirement

Meta's Android DAT dependencies are hosted on GitHub Packages. To build the Meta feature, add a GitHub token with `read:packages` to `~/.gradle/gradle.properties`:

```properties
github_token=ghp_xxxxxxxxxxxxx
```

You can also export `GITHUB_TOKEN` before running Gradle.

## Build and packaging verification

Build the app bundle:

```bash
cd play_feature_delivery
./gradlew :app:bundleDebug
```

Convert it to a local-testing APK set:

```bash
bundletool build-apks \
  --bundle app/build/outputs/bundle/debug/app-debug.aab \
  --local-testing \
  --output /tmp/xgglass-pfd-sample.apks \
  --overwrite
```

Inspect the APK set to confirm it contains both base splits and Meta feature splits:

```bash
unzip -l /tmp/xgglass-pfd-sample.apks | grep -E 'base|meta'
```

## Runtime verification

If an Android emulator or device is available, install the local-testing APK set and launch the app:

```bash
bundletool install-apks --apks /tmp/xgglass-pfd-sample.apks
adb shell am start -n com.example.xgglass.pfd/.MainActivity
adb logcat -d -s XG_PFD_SAMPLE
```

Expected evidence is a log sequence showing the base app starting, the `meta` feature reaching `INSTALLED` or already being present, and `Meta adapter class instantiated`. The sample supplies a reflection-created local-testing `DeviceSelector` proxy so construction does not touch Meta DAT global device state on an emulator; a later connection attempt can still fail honestly without Meta glasses hardware.

Play Feature Delivery is Play-only. Sideloaded APKs and non-Play stores do not provide the same on-demand delivery behavior; for local development, use the `bundletool build-apks --local-testing` flow above.

Verified in this repository on 2026-07-08 with `./gradlew :app:bundleDebug`, `bundletool build-apks --local-testing`, archive inspection showing base and `meta` splits, and runtime launch on `xg_glass_avd` showing `Meta adapter class instantiated: com.xgglass.device.meta.MetaWearablesGlassesClient; model=META`.
