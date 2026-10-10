# Android source and build status

**Verified: 10 October 2026.** The Android Gradle build now compiles, all current local Android unit tests pass, and a debug APK was packaged and signature-checked. The APK has **not** been installed or exercised on a physical device or emulator; camera lifecycle, flash behavior, and UI runtime remain unverified. This remains a synthetic hackathon prototype, not a clinical tool.

## Native source currently included

`android/` contains a Kotlin/Jetpack Compose prototype with:

- Room entities/DAOs for local synthetic patient profiles and screening records;
- rear Camera2 preview with a best-effort ambient `TextureView` snapshot and brief torch-on snapshot; ambient-only fallback is used when flash is unavailable or fails;
- local in-memory geometric ROI crop and preview of ambient, flash, compensated, and ROI images;
- experimental clipped/normalized flash-minus-ambient visualization;
- RGB means, R/G/B ratios, RGB standard deviation, CIE Lab*, YCrCb, chromaticity, and valid-pixel summary;
- basic local brightness/edge engineering proxy and a manual region-confirmation gate;
- a `ScreeningModel` load/predict/close abstraction with a named fixed-fixture DEMO adapter and a fail-closed missing-clinical-model adapter; fixed maternal/newborn cases are independent from camera pixels/features and expose no confidence value;
- English/Hindi/Bhojpuri deterministic safety copy and optional Android TTS;
- local FHIR-style JSON and referral-text share intents;
- pure-Kotlin unit tests for ROI bounds/crops, feature vectors, pixel clipping, ambient-only/dimension-mismatch fallback, and demo-model boundaries.

## Build tooling and verification evidence

- Verified on 10 October 2026 with JDK 17, Gradle 8.13 wrapper, Android SDK platform 36, and the project's configured Android Gradle/Kotlin plugins.
- Fixed Kotlin parsing errors caused by the reserved keyword `in` appearing as the first package segment: Kotlin package declarations and imports now escape that segment with backticks while preserving the Android application ID `in.abhaynayan.app`.
- Reduced Gradle memory/worker settings in `android/gradle.properties` for the constrained build environment. The project is configured for one worker and in-process Kotlin compilation.
- Successful command from `android/`:

  ```bash
  ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
  ```

  The wrapper executable bit is set in the refreshed source archive. Gradle reported **BUILD SUCCESSFUL**. The same Gradle project/build tasks used by Android Studio were verified from the command line; the Android Studio GUI itself was not launched.
- Android unit-test XML reports: `DemoInferenceTest` **9 passed**, `OpticalPipelineTest` **10 passed**; **19 total, 0 failures, 0 errors, 0 skipped**.
- APK: `android/app/build/outputs/apk/debug/app-debug.apk` (about 12 MB), application ID `in.abhaynayan.app`, version `0.1.0-demo`, min SDK 26, target SDK 36. `aapt dump badging` confirmed package/metadata and `apksigner verify` passed with the Android debug signing certificate (APK Signature Scheme v2).
- This refreshed release ZIP includes the verified APK (via `python3 scripts/package_release.py --include-android-apk`). It is a **debug-signed development APK**, not a Play Store/release-signed build.
- A Camera2 deprecated-API compiler warning remains; it did not prevent the build. The command-line SDK tools also printed an SDK XML version warning; build, tests, and APK packaging still completed successfully.

## Device verification still required

No emulator or physical device was attached (`adb devices -l` showed no devices), so this is build/test verification, not runtime acceptance. Before relying on the app, install the APK on a compatible Android 8+ device and verify camera permission grant/denial, lifecycle/back-navigation cleanup, torch availability/failure and ambient-only fallback, capture quality/retry behavior, manual ROI confirmation, synthetic-only fixture labeling, Room persistence, TTS language availability, accessibility, and memory cleanup.

## Important implementation boundaries

- Camera inputs are **preview snapshots**, not an `ImageReader` still-photo pair. A torch is enabled briefly when supported. Auto exposure remains enabled; no ISO/exposure lock is performed. Camera2 failures fall back to a single ambient frame instead of intentionally crashing the flow.
- There is no MediaPipe model, face/eye/blink/distance/motion detector, anatomy-aware ROI segmentation, or validated image-quality gate. The central ROI is a manual geometric crop; maternal/newborn anatomy is not detected.
- F−A processing is a clipped and percentile-normalized visualization prototype. It is not color-calibrated, clinically calibrated, or proven invariant to ambient illumination.
- The extracted features are engineering/debug data only. They do not feed the result selector. The fixed synthetic fixture is selected separately and is never presented as a camera-derived estimate. A local/non-demo profile cannot save a fixture result.
- No `.tflite` weights/runtime adapter, active Gemini client, Android sync queue, or full native patient-demographic/referral/history/settings flow is included. The broader prototype flows are primarily in the PWA/backend.
- Frame buffers are held in memory for immediate local review and are not saved with a screening record. The native manifest intentionally omits Internet permission.

## Build on a provisioned machine

Requirements: JDK 17, Android SDK platform 36, compatible Android build tools, and network access for Maven/Google dependencies.

```bash
cd android
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

PowerShell:

```powershell
cd android
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Expected debug output: `android/app/build/outputs/apk/debug/app-debug.apk`. A debug APK is not a store release and is not evidence of clinical readiness. The runnable web/API prototype is separate and has broader workflow coverage than this focused native shell.
