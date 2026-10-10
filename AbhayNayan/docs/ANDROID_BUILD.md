# Android build and verification record

**Date:** 10 October 2026 · **Machine:** Windows 11, 16 GB RAM (about 1 GB free during builds), JDK 22.0.1 (Oracle) · **SDK:** command-line tools 16111833, `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`, installed to `E:\Android\Sdk`.

Toolchain versions are **unchanged** from the repository: Gradle wrapper 8.13, AGP 8.13.2, Kotlin 2.2.20, KSP 2.2.20-2.0.4, Compose BOM 2025.10.00, Room 2.7.2. The only new libraries are `androidx.work:work-runtime-ktx:2.10.1`, `androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4`, and the test-only `org.json:json:20240303`. `gradle.properties` (768 MB heap, one worker, in-process Kotlin) was kept.

## Commands and results

From `AbhayNayan/android/` (PowerShell users: `.\gradlew.bat …`; `JAVA_HOME` pointed at JDK 22):

| Step | Command | Result |
|---|---|---|
| Baseline (before any change) | `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` | BUILD SUCCESSFUL (7 m 31 s cold), 19/19 tests, APK 11.6 MB |
| Final (after the hand-shake fix) | `ABHAYNAYAN_LIVE_BACKEND=http://127.0.0.1:8765 ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | **BUILD SUCCESSFUL** (1 m 32 s warm) |
| Unit tests | JUnit XML in `app/build/test-results/testDebugUnitTest/` | **101 tests, 0 failures, 0 errors, 0 skipped** (12 suites) |
| Live backend report test | `ReportGenerationTest.liveBackendAcceptsRecordAndProducesReferralPdf` against the real FastAPI backend (`uvicorn app.main:app`, Gemini key unset) | **Passed**: sync accepted, duplicate re-sync reported as duplicate, Bhojpuri counselling returned, referral created, `/api/referrals/{id}/pdf` returned a valid 1-page PDF (`%PDF-`, 2.7 KB) with ID, type, worker/PHC, next step, and safety notice. Skipped automatically when `ABHAYNAYAN_LIVE_BACKEND` is unset. |
| Lint | `app/build/reports/lint-results-debug.html` | **0 errors, 14 warnings** (dependency-update notices, portrait lock — kept deliberately because the ROI guide assumes portrait, minor KTX/style) |
| Room migration | `python3 scripts/verify_room_migration.py` | **PASS**: generated 1→2 SQL run on SQLite; all 3 tables match `2.json`; a v1 record keeps its data with correct defaults; nullable `estimate` accepted |

Test suites: `ClinicalTest` 9, `DemoInferenceTest` 9, `ImageAnalysisTest` 15, `ModelContractTest` 12, `OpticalPipelineTest` 10, `WorkflowEngineTest` 10, `ReportGenerationTest` 3, `ApiClientTest` 8, `PayloadsTest` 8, `CounsellingServiceTest` 5, `SyncEngineTest` 9, `NavigationAndLoggingTest` 3. Hand-shake coverage: shifts of (6,0), (−5,3), (3,−4), (9,7) px are recovered exactly with the same EI as a still pair; a (45,30) px move only skips the flash comparison; a shaken capture passes the checks and produces every report format. HTTP tests use a local mock server (no live backend, no Gemini key, no patient data).

## APK

| Check | Result |
|---|---|
| Path | `AbhayNayan/android/app/build/outputs/apk/debug/app-debug.apk` |
| Size | 12,663,919 bytes (12.1 MiB) |
| SHA-256 | `1218518679f4baceb1e22ac134ea9c473021d6d387e566a6d60f24f41326c412` (build of 10 Oct 2026 14:37, including the hand-shake fix; rebuilding changes it) |
| `aapt dump badging` | package `in.abhaynayan.app`, versionCode 2, versionName `0.2.0-demo`, minSdk 26, targetSdk 36, label "AbhayNayan", launcher `MainActivity` |
| Permissions | `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`; merged from WorkManager: `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, plus the AndroidX dynamic-receiver permission |
| `apksigner verify` | Verifies (v2 scheme), Android Debug certificate; **debug build, not for store release** |
| Secret / data scan | Unpacked 134 files: no `assets/`; no CSV/XLSX/DB/model/archive files; only PNGs are AndroidX notification 9-patches; no `AIza…`, `sk-…`, private keys, or `GEMINI_API_KEY`. Cleartext hosts (`10.0.2.2`, `127.0.0.1`, `localhost`) appear only in the **debug** network-security config; the debug default URL `http://10.0.2.2:8000` is in the dex. |

## Not executed (no device/emulator)

`adb devices -l` listed no devices, and an emulator was not practical with about 1 GB of free RAM. The following have **not** been tested at runtime:

- install/launch; Compose layout at large font scale and on small screens; TalkBack;
- Camera2: permission grant/deny, preview, AE-lock/manual-sensor pair capture, torch warm-up, `SENSOR_TIMESTAMP` matching, ambient-only fallback, pause/resume release;
- gallery import; TTS voices; WorkManager sync against a real backend; referral PDF opening;
- Room v1→v2 on-device upgrade (the SQL was verified on desktop SQLite only);
- cold-start time, memory, battery during capture.

### Smoke test to run on a phone

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`, then open **AbhayNayan**.
2. Home shows the online/offline dot, both workflows with "Estimate disabled", and zero counters. Switch to हिन्दी and back.
3. Maternal → choose "Synthetic demo mother" → Pregnant, week 24 → Continue → Allow camera → Capture. Check the "This camera" panel for the lock strategy, then complete the checks (Retake on purpose once with the lens covered: expect "target area looks empty").
4. Analysis → pick "Higher-review (synthetic)" → result shows `DEMO_SYNTHETIC` and `FIXED DEMO VALUE · NOT FROM IMAGE` → Save. Check counselling in all three languages, Play/Stop, Share referral summary, Share FHIR JSON.
5. Newborn → create a real profile (synthetic off) → age 20 h, jaundice Yes → expect an **URGENT REFERRAL** banner → capture → the result shows `DISABLED_NO_VALID_MODEL`, no value, and "On this phone only".
6. With `uvicorn` running on the PC and `adb reverse tcp:8000 tcp:8000`, set `http://127.0.0.1:8000` in Settings → Test connection → Sync now; the demo record becomes "Synced to server" and appears in the web PHC view.
7. Rotate/lock/unlock during the camera step; the app must not crash and the camera must reopen.
