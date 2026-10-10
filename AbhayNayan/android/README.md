# Native Android prototype

This is a Kotlin/Compose Android shell for the AbhayNayan demo. It uses Room, Camera2 preview snapshots, a manual target guide, a local brightness/edge quality proxy, fixed synthetic case fixtures, and Android TextToSpeech. It has no network permission, no image upload, no active clinical model, and no patient-image persistence. The result numbers are fixed synthetic fixtures, never generated from camera pixels or optical features.

## Build and test

Verified 10 October 2026 with JDK 17, Gradle 8.13, and Android SDK platform 36. The Kotlin package/import syntax was fixed for the reserved `in` package segment, and Gradle memory settings were tuned for this constrained build environment.

From this directory:

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

**Verification result:** build successful; 19 Android unit tests passed (9 `DemoInferenceTest` + 10 `OpticalPipelineTest`), no failures/errors/skips. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The same Gradle project and tasks Android Studio uses were verified from the command line; Android Studio's GUI was not launched. No emulator or physical device was attached, so runtime/camera testing is still outstanding.

The APK included in the release archive is debug-signed and is for development/demo installation only, not a store/release build. Read `../docs/ANDROID_STATUS.md` for exact build evidence and remaining device checks.

## Open in Android Studio

1. Extract the ZIP and choose **File → Open**, selecting the extracted `AbhayNayan/android` folder (the folder containing `settings.gradle.kts`).
2. In **Settings/Preferences → Build, Execution, Deployment → Build Tools → Gradle**, select JDK 17 for the Gradle JDK. Install Android SDK Platform 36 if prompted and accept the SDK licenses.
3. Wait for Gradle sync to finish. Run the `:app:testDebugUnitTest` and `:app:assembleDebug` tasks from the Gradle tool window, or use the command above in the Android folder's terminal.
4. The generated APK appears at `app/build/outputs/apk/debug/app-debug.apk`.

`local.properties` is machine-specific and intentionally not included; Android Studio creates it for the local SDK installation.

## Install on an Android phone

1. Download and extract the release ZIP.
2. Copy `AbhayNayan/android/app/build/outputs/apk/debug/app-debug.apk` to the phone (USB, cloud drive, or another trusted transfer method).
3. Open the APK on the phone. If prompted, allow the file manager/browser to **Install unknown apps** for this one installation, then confirm installation.
4. Open **AbhayNayan Demo**. Camera permission is optional; the abstract synthetic chart can exercise the local feature pipeline without taking a photo. Use synthetic demo cases only.

Android may block sideloading by default. Only install an APK you obtained from the release ZIP you trust. This debug APK has no Google Play distribution/signing guarantee.

## Native demo flow

1. Choose the synthetic demo profile or create a local-only profile.
2. Select maternal anemia or neonatal jaundice.
3. Grant optional Camera permission. Camera2 captures an ambient `TextureView` preview snapshot and, when supported, a short torch-on preview snapshot; if flash fails, it falls back to ambient-only. This is not a still-photo pair and does not lock exposure or ISO.
4. Review the basic brightness/edge engineering check, manually confirm the geometric guide, and inspect the local ROI, F−A visualization, RGB/Lab*/YCrCb features, and measured image-processing time. There is no face/eye landmark detector or anatomical ROI model.
5. A fixed demo case may follow the camera flow only for a synthetic demo profile. The case value is selected independently of camera pixels/features and is labelled `FIXED DEMO VALUE · NOT FROM IMAGE`. Local/non-demo profiles cannot save a fixture result.
6. Review the safety notice; use English/Hindi/Bhojpuri deterministic counselling/TTS and share local FHIR-style JSON/referral text. Temporary image buffers are held in memory for the local preview and are not stored with the screening.

The native source is a focused shell; it does not have parity with every PWA dashboard, sync, Gemini workflow, or registration field. The model interface is not active and no clinically validated inference model is included. Do not use results for diagnosis, triage, care decisions, or to replace CBC, serum bilirubin testing, local protocol, or clinical judgment. Confirm only under appropriate clinical testing/protocol. See `../docs/SAFETY.md` and `../docs/ANDROID_STATUS.md`.
