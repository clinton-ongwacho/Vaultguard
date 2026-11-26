# Security Summary

**Reviewed Files**: 48
**Issues Resolved**: 10

### Issues Resolved
| Area | File | Line | What Was Happening | Impact | Status | How It Was Fixed |
|---|---|---|---|---|---|---|
| Session Photo Encryption | `app/src/main/java/com/example/security/IntruderPhotoCapturer.kt` | 91 | Front-camera session captures were stored in plaintext JPEG files inside `session_photos/` without media indexing suppression. | High | Fixed | Added `.nomedia` creation, encrypted captured session images with AES-256-GCM (`VaultFileCrypto`) into `.vgem` files, deleted temporary JPEGs immediately, and decrypted photos only in memory for authorized display. |
| Encrypted Config Storage | `app/src/main/java/com/example/data/repository/VaultRepository.kt` | 64 | The stealth wake word and dialer PIN were persisted unencrypted inside the local Room `admin_config` table. | Medium | Fixed | Added Keystore-backed AES-256-GCM helpers in `SecretCipher` (`vgc1:`) and transparent encode/decode at the `VaultRepository` database boundary, with automatic migration of existing plaintext configs. |
| Password Hashing & Throttling | `app/src/main/java/com/example/security/PasswordHasher.kt` | 10 | Password derivation used 10,000 PBKDF2 iterations without clearing character buffers, and wrong password retries had no rate limit. | High | Fixed | Upgraded PBKDF2-HMAC-SHA256 to 120,000 iterations (`v2$` format) with legacy hash auto-upgrade, wiped password buffers in `finally`, and added progressive cooldown throttling after 5 failed attempts. |
| Stealth Search Wake Gate | `app/src/main/java/com/example/StealthSearchActivity.kt` | 22 | An empty `ACTION_SEARCH` query (`query.isEmpty()`) bypassed wake-word matching and summoned VaultGuard while disguised. | High | Fixed | Changed matching logic to require `query.isNotEmpty()` alongside an exact configured wake word or dialer trigger match. |
| OTA Download & Redirect Safety | `app/src/main/java/com/example/ota/OtaUpdateManager.kt` | 69 | Update downloads only verified an `https://` prefix on the initial URL, lacked a stream size cap, and flagged `hasUpdate = true` for untrusted releases. | High | Fixed | Added `ApkSecurityVerifier.isValidOtaDownloadUrl` blocking cleartext, embedded userinfo, non-443 ports, `localhost`, `.local`, and private/link-local IP ranges, added a redirect-checking OkHttp network interceptor, capped APK downloads at 200 MB, and suppressed `hasUpdate` when signature or URL checks fail. |
| Vault Path Confinement & Cleanup | `app/src/main/java/com/example/data/VaultMediaManager.kt` | 218 | Decrypted stream filenames used raw extensions up to 8 characters without alphanumeric filtering or canonical directory confinement checks, and startup did not clear stale crash temp previews. | Medium | Fixed | Enforced strict alphanumeric extension filtering, verified canonical path containment within private vault, stream, preview, and `filesDir` directories, and cleared stale decrypted temp files on app startup in `VaultGuardApp`. |
| Unexported Main Entry & Backups | `app/src/main/AndroidManifest.xml` | 48 | `MainActivity` was exported directly (exposing stealth wake extras) and backup XML files lacked explicit rules, with no explicit cleartext-disabled network security config. | Medium | Fixed | Set `MainActivity` `android:exported="false"` (preserving exported launcher aliases), created `network_security_config.xml` disabling cleartext traffic, and excluded all private app domains in `backup_rules.xml` and `data_extraction_rules.xml`. |
| Diagnostic Log & PII Redaction | `app/src/main/java/com/example/data/cloud/FirestoreErrorHandler.kt` | 30 | Auth user IDs, email addresses, provider emails, and raw exception strings were written directly to Android logcat in all builds. | Medium | Fixed | Removed user IDs, emails, and raw exception messages from `FirestoreErrorHandler`, masked user path segments, and gated diagnostic logs behind `BuildConfig.DEBUG` across auth, camera, and ViewModel classes. |
| Unrelated Project OAuth Records | `app/google-services.json` | 7 | Configuration contained OAuth client records and package entries belonging to two unrelated projects. | Low | Fixed | Removed the two unrelated client entries so only `com.aistudio.vaultguard.kxmpzq` remains configured. |
| GitHub Repository Hygiene | `.gitignore` | 18 | Root and app `.gitignore` files lacked comprehensive ignore patterns for `.env.*`, keystores, certificates, APKs/AABs, and local Firebase caches. | Medium | Fixed | Expanded `.gitignore` and `app/.gitignore` to block local secrets, keystores, build binaries, logs, and Firebase caches before pushing to GitHub. |

### Safeguards Verified
| Area | File | How It's Protected |
|---|---|---|
| Database Queries | `app/src/main/java/com/example/data/db/VaultDao.kt` | Uses parameterized Room SQL queries (`@Query` bindings and `@Insert`/`@Update`) with zero dynamic SQL concatenation. |
| Encrypted Vault Media | `app/src/main/java/com/example/security/VaultFileCrypto.kt` | Encrypts all stored vault files and session captures in 64 KB chunks using AES-256-GCM backed by AndroidKeyStore with unique random IVs per chunk. |
| Cloud Data Access Control | `firestore.rules` | Enforces authenticated Google Sign-In ownership checks (`isOwner(userId)`) on user documents and subcollections, validates field types and lengths, and restricts APK release publishing to the publisher's UID. |
| Google Authentication | `app/src/main/java/com/example/security/GoogleAuthHelper.kt` | Uses Jetpack Credential Manager with Firebase Google Auth tokens rather than hardcoded credentials or anonymous login. |
| Screen Capture Protection | `app/src/main/java/com/example/MainActivity.kt` | Enforces `WindowManager.LayoutParams.FLAG_SECURE` across both `MainActivity` and `GatekeeperActivity` to block screenshots and Recents thumbnails. |

---

## PoC Verification

### Plaintext Session Photo Exposure

#### Summary
| Field | Value |
|---|---|
| Issue Type | Unencrypted Sensitive Media on Disk |
| Impact | High |
| Affected File | `app/src/main/java/com/example/security/IntruderPhotoCapturer.kt:91` |
| Test Scenario | Trigger front-camera session capture for restricted app `org.example.chat` and inspect storage on disk vs UI decryption path |

#### How It Was Fixed
Session photos are captured to a temporary cache file, immediately encrypted with AES-256-GCM into a `.vgem` file inside `filesDir/session_photos/` (alongside `.nomedia`), and the temporary JPEG is wiped. `VaultRepository.decryptSessionPhotoBytes` verifies the file stays within `filesDir` and decrypts bytes in memory for `DashboardScreen`.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | `IntruderPhotoCapturer.captureFrontPhoto` allocates `session_<ts>_<pkg>.vgem` in `filesDir/session_photos` and ensures `.nomedia` exists | Storage prepared |
| 2 | Camera capture or YUV fallback passes bytes into `VaultFileCrypto.encryptBytes` at `IntruderPhotoCapturer.kt:284` and deletes temporary JPEG in `finally` | Only `VGEM1` ciphertext remains on disk |
| 3 | `DashboardScreen` calls `VaultRepository.decryptSessionPhotoBytes` which confines path inside `filesDir` and decrypts bytes into memory for `AsyncImage` | **Handled Safely** |

#### Conclusion
**Fix Verified** - Raw session capture images are never retained in plaintext on disk and cannot leak outside the private app directory.

---

### Weak Password Derivation and Unrestricted Brute-Force Attempts

#### Summary
| Field | Value |
|---|---|
| Issue Type | Weak Key Derivation and Missing Rate Limit |
| Impact | High |
| Affected File | `app/src/main/java/com/example/data/repository/VaultRepository.kt:213` |
| Test Scenario | Submit 5 consecutive wrong passwords via `verifyAdminPassword`, followed immediately by the valid admin password |

#### How It Was Fixed
`PasswordHasher` now hashes new passwords with 120,000 PBKDF2-HMAC-SHA256 iterations (`v2$`) and zeroes `CharArray`/`PBEKeySpec` buffers. `VaultRepository.verifyAdminPassword` tracks consecutive failures and activates progressive cooldowns starting at 5 failures.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Five wrong passwords enter `VaultRepository.verifyAdminPassword` at `VaultRepository.kt:213` | Failure counter reaches 5 and sets cooldown timestamp |
| 2 | Subsequent password check checks `getRemainingAuthLockoutSeconds()` at `VaultRepository.kt:215` | Remaining cooldown > 0s detected |
| 3 | `verifyAdminPassword` returns `false` early without validating until cooldown expires | **Handled Safely** |

#### Conclusion
**Fix Verified** - Offline password cracking cost is raised 12x with secure buffer zeroing and online brute-force attempts are throttled automatically.

---

### Blank Intent Bypass in Stealth Search Activity

#### Summary
| Field | Value |
|---|---|
| Issue Type | Authentication Bypass via Empty Intent Query |
| Impact | High |
| Affected File | `app/src/main/java/com/example/StealthSearchActivity.kt:22` |
| Test Scenario | Send `Intent(Intent.ACTION_SEARCH)` with `SearchManager.QUERY = "   "` to `StealthSearchActivity` while disguised |

#### How It Was Fixed
Removed the `query.isEmpty() ||` branch so `StealthSearchActivity` only triggers `MainActivity` when the trimmed search query is non-empty and matches the configured wake word or dialer secret.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | `ACTION_SEARCH` intent arrives at `StealthSearchActivity.onCreate` (`StealthSearchActivity.kt:21`) with blank query | `query` trims to `""` |
| 2 | `matched` evaluates `query.isNotEmpty() && (...)` at `StealthSearchActivity.kt:22` | Evaluates to `false` |
| 3 | `startActivity(wakeIntent)` is skipped and `finish()` closes the activity immediately | **Handled Safely** |

#### Conclusion
**Fix Verified** - Blank or missing search intents cannot reveal the disguised VaultGuard console.

---

### Unsafe OTA Download URL / Redirect Handling

#### Summary
| Field | Value |
|---|---|
| Issue Type | Unsafe Remote Update URL & Unbounded Download |
| Impact | High |
| Affected File | `app/src/main/java/com/example/ota/ApkSecurityVerifier.kt:43` |
| Test Scenario | Supply `http://...`, `https://169.254.169.254/latest/meta-data/`, or `https://127.0.0.1/v2.apk` to OTA validation or HTTP redirect |

#### How It Was Fixed
`ApkSecurityVerifier.isValidOtaDownloadUrl` restricts OTA URLs to HTTPS on standard port 443 with no embedded credentials, and rejects `localhost`, `.local`/internal suffixes, and private, loopback, or link-local IP literals. `OtaUpdateManager` applies this check on release observation, inside an OkHttp network interceptor (blocking unsafe redirects), and caps downloaded APK size at 200 MB.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Candidate URL `https://169.254.169.254/latest/meta-data/` enters `isValidOtaDownloadUrl` (`ApkSecurityVerifier.kt:43`) | URI parsed as IPv4 literal |
| 2 | Address inspection at `ApkSecurityVerifier.kt:69` detects link-local range `169.254.0.0/16` | Validation returns `false` |
| 3 | `OtaUpdateManager` suppresses `hasUpdate` and aborts any download or redirect targeting the blocked host | **Handled Safely** |

#### Conclusion
**Fix Verified** - Untrusted update metadata, cleartext downgrades, local/metadata network redirects, and oversized downloads are rejected before writing or installing APKs.

---

### Vault Media Extension Sanitization & Path Traversal

#### Summary
| Field | Value |
|---|---|
| Issue Type | Path Traversal / Unsanitized Filename Handling |
| Impact | Medium |
| Affected File | `app/src/main/java/com/example/data/VaultMediaManager.kt:218` |
| Test Scenario | Request session photo read at `filesDir/../cache/outside_secret.txt` or vault item with traversal characters in filename/ID |

#### How It Was Fixed
`VaultRepository.resolveConfinedFilesDirFile` and `VaultMediaManager.resolveVaultFile` validate canonical paths against their private root directories and enforce strict alphanumeric filename/extension patterns. `VaultGuardApp.onCreate` clears any leftover temporary previews at startup.

#### Step-by-Step Trace
| Step | What Happens in the Code | Outcome |
|---|---|---|
| 1 | Path `filesDir/../cache/outside_secret.txt` is passed to `VaultRepository.decryptSessionPhotoBytes` (`VaultRepository.kt:599`) | Entered path confinement helper |
| 2 | `resolveConfinedFilesDirFile` (`VaultRepository.kt:584`) resolves canonical path `/data/.../cache/outside_secret.txt` | Does not start with `filesDir.canonicalPath + "/"` |
| 3 | Method returns `null` immediately without reading the external file | **Handled Safely** |

#### Conclusion
**Fix Verified** - Reads and writes for vault media and session captures remain strictly confined inside their intended private directories.
