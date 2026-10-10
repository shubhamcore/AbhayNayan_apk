# AbhayNayan release notes

**Package:** 0.1.0-final-demo · **Updated:** 10 October 2026

This is a hackathon **prototype source release**, not a clinical product. It contains the runnable FastAPI-served PWA, demo API, tests, documentation, native Android source, Docker configuration, and a debug-signed Android APK. The APK is build-verified and unit-tested but has not been installed or exercised on a device/emulator.

## Android 0.3.0-demo (10 October 2026)

- Every saved screening now has a **PDF report generated on the phone** (works offline, also for "no estimate" records), with Share/Open buttons at the top of the record screen. The save button reads **Save & view report**.
- Default backend is the deployed `https://abhaynayan-web.onrender.com`; phones upgraded from 0.2.0 switch automatically unless a URL was saved in Settings.
- Supports **API contract 1.0** (null fixture scores, provenance, context, per-record sync results, error envelope, counselling `request_id`, referral reason), with automatic fallback for pre-1.0 servers. Verified live against both.

## Android 0.2.0-demo (10 October 2026)

- Rebuilt the native app around separate maternal and newborn workflows, four explicit result modes, a fail-closed typed model contract (no model bundled), Camera2 `ImageReader` pair capture with exposure locking and validation, objective image-quality checks, Room v2 offline queue with WorkManager sync, backend counselling/referral integration, escalation-only referral rules, and pseudonymous FHIR export.
- Verified: 101 unit tests pass (including a live-backend referral-PDF test), lint 0 errors; hand-held movement between the flash pair is now corrected instead of forcing a retake, Room 1→2 migration SQL checked on SQLite, debug APK `0.2.0-demo` (12.1 MiB) signature-checked and scanned for secrets. Not yet installed on a device. See `docs/ANDROID_BUILD.md`.

## Earlier Android build-fix and verification (0.1.0)

- Fixed Kotlin syntax across Android source and tests: `in` is a Kotlin keyword, so the first package segment is now escaped with backticks while the Android application ID stays `in.abhaynayan.app`.
- Tuned Gradle worker/memory settings for the constrained build environment.
- Verified using JDK 17, Gradle 8.13, Android SDK platform 36, with:

  ```bash
  cd android
  ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
  ```

  Result: **BUILD SUCCESSFUL**. `DemoInferenceTest`: 9 passed. `OpticalPipelineTest`: 10 passed. **19 total; zero failures, errors, or skipped tests.**
- Produced `android/app/build/outputs/apk/debug/app-debug.apk` (about 12 MB), application ID `in.abhaynayan.app`, version `0.1.0-demo`, min SDK 26 and target SDK 36. APK metadata and v2 debug signature were checked.
- Android Studio itself was not launched; the same Gradle project/build tasks were verified from the command line. No emulator or physical device was attached; installation, camera lifecycle, flash behavior, TTS, and runtime UI remain unverified.
- The APK is debug-signed for development/demo use, not a Play Store or release-signed artifact.

## Native optical and demo behavior

- Added a testable pure-Kotlin image pipeline for manual geometric ROI crop, clipped/percentile-normalized positive flash-minus-ambient visualization, RGB means/ratios/standard deviations, CIE Lab*, YCrCb, chromaticity, and valid-pixel reporting.
- Added abstract offline color-chart pixels for exercising the local pipeline without a camera or anatomical image.
- Added a `ScreeningModel` load/predict/close boundary, explicit fixed-fixture DEMO adapter (no confidence estimate), and fail-closed missing-clinical-model path. No TFLite runtime/weights or clinical tensor contract is bundled.
- Extended the Camera2 preview utility to attempt an ambient preview snapshot plus a brief torch-on preview snapshot. It falls back to ambient-only if flash/capture fails; auto exposure stays enabled and no ISO/exposure lock is claimed.
- Added on-device debug previews for ambient, flash, compensated, and ROI images, along with the locally measured image-processing time. This timing is not model-inference time.
- Allowed a fixed fixture to follow a Camera2 capture only for a synthetic demo profile. The fixture remains independent from captured pixels/features, is labelled `FIXED DEMO VALUE · NOT FROM IMAGE`, and is unavailable for local/non-demo profiles.

## Existing user-visible prototype workflows

- Maternal-anemia and neonatal-jaundice PWA demo journeys with distinct local synthetic maternal/newborn profiles; newborn context includes age in hours.
- Local browser camera/file quality and color-feature visualization; manual guide; no image upload.
- Fixed deterministic PWA fixtures selected only through the explicit synthetic-demo path; no estimate is inferred from pixels or patient data.
- Offline local profile/history storage, queued synthetic demo sync, English/Hindi/Bhojpuri deterministic counselling, optional server-only Gemini adapter, local FHIR-style JSON, referral PDF/print, and PHC prototype dashboard.
- FastAPI safety schemas, server-side demo-profile guards, SQLite demo store, OpenCV utilities, and local fallback copy.

## Verification

- **Android, 10 October 2026:** Gradle unit tests and debug APK assembly passed; 19 Kotlin unit tests passed as detailed above.
- **Web/backend, 9 October 2026:** **60 Python tests, 29 Node.js web-module tests, and 1 JSDOM maternal/newborn workflow smoke** passed; Python `compileall`, `pip check`, and web syntax checks also passed.
- These checks do not constitute emulator/real-device or clinical validation. Docker, live Gemini, real-device camera behavior, production security, and clinical/regulatory validation are not verified.

## Release archive hygiene

`python3 scripts/package_release.py --include-android-apk` creates `../AbhayNayan-0.1.0-final.zip` and a SHA-256 sidecar. The APK option fails closed if the expected APK is missing. The archive excludes `.env` secrets, generated SQLite databases/local records, virtual environments, `node_modules`, Android/Gradle build caches (except the explicitly included debug APK), and other generated output. It includes `.env.example`, the Gradle wrapper, Android source and tests, documentation, and web/backend source.

## Limitations

- No approved or clinically validated hemoglobin/bilirubin model is supplied; displayed fixture values are synthetic and independent of image features.
- No MediaPipe landmarks, face/eye/blink/distance/motion checks, or anatomy-aware segmentation are present in Android; the ROI is a manually positioned geometric crop.
- Experimental F−A visualization is not color-calibrated or proven illumination-invariant.
- No active Android TFLite adapter/weights, native Gemini/API/sync workflow, or production Android parity is included.
- Android build and unit tests passed, but the APK has not been installed or device-tested; Camera2/runtime behavior is not verified.
- The research baseline is disabled research material; data reuse permissions are unverified.
- Docker, live Gemini, production security, clinical performance, regulatory readiness, and real-patient use are not claimed.
