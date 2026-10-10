# Final demo and release checklist

**Build identity:** AbhayNayan 0.1.0-final-demo · **Verification:** 9–10 October 2026  
**Scope:** Runnable FastAPI/PWA prototype, build-verified native Android debug APK and source project, and release archive. Not a clinical product.

## Before a live presentation

- [x] Automated web/backend tests and Android Gradle build/unit tests recorded below.
- [x] Started the API/PWA from `scripts/run_local.sh`; `/` and `/api/health` returned HTTP 200 in the local smoke check. Open the target browser before a live presentation.
- [ ] Use only seeded synthetic profiles and the abstract synthetic image for the displayed PWA fixture result. In Android, a Camera2 preview or abstract color-chart pattern may demonstrate local processing, but the separately selected fixed result must be labelled `NOT FROM IMAGE`.
- [ ] Point out that image processing is local; it does not generate the numeric estimate. Android F−A and ROI features are engineering visualizations, not a biomarker model.
- [ ] Show the safety boundary, selected DEMO fixture, newborn age-in-hours context, and English/Hindi/Bhojpuri fallback.
- [ ] Show local FHIR JSON/referral print or server PDF, offline outbox, then PHC demo dashboard.
- [ ] Do not use a real patient record, photo, or raw Drive dataset in a public presentation.
- [ ] Make no diagnostic, clinical accuracy, battery, latency, fairness, or production-readiness claims.

## Completed release evidence

- [x] Project plan and architecture boundary recorded.
- [x] FastAPI safety schemas, SQLite demo store, routes, and server-side synthetic-profile guards implemented.
- [x] PWA workflows wired for maternal and newborn synthetic profiles; neonatal demo includes age in hours.
- [x] Local English/Hindi/Bhojpuri fallback counselling.
- [x] Pseudonymous synthetic sync and PHC demo dashboard.
- [x] Local FHIR-style Bundle and referral PDF/print flow.
- [x] Dataset audit and disabled research-only anemia baseline artifact; raw data excluded.
- [x] Dockerfile/Compose configuration and `.env.example` provided.
- [x] Kotlin/Compose/Camera2/Room source, Gradle 8.13 wrapper, Android CI workflow, and native geometric ROI/differential/feature pipeline included.
- [x] Android build on 10 October 2026: `:app:testDebugUnitTest` and `:app:assembleDebug` both succeeded using JDK 17 / Android SDK 36. Kotlin package/import syntax for the reserved `in` segment was corrected.
- [x] Android unit tests: **19 passed** (9 `DemoInferenceTest`, 10 `OpticalPipelineTest`); zero failures, errors, or skipped tests.
- [x] Debug APK metadata and v2 debug signature verified. It is a development APK, not release-signed; no device/emulator install or runtime camera test has been performed.
- [x] Refreshed release ZIP `../AbhayNayan-0.1.0-final.zip` includes the debug APK, source, and tests; the packager validates ZIP integrity and writes an adjacent SHA-256 sidecar. Generated SQLite files, real `.env` files, dependency/build caches, and unrelated generated outputs are excluded.
- [x] `scripts/verify_all.sh` completed successfully on 9 October 2026 (web/backend checks): 60 Python tests, 29 Node.js tests, 1 JSDOM workflow smoke, plus Python compile, dependency, and web syntax checks.
- [x] JSDOM exercised maternal and newborn synthetic flows, newborn age-in-hours counselling context, safety copy, and local history. This is a simulated DOM test, not a real browser/device test.

## Last verified automated checks

- Python: **60 passed** (`python -m pytest -q`).
- Python compile check: `python -m compileall -q backend tests` passed.
- Python package check: `python -m pip check` reported no broken requirements.
- Web modules: **29 passed** (`npm run test:web`).
- JSDOM workflow: **1 passed** (`npm run test:ui`) — maternal and newborn pathways in a simulated browser DOM.
- Frontend syntax: `npm run check:web` passed.
- Android: **19 passed** (`:app:testDebugUnitTest`); `:app:assembleDebug` completed successfully.
- Pytest emits one Starlette/httpx deprecation warning; npm reports a deprecated transitive `whatwg-encoding` package. Android reports a deprecated Camera2 API compiler warning; it does not fail the build.

## Verification not completed

- [ ] Real desktop/mobile browser manual acceptance run and camera permission/orientation test.
- [ ] Android Studio GUI launch/import; Gradle compilation/tests were run directly through the same project wrapper/build tasks.
- [ ] Android install/runtime checks on a physical device or emulator: camera permission, Camera2 lifecycle, torch/ambient fallback, image quality retry, Room persistence, TTS language availability, accessibility, and memory cleanup. No device was attached during this build.
- [ ] Remote Android CI workflow; it is configured but has not been executed in GitHub Actions.
- [ ] Docker image build and container health check; Docker is unavailable in this environment.
- [ ] Optional live Gemini call; no key has been used in this build.
- [ ] Clinical validation, prospective study, device/lighting validation, regulatory review, and production security review; none are claimed.

## Non-claims and release blockers for any clinical deployment

1. No approved or clinically validated hemoglobin or bilirubin model, clinical threshold, or patient-use workflow is supplied. Visible numeric values are fixed synthetic fixtures.
2. Camera/import analysis is local quality/feature visualization only; estimates are never computed from image pixels.
3. Dataset license, consent, provenance, and intended reuse remain unverified; do not redistribute raw data.
4. No production authentication, authorization, audit-grade retention, clinical governance, hosting, or regulatory assurance is included.
5. A debug APK is not a store release and is not evidence of clinical readiness or successful behavior on target devices.

Any unchecked verification item remains an explicit limitation; do not describe it as complete.
