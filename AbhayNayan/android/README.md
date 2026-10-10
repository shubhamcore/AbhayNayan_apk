# AbhayNayan — native Android app

An offline-first screening workflow and counselling assistant for ASHA workers, ANMs, and PHC medical officers. It has separate **maternal anemia** and **newborn jaundice** workflows. Kotlin + Jetpack Compose (Material 3), Room, Camera2, WorkManager; no WebView.

> **Safety boundary.** This is a prototype and is **not clinically validated**. It does **not** measure hemoglobin or bilirubin: no valid model exists for either (see [../docs/ANDROID_ML_STATUS.md](../docs/ANDROID_ML_STATUS.md)). Real profiles get photo-quality checks, danger-sign-based referral advice, and counselling only. Synthetic demo profiles may show fixed fixture values labelled `FIXED DEMO VALUE · NOT FROM IMAGE`. Always confirm with an appropriate test (CBC/point-of-care Hb; serum or transcutaneous bilirubin) and follow local protocol. See [../docs/SAFETY.md](../docs/SAFETY.md).

## What is implemented

- **Home:** branding, online/offline state, server status, both workflow entry points, recent records, sync counters (pending / failed / synced / phone-only), language (English · हिन्दी · भोजपुरी), help & safety, settings, and app/model version.
- **Guided workflow (7 steps):** profile → details → prepare → capture → image checks → analysis → result, then save → counselling & referral. Back/Cancel are available at every step, with Retake on the checks screen, Retry on the analysis screen, and Save on the result screen.
  - **Maternal:** pregnancy status, optional gestational week, maternal danger signs.
  - **Newborn:** age in hours (required, ≤ 672), optional gestational age at birth, visible jaundice yes/no, IMNCI-style danger signs. Visible jaundice under 24 h or any danger sign shows an **urgent referral** banner immediately.
- **Result modes:** `DEMO_SYNTHETIC`, `EXPERIMENTAL_RESEARCH_ONLY`, `VALIDATED_FOR_INTENDED_USE`, `DISABLED_NO_VALID_MODEL`, each with its own banner; provenance, quality status, limitations, and next step are always shown.
- **Camera:** rear Camera2 with a `YUV_420_888` `ImageReader`. It reads capabilities (hardware level, manual sensor, AE/AWB lock, flash). The ambient + torch pair is captured with exposure held constant, or the app falls back to a single frame and says so. The camera is released on pause/dispose. Gallery import (JPEG/PNG/WebP) is also available, and demo profiles can use a synthetic colour chart.
- **Image checks:** too small, too dark, too bright, glare, blur, target missing, and movement between frames, each with a plain-language reason and Retake. The worker confirms the target region manually; there is no landmark model.
- **Offline-first storage:** Room v2 (auto-migrated from v1; existing records kept). Records are `PENDING → SYNCING → SYNCED` or `FAILED`, or `LOCAL_ONLY` when the backend contract cannot accept them. The WorkManager sync is duplicate-safe.
- **Backend:** health, sync, counselling (Gemini runs server-side only), referral + PDF link, and dashboard counts. See [../docs/ANDROID_API_INTEGRATION.md](../docs/ANDROID_API_INTEGRATION.md).
- **Counselling:** on-device text in three languages (the demo text matches the backend fallback exactly). Server text is used when available. Android TTS has play/stop, with a Hindi voice used for Bhojpuri when needed.
- **Referral & interoperability:** a structured referral summary to share, a server referral brief for demo records, and pseudonymous FHIR R4-style JSON with the same resources and IDs as the backend. Records without an estimate use `dataAbsentReason`.
- **Privacy:** photos are held in memory only and are never saved or uploaded. Profiles get random IDs, and nicknames never leave the phone. Logs are redacted (`SafeLog`). Backup/device transfer is excluded, cleartext is allowed in debug builds only for local development hosts, and there are no API keys in the app.

## Source layout (`app/src/main/java/in/abhaynayan/app/`)

| Path | Contents |
|---|---|
| `AbhayApp.kt`, `MainActivity.kt` | Dependency container; Compose root and back handling |
| `core/` | Pure Kotlin: demo fixtures, `ModelContract` (modes, typed models, card validation), `ImageAnalysis` (quality gate, pair validation, ΔI/EI, YUV, rotation), `CameraModel`, `Clinical` (metadata, danger signs, referral rules, counselling text), `Workflow` (assessment/analysis engine), `OpticalPipeline` |
| `data/` | Room entities/DAOs (v2), `ScreeningRepository` |
| `net/` | `ApiClient` (HttpURLConnection, typed results), `Payloads` (schema-exact bodies), `BackendConfig` (URL policy, settings, network monitor) |
| `sync/` | `SyncEngine`, Room store, `SyncWorker` |
| `services/` | `CounsellingService`, `CounselingSpeaker` (TTS) |
| `interop/` | `FhirExport`, `ReferralSummary` |
| `vision/` | `Camera2PreviewView` (Camera2 session, pair capture) |
| `ui/` | Theme, strings, navigation, ViewModel, screens |
| `util/` | `SafeLog` / `Redactor` |

## Prerequisites

- JDK 17+ (CI uses 17; verified locally with JDK 22).
- Android SDK Platform 36 and Build-Tools 36.x. Create `android/local.properties` with `sdk.dir=…`; it is git-ignored. Use forward slashes on Windows, e.g. `sdk.dir=E:/Android/Sdk`.
- The Gradle wrapper (8.13) downloads everything else.

## Build, test, install

```bash
cd android
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug      # macOS/Linux
gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug    # Windows
python3 scripts/verify_room_migration.py                              # optional: checks the Room 1→2 SQL on SQLite
./gradlew --no-daemon :app:lintDebug                                  # Android lint
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed; not a store build). Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy the file to the phone and allow "Install unknown apps" for that one install.

Verified results, sizes, and checks are in [../docs/ANDROID_BUILD.md](../docs/ANDROID_BUILD.md).

## Backend configuration

| Situation | URL (Settings › Backend server) |
|---|---|
| Emulator + `uvicorn` on the dev machine | `http://10.0.2.2:8000` (debug default) |
| Physical phone + dev machine over USB | `adb reverse tcp:8000 tcp:8000`, then `http://127.0.0.1:8000` |
| Deployed backend | `https://…` (required for release builds; set `-PabhaynayanReleaseApiUrl=https://…` or enter it in Settings) |
| No backend | Switch the backend off; everything works offline and records stay on the phone |

Start the backend from the repository root with `scripts/run_local.sh` or `scripts/run_local.ps1`. Only synthetic demo records sync; the current backend rejects anything else.

## Camera permission and device limits

The camera permission is requested on the capture step; if it is denied, the app explains how to enable it and offers photo import. Devices without a flash, or without AE lock/manual sensor control, use **ambient-only** mode, and the "This camera" panel shows which mode is active. LEGACY camera devices often don't report exposure metadata, so the flash comparison is rejected there. Frame interval is measured; 100 ms is a target and is not guaranteed.

## Model artifacts

None are bundled, and none should be until they meet the card requirements in [../docs/ANDROID_ML_STATUS.md](../docs/ANDROID_ML_STATUS.md). That document also gives the exact steps to add one (assets, card, runtime, tests, opt-in).

## Troubleshooting

- `SDK location not found`: create `local.properties`; check for backslash escapes on Windows.
- `sdkmanager` fails with `'C:\Users\First' is not recognized`: install the SDK to a path without spaces.
- Gradle daemon killed / out of memory: `gradle.properties` already limits the heap to 768 MB with one worker. Close other apps, or raise `-Xmx` slightly if RAM allows.
- "Cannot reach the server": check the URL table above, then **Settings › Test connection**. HTTP to LAN IPs is blocked by design; use `adb reverse` or HTTPS.
- No Bhojpuri voice: expected on most phones. The app uses a Hindi voice or asks the worker to read the text aloud.

## Not yet done

- No installation or runtime test on a device or emulator (none available during this work). The camera pair capture, TTS, WorkManager, and Compose UI have compiled and passed unit tests, but they still need a device smoke test (see [../docs/ANDROID_BUILD.md](../docs/ANDROID_BUILD.md)).
- No biomarker model; no anatomical landmark/ROI model.
- Hindi/Bhojpuri strings need review by fluent speakers. The referral rules need clinical governance review (`ReferralRules.REVIEW_STATUS`).
- Room data is not encrypted by the app.
- Non-demo records cannot sync until the backend contract changes (see [../docs/ANDROID_API_INTEGRATION.md](../docs/ANDROID_API_INTEGRATION.md)).
- There are no instrumented (on-device) tests.
