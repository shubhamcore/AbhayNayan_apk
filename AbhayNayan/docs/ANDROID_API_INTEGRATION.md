# Android ↔ backend integration

The website backend (`hrdkkmr/AbhayNayan_web`, `backend/app/main.py`, `backend/app/models/schemas.py`) is the contract. `docs/API.md` documents the endpoints; this file covers only how the Android client uses them and what is missing for the mobile workflows. No endpoint was invented.

## Configuration

| Build | Default base URL | Cleartext |
|---|---|---|
| debug | `http://10.0.2.2:8000` (emulator → host). Override: `-PabhaynayanDebugApiUrl=…` | Allowed only to `10.0.2.2`, `127.0.0.1`, `localhost` (`src/debug/res/xml/network_security_config.xml`) |
| release | none (backend disabled until configured). Override: `-PabhaynayanReleaseApiUrl=https://…` (Gradle rejects non-HTTPS) | Never |

The URL can be changed in **Settings › Backend server**, validated by `BackendUrlPolicy`. Physical device + local server: `adb reverse tcp:8000 tcp:8000`, then use `http://127.0.0.1:8000` in a debug build. Allowed CORS origins do not matter for the native client.

No API key is stored in or sent by the app. Gemini is called only by the backend.

## Endpoints used

| Endpoint | Android use | Code |
|---|---|---|
| `GET /api/health` | Server status on Home/Settings, Gemini-configured flag | `ApiClient.health` |
| `POST /api/sync` | Offline queue upload, batches ≤ 100 | `SyncEngine`, `Payloads.screeningRecord` |
| `POST /api/counselling` | Counselling wording for synthetic demo records | `CounsellingService` |
| `POST /api/referrals` | Server referral brief for demo records; PDF opened at `GET /api/referrals/{id}/pdf` | `AppViewModel.createReferral` |
| `GET /api/dashboard` | Aggregate counts in Settings | `ApiClient.dashboard` |
| `GET /api/screenings` | Client method only (`recentScreenings`); not shown in the UI | `ApiClient.recentScreenings` |

Not used: `/api/screenings` POST (the app syncs locally created records instead), `/api/fhir` (FHIR is built locally with the same resource set and ID derivation as `FHIRGenerator`; `PayloadsTest` checks the IDs against values computed by the Python code), and `/api/vision/*` (images never leave the phone).

## Payload rules

- Bodies contain exactly the fields of the Pydantic models (`extra='forbid'`). `PayloadsTest` asserts the key sets.
- `patient` is `{patient_id, demo: true}` with a random pseudonymous ID (`ABH-XXXXXXXX`). Nicknames are never sent.
- `result.confidence` / `result.quality_score` are the fixture placeholders, the same as the website PWA. They are not measurements.
- `worker_id` / `worker_name` use the pseudonymous worker code from Settings.
- `fhir_bundle` is `null`; the server regenerates it.

## Reliability

- **Timeouts:** connect 8 s, read 15 s; response size capped at 2 MB; redirects not followed.
- **Errors:** `ApiResult.Failure` with a user message. Response bodies, stack traces, and URLs are never shown. 408/429/5xx/timeouts/unreachable/invalid JSON are retryable; 400/401/403/404/422 are not.
- **Duplicate safety:** client-generated record IDs (server `INSERT OR IGNORE` + `synced_ids`); rows are claimed `PENDING → SYNCING` before sending; a mutex prevents overlapping runs; WorkManager unique work uses `KEEP`; interrupted `SYNCING` rows are reset on the next run.
- **Retries:** WorkManager (network constraint, exponential backoff from 30 s); a record becomes `FAILED` after 8 attempts or a validation rejection. A batch rejected with 422 is retried record by record to isolate the bad one. Users can retry `FAILED` records from the record screen.
- **Counselling:** on-device text is shown at once; server text replaces it when available; identical concurrent requests share one call; results are cached per record and language. Referral urgency always comes from the deterministic `ReferralRules`, never from generated text.

All of this is covered by `ApiClientTest`, `SyncEngineTest`, and `CounsellingServiceTest` against a local mock HTTP server.

## Gaps the website developer would need to close

The current backend accepts synthetic demo records only. Real-profile records, and records without an estimate (the normal Android result while no model exists), therefore stay `LOCAL_ONLY` on the phone. Proposed changes, **not implemented** on either side:

1. **Record without estimate.** Allow `ScreeningResult.estimate` to be `null` when `model_mode == "DISABLED_NO_VALID_MODEL"`, and add `result_mode` (`DEMO_SYNTHETIC | EXPERIMENTAL_RESEARCH_ONLY | VALIDATED_FOR_INTENDED_USE | DISABLED_NO_VALID_MODEL`), `quality_status` (string ≤ 120), `referral_urgency` (`URGENT_REFERRAL | PROMPT_CLINICIAN_REVIEW | CONFIRMATORY_TESTING`), and `metadata` (`{pregnancy, gestational_week, infant_age_hours, gestational_age_at_birth_weeks, visible_jaundice, danger_signs[]}`).
2. **Non-demo sync** requires authentication (per-worker tokens), audit logging, retention policy, and a governance decision before `patient.demo == false` is accepted.
3. **Counselling without estimate:** accept `estimate: null` with `result_mode: DISABLED_NO_VALID_MODEL` and return "get the confirmatory test" wording.
4. **Referral `worker_name`:** rename to `worker_code` (pseudonymous) to discourage personal names.
5. **Version endpoint:** add `api_version` to `/api/health` so clients can detect contract changes.
6. **Referral PDF labelling (`backend/app/services/referral_pdf.py`):** the PDF row reads "Estimated bilirubin/Hb screening value 12.8 mg/dL" with no "synthetic/demo" wording in that row (only the tag/mode rows and footer say so). Relabel it, e.g. "SYNTHETIC DEMO VALUE (not measured)", so a printed brief cannot be mistaken for a laboratory result. Verified on 10 October 2026 by generating a PDF through the Android client against the local backend.
