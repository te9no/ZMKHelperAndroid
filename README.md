# ZMK Helper Android

Android app for updating ZMK keyboard firmware from GitHub Actions artifacts.

## Current behavior

- Register a GitHub repository as `owner/repo` or a `github.com` URL.
- Login with GitHub OAuth Device Flow, or paste a token manually, to support private repositories and higher API rate limits.
- Keep GitHub login, token clearing, network diagnostics, and browser shortcuts in the left side menu so the main screen stays focused on firmware selection and writing.
- Open the side menu by swiping in from the left edge or tapping `Menu`.
- Keep the GitHub token field in the side menu instead of the main workflow.
- Use `Select Artifact` and `Select Firmware` buttons that open full-screen selection lists instead of cramped embedded lists.
- Load successful GitHub Actions workflow runs, optionally filtered by branch.
- Select a branch from the repository branch list instead of typing the branch name manually.
- Show available, non-expired workflow artifacts ordered by GitHub's run response.
- Group artifact choices by GitHub Actions run/build so multiple artifacts from the same build stay together.
- Select the latest artifact by default, or choose a specific branch/commit timestamp from the list.
- Download the selected artifact ZIP and list every `.uf2`, `.bin`, `.hex`, or BLE OTA `.zip` file inside it.
- Cache artifact ZIPs and extracted firmware files by GitHub artifact ID. Re-selecting the same artifact reuses extracted files first, then the cached ZIP, and only downloads again if neither cache exists.
- Cache the Build artifact list by repository and branch so the app can show the previous build list immediately on startup or before a GitHub refresh completes.
- Select the exact firmware file to write, such as the left/right UF2 from a split ZMK build.
- Select a Nordic/Adafruit DFU `*.zip` package and write it over BLE OTA.
- Convert a selected UF2 into a Blueboot-compatible BLE OTA ZIP in the app, using the same defaults as `zmk-feature-blueboot`.
- Scan for BLE OTA devices from the app, select the target half, and keep the selected BLE device address for the next update.
- Register the ZMK bootloader volume through Android's Storage Access Framework.
- Arm write mode, snapshot currently connected removable volumes, then treat a newly mounted removable drive as the bootloader drive.
- Prefer newly mounted removable volumes whose names look like bootloaders, including `XIAO`, `BOOT`, `UF2`, `RP2040`, `nRF52`, or `nice!nano`.
- If USB attach arrives before Android exposes the removable volume, poll for the bootloader drive for up to about 10 seconds.
- If Android folder permission has not been granted yet, the app opens folder selection after detecting the new bootloader drive; after permission is granted it writes the selected firmware. Manual folder registration is kept in the side menu as a fallback.
- Keep the selected artifact and firmware file visible after writing so the next side/board can be flashed without losing context.
- Show determinate write progress based on bytes copied to the bootloader volume.
- Persist the selected artifact ID and firmware filename in app preferences so USB/bootloader events or list reloads restore the same UF2 instead of falling back to the latest artifact.
- On Activity resume, rebuild the selected artifact and firmware objects from saved preferences plus cached firmware files, so connecting a keyboard does not clear the active selection.
- The Activity handles external keyboard configuration changes (`keyboard`, `keyboardHidden`, `navigation`) to avoid unnecessary recreation when a keyboard is plugged in.

## Important Android limitation

Android apps cannot reliably write directly to arbitrary mounted USB mass-storage paths. This app therefore stores a persistable Storage Access Framework URI for the bootloader volume. The user must choose the bootloader folder once after the keyboard is in bootloader mode. After that, write mode can use the saved URI.

Write mode detects bootloader insertion by comparing removable storage volumes before and after write mode is armed. A newly mounted removable volume with a bootloader-like name is treated as the bootloader drive candidate. Direct automatic writing to a never-authorized drive is not possible on Android, so first use may still require folder selection.

## BLE OTA updates

BLE OTA is intended for keyboard firmware packages generated as Nordic/Adafruit DFU ZIP files, for example `keyboard_left-ble-ota.zip`.

1. Load the GitHub Actions build artifact.
2. Tap `Select Firmware` and choose the DFU `.zip` file for the target keyboard or half.
3. Put the target keyboard or half into BLE DFU mode.
4. Tap `Scan BLE OTA devices`.
5. Select the advertising DFU device.
6. Tap `Write selected ZIP over BLE OTA`.

USB mass-storage writing still uses `.uf2` files. BLE OTA requires a `.zip` DFU package; selecting a `.uf2`, `.bin`, or `.hex` for BLE OTA is rejected.

The app uses Nordic's Android DFU library, which supports nRF51/nRF52 devices with compatible nRF5 SDK Secure or Legacy DFU bootloaders, including Adafruit-style BLE DFU packages. It is not a generic BLE file-transfer protocol; each keyboard firmware must provide a compatible DFU ZIP.

### Blueboot UF2 conversion

For keyboards using [`zmk-feature-blueboot`](https://github.com/te9no/zmk-feature-blueboot), the app can create the BLE OTA package from the selected UF2:

1. Select the target `.uf2` firmware from the artifact.
2. Tap `Convert selected UF2 to BLE OTA ZIP`.
3. The generated `*-blueboot.zip` is cached beside the extracted UF2 and selected automatically.
4. Put the keyboard into Blueboot/BLE DFU mode.
5. Scan and write the selected ZIP over BLE OTA.

The in-app conversion uses Blueboot's default compatibility values: SoftDevice requirement `0x0123` for S140 7.3.0 and device type `0x0052` for nRF52840. If a board uses different bootloader compatibility values, generate the ZIP in the firmware build instead.

## GitHub login setup

GitHub Device Flow requires an OAuth App client ID.

1. Create a GitHub OAuth App in GitHub developer settings.
2. Enable Device Flow for that OAuth App.
3. Set the OAuth App client ID in `MainActivity.GITHUB_OAUTH_CLIENT_ID`.
4. Tap `Login with GitHub`.
5. Enter the displayed code at `https://github.com/login/device` and authorize.

If login fails with `HTTP 400 device flow must be explicitly enabled`, open the GitHub OAuth App settings and enable `Device Flow`. The setting is per OAuth App, not per user. After enabling it, retry `Login with GitHub`.

The app requests the `repo` scope so it can read Actions artifacts from private repositories. For public-only repositories, manual token entry is optional but still useful to avoid low unauthenticated API rate limits.

Actions artifact ZIP downloads require authentication. If the app reports `Artifact download failed: HTTP 401`, use `Clear GitHub token`, run `Login with GitHub` again, and confirm the OAuth authorization grants access to the target repository. The app uses the token only for the GitHub API request and follows the artifact redirect without attaching the bearer token to the signed download URL.

If Android reports `Unable to resolve host github.com`, the app cannot resolve GitHub DNS from the device. Check Wi-Fi/mobile data, captive portal login, Android Private DNS, VPN/ad blocker apps, and try switching networks. The app declares both `INTERNET` and `ACCESS_NETWORK_STATE` permissions and reports Android's active network state before GitHub requests.

Use `Run GitHub network diagnostic` in the app to check Android network validation, DNS resolution for `github.com` and `api.github.com`, and HTTPS reachability. Use `Open network settings` to quickly switch Wi-Fi/mobile data, disable Private DNS, or turn off VPN/ad blockers.

If the browser can open GitHub but the app still cannot resolve `github.com`, use `Open GitHub token page`, create a token, paste it into `GitHub token`, and try loading builds. A classic token needs `repo` for private repositories. A fine-grained token must be allowed for the target repository and include read access for Actions artifacts. If `api.github.com` also fails in the diagnostic, the app cannot use GitHub from that network until DNS/VPN/Private DNS is fixed.

## Build

Open this directory in Android Studio and build the `app` module.

Use the Gradle wrapper:

```powershell
.\gradlew.bat assembleDebug
```

## GitHub Actions release

The repository includes `.github/workflows/android-release.yml`.

- `workflow_dispatch` builds a signed release APK and uploads it as a workflow artifact.
- Pushing a tag matching `v*` builds the signed release APK, creates a GitHub Release, and attaches the APK.
- The workflow requires these repository secrets:
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`

Example:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

Create the keystore locally, base64-encode it, and store the encoded value in `ANDROID_KEYSTORE_BASE64`. Do not commit `.jks` or `.keystore` files.
