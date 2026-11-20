# Web / PWA guide

The web edition is a PWA for selecting, converting, and saving ZMK firmware from GitHub Actions or local storage on Android and iPad. BLE OTA is not included yet.

## Features

- Sign in with GitHub OAuth Device Flow or a GitHub token
- Load branches and successful Actions builds from public or private repositories
- Group artifacts by workflow build
- Cache artifact ZIP files locally and extract UF2, BIN, HEX, and ZIP files
- Select a local UF2 or ZIP without linking a repository
- Convert UF2 into a Blueboot ZIP entirely on the device
- Save directly to a selected UF2 drive on supported Android Chromium browsers
- Download the UF2 for transfer through the Files app when the directory picker is unavailable, including iPad
- Read CDC debug output through WebUSB on Android Chrome
- Send the 1200-baud bootloader trigger through WebUSB on Android Chrome
- Install the site on the home screen

## Updating over USB on Android

1. Open the web edition in Chrome.
2. Select firmware from a GitHub build or with `Choose UF2 / ZIP from this device`.
3. Start the keyboard bootloader.
4. Select `Save to UF2 drive`.
5. In Android's folder picker, select the bootloader drive. It may be named `XIAO-SENSE`, `XIAO-BOOT`, `BOOT`, or `UF2`.
6. Keep the cable connected until the keyboard reboots.

A browser cannot monitor USB drives without user permission. Unlike the native Android edition, the web edition therefore requires selecting the bootloader folder.

For firmware exposing a CDC interface, use `CDC Debug Console` to read output at 115200 baud. `Start bootloader at 1200 baud` sends the line-coding and DTR sequence to the selected CDC device. After the bootloader drive appears, select it with the UF2 save action.

## Updating over USB on iPad

1. Open the web edition in Safari and select the target UF2.
2. Connect the keyboard over USB-C and start its bootloader.
3. Select `Download firmware`.
4. Open the downloaded UF2 in Safari Downloads or the Files app.
5. Move or copy it to the bootloader drive shown under Locations.

Some combinations of iPad, keyboard, and USB adapter cannot supply enough power. Use a powered USB hub when necessary.

iPad Safari does not provide WebUSB, so the CDC console and 1200-baud trigger are unavailable there.

## GitHub authentication

When the deployed site has an OAuth proxy configured, use `Sign in with GitHub` in the menu. The device code is copied to the clipboard when possible.

If the OAuth proxy is unavailable, enter a fine-grained personal access token in the menu. Private repositories require read access to the target repository's Actions and Contents. The token is stored in browser local storage; remove it with `Sign out / clear token` when using a shared device.

## Cache behavior

- Build metadata is reused for 30 minutes.
- Downloaded artifact ZIP files are stored in IndexedDB.
- Clearing the site's browser data also removes the cache and token.
- Loading another artifact or refreshing data from GitHub still requires network access.

## Development

```powershell
cd web
npm install
npm run dev
```

Run tests and create a production build:

```powershell
npm test
npm run build
```

See [`web/worker/README.md`](../web/worker/README.md) for OAuth proxy deployment.
