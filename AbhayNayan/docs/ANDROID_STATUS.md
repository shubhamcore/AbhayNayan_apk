# Android source and build status

**Updated: 10 October 2026 (version `0.3.0-demo`).** The native app builds; 111 unit tests pass (plus a live-backend report test run against contract 1.0 and 0.1 servers); lint reports 0 errors; every record gets an on-device PDF report; and a debug APK was produced, signature-checked, and scanned for secrets. **The app has not been installed or run on a device or emulator.** It is a prototype, not a clinical tool.

Details:

- What was found before this update: [ANDROID_AUDIT.md](ANDROID_AUDIT.md)
- Build commands, evidence, APK checks, and device smoke-test steps: [ANDROID_BUILD.md](ANDROID_BUILD.md)
- Backend endpoints used and proposed contract changes: [ANDROID_API_INTEGRATION.md](ANDROID_API_INTEGRATION.md)
- Model candidates, why none is deployed, and the optical pipeline maths: [ANDROID_ML_STATUS.md](ANDROID_ML_STATUS.md)
- Features, configuration, troubleshooting: [../android/README.md](../android/README.md)

## Summary of the native app

- Separate maternal and newborn workflows (profile → details → prepare → capture → checks → analysis → result → counselling/referral), ViewModel-held state, and back/cancel/retake/retry/save.
- Result modes `DEMO_SYNTHETIC`, `EXPERIMENTAL_RESEARCH_ONLY`, `VALIDATED_FOR_INTENDED_USE`, `DISABLED_NO_VALID_MODEL`; no model is bundled, so real profiles always get `DISABLED_NO_VALID_MODEL`.
- Camera2 `ImageReader` capture with exposure held constant (manual sensor or AE/AWB lock) for an ambient + torch pair, plus metadata-based pair validation; ambient-only fallback otherwise.
- Room v2 offline queue (auto-migrated from v1) with WorkManager sync to `/api/sync`; counselling via `/api/counselling` with on-device fallback; referral via `/api/referrals`.
- `INTERNET` permission is now declared (backend sync/counselling only); images are still never uploaded or saved.
