# Native distribution

TeamCode targets macOS Apple Silicon/Intel, Linux ARM64/x64 and Windows x64. The CLI is a Kotlin/Native executable and needs no JVM. This page describes the versioned build/release path. `teamcode update --check` checks signed metadata; self-update installation, rollback and installer automation remain V1 work.

## Build identity

Gradle uses `-PteamcodeVersion=0.1.0-alpha.1` (or the `ORG_GRADLE_PROJECT_teamcodeVersion` environment variable). The default remains `0.1.0-SNAPSHOT`. A leading `v` is accepted for tag builds; versions use bounded semantic version numbers and optional prerelease identifiers, without build metadata. Invalid values fail before compilation.

The API module generates `BuildInfo.VERSION` from the Gradle project version. Both native `teamcode version` and the server capabilities `serverVersion` use it. It is a build input, not a runtime environment override. A fixture-server smoke verifies that the actual native executable and packaged server report the requested version.

## Build artifacts and publication

The Build workflow compiles optimized **release** executables and runs the existing native authentication, TLS/streaming, launcher, cancellation, guidance and service acceptance before staging assets. Runtime acceptance runs on actual target hosts, including macOS Intel and Linux ARM64. Linux ARM64 is cross-compiled on an x64 host, then downloaded and executed on the ARM64 runner; [Kotlin/Native does not support an ARM64 Linux compiler host](https://kotlinlang.org/docs/native-target-support.html#hosts). macOS Intel is cross-compiled on Apple Silicon to reduce compiler time, then downloaded and tested on a real Intel runner. No target is published based on cross-compilation alone. The pinned host labels are listed in [GitHub's runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).

Each target stages a raw binary and private build metadata. Staging checks the native ELF/Mach-O/PE architecture, size, actual `version` execution, source commit and SHA-256. The assembly job requires all five distinct targets from exactly one version/commit, verifies every hash/header again, rejects missing/unexpected files and produces:

- `teamcode-VERSION-macos-arm64`
- `teamcode-VERSION-macos-x64`
- `teamcode-VERSION-linux-arm64`
- `teamcode-VERSION-linux-x64`
- `teamcode-VERSION-windows-x64.exe`
- `manifest.json`: schema version, version/tag, source commit and target-specific filename/hash/size.
- `SHA256SUMS`: binary and manifest hashes.
- Published version releases additionally include `manifest.sig`, an RSA-PSS-SHA256 signature envelope over the exact manifest bytes, with a pinned key fingerprint.

Successful main/PR runs retain a `teamcode-release-bundle` Actions artifact for seven days. A snapshot artifact is development evidence, not a stable release. Publishing a tag `vVERSION` runs all acceptance again with that exact version and publishes a GitHub release only after the combined bundle, JVM/replica, Helm and image jobs pass and its manifest signature verifies against the independently pinned release key. Missing/mismatched signing credentials prevent publication. Snapshot bundles remain unsigned development artifacts. Assets are uploaded to a draft first; the release becomes public only after successful upload. Prerelease versions are marked as GitHub prereleases. Existing releases are not overwritten automatically; publication failure must be investigated instead of replacing assets silently.

Main pushes keep publishing the tested server image to `nathanfallet/teamcode:latest` and `sha-COMMIT`. Tag pushes publish `nathanfallet/teamcode:vVERSION` and `sha-COMMIT-vVERSION`, preserving `latest` and the main snapshot's commit tag. This prevents rebuilding a tagged version from silently replacing a snapshot image identified by the same source commit. Helm continues to default to `latest`.

## Manual installation boundary

For a published version, choose the binary for the actual OS/CPU and verify its SHA-256 against the release manifest/checksums before execution. Rename it to `teamcode` (`teamcode.exe` on Windows) and put it in a stable user-owned directory on PATH. POSIX downloads require executable permission (`chmod 755 teamcode`). Keep the same installed path when registering a user service. Never copy a personal TeamCode configuration into a release bundle.

Linux binaries use platform shared libraries, including glibc/libcurl and `libcrypt.so.1`; these are not advertised as fully static builds. OS/CPU support is accepted only after its actual runner checks pass. Apple Developer notarization and Windows Authenticode signing are not currently provided. The publisher signature is independent of platform executable signing. `SHA256SUMS` is a convenience; authenticated artifact identity comes from the signed manifest and its independently pinned release key. The native `verify-release` command verifies that signature using compiled public roots and optionally checks a binary against the authenticated manifest. Automated installation still remains to be implemented. Linux verification requires OpenSSL libcrypto 3 (or a compatible libcrypto 4); a missing or incompatible library fails closed.

## Remaining V1 distribution acceptance

- Complete five-target native signature/key-rotation runtime acceptance; local JVM/macOS verification alone does not accept the other native adapters.
- Implement user-owned installation, bounded authenticated release discovery/downloads, update and rollback, with a fixed stable executable path and explicit version selection.
- Preserve login/device identity and never rewrite personal Claude/Codex configuration during installation or updates.
- Drain the owned user service and handle executable replacement on Windows without stopping unrelated processes; restore working registration/binary on failure.
- Exercise fresh installation, update/rollback, offline/interrupted download, integrity refusal and restart on each target. Continue separate clean-workstation reboot/logon and pre-login service acceptance.
- Publish and download an actual prerelease through this pipeline before claiming versioned distribution is accepted end to end.

TeamClaude's `src/updater.js` and `test/updater.test.js` provide the reference for installation ownership, opt-out, bounded version checks and leaving source checkouts alone. Its npm replacement mechanism does not apply to native executable replacement. TeamCode retains native CLI/client/API boundaries and will adapt service drainage and rollback rather than invoking npm or overwriting arbitrary executables.

## Publisher signing key

The public RSA-3072 key is tracked at `release/trust/teamcode-release-2026.pem`. Its key ID is SHA-256 of the DER SubjectPublicKeyInfo. The corresponding private key is a dedicated `TEAMCODE_RELEASE_SIGNING_KEY` GitHub Actions secret, separate from all self-hosted server/team/provider credentials. Signing uses PSS with SHA-256, MGF1-SHA256 and a 32-byte salt. The signature envelope is schema version 1 and contains only algorithm, key ID and base64 signature. Publisher verification refuses another key, altered bytes, unsupported schema/algorithm or an existing signature output. Private material is written only to a private temporary file and deleted after signing; OpenSSL subprocesses do not inherit the key environment variable.

A private owner backup was created at `~/.config/teamcode-release-signing/rsa-3072-2026.pem` with owner-only permissions. It is never part of the repository, Docker image or release bundle. Fork maintainers must generate their own key, configure their own Actions secret and ship their own pinned public trust root; the self-hosted server cannot replace this trust root. Key rotation must first ship the next public trust root through an accepted release signed by an already trusted key, then change the publisher key. Common native tests exercise old/new/unknown keys, a bridge release trusting both roots, retirement of the old root and rejection of spoofed key IDs. A compromised root still requires an independently authenticated recovery channel; this implementation does not claim remote revocation of an already installed trust root.

The native verifier uses the same RSA-PSS/SHA-256 parameters through platform crypto: [Apple Security](https://developer.apple.com/documentation/security/seckeyalgorithm/rsasignaturedigestpsssha256), [Windows BCrypt](https://learn.microsoft.com/en-us/windows/win32/api/bcrypt/nf-bcrypt-bcryptverifysignature) and [OpenSSL EVP](https://docs.openssl.org/3.4/man7/RSA-PSS/). The exact bytes must be verified against a shipped public key before parsing manifest contents or executing an asset. Apple imports a build-generated PKCS#1 public key; Linux and Windows import SubjectPublicKeyInfo. The JVM test implementation uses JCE with the same fixed parameters. No private key or user-provided trust-root override is compiled into the CLI. Cross-platform runtime acceptance is recorded separately in the status ledger.


## Native verification command

Verification works offline without a TeamCode login or configured server:

```sh
teamcode verify-release --manifest manifest.json --signature manifest.sig
teamcode verify-release --manifest manifest.json --signature manifest.sig \
  --binary teamcode-VERSION-linux-x64 --target linux-x64
```

Use the actual OS/CPU target and release asset filename. Providing only one of `--binary`/`--target` fails. The command authenticates the exact bounded manifest bytes against one of the build-generated roots before reading its fields. It then validates the version/tag, source commit, canonical asset names and bounded sizes. Binary verification streams the file through SHA-256 and requires an exact length/hash match; it never executes downloaded code. An additional future target in a schema-1 manifest does not prevent verification by an older supported client, but that client cannot select an unsupported target.

The signature cannot supply a new public key. Fork maintainers replace the PEM roots under `release/trust`, rebuild, and replace the public golden smoke fixture with metadata signed by their own publisher root. `scripts/generate-release-test-fixtures.py` separately creates disposable cryptographic vectors for algorithm/rotation tests; it never uses the publisher's private key. Common tests include wrong padding, salt length and MGF digest, modified bytes, retired/unknown keys, malformed authenticated metadata and exact binary hash/length rejection. `scripts/smoke-release-verification.py` exercises the compiled publisher root and safe failures through the actual native command using temporary Unicode paths.


## Checking published versions

`teamcode update --check` explicitly checks the latest stable GitHub release, downloads its canonical `manifest.json`/`manifest.sig` and authenticates the exact bytes against the compiled publisher roots before reporting a version. `--version VERSION` checks a specific version, including prereleases. A valid signature over a different version is refused. The check compares full SemVer precedence without integer overflow, and checking an older version never advertises it as an available upgrade. A missing stable release is reported separately from HTTP, signature or schema failure. No TeamCode login is read, and this command neither installs files nor restarts services. `teamcode update` installation and `--rollback` remain unavailable until their V1 implementation/acceptance is complete.

The typed client owns a separate credential-free HTTP client; it never receives team/provider credentials. The [public GitHub release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release) provides only a bounded version hint, not trust or arbitrary asset URLs. Canonical download paths are derived from the validated version/target. Downloads permit at most five redirects to HTTPS GitHub release/CDN hosts, with no embedded user credentials, nonstandard ports or fragment. API redirects are rejected. Metadata and binary bodies are bounded even without trustworthy Content-Length; binary transfer enforces the declared exact length and leaves SHA-256 authentication to the staging verifier. Encoded bodies and inconsistent lengths fail, and errors do not expose response bodies or signed CDN query strings. Checks/downloads have connection, socket and total deadlines; coroutine cancellation is preserved. The explicit check makes network requests each time; periodic cached checks and opt-in unattended installation remain planned.

Signed golden fixtures have `-text` Git attributes. A Windows autocrlf checkout must preserve their authenticated bytes; the verifier deliberately rejects CRLF-translated payloads rather than repairing them. A regression test performs a real Git index checkout with autocrlf enabled and checks both successful unchanged-byte verification and rejection after newline translation.
