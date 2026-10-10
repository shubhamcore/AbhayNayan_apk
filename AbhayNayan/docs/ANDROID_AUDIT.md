# Android audit — AbhayNayan

**Audit date:** 10 October 2026 · **Repository:** `shubhamcore/AbhayNayan_apk` (Android project in `AbhayNayan/android/`) · **Reference backend:** `hrdkkmr/AbhayNayan_web` at commit `7791e31`.

This is the starting-point audit. What was changed afterwards is described in [ANDROID_BUILD.md](ANDROID_BUILD.md), [ANDROID_API_INTEGRATION.md](ANDROID_API_INTEGRATION.md), and [ANDROID_ML_STATUS.md](ANDROID_ML_STATUS.md).

## Starting state (commit `8f65b73`)

| Area | Found |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 (Compose BOM 2025.10.00); one 847-line `MainActivity.kt` with an enum page switch |
| IDs | `namespace` and `applicationId` `in.abhaynayan.app` (Kotlin packages escape the reserved `in` segment with backticks) |
| Toolchain | Gradle wrapper 8.13, AGP 8.13.2, Kotlin 2.2.20, KSP 2.2.20-2.0.4, Java/JVM target 17 |
| SDK | `minSdk 26`, `targetSdk 36`, `compileSdk 36` |
| Architecture | Activity-hosted Compose state (`remember`), no ViewModel; workflow state lost on configuration change |
| Screens | Home, setup (profile + pathway), camera, demo-case picker, result; maternal and neonatal shared one flow |
| Camera | Camera2 `TextureView` preview snapshots (`getBitmap`) with a torch toggle; auto-exposure left on, so the "flash pair" was not exposure-matched; no capture metadata |
| Quality | Brightness/edge proxy + manual ROI confirmation |
| Network | None (no `INTERNET` permission, no client) |
| Storage | Room v1 (`patients`, `screenings`), exported schema `1.json`; non-null `estimate` only allowed fixture records |
| ML | `DemoScreeningModel` (fixed fixtures) and fail-closed `MissingClinicalModel`; no model files, no runtime |
| Tests / CI | 19 JVM unit tests (`DemoInferenceTest`, `OpticalPipelineTest`); GitHub Actions job builds the debug APK on JDK 17 |
| Baseline build here | **Passed** before any change: `gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL in 7m 31s`, APK 11.6 MB (JDK 22, freshly installed SDK platform 36 / build-tools 36.0.0) |
| Previous APK | Not committed (ignored by `.gitignore`); no device/emulator available, so never installed |

## Backend (website) findings

- `backend/app/` in this repository is byte-identical to `hrdkkmr/AbhayNayan_web` (`7791e31`); only docs differ.
- No `docs/API_CONTRACT.md` or `docs/ANDROID_INTEGRATION.md` exists. The contract was derived from `backend/app/main.py` and `backend/app/models/schemas.py` (see [ANDROID_API_INTEGRATION.md](ANDROID_API_INTEGRATION.md)).
- `/api/sync`, `/api/referrals`, `/api/fhir`, and `/api/screenings` accept **synthetic demo records only** (`patient.demo == true`, `model_mode == "DEMO"`), and every Pydantic model uses `extra='forbid'`. No endpoint accepts a record without an estimate.
- `/api/counselling` requires an estimate, unit, risk tag, confidence, and quality score; Gemini runs server-side with deterministic fallback.
- No authentication exists on the API.

## ML and data findings (summary)

Full detail in [ANDROID_ML_STATUS.md](ANDROID_ML_STATUS.md).

- **Ridge baseline** (`models/anemia_color_baseline.json`): re-run from the Drive CSV — MAE 1.5041, RMSE 1.9392, R² 0.3987 reproduced exactly; coefficients identical. Uses three undocumented precomputed "%Red/Green/Blue pixel" columns that the phone cannot reproduce.
- **Colab notebook**: now contains a MobileNetV3-Small Hb regressor on Eyes-Defy-Anemia (288 images / 145 patients). Test R² = −0.197. Saved only as an ephemeral Kaggle `.keras` file; no TFLite export, no model card.
- **Neonatal ZIP**: 478 JPEGs (239 forehead, 239 sternum), no label file. Skin images, not sclera.

## Blockers found

1. No Android SDK on this machine → installed command-line tools, `platforms;android-36`, `build-tools;36.0.0`, `platform-tools` to `E:\Android\Sdk` (the default per-user path contains a space that breaks `sdkmanager.bat`).
2. Only JDK 22 installed (CI uses 17). The existing Gradle 8.13 / AGP 8.13.2 stack builds on JDK 22, so versions were left unchanged.
3. About 1 GB of free RAM: the existing `-Xmx768m`, single-worker, in-process Kotlin settings were kept and are sufficient.
4. No emulator or physical device; an emulator is not practical with this much free memory. Runtime testing remains outstanding.

## Planned changes (implemented)

Separate maternal/neonatal workflows with minimal metadata; four explicit result modes; typed, fail-closed model contract; Camera2 `ImageReader` capture with exposure locking and pair validation; objective quality checks; backend client for health/sync/counselling/referral/dashboard; Room v2 sync queue with WorkManager; escalation-only referral rules; FHIR export aligned with the backend; tests with a mocked HTTP server; documentation.
