# Project rules for coding agents

1. Canonical branch: `main` only. Update this file and `DEVELOPMENT_LOG.md` with significant changes.
2. Project: **LAN Мост** (`de.roserge.lanmost`) — an intentionally simple offline/local Android SMB file-transfer utility.
3. User-facing language: Russian, readable large touch controls, no ads, subscriptions, cloud logins, telemetry or tracking.
4. Prioritize safety: never delete or overwrite user files, never upload credentials, keys, passwords, screenshots with secrets or private data.
5. Preserve update compatibility: keep `applicationId` unchanged; sign production APK with the same permanent developer keystore. Do not invent, publish or rotate signing keys without explicit approval.
6. Keep the default Windows SMB server `192.168.178.21`, share `Download`, user `Oneplus` editable; never commit a password.
7. Add functional tests whenever introducing or changing SMB, filename handling, transfer error recovery or permissions.
8. Ensure source builds on GitHub Actions / Android Studio before claiming APK works. Test upload and download using a real Android phone and Windows SMB share before a stable release.
9. Developer contact: `roserge.nbg@gmail.com`.
