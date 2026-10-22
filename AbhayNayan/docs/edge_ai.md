# Edge AI: on-device research models

Status (22 October 2026): **implemented, trained, evaluated, integrated behind a developer toggle; not clinically validated.** With the toggle off (the default), or when the model files are absent (a CI or Git checkout build), the app behaves exactly as before: `DISABLED_NO_VALID_MODEL`, no estimate.

Training, evaluation and the server-side counterparts live in AbhayNayan_web: `docs/ml_architecture.md`, `docs/ml_evaluation.md`, `docs/ml_integration.md`, `docs/model_cards/`.

## 1. Runtime and model format

| Item | Choice | Why |
|---|---|---|
| Runtime | **Pure Kotlin** (`kotlin-linear-cf-v1`), no TFLite/LiteRT/ONNX | The chosen edge models are linear models over 25 colour features. On the training data, a frozen MobileNetV3 did not beat colour features for hemoglobin (grouped CV R² 0.42 vs 0.50), so a native runtime would add APK size and complexity for no measured gain. |
| Format | JSON (`model.json`: feature names, standardisation mean/scale, coefficients, intercept, training feature ranges) | Human-auditable; checked by SHA-256; no code execution on load |
| CPU / minSdk | Any CPU; plain `Double` arithmetic; minSdk 26 unchanged | No native libraries, no NNAPI/GPU delegate |
| Quantisation | Not applicable | Model sizes are 4–6 KB |
| Offline | Fully offline | No network call is involved in inference; photos never leave the phone |

## 2. Models bundled in a research build

| Asset | Model | Output |
|---|---|---|
| `assets/models/hemoglobin/model.json` + `metadata.json` | `hb-ridge-cf` 1.0.0-research | Hb g/dL, through the existing `HemoglobinModel` slot |
| `assets/models/jaundice_class/model.json` | `njn-logreg-cf` 1.0.0-research | score 0–1 for the NJN dataset class "jaundice"; **never** the bilirubin slot, never mg/dL |

**Bilirubin:** no model exists, because there are no bilirubin labels. `BilirubinModel` stays `UnavailableBilirubinModel`, even with the toggle on (tested).

Held-out results (from AbhayNayan_web `docs/ml_evaluation.md`):

- Hb: on 54 unseen patients from the same capture device, MAE 1.30 g/dL (95% CI 1.04–1.58), R² 0.51. On 110 children from 3 unseen Ghanaian hospitals, MAE 2.07 and R² −0.64, which is worse than predicting the mean. Hb of 7–10 g/dL is over-estimated by about 2.7 g/dL.
- NJN class: AUROC 0.79 (0.72–0.87); sensitivity 0.86, specificity 0.53 at the research threshold.

## 3. Preprocessing contract (`cf-v1`, `core/ColourFeatures.kt`)

This is a line-for-line port of `backend/app/ml/features.py`:

1. **Input to the model.** `WorkflowEngine.modelInput` supplies the raw **ambient** colours, never the flash-differential preview (that image is clipped and normalised for display; the old code would have passed it). If the image has transparency, it is a pre-segmented crop, so the whole image is used and the mask defines the region. Otherwise the confirmed manual-guide rectangle is cropped.
2. Subsample with `step = ceil(max(W, H) / 256)`.
3. Valid pixels have alpha ≥ 128 and 20 ≤ max(R,G,B) < 250. At least 100 are required, otherwise a `FeatureException` is thrown and no value is produced.
4. Compute 25 features (means, SDs, chromaticity, percentiles, HSV S/V, CIE L\*a\*b\*, erythema and yellow indices), then standardise and apply the linear model. NJN uses a sigmoid on top.

**Parity.** `ColourFeaturesTest` checks the Kotlin features against Python golden vectors from 6 synthetic images (`src/test/resources/ml_golden/`, no patient pixels) to 1e-6. `EdgeModelsTest.trainedAssetsReproducePythonGoldenOutputs` checks both models' outputs against Python to 1e-6 whenever the trained assets are present.

**Known differences from the training input.**

- Gallery import downsizes to ≤ 640 px with bilinear filtering.
- Camera captures are rectangular guide crops of whole eyes, while the models were trained on expert-segmented crops from a dedicated capture device (Eyes-defy-anemia).
- Any phone capture is therefore out of the training domain, and the app shows an out-of-distribution note when the features fall outside the training range.

## 4. Quality gate for segmented images

`ImageQualityChecker` judged brightness on the whole frame. A segmented crop is about 95% transparent (black), so **159 of 217 Eyes-defy-anemia crops would have failed as `TOO_DARK`**, and the model could never be reached with its intended input. For images with transparency, the gate now uses opaque pixels only: brightness, contrast, sharpness on the mask's bounding box, and clipping with the server's 25% limit. The camera path is unchanged.

## 5. Opting in and what the user sees

- Settings → **Research models (developer)** → "Use experimental on-device research models" (persisted, default off).
- Hb result: amber `Experimental · research only` badge. The model status lists the reasons and, when relevant, "outside the model's training data". The record is saved locally with `resultMode = EXPERIMENTAL_RESEARCH_ONLY`. The sync contract accepts only synthetic demo records, so it stays on the phone.
- Neonatal result: a separate amber card **"Research score · not bilirubin"** shows the score, the research threshold, the dataset class and an explanation. It is neither saved nor synced. The bilirubin field still reads "No valid model · estimate disabled".
- Failures: a feature error, model exception, non-finite or implausible output (Hb outside 2–25 g/dL) gives "The model could not produce a result … No value is shown". A demo fixture is never substituted. Demo fixtures remain available only on synthetic profiles and stay labelled `DEMO_SYNTHETIC`.

## 6. Artifact management

The model files are **gitignored** (`android/app/src/main/assets/models/`), because they derive from datasets whose licence and consent are unverified. To build a research APK locally:

```bash
# in AbhayNayan_web, after training (docs/ml_integration.md §6)
.venv/Scripts/python scripts/ml/export_android_assets.py --apk-android ../../AbhayNayan_apk/AbhayNayan/android
.venv/Scripts/python scripts/ml/export_golden_vectors.py --android-dir ../../AbhayNayan_apk/AbhayNayan/android/app/src/test/resources/ml_golden
# in AbhayNayan_apk/AbhayNayan/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Load-time checks (`EdgeModelLoader`, all fail closed and covered by `EdgeModelsTest`):

- `metadata.json` must parse: `input_kind: colour_features`, `feature_version: cf-v1`, `feature_count: 25`, `runtime: kotlin-linear-cf-v1`, biomarker `HEMOGLOBIN`, unit g/dL, subject-level split, `RESEARCH_ONLY`.
- `model.json` must match `artifact_sha256`, have the expected artifact type, the cf-v1 feature names, finite parameters and a positive scale.
- Missing, corrupt, tampered, wrong-biomarker, unknown-runtime, validated-claim and unreadable-asset cases each keep `DISABLED_NO_VALID_MODEL` with a stated reason.

## 7. Measurements

| Measure | Value | Notes |
|---|---|---|
| Model size | 5.9 KB (Hb `model.json`), 1.1 KB (card), 5.4 KB (NJN) | inside the APK |
| APK size | 12.6 MB debug APK with assets | not a release build |
| Latency (desktop JVM, **not a phone**) | cf-v1 + model on the 462×154 guide crop of a 640×480 frame: median 6.0 ms, p90 7.5 ms; full 640×480 frame: median 12.5 ms | `EdgeBenchmarkTest`, HotSpot 22.0.1, Intel Core Ultra 5 125H, 200 runs after 30 warm-up. Expect several times slower on a low-end phone; it runs off the main thread. |
| Device latency / memory | **not measured** | no device or emulator was available (`adb devices` empty; no system image installed) |

## 8. Tests

`ColourFeaturesTest` (6), `EdgeModelsTest` (11), `EdgeBenchmarkTest` (1), plus the existing suite. Instrumented and UI tests were not run, because no device or emulator was available. The Compose UI changes were compiled, but not exercised on a screen.
