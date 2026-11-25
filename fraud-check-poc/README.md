# Fraud Check PoC (Android, Kotlin)

A small app that runs the client-side fraud safety-net checks during a mock loan form and shows a local risk report.
It is for testing the checks on real phones, emulators, rooted phones and cloner apps before building the production SDK.

**Local score is a preview only.** In production the backend verifies the Play Integrity token and key attestation chain and makes the decision.

## Open and run
1. Open this folder in Android Studio (Ladybug or newer). It uses AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 24.
2. Let Studio create the Gradle wrapper if asked (or run `gradle wrapper --gradle-version 8.9`). CI builds the APK with the `Build Fraud Check PoC APK` workflow on the `fraud-check-poc` branch.
3. Optional, for Play Integrity: link a Google Cloud project to the app in Play Console, put its project number in `gradle.properties` (`cloudProjectNumber=...`), and install the app from a Play internal testing track (Android 13+ only returns the full verdict for Play-installed apps).
4. Run on a device. Hold the phone, type the name, PAN and phone fields normally, tap **Run safety checks**, allow approximate location once.
5. **Share report (JSON)** exports the signals for your backend team.

## What it checks
| File | Checks |
| --- | --- |
| `checks/IntegrityCheck.kt` | Play Integrity standard request bound by `requestHash`; hardware key attestation (TEE/StrongBox) with challenge |
| `checks/DeviceChecks.kt` | Root traces, emulator traces, debugger, Frida/Xposed/LSPosed in memory |
| `checks/ClonerCheck.kt` | Data dir is not `/data/{data,user/N}/<our package>` (virtual-space cloners), shared UID, OEM dual-app user IDs, known cloner apps |
| `checks/LocationCheck.kt` | Mock location on a one-time COARSE fix, fake GPS apps, SIM country |
| `checks/NetworkCheck.kt` | VPN, HTTP proxy |
| `checks/SensorRecorder.kt` | Accelerometer/gyroscope present and not perfectly constant while filling the form |
| `checks/BatteryCheck.kt` | 100% and plugged in, or 0 C temperature |
| `checks/CameraCheck.kt` | No front camera, external camera (weak virtual-camera hint) |
| `checks/BehaviourTracker.kt` | Pastes/autofill into KYC fields, scripted typing rhythm, identical touches (no text or clipboard content stored) |
| `core/RiskScorer.kt` | Weighted 0-100 score, hard stops, bands (<35 continue, 35-69 step-up, 70+ reject/review), JSON export |

## Deliberately NOT included (Google Play personal loan policy + RBI)
No `READ_SMS`, `READ_CALL_LOG`, `READ_CONTACTS`, `ACCESS_FINE_LOCATION`, `READ_PHONE_NUMBERS`, `QUERY_ALL_PACKAGES`, storage or media permissions.
Package checks use a short `<queries>` list in the manifest instead of `QUERY_ALL_PACKAGES`. Use Account Aggregator, DigiLocker, CKYC and penny drop on the backend instead of on-device SMS/contacts forensics.

## Verification status
- All Kotlin sources compile against the real Android 15 framework classes (Robolectric `android-all` API 35 jar). AndroidX and Play Integrity were stubbed for that check because Google Maven was not reachable from the build machine, so do a full build in Android Studio.
- `RiskScorerTest` (5 tests) passes.
- Not yet run on a device.

## Next steps toward production
Move checks into an SDK module, get `sessionId` from the backend, POST the JSON + integrity token + attestation chain to the backend verifier, add the liveness vendor SDK for the selfie step, and obfuscate with R8.
