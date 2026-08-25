# ZMK Helper Android User Guide

[README](../README.md) | [日本語](guide-ja.md) | [Development](development.md)

## 1. Choose an update path

| Update path | File to select | Keyboard requirement |
| --- | --- | --- |
| USB UF2 | `.uf2` | USB mass-storage bootloader |
| BLE OTA | Nordic/Adafruit DFU `.zip` | BLE DFU-capable bootloader |
| Blueboot OTA | `.zip` converted from `.uf2` in the app | Compatible `zmk-feature-blueboot` setup |

The app also lists `.bin` and `.hex` files found in artifacts, but those formats cannot necessarily be copied to a UF2 drive or sent through BLE DFU. Use `.uf2` for a normal UF2 bootloader and a compatible DFU `.zip` for BLE OTA.

## 2. Installation and permissions

1. Download the latest `zmk-helper-*-release.apk` from [Releases](https://github.com/te9no/ZMKHelperAndroid/releases).
2. Open the APK and, if required, allow the browser or file manager to install unknown apps.
3. Install and launch ZMK Helper.

To preserve settings and cached selections during an upgrade, install over the existing version instead of uninstalling it first.

Android requests permissions when the related feature is first used:

- USB access: connection to a CDC serial interface
- Bluetooth scan/connect: BLE DFU discovery and connection
- Notifications: BLE OTA foreground service and progress
- Folder access: writing to a UF2 bootloader drive

A denied permission can be changed under Android Settings > Apps > ZMK Helper > Permissions.

## 3. Sign in to GitHub

Some public repository data may load without signing in, but GitHub Actions artifact downloads and private repositories require authentication.

1. Open the side menu with `Menu` or a rightward swipe from the left edge.
2. Tap `Login with GitHub`.
3. The device code is shown and copied to the clipboard automatically.
4. Sign in on GitHub's device activation page and complete two-factor authentication if required.
5. Enter the code and authorize access.
6. Return to the app and wait for `GitHub login complete`.

The token is stored in the app's private preferences and is not displayed. To authenticate again, use `Clear GitHub token`, then repeat login.

If `HTTP 400 device flow must be explicitly enabled` appears, the OAuth App administrator must enable Device Flow in GitHub Developer settings. The OAuth client ID belongs to the app registration and is not issued separately for each user.

## 4. Select a repository and firmware

Follow the screen from `01 Firmware source` to `02 Choose firmware` and `03 Update keyboard`.

Unavailable actions are dimmed until their prerequisites are complete. For example, `Select Artifact` requires loaded builds, `Select Firmware` requires an extracted artifact, and write actions require a compatible UF2 or DFU ZIP selection.

### 4.1 Repository and branch

1. Enter `owner/repo` or `https://github.com/owner/repo` in `GitHub repo URL or owner/repo`.
2. Tap `Save repo`.
3. Tap `Select Branch` and select the target branch.
4. Select `All branches` to remove the branch filter.
5. Tap `Load successful Actions builds`.

If the branch API fails, the app infers branch names from recent Actions runs. For a private repository, the signed-in account must have access.

### 4.2 Artifact and firmware

1. Tap `Select Artifact`.
2. Select an artifact from the list grouped by workflow run.
3. Verify the branch, commit, build time, and artifact name.
4. Tap `Select Firmware` and select the exact keyboard file.
5. Recheck the selection in `03 Update keyboard`.

The artifact and firmware summary surfaces are also tappable. For split keyboards, verify left/right and central/peripheral names carefully. The selection remains available after writing or reconnecting USB.

## 5. Update over USB UF2

### Preparation

- Select a `.uf2` built for the exact keyboard.
- Use an Android device with USB host/OTG support.
- Use a data-capable cable, not a charge-only cable.
- Confirm how the keyboard enters bootloader mode.

The bootloader procedure depends on the board. Common methods include double-tapping reset, using a BOOT button, or invoking a ZMK bootloader behavior.

### Automatic writing

1. Select `USB UF2` under `03 Update keyboard`.
2. Tap `Wait for bootloader and write`.
3. Only then start the keyboard in bootloader mode.
4. The app detects a newly attached or mounted removable drive.
5. On first use, or when saved access is stale, Android opens a folder picker. Select the bootloader drive, commonly named with `XIAO`, `BOOT`, or `UF2`.
6. Grant access to start writing.
7. Watch the progress bar and status.
8. Keep the cable connected until `Firmware written` appears and the keyboard reboots.

To avoid writing to an unrelated drive, removable drives that already existed before write mode was armed are not treated as new bootloader candidates. Always tap the wait button first.

If Android does not publish the drive as a `StorageVolume`, the app reacts to a USB mass-storage attachment or CDC trigger. After about three seconds, it tries the saved folder permission directly and opens the picker if that permission is stale. Normal detection continues for up to about 60 seconds.

Use `Write now to registered drive` only when the bootloader is already mounted and saved folder access is valid for the current drive.

## 6. CDC Debug and 1200-baud writing

The keyboard firmware must expose a USB CDC ACM interface.

### View logs

1. Connect the keyboard over USB.
2. Tap `Open CDC Debug Console`.
3. Use `Select CDC Port`, select the device, and grant USB access.
4. Read the log at 115200 bps.

Configurations exposing both Studio and CDC Debug can show multiple ports on one USB device. Switch between `Port 1` and `Port 2` if no ZMK log appears. `Reconnect` reopens the port and `Clear Log` clears displayed text.

### Trigger and automatically write

1. Select the target `.uf2` first.
2. Tap `1200 baud + Auto Write` in the CDC console.
3. Keep the cable connected.
4. The app tries each CDC port on the same USB device.
5. When the bootloader starts, normal USB UF2 writing follows automatically.

CDC Debug alone can provide logs without implementing the trigger. Enable a CDC ACM bootloader trigger in keyboard firmware when required.

## 7. Update over BLE OTA

BLE OTA does not send a raw `.uf2`. It requires a Nordic/Adafruit DFU `.zip` built for the target bootloader. Select a DFU package containing the manifest and firmware, not merely the ZIP downloaded as a GitHub Actions artifact.

1. Select the target DFU `.zip`.
2. Select `BLE OTA` under `03 Update keyboard`.
3. Put the keyboard into BLE DFU mode.
4. Tap `Scan BLE OTA devices`.
5. Select the target, commonly named `AdaDFU`, and grant Bluetooth/notification permissions if asked.
6. Tap `Write selected ZIP over BLE OTA`.
7. Keep the keyboard powered until it completes and reboots.

Rescan immediately before every attempt and select the currently advertising DFU device. DFU mode can change the BLE address, and a stale selection often causes `GATT CONN TIMEOUT (147)`.

If transfer fails after progress starts, keep the keyboard in DFU mode and tap the write button again. If it fails before connection, rescan first.

For throughput, the app requests MTU 517, disables Packet Receipt Notifications, and allows five DFU retries. Actual speed depends on the Android device, distance, interference, and bootloader.

## 8. Convert UF2 for Blueboot

This feature targets keyboards using [`zmk-feature-blueboot`](https://github.com/te9no/zmk-feature-blueboot). It does not make every UF2 compatible with every BLE bootloader.

1. Select the target `.uf2`.
2. Switch to `BLE OTA`.
3. Tap `Convert selected UF2 to BLE OTA ZIP`.
4. Confirm that the generated `*-blueboot.zip` is selected automatically.
5. Enter Blueboot/BLE DFU mode, scan, select the device, and write.

Defaults are SoftDevice requirement `0x0123` and device type `0x0052`, intended for S140 7.3.0 and nRF52840. Generate the correct DFU ZIP during the build when another target requires different compatibility values.

## 9. Cache and saved data

To reduce network use and waiting time, the app stores:

- Build artifact lists by repository and branch
- GitHub artifact ZIPs and extracted firmware
- Converted Blueboot ZIPs
- Selected repository, branch, artifact, firmware, and update transport
- GitHub token and bootloader folder permission

For a previously selected artifact, the app checks extracted files, then the cached ZIP, and downloads from GitHub only when needed. Tap `Load successful Actions builds` to refresh. Clearing app data or uninstalling removes settings, credentials, selections, and cache.

## 10. Troubleshooting

### Artifact download failed: HTTP 401

Use `Clear GitHub token`, sign in again, verify private repository access, and reload the builds.

### Unable to resolve host github.com

Run `Run GitHub network diagnostic`. Check Wi-Fi/mobile data, captive portal login, Private DNS, VPN, and ad-blocker rules. Try switching between Wi-Fi and mobile data.

### The branch list is empty

Verify the repository format, token access, and the presence of branches or Actions runs. Refresh authentication and open `Select Branch` again.

### USB is attached but no drive appears

- Wait up to about 60 seconds.
- Check whether Android's file manager sees the UF2 drive.
- Use a data-capable cable and working OTG adapter.
- Arm write mode before restarting the bootloader.
- In the folder picker, select the current UF2 drive instead of internal storage.

### The bootloader starts but firmware is not written

- Verify that a `.uf2` is selected.
- Check the status for a folder access request.
- Select the currently mounted bootloader drive again.
- Replace stale access with `Register bootloader folder`.
- Arm write mode again and restart the bootloader.

### CDC logging or the 1200-baud trigger does not work

Try another CDC port, grant USB permission, use a data-capable cable, and verify CDC Debug in firmware. The trigger additionally requires a CDC ACM bootloader trigger.

### BLE OTA is slow or fails

Move the devices closer, restart DFU mode, rescan and select the current device, reduce Bluetooth/2.4 GHz interference, and verify that the ZIP targets the bootloader.

## 11. Pre-update checklist

- [ ] Repository, branch, and commit are correct
- [ ] Artifact and firmware names match the keyboard and side
- [ ] USB uses UF2; BLE uses a DFU ZIP
- [ ] Android device and keyboard have sufficient battery power
- [ ] USB cable supports data, or BLE devices are close together
- [ ] Connections remain active until completion or reboot
