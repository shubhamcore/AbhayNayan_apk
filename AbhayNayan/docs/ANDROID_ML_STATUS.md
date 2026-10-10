# Android ML status

**Status on 10 October 2026: no biomarker model is deployed in the Android app.** Both workflows run in `DISABLED_NO_VALID_MODEL` mode for real profiles. Synthetic demo profiles can attach a fixed fixture (`DEMO_SYNTHETIC`). The fixture is never calculated from the photo.

## Result modes

| Mode | When it is used | Estimate shown |
|---|---|---|
| `DEMO_SYNTHETIC` | Synthetic demo profile + an explicitly chosen fixture | Fixed fixture, labelled `FIXED DEMO VALUE · NOT FROM IMAGE` |
| `EXPERIMENTAL_RESEARCH_ONLY` | A research model card + artifact + runtime **and** a developer opt-in | Yes, labelled research-only (not reachable in this build) |
| `VALIDATED_FOR_INTENDED_USE` | Card status `VALIDATED`, `redistribution_approved`, matching `approval_reference` authorization, **and** `ModelRegistry.VALIDATED_MODE_ALLOWED = true` | Yes (constant is `false`; unreachable) |
| `DISABLED_NO_VALID_MODEL` | Anything missing, mismatched, or not opted in | No value; FHIR uses `dataAbsentReason = not-performed` |

The mode is decided by `ModelModeResolver.resolve` (`core/ModelContract.kt`), which fails closed. Hemoglobin and bilirubin use separate typed interfaces (`HemoglobinModel`, `BilirubinModel`). A card whose `biomarker` does not match its slot is rejected, so a hemoglobin model can never serve the neonatal workflow (tested in `ModelContractTest` and `WorkflowEngineTest`). Model outputs pass `ModelOutputValidator`: one finite scalar inside a physiological range, otherwise discarded rather than clamped.

## Candidates inspected

### Model A1 — Ridge colour baseline (`models/anemia_color_baseline.json`)

| Field | Value |
|---|---|
| Biomarker / output | Hemoglobin, g/dL, scalar |
| Inputs | 3 floats: `%Red Pixel`, `%Green pixel`, `%Blue pixel` columns of `Anemia_Dataset.csv` (sum ≈ 100) |
| Preprocessing | `StandardScaler` (mean/scale stored), Ridge α = 1.0 |
| Labels | `Hb` column of the CSV (source lab method undocumented) |
| Subjects / samples | 104 / 104 (one row per name) |
| Split | 5-fold `GroupKFold` by subject name (internal CV only) |
| Metrics | MAE **1.5041** g/dL, RMSE **1.9392** g/dL, R² **0.3987** |
| Verification | Re-ran `scripts/train_anemia_baseline.py` on the Drive CSV (scikit-learn 1.8.0): metrics and all coefficients matched the committed artifact exactly. The CSV (contains names) was deleted after the run. |
| Runtime | Plain arithmetic; no runtime needed |
| Licence / redistribution | Unverified |
| **Decision** | **Not deployed.** How the "pixel percentages" were computed (which images, ROI, segmentation, device, colour pipeline) is undocumented, so camera features on the phone cannot be shown to match the training features. Substituting the app's own RGB chromaticity would be exactly the arbitrary substitution the brief forbids. The error is also too large for patient-level use. |

### Model A2 — MobileNetV3-Small regression (Colab/Kaggle notebook, cell 5)

| Field | Value |
|---|---|
| Biomarker / output | Hemoglobin, g/dL, `Dense(1)` linear |
| Input | 224×224×3 float32 RGB, `mobilenet_v3.preprocess_input`, from expert-segmented palpebral PNGs |
| Data | Eyes-Defy-Anemia (Kaggle): India + Italy; 398 matched, 110 unreadable → 288 images / 145 patients |
| Split | Patient-level `train_test_split` 70/15/15 → 101/22/22 patients (201/43/44 images) |
| Training | Frozen ImageNet backbone, GAP → Dropout → Dense(64) → Dense(1), Adam 1e-3, early stopping (stopped at epoch 21) |
| Test metrics (as printed in the notebook) | MAE **1.838** g/dL, RMSE **2.190** g/dL, R² **−0.197** |
| Artifact | `/kaggle/working/hemovision_baseline.keras` — ephemeral Kaggle output; not in the Drive folder; no TFLite export; no model card |
| **Decision** | **Not deployed.** A negative R² means it does worse than predicting the mean Hb. There is no artifact to bundle, and its inputs are segmented crops that the phone does not produce. |

### Model B — bilirubin

| Field | Value |
|---|---|
| Data inspected | `Neonatal Jaundice Assessment ….zip`: 478 JPEGs (239 `Forehead/`, 239 `Sternum/`), numeric filenames, **no label file of any kind** |
| Other archives | `NJN.rar`, `archive.zip` (≈635 MB), `CP-AnemiC ….zip` (nested RAR): not downloaded/extracted; contents, labels, and licences unverified |
| **Decision** | **No model trained or deployed.** No ground-truth bilirubin is available, and labels must not be synthesised or inferred from filenames. The images are forehead/sternum skin, not the sclera ROI the app targets. |

## On-device feasibility

No suitable artifact exists, so no inference runtime (TensorFlow Lite / LiteRT) was added; adding one would only enlarge the APK. To add a model later:

1. Place `assets/models/<hemoglobin|bilirubin>/model.tflite` and `metadata.json` (fields parsed by `ModelArtifactMetadata.parse`: name, version, biomarker, input_shape `[1,H,W,3]`, input_dtype, channel_order, normalization, output_shape (scalar), output_unit, runtime, training_label_source, subjects, samples, `subject_level_split: true`, validation_status, limitations).
2. Add the LiteRT dependency, implement `HemoglobinModel`/`BilirubinModel` with the exact preprocessing in the card, run off the main thread, and set `ModelRegistry.RUNTIME_AVAILABLE`.
3. Add golden input/output fixture tests and measure cold/warm latency, memory, and APK size on a named device.
4. Experimental mode still needs a developer opt-in, and validated mode needs evidence and authorization (see the table above).

## Optical pipeline (implemented, research visualization only)

All maths lives in `core/ImageAnalysis.kt` and is covered by `ImageAnalysisTest`.

- **Capture format:** Camera2 `YUV_420_888`, 4:3 stream closest to 640×480. Converted with full-range BT.601 (JFIF): `R = Y + 1.402·Cr`, `G = Y − 0.344136·Cb − 0.714136·Cr`, `B = Y + 1.772·Cb`, clamped to 0–255. Plane row/pixel strides are honoured. The frame is rotated by `SENSOR_ORIENTATION` so it is upright, then held in memory only.
- **Pair protocol:** (1) lock exposure. With `MANUAL_SENSOR`, exposure time and ISO are copied from the converged preview result and AE is set to `OFF`; otherwise AE lock (and AWB lock if available) is used, waiting for `AE_STATE_LOCKED`. (2) Capture the ambient frame. (3) Switch the torch on with the same settings and wait for three frames reporting `FLASH_STATE_FIRED`. (4) Capture the flash frame. (5) Restore auto preview. Without flash or any lock, only an ambient frame is captured (explicit fallback mode). Each image is paired to its `CaptureResult` by `SENSOR_TIMESTAMP`.
- **Interval:** measured from sensor timestamps and shown in the technical details. The nominal 100 ms is a target only: torch warm-up typically makes it longer, and pairs more than 1,000 ms apart are rejected.
- **Hand-movement compensation:** hand-held capture always moves a few pixels between the two frames, so the translation is **estimated and corrected**, not treated as an error. Both frames are converted to band-passed (local texture minus a blurred copy), z-normalised luma, so flash shading cannot fake a match. A coarse search on a ~64-px-wide box-downsampled image (±10 % of the width) is followed by a full-resolution refinement of ±1 cell. ΔI then uses `I_flash(x+dx, y+dy)`. Measured: exact recovery of shifts up to ±10 % of the width; 68 ms for a 480×640 pair on a desktop JVM (expect several times longer on a low-end phone; it runs off the main thread).
- **Pair validation (`DifferentialAnalyzer.analyze`):** the flash comparison is skipped if sizes differ; exposure time or ISO differ by more than 2 % or were not reported; the flash frame reports not fired; the frames are more than 1,000 ms apart; movement exceeds 10 % of the frame width, or the aligned residual exceeds 0.60 (scene change, e.g. a blink); more than 5 % of ROI pixels are clipped in the flash frame; the mean linear ΔI is below 0.01; or fewer than 30 % of ROI pixels are valid. **None of these force a retake**: the ambient photo is still checked and used.
- **ΔI:** per channel on linearised values: inverse sRGB transfer to [0,1], then `ΔI = I_flash − I_ambient`. The phone ISP's tone curve is unknown, so linearisation is approximate and not radiometric calibration.
- **Invalid pixels:** any channel ≥ 250 in either frame, or ΔR ≤ 0, or ΔG ≤ 0.
- **EI:** `EI = ln(ΔR + ε) − ln(ΔG + ε)`, ε = 1e-4, mean and median over valid ROI pixels. It is computed only when the pair is accepted.
- **ROI:** a fixed manual guide `NormalizedRect(0.14, 0.34, 0.86, 0.66)` that matches the on-screen box. **No landmark model**: MediaPipe Face Mesh is not in the project, and a face mesh would not identify the palpebral conjunctiva or neonatal sclera reliably anyway. The worker confirms placement.
- **Quality gate (`ImageQualityChecker`, heuristic thresholds):** dimension < 64 px; mean luma < 40 or > 215; > 10 % clipped ROI pixels; ROI luma SD < 3 (target missing/uniform); sharpness (the larger of ROI and whole-frame Laplacian variance) < 8 (blur). Sharpness uses the whole frame because the conjunctiva and sclera are smooth; hand-shake blur smears the entire frame. Movement between the pair frames is never blocking (see above). Passing the gate says nothing about the accuracy of any biomarker estimate.

None of these features feed any estimate. They are displayed under "Technical details" for research and supervision only.
