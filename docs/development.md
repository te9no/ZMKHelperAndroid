# Development, Build, and Release

[README](../README.md) | [日本語ユーザーガイド](guide-ja.md) | [English user guide](guide-en.md)

## Requirements

- Android Studio and Android SDK 35
- JDK 17
- Git
- Android 8.0/API 26 or later test device

The app is native Android Java. Gradle configuration uses Kotlin DSL.

## Debug build

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

If `JAVA_HOME` is missing, use Android Studio's bundled runtime for the current session:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
```

macOS/Linux:

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Verification

Run before a release:

```powershell
.\gradlew.bat clean assembleDebug lintDebug
```

Device coverage should include:

- GitHub login, logout, public/private artifact loading
- Branch, artifact, and firmware selection persistence
- USB UF2 with first-time and saved folder permissions
- CDC port selection and 1200-baud trigger
- BLE scan, transfer, failure, and retry
- Activity resume and external keyboard attachment without selection loss

## Release signing

Release signing reads:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

With those variables set:

```powershell
.\gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

Never commit keystores, passwords, tokens, or decoded CI secrets.

## GitHub Actions release

`.github/workflows/android-release.yml` creates a signed APK.

- `workflow_dispatch`: signed workflow artifact
- `v*` tag push: GitHub Release with attached APK
- Tag containing `-`, such as `v0.3.0-alpha.1`: prerelease
- Tag without `-`, such as `v0.3.0`: stable release

Required repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Release procedure:

1. Increment `versionCode` and set `versionName` in `app/build.gradle.kts`.
2. Run the verification tasks and device smoke tests.
3. Commit and push the version change.
4. Create and push an annotated tag.
5. Verify release state, APK asset, and SHA-256 digest.

```powershell
git tag -a v0.3.0 -m "v0.3.0"
git push origin v0.3.0
```

## Documentation maintenance

Button labels in the guides match the app. When the workflow changes, update `guide-ja.md`, `guide-en.md`, and the README quick start together. Keep timeout and fallback descriptions aligned with `MainActivity`.
