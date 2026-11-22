# Release operations

1. Update `versionName` and increment `versionCode` in `app/build.gradle.kts`.
2. Run tests, lint, a signed build, and review the light/dark and large-text screenshots.
3. Push the matching `vVERSION` tag. **Release APK** publishes `launcher-mobile.apk`, `update-manifest.json`, and `SHA256SUMS`.
4. The Worker reads the latest GitHub release using a short cache lifetime. APKs do not need separate Cloudflare storage.
5. Check `/healthz` and `/v1/android/stable` on the custom domain.

The manifest envelope contains standard Base64 UTF-8 JSON `payload` bytes and a standard Base64 DER ECDSA P-256/SHA-256 `signature`. The embedded public key is a Base64 X.509 SubjectPublicKeyInfo DER value. Always sign the original payload bytes.

The APK and update-manifest signing keys are separate. Do not regenerate them for routine builds. A manifest-key rotation needs an explicit APK trust transition; Android signing-key rotation needs a supported signing lineage. An arbitrary new keystore breaks in-place updates.

Cloudflare deployment requires access to the account and DNS zone owning `launcher.laogaohohoho.me`. `wrangler deploy --dry-run` validates without publishing. Configure `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` before automated deployment. Set the repository variable `CLOUDFLARE_DEPLOY_ENABLED=true` to deploy automatically on Worker changes; manual deployment remains available from Actions.

Users check for updates in Settings or on the pairing screen, including without a connected PC. Android may ask to allow installations from this app and always handles final installation confirmation. A debug build has a different signing certificate and is intended for local validation.
