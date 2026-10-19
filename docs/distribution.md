# Native distribution

TeamCode targets macOS Apple Silicon/Intel, Linux ARM64/x64 and Windows x64. The CLI is a Kotlin/Native executable and needs no JVM. This page describes the versioned build/release path. Self-update, rollback and installer automation remain V1 work; no `teamcode update` command is implemented yet.

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

Linux binaries use platform shared libraries, including glibc/libcurl and `libcrypt.so.1`; these are not advertised as fully static builds. OS/CPU support is accepted only after its actual runner checks pass. Apple Developer notarization and Windows Authenticode signing are not currently provided. The publisher signature is independent of platform executable signing. `SHA256SUMS` is a convenience; authenticated artifact identity comes from the signed manifest and its independently pinned release key. Native updater verification of that signature is still required before installing executable code.

## Remaining V1 distribution acceptance

- Complete native signature verification and key-rotation acceptance; the publisher-side signer alone does not protect an updater.
- Implement user-owned installation, bounded authenticated release discovery/downloads, update and rollback, with a fixed stable executable path and explicit version selection.
- Preserve login/device identity and never rewrite personal Claude/Codex configuration during installation or updates.
- Drain the owned user service and handle executable replacement on Windows without stopping unrelated processes; restore working registration/binary on failure.
- Exercise fresh installation, update/rollback, offline/interrupted download, integrity refusal and restart on each target. Continue separate clean-workstation reboot/logon and pre-login service acceptance.
- Publish and download an actual prerelease through this pipeline before claiming versioned distribution is accepted end to end.

TeamClaude's `src/updater.js` and `test/updater.test.js` provide the reference for installation ownership, opt-out, bounded version checks and leaving source checkouts alone. Its npm replacement mechanism does not apply to native executable replacement. TeamCode retains native CLI/client/API boundaries and will adapt service drainage and rollback rather than invoking npm or overwriting arbitrary executables.

## Publisher signing key

The public RSA-3072 key is tracked at `release/trust/teamcode-release-2026.pem`. Its key ID is SHA-256 of the DER SubjectPublicKeyInfo. The corresponding private key is a dedicated `TEAMCODE_RELEASE_SIGNING_KEY` GitHub Actions secret, separate from all self-hosted server/team/provider credentials. Signing uses PSS with SHA-256, MGF1-SHA256 and a 32-byte salt. The signature envelope is schema version 1 and contains only algorithm, key ID and base64 signature. Publisher verification refuses another key, altered bytes, unsupported schema/algorithm or an existing signature output. Private material is written only to a private temporary file and deleted after signing; OpenSSL subprocesses do not inherit the key environment variable.

A private owner backup was created at `~/.config/teamcode-release-signing/rsa-3072-2026.pem` with owner-only permissions. It is never part of the repository, Docker image or release bundle. Fork maintainers must generate their own key, configure their own Actions secret and ship their own pinned public trust root; the self-hosted server cannot replace this trust root. Key rotation must first ship the next public trust root through an accepted release signed by an already trusted key, then change the publisher key. Native old/new/unknown-key and compromised-key recovery acceptance remains required before automatic updates are claimed.

The planned native verifier can use the same RSA-PSS/SHA-256 parameters through platform crypto: [Apple Security](https://developer.apple.com/documentation/security/seckeyalgorithm/rsasignaturedigestpsssha256), [Windows BCrypt](https://learn.microsoft.com/en-us/windows/win32/api/bcrypt/nf-bcrypt-bcryptverifysignature) and [OpenSSL EVP](https://docs.openssl.org/3.4/man7/RSA-PSS/). The exact bytes must be verified against a shipped public key before parsing manifest contents or executing an asset. These native adapters are still implementation/acceptance work.
