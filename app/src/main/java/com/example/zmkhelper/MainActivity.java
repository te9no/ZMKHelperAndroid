package com.example.zmkhelper;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import no.nordicsemi.android.dfu.DfuProgressListener;
import no.nordicsemi.android.dfu.DfuProgressListenerAdapter;
import no.nordicsemi.android.dfu.DfuServiceInitiator;
import no.nordicsemi.android.dfu.DfuServiceListenerHelper;

public final class MainActivity extends Activity {
    private static final int REQ_BOOTLOADER_FOLDER = 41;
    private static final int REQ_BLE_PERMISSIONS = 42;
    private static final String GITHUB_OAUTH_CLIENT_ID = "Ov23li28WjgBpOYKKb9Y";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final GitHubActionsClient github = new GitHubActionsClient();
    private final GitHubOAuthClient oauth = new GitHubOAuthClient();
    private final FirmwareWriter writer = new FirmwareWriter();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<FirmwareBuild> builds = new ArrayList<>();
    private final List<FirmwareFile> firmwareFiles = new ArrayList<>();
    private final List<BleDeviceItem> bleDevices = new ArrayList<>();

    private SharedPreferences prefs;
    private EditText repoInput;
    private EditText tokenInput;
    private EditText branchInput;
    private TextView artifactSummary;
    private TextView firmwareSummary;
    private TextView bleSummary;
    private TextView selectedInfo;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout sideMenu;
    private View menuScrim;
    private ArrayAdapter<FirmwareBuild> buildAdapter;
    private ArrayAdapter<FirmwareFile> firmwareAdapter;
    private ArrayAdapter<BleDeviceItem> bleAdapter;
    private FirmwareBuild selectedBuild;
    private FirmwareFile selectedFirmware;
    private BleDeviceItem selectedBleDevice;
    private boolean writeMode;
    private boolean pendingWriteAfterFolderPick;
    private boolean pendingBleScan;
    private boolean pendingBleWrite;
    private boolean scanningBle;
    private int bootloaderPollAttempts;
    private float touchStartX;
    private float touchStartY;
    private ScanCallback scanCallback;
    private final Set<String> writeModeVolumeKeys = new HashSet<>();

    private final DfuProgressListener dfuProgressListener = new DfuProgressListenerAdapter() {
        @Override
        public void onDeviceConnecting(String deviceAddress) {
            runOnUiThread(() -> setStatus("BLE OTA: connecting to " + deviceAddress + "..."));
        }

        @Override
        public void onDfuProcessStarting(String deviceAddress) {
            runOnUiThread(() -> {
                setBusy(true);
                setStatus("BLE OTA: starting DFU...");
            });
        }

        @Override
        public void onProgressChanged(String deviceAddress, int percent, float speed, float avgSpeed,
                int currentPart, int partsTotal) {
            runOnUiThread(() -> {
                setProgressPercent(percent);
                setStatus("BLE OTA: writing " + percent + "% (" + currentPart + "/" + partsTotal + ")");
            });
        }

        @Override
        public void onDfuCompleted(String deviceAddress) {
            runOnUiThread(() -> {
                setBusy(false);
                setProgressPercent(100);
                setStatus("BLE OTA complete. The keyboard should reboot into the updated firmware.");
            });
        }

        @Override
        public void onDfuAborted(String deviceAddress) {
            runOnUiThread(() -> {
                setBusy(false);
                setStatus("BLE OTA aborted.");
            });
        }

        @Override
        public void onError(String deviceAddress, int error, int errorType, String message) {
            runOnUiThread(() -> {
                setBusy(false);
                setStatus("BLE OTA error: " + message + " (" + error + ")");
            });
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && writeMode) {
                handleBootloaderDriveEvent("USB device attached");
            }
        }
    };

    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_MEDIA_MOUNTED.equals(intent.getAction()) && writeMode) {
                handleBootloaderDriveEvent("New storage volume mounted");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("zmk-helper", MODE_PRIVATE);
        buildUi();
        loadPrefs();
        registerReceiver(usbReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED));
        IntentFilter mediaFilter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        mediaFilter.addDataScheme("file");
        registerReceiver(mediaReceiver, mediaFilter);
        DfuServiceListenerHelper.registerProgressListener(this, dfuProgressListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreSelectionFromPrefsAndCache();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbReceiver);
        unregisterReceiver(mediaReceiver);
        stopBleScan();
        DfuServiceListenerHelper.unregisterProgressListener(this, dfuProgressListener);
        networkExecutor.shutdownNow();
        writeExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BLE_PERMISSIONS) {
            return;
        }
        if (grantResults.length == 0) {
            pendingBleScan = false;
            pendingBleWrite = false;
            setStatus("Bluetooth permission is required for BLE OTA updates.");
            return;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                pendingBleScan = false;
                pendingBleWrite = false;
                setStatus("Bluetooth permission is required for BLE OTA updates.");
                return;
            }
        }
        if (pendingBleScan) {
            pendingBleScan = false;
            startBleScan();
        } else if (pendingBleWrite) {
            pendingBleWrite = false;
            startBleDfu();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BOOTLOADER_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
            prefs.edit().putString("bootloaderUri", uri.toString()).apply();
            setStatus("Bootloader folder registered: " + uri);
            if (pendingWriteAfterFolderPick) {
                pendingWriteAfterFolderPick = false;
                writeSelectedBuild();
            }
        }
    }

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFFF4F7FB);
        frame.setOnTouchListener((view, event) -> handleRootSwipe(event));
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP);
        int pad = dp(16);
        root.setPadding(pad, dp(40), pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        frame.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(14));
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        Button menuButton = compactButton("Menu");
        menuButton.setOnClickListener(v -> showSideMenu(true));
        header.addView(menuButton);

        TextView title = new TextView(this);
        title.setText("ZMK Firmware Helper");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF14213D);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title);

        LinearLayout repoCard = card();
        repoInput = input("GitHub repo URL or owner/repo");
        branchInput = input("Branch filter (optional)");
        repoCard.addView(repoInput);
        repoCard.addView(branchInput);

        Button save = button("Save repo");
        save.setOnClickListener(v -> savePrefs());
        repoCard.addView(save);

        Button load = button("Load successful Actions builds");
        load.setOnClickListener(v -> loadBuilds());
        repoCard.addView(load);
        root.addView(repoCard);

        LinearLayout buildsCard = card();

        TextView buildLabel = new TextView(this);
        buildLabel.setText("Build artifacts");
        styleSectionLabel(buildLabel);
        buildsCard.addView(buildLabel);

        buildAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, builds);
        artifactSummary = summaryText("No artifact selected");
        buildsCard.addView(artifactSummary);
        Button selectArtifact = button("Select Artifact");
        selectArtifact.setOnClickListener(v -> showArtifactSelector());
        buildsCard.addView(selectArtifact);
        root.addView(buildsCard);

        LinearLayout firmwareCard = card();

        TextView firmwareLabel = new TextView(this);
        firmwareLabel.setText("Firmware files inside selected artifact");
        styleSectionLabel(firmwareLabel);
        firmwareCard.addView(firmwareLabel);

        firmwareAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, firmwareFiles);
        firmwareSummary = summaryText("No firmware selected");
        firmwareCard.addView(firmwareSummary);
        Button selectFirmware = button("Select Firmware");
        selectFirmware.setOnClickListener(v -> showFirmwareSelector());
        firmwareCard.addView(selectFirmware);
        root.addView(firmwareCard);

        LinearLayout bleCard = card();
        TextView bleLabel = new TextView(this);
        bleLabel.setText("BLE OTA update");
        styleSectionLabel(bleLabel);
        bleCard.addView(bleLabel);

        bleAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, bleDevices);
        bleSummary = summaryText("No BLE DFU device selected");
        bleCard.addView(bleSummary);

        Button scanBle = button("Scan BLE OTA devices");
        scanBle.setOnClickListener(v -> showBleDeviceSelector());
        bleCard.addView(scanBle);

        Button writeBle = button("Write selected ZIP over BLE OTA");
        writeBle.setOnClickListener(v -> startBleDfu());
        bleCard.addView(writeBle);
        root.addView(bleCard);

        LinearLayout writeCard = card();

        selectedInfo = new TextView(this);
        selectedInfo.setText("Selected firmware: none");
        selectedInfo.setTextColor(0xFF1F2A44);
        selectedInfo.setPadding(0, 0, 0, dp(12));
        writeCard.addView(selectedInfo);

        Button waitWrite = button("Start write mode and wait for bootloader");
        waitWrite.setOnClickListener(v -> startWriteMode());
        writeCard.addView(waitWrite);

        Button writeNow = button("Write selected firmware now");
        writeNow.setOnClickListener(v -> writeSelectedBuild());
        writeCard.addView(writeNow);

        progress = new ProgressBar(this);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);
        writeCard.addView(progress);

        status = new TextView(this);
        status.setText("Register a GitHub repo, load builds, select an artifact, then choose the bootloader volume folder.");
        status.setTextColor(0xFF46556F);
        status.setPadding(0, dp(12), 0, 0);
        writeCard.addView(status);
        root.addView(writeCard);

        menuScrim = new View(this);
        menuScrim.setBackgroundColor(0x66000000);
        menuScrim.setVisibility(View.GONE);
        menuScrim.setOnClickListener(v -> showSideMenu(false));
        frame.addView(menuScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        sideMenu = buildSideMenu();
        sideMenu.setVisibility(View.GONE);
        sideMenu.setElevation(dp(12));
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(
                dp(312),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START);
        frame.addView(sideMenu, menuParams);

        setContentView(frame);
    }

    private LinearLayout buildSideMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(16), dp(24), dp(16), dp(16));
        menu.setBackgroundColor(0xFFFDFEFF);

        TextView title = new TextView(this);
        title.setText("GitHub / Network");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF14213D);
        title.setPadding(0, 0, 0, dp(12));
        menu.addView(title);

        tokenInput = input("GitHub token (optional for private repos)");
        menu.addView(tokenInput);

        Button close = button("Close menu");
        close.setOnClickListener(v -> showSideMenu(false));
        menu.addView(close);

        Button login = button("Login with GitHub");
        login.setOnClickListener(v -> {
            showSideMenu(false);
            loginWithGitHub();
        });
        menu.addView(login);

        Button logout = button("Clear GitHub token");
        logout.setOnClickListener(v -> {
            showSideMenu(false);
            clearGitHubToken();
        });
        menu.addView(logout);

        Button networkCheck = button("Run GitHub network diagnostic");
        networkCheck.setOnClickListener(v -> {
            showSideMenu(false);
            runNetworkDiagnostic();
        });
        menu.addView(networkCheck);

        Button networkSettings = button("Open network settings");
        networkSettings.setOnClickListener(v -> {
            showSideMenu(false);
            startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
        });
        menu.addView(networkSettings);

        Button openGitHub = button("Open GitHub in browser");
        openGitHub.setOnClickListener(v -> {
            showSideMenu(false);
            openUrl("https://github.com");
        });
        menu.addView(openGitHub);

        Button openTokenPage = button("Open GitHub token page");
        openTokenPage.setOnClickListener(v -> {
            showSideMenu(false);
            openUrl("https://github.com/settings/tokens");
        });
        menu.addView(openTokenPage);

        Button pickFolder = button("Register bootloader folder");
        pickFolder.setOnClickListener(v -> {
            showSideMenu(false);
            pickBootloaderFolder();
        });
        menu.addView(pickFolder);

        return menu;
    }

    private void showSideMenu(boolean show) {
        if (sideMenu == null || menuScrim == null) {
            return;
        }
        sideMenu.setVisibility(show ? View.VISIBLE : View.GONE);
        menuScrim.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showArtifactSelector() {
        if (builds.isEmpty()) {
            toast("Load builds first");
            return;
        }
        Dialog dialog = fullScreenListDialog("Select Artifact", builds, (position) -> {
            selectedBuild = builds.get(position);
            selectedFirmware = null;
            firmwareFiles.clear();
            rememberSelectedBuild();
            updateSelectedInfo();
            setStatus("Selected artifact: " + selectedBuild.artifactName + ". Loading firmware files...");
            loadFirmwareFilesForSelectedBuild();
        });
        dialog.show();
        dialog.getWindow().setLayout(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private void showFirmwareSelector() {
        if (firmwareFiles.isEmpty()) {
            toast(selectedBuild == null ? "Select an artifact first" : "Firmware files are still loading");
            return;
        }
        Dialog dialog = fullScreenListDialog("Select Firmware", firmwareFiles, (position) -> {
            selectedFirmware = firmwareFiles.get(position);
            rememberSelectedFirmware();
            updateSelectedInfo();
            setStatus("Selected firmware: " + selectedFirmware.name);
        });
        dialog.show();
        dialog.getWindow().setLayout(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private void showBleDeviceSelector() {
        if (!ensureBlePermissions(true)) {
            return;
        }
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackgroundColor(0xFFF4F7FB);

        TextView title = new TextView(this);
        title.setText("Select BLE OTA Device");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF14213D);
        title.setPadding(0, 0, 0, dp(10));
        panel.addView(title);

        ListView list = new ListView(this);
        list.setAdapter(bleAdapter);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setDividerHeight(dp(1));
        list.setBackground(rounded(0xFFFFFFFF, 0xFFE4EAF2, dp(18)));
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectedBleDevice = bleDevices.get(position);
            prefs.edit()
                    .putString("selectedBleName", selectedBleDevice.name)
                    .putString("selectedBleAddress", selectedBleDevice.address)
                    .apply();
            updateBleSummary();
            stopBleScan();
            setStatus("Selected BLE OTA device: " + selectedBleDevice);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f);
        listParams.setMargins(0, 0, 0, dp(12));
        panel.addView(list, listParams);

        Button rescan = button("Rescan");
        rescan.setOnClickListener(v -> startBleScan());
        panel.addView(rescan);

        Button close = button("Cancel");
        close.setOnClickListener(v -> {
            stopBleScan();
            dialog.dismiss();
        });
        panel.addView(close);

        dialog.setOnDismissListener(v -> stopBleScan());
        dialog.setContentView(panel);
        dialog.show();
        dialog.getWindow().setLayout(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        startBleScan();
    }

    private <T> Dialog fullScreenListDialog(String titleText, List<T> items, ListSelection selection) {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackgroundColor(0xFFF4F7FB);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF14213D);
        title.setPadding(0, 0, 0, dp(10));
        panel.addView(title);

        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, items));
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setDividerHeight(dp(1));
        list.setBackground(rounded(0xFFFFFFFF, 0xFFE4EAF2, dp(18)));
        list.setOnItemClickListener((parent, view, position, id) -> {
            selection.onSelected(position);
            dialog.dismiss();
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f);
        listParams.setMargins(0, 0, 0, dp(12));
        panel.addView(list, listParams);

        Button close = button("Cancel");
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close);

        dialog.setContentView(panel);
        return dialog;
    }

    private interface ListSelection {
        void onSelected(int position);
    }

    private boolean handleRootSwipe(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                return touchStartX < dp(32);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float dx = event.getX() - touchStartX;
                float dy = Math.abs(event.getY() - touchStartY);
                if (touchStartX < dp(32) && dx > dp(80) && dy < dp(80)) {
                    showSideMenu(true);
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    private boolean handleMenuSwipe(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float dx = event.getX() - touchStartX;
                float dy = Math.abs(event.getY() - touchStartY);
                if (dx < -dp(80) && dy < dp(90)) {
                    showSideMenu(false);
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    private EditText input(String hint) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setTextColor(0xFF1F2A44);
        edit.setHintTextColor(0xFF7E8AA2);
        edit.setPadding(dp(14), 0, dp(14), 0);
        edit.setBackground(rounded(0xFFFFFFFF, 0xFFD9E1EC, dp(14)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48));
        params.setMargins(0, dp(6), 0, dp(6));
        edit.setLayoutParams(params);
        return edit;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setBackground(rounded(0xFF2457A6, 0xFF2457A6, dp(16)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48));
        params.setMargins(0, dp(6), 0, dp(6));
        button.setLayoutParams(params);
        return button;
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setBackground(rounded(0xFF2457A6, 0xFF2457A6, dp(14)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), dp(52));
        params.setMargins(0, 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView summaryText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(0xFF46556F);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(rounded(0xFFF8FAFE, 0xFFD9E1EC, dp(14)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(rounded(0xFFFFFFFF, 0xFFE4EAF2, dp(22)));
        card.setElevation(dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, dp(10));
        card.setLayoutParams(params);
        return card;
    }

    private void styleSectionLabel(TextView label) {
        label.setTextSize(18);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(0xFF14213D);
        label.setPadding(0, 0, 0, dp(10));
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void loadPrefs() {
        repoInput.setText(prefs.getString("repo", ""));
        tokenInput.setText(prefs.getString("token", ""));
        branchInput.setText(prefs.getString("branch", ""));
        String bleAddress = prefs.getString("selectedBleAddress", "");
        if (!bleAddress.isEmpty()) {
            selectedBleDevice = new BleDeviceItem(
                    prefs.getString("selectedBleName", "BLE OTA device"),
                    bleAddress,
                    0);
        }
        restoreSelectedInfoDisplay();
        updateBleSummary();
        loadCachedBuildsIntoUi();
    }

    private void savePrefs() {
        prefs.edit()
                .putString("repo", repoInput.getText().toString().trim())
                .putString("token", tokenInput.getText().toString().trim())
                .putString("branch", branchInput.getText().toString().trim())
                .apply();
        hideKeyboard();
        setStatus("Repository settings saved.");
    }

    private void loginWithGitHub() {
        savePrefs();
        setBusy(true);
        networkExecutor.submit(() -> {
            try {
                String network = NetworkDiagnostics.requireInternet(this);
                runOnUiThread(() -> setStatus("Requesting GitHub login code...\nNetwork: " + network));
                GitHubOAuthClient.DeviceCode code = oauth.requestDeviceCode(GITHUB_OAUTH_CLIENT_ID, "repo");
                runOnUiThread(() -> {
                    copyToClipboard("GitHub device code", code.userCode);
                    setStatus("GitHub login code: " + code.userCode
                            + "\nCode copied to clipboard."
                            + "\nOpening " + code.verificationUri
                            + "\nAuthorize within " + (code.expiresInSeconds / 60) + " minutes.");
                    Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri));
                    startActivity(browser);
                });
                GitHubOAuthClient.TokenResult token = oauth.pollForToken(GITHUB_OAUTH_CLIENT_ID, code, seconds ->
                        runOnUiThread(() -> setStatus("Waiting for GitHub authorization. Code: "
                                + code.userCode + "\nNext check in " + seconds + " seconds.")));
                runOnUiThread(() -> {
                    tokenInput.setText(token.accessToken);
                    prefs.edit().putString("token", token.accessToken).apply();
                    setBusy(false);
                    setStatus("GitHub login complete. Token stored. Scope: " + token.scope);
                });
            } catch (Exception e) {
                fail(explain(e));
            }
        });
    }

    private void clearGitHubToken() {
        tokenInput.setText("");
        prefs.edit().putString("token", "").apply();
        setStatus("GitHub token cleared.");
    }

    private String authStatus() {
        String token = tokenInput.getText().toString().trim();
        return token.isEmpty() ? "\nGitHub auth: not logged in" : "\nGitHub auth: token present";
    }

    private void runNetworkDiagnostic() {
        setBusy(true);
        setStatus("Running GitHub network diagnostic...");
        networkExecutor.submit(() -> {
            String result = GitHubNetworkDiagnostic.run(this);
            runOnUiThread(() -> {
                setBusy(false);
                setStatus(result);
            });
        });
    }

    private void loadBuilds() {
        savePrefs();
        loadCachedBuildsIntoUi();
        setBusy(true);
        setStatus("Loading GitHub Actions runs..." + authStatus());
        networkExecutor.submit(() -> {
            try {
                NetworkDiagnostics.requireInternet(this);
                RepoConfig config = RepoConfig.parse(repoInput.getText().toString(), tokenInput.getText().toString());
                List<FirmwareBuild> loaded = github.listFirmwareBuilds(config, branchInput.getText().toString(), 30);
                BuildCache.save(getCacheDir(), config, branchInput.getText().toString(), loaded);
                runOnUiThread(() -> {
                    builds.clear();
                    builds.addAll(loaded);
                    selectedBuild = restoreSelectedBuild(builds);
                    selectedFirmware = null;
                    firmwareFiles.clear();
                    updateSelectedInfo();
                    updatePickerSummaries();
                    buildAdapter.notifyDataSetChanged();
                    firmwareAdapter.notifyDataSetChanged();
                    setBusy(false);
                    if (builds.isEmpty()) {
                        setStatus("No non-expired artifacts found in successful runs.");
                    } else {
                        setStatus("Loaded " + builds.size() + " artifacts. Loading firmware files from the latest artifact...");
                        loadFirmwareFilesForSelectedBuild();
                    }
                });
            } catch (Exception e) {
                fail(explain(e));
            }
        });
    }

    private void loadCachedBuildsIntoUi() {
        try {
            if (repoInput.getText().toString().trim().isEmpty()) {
                return;
            }
            RepoConfig config = RepoConfig.parse(repoInput.getText().toString(), tokenInput.getText().toString());
            List<FirmwareBuild> cached = BuildCache.load(getCacheDir(), config, branchInput.getText().toString());
            if (cached.isEmpty()) {
                return;
            }
            builds.clear();
            builds.addAll(cached);
            selectedBuild = restoreSelectedBuild(builds);
            firmwareFiles.clear();
            selectedFirmware = null;
            if (buildAdapter != null) {
                buildAdapter.notifyDataSetChanged();
            }
            if (firmwareAdapter != null) {
                firmwareAdapter.notifyDataSetChanged();
            }
            updateSelectedInfo();
            updatePickerSummaries();
            setStatus("Loaded " + cached.size() + " cached build artifacts. Use Load to refresh from GitHub.");
            loadFirmwareFilesForSelectedBuild();
        } catch (Exception ignored) {
        }
    }

    private void loadFirmwareFilesForSelectedBuild() {
        if (selectedBuild == null) return;
        setBusy(true);
        FirmwareBuild build = selectedBuild;
        setStatus("Downloading artifact firmware list..." + authStatus());
        networkExecutor.submit(() -> {
            try {
                NetworkDiagnostics.requireInternet(this);
                RepoConfig config = RepoConfig.parse(repoInput.getText().toString(), tokenInput.getText().toString());
                List<FirmwareFile> loaded = github.downloadFirmwareFiles(config, build, getCacheDir());
                runOnUiThread(() -> {
                    if (selectedBuild != build) {
                        return;
                    }
                    firmwareFiles.clear();
                    firmwareFiles.addAll(loaded);
                    selectedFirmware = restoreSelectedFirmware(firmwareFiles);
                    rememberSelectedFirmware();
                    firmwareAdapter.notifyDataSetChanged();
                    updateSelectedInfo();
                    updatePickerSummaries();
                    setBusy(false);
                    setStatus("Loaded " + firmwareFiles.size() + " firmware files from " + build.artifactName
                            + ". Cached artifact data is reused when available. Select the target side/board UF2 before writing.");
                });
            } catch (Exception e) {
                fail(explain(e));
            }
        });
    }

    private void pickBootloaderFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_BOOTLOADER_FOLDER);
    }

    private boolean ensureBlePermissions(boolean forScan) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (forScan && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else if (Build.VERSION.SDK_INT >= 23 && forScan
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (missing.isEmpty()) {
            return true;
        }
        pendingBleScan = forScan;
        pendingBleWrite = !forScan;
        requestPermissions(missing.toArray(new String[0]), REQ_BLE_PERMISSIONS);
        return false;
    }

    @SuppressWarnings("MissingPermission")
    private void startBleScan() {
        if (!ensureBlePermissions(true)) {
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            setStatus("BLE is not available on this device.");
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Bluetooth is disabled. Enable Bluetooth, then scan again.");
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("BLE scanner is not available yet. Try again after Bluetooth is ready.");
            return;
        }
        stopBleScan();
        bleDevices.clear();
        if (bleAdapter != null) {
            bleAdapter.notifyDataSetChanged();
        }
        scanningBle = true;
        setStatus("Scanning for BLE OTA devices. Put the target keyboard into BLE DFU mode.");
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                addOrUpdateBleDevice(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) {
                    addOrUpdateBleDevice(result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanningBle = false;
                setStatus("BLE scan failed: " + errorCode);
            }
        };
        scanner.startScan(scanCallback);
        mainHandler.postDelayed(() -> {
            if (scanningBle) {
                stopBleScan();
                setStatus("BLE scan finished. Select a device from the list, or rescan.");
            }
        }, 15000);
    }

    @SuppressWarnings("MissingPermission")
    private void stopBleScan() {
        if (!scanningBle || scanCallback == null) {
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (scanner != null) {
            scanner.stopScan(scanCallback);
        }
        scanningBle = false;
        scanCallback = null;
    }

    @SuppressWarnings("MissingPermission")
    private void addOrUpdateBleDevice(ScanResult result) {
        BluetoothDevice device = result.getDevice();
        if (device == null) {
            return;
        }
        String address = device.getAddress();
        String name = device.getName();
        if ((name == null || name.isEmpty()) && result.getScanRecord() != null) {
            name = result.getScanRecord().getDeviceName();
        }
        if (name == null || name.isEmpty()) {
            name = "Unknown BLE device";
        }
        for (int i = 0; i < bleDevices.size(); i++) {
            if (bleDevices.get(i).address.equals(address)) {
                bleDevices.set(i, new BleDeviceItem(name, address, result.getRssi()));
                bleAdapter.notifyDataSetChanged();
                return;
            }
        }
        bleDevices.add(new BleDeviceItem(name, address, result.getRssi()));
        bleAdapter.notifyDataSetChanged();
    }

    private void startBleDfu() {
        if (!ensureFirmwareSelected()) {
            return;
        }
        if (selectedFirmware == null || !selectedFirmware.name.toLowerCase(java.util.Locale.US).endsWith(".zip")) {
            toast("Select a BLE OTA ZIP firmware file first");
            setStatus("BLE OTA requires a Nordic/Adafruit DFU ZIP generated for the target keyboard.");
            return;
        }
        if (selectedBleDevice == null) {
            toast("Select a BLE OTA device first");
            showBleDeviceSelector();
            return;
        }
        if (!ensureBlePermissions(false)) {
            return;
        }
        stopBleScan();
        setBusy(true);
        setProgressPercent(0);
        setStatus("Starting BLE OTA for " + selectedBleDevice.name + "...");
        DfuServiceInitiator.createDfuNotificationChannel(this);
        DfuServiceInitiator initiator = new DfuServiceInitiator(selectedBleDevice.address)
                .setDeviceName(selectedBleDevice.name)
                .setKeepBond(false)
                .setRestoreBond(false)
                .setNumberOfRetries(2)
                .setPacketsReceiptNotificationsEnabled(true)
                .setPacketsReceiptNotificationsValue(12)
                .setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true)
                .setZip(selectedFirmware.file.getAbsolutePath());
        initiator.start(this, DfuUpdateService.class);
    }

    private void startWriteMode() {
        if (!ensureFirmwareSelected()) return;
        writeModeVolumeKeys.clear();
        writeModeVolumeKeys.addAll(currentRemovableVolumeKeys());
        bootloaderPollAttempts = 0;
        writeMode = true;
        setStatus("Write mode is armed. Existing removable volumes: " + writeModeVolumeKeys.size()
                + "\nPut the ZMK keyboard into bootloader mode. A newly mounted XIAO/BOOT/UF2 drive will be treated as the bootloader drive.");
    }

    private void writeSelectedBuild() {
        if (!ensureReady()) return;
        writeMode = false;
        bootloaderPollAttempts = 0;
        setBusy(true);
        setProgressPercent(0);
        setStatus("Preparing selected firmware...");
        FirmwareFile firmware = selectedFirmware;
        writeExecutor.submit(() -> {
            try {
                Uri folder = Uri.parse(prefs.getString("bootloaderUri", ""));
                runOnUiThread(() -> setStatus("Writing " + firmware.name + " to bootloader volume..."));
                writer.writeToBootloader(this, folder, firmware.file, firmware.name, (written, total) -> {
                    int percent = (int) Math.min(100L, (written * 100L) / Math.max(1L, total));
                    runOnUiThread(() -> {
                        setProgressPercent(percent);
                        setStatus("Writing " + firmware.name + "... " + percent + "%");
                    });
                });
                runOnUiThread(() -> {
                    setBusy(false);
                    setStatus("Firmware written: " + firmware.name + ". The keyboard should reboot after the bootloader processes it.");
                    updateSelectedInfo();
                });
            } catch (Exception e) {
                fail(explain(e));
            }
        });
    }

    private boolean ensureFirmwareSelected() {
        if (repoInput.getText().toString().trim().isEmpty()) {
            toast("Repo is required");
            return false;
        }
        if (selectedBuild == null) {
            if (builds.isEmpty()) {
                toast("Load builds first");
                return false;
            }
            selectedBuild = builds.get(0);
        }
        if (selectedFirmware == null) {
            toast("Select a firmware file inside the artifact first");
            return false;
        }
        updateSelectedInfo();
        return true;
    }

    private boolean ensureReady() {
        if (!ensureFirmwareSelected()) {
            return false;
        }
        if (prefs.getString("bootloaderUri", "").isEmpty()) {
            toast("Register the bootloader folder first");
            return false;
        }
        return true;
    }

    private void handleBootloaderDriveEvent(String reason) {
        Set<String> current = currentRemovableVolumeKeys();
        current.removeAll(writeModeVolumeKeys);
        if (current.isEmpty()) {
            retryBootloaderDetection(reason, "No newly mounted removable drive is visible yet.");
            return;
        }
        String detected = chooseBootloaderCandidate(current);
        if (!isLikelyBootloaderVolume(detected)) {
            retryBootloaderDetection(reason, "New removable drive does not look like a known bootloader volume: " + detected);
            return;
        }
        bootloaderPollAttempts = 0;
        setStatus(reason + ". Detected bootloader drive candidate: " + detected);
        if (prefs.getString("bootloaderUri", "").isEmpty()) {
            pendingWriteAfterFolderPick = true;
            setStatus("Bootloader drive detected: " + detected
                    + "\nAndroid requires folder permission before writing. Select this newly mounted bootloader drive.");
            pickBootloaderFolder();
            return;
        }
        writeSelectedBuild();
    }

    private void retryBootloaderDetection(String reason, String detail) {
        if (!writeMode) {
            return;
        }
        if (bootloaderPollAttempts >= 20) {
            setStatus(reason + ". " + detail + "\nTimed out waiting for XIAO/BOOT/UF2 removable drive. Re-arm write mode and try again.");
            return;
        }
        bootloaderPollAttempts++;
        setStatus(reason + ". " + detail + "\nWaiting for media mount... retry " + bootloaderPollAttempts + "/20");
        mainHandler.postDelayed(() -> {
            if (writeMode) {
                handleBootloaderDriveEvent("Bootloader drive poll");
            }
        }, 500);
    }

    private String chooseBootloaderCandidate(Set<String> candidates) {
        for (String candidate : candidates) {
            if (isLikelyBootloaderVolume(candidate)) {
                return candidate;
            }
        }
        return candidates.iterator().next();
    }

    private boolean isLikelyBootloaderVolume(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        return lower.contains("xiao")
                || lower.contains("boot")
                || lower.contains("uf2")
                || lower.contains("rp2040")
                || lower.contains("nrf52")
                || lower.contains("nicenano")
                || lower.contains("nice!nano");
    }

    private Set<String> currentRemovableVolumeKeys() {
        Set<String> keys = new HashSet<>();
        StorageManager storageManager = (StorageManager) getSystemService(STORAGE_SERVICE);
        if (storageManager == null) {
            return keys;
        }
        for (StorageVolume volume : storageManager.getStorageVolumes()) {
            if (volume.isRemovable()) {
                keys.add(volumeKey(volume));
            }
        }
        return keys;
    }

    private String volumeKey(StorageVolume volume) {
        String uuid = volume.getUuid();
        String description = volume.getDescription(this);
        if (uuid != null && !uuid.isEmpty()) {
            return description + ":" + uuid + ":" + volume.getState();
        }
        return description + ":" + volume.getState();
    }

    private void updateSelectedInfo() {
        if (selectedInfo == null) {
            return;
        }
        if (selectedBuild == null || selectedFirmware == null) {
            selectedInfo.setText("Selected firmware: none");
            updatePickerSummaries();
            return;
        }
        String shortSha = selectedBuild.sha == null || selectedBuild.sha.length() < 7
                ? selectedBuild.sha
                : selectedBuild.sha.substring(0, 7);
        selectedInfo.setText("Selected firmware: " + selectedFirmware.name
                + "\nArtifact: " + selectedBuild.artifactName
                + "\nBranch/commit: " + selectedBuild.branch + " @ " + shortSha
                + "\nBuild time: " + selectedBuild.createdAt);
        updatePickerSummaries();
    }

    private void updateBleSummary() {
        if (bleSummary == null) {
            return;
        }
        if (selectedBleDevice == null) {
            bleSummary.setText("No BLE DFU device selected");
        } else {
            bleSummary.setText(selectedBleDevice.name
                    + "\n" + selectedBleDevice.address
                    + "\nUse a .zip firmware generated for BLE OTA.");
        }
    }

    private void updatePickerSummaries() {
        if (artifactSummary != null) {
            if (selectedBuild == null) {
                artifactSummary.setText("No artifact selected");
            } else {
                String shortSha = selectedBuild.sha == null || selectedBuild.sha.length() < 7
                        ? selectedBuild.sha
                        : selectedBuild.sha.substring(0, 7);
                artifactSummary.setText(selectedBuild.artifactName
                        + "\n" + selectedBuild.branch + " @ " + shortSha
                        + "\n" + selectedBuild.createdAt);
            }
        }
        if (firmwareSummary != null) {
            if (selectedFirmware == null) {
                firmwareSummary.setText("No firmware selected");
            } else {
                firmwareSummary.setText(selectedFirmware.name
                        + "\n" + selectedFirmware.zipPath
                        + "\n" + (selectedFirmware.sizeBytes / 1024) + " KiB");
            }
        }
    }

    private void rememberSelectedBuild() {
        if (selectedBuild == null) {
            prefs.edit()
                    .remove("selectedArtifactId")
                    .remove("selectedFirmwareName")
                    .apply();
            return;
        }
        prefs.edit()
                .putLong("selectedArtifactId", selectedBuild.artifactId)
                .remove("selectedFirmwareName")
                .apply();
    }

    private void rememberSelectedFirmware() {
        if (selectedBuild == null || selectedFirmware == null) {
            return;
        }
        prefs.edit()
                .putLong("selectedArtifactId", selectedBuild.artifactId)
                .putString("selectedFirmwareName", selectedFirmware.name)
                .putString("selectedArtifactName", selectedBuild.artifactName)
                .putString("selectedBranch", selectedBuild.branch)
                .putString("selectedSha", selectedBuild.sha)
                .putString("selectedCreatedAt", selectedBuild.createdAt)
                .apply();
    }

    private FirmwareBuild restoreSelectedBuild(List<FirmwareBuild> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        long savedArtifactId = prefs.getLong("selectedArtifactId", -1);
        if (savedArtifactId != -1) {
            for (FirmwareBuild build : candidates) {
                if (build.artifactId == savedArtifactId) {
                    return build;
                }
            }
        }
        return candidates.get(0);
    }

    private FirmwareFile restoreSelectedFirmware(List<FirmwareFile> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        String savedName = prefs.getString("selectedFirmwareName", "");
        if (!savedName.isEmpty()) {
            for (FirmwareFile firmware : candidates) {
                if (savedName.equals(firmware.name)) {
                    return firmware;
                }
            }
        }
        return candidates.get(0);
    }

    private void restoreSelectedInfoDisplay() {
        if (selectedInfo == null) {
            return;
        }
        String firmware = prefs.getString("selectedFirmwareName", "");
        if (firmware.isEmpty()) {
            selectedInfo.setText("Selected firmware: none");
            updatePickerSummaries();
            return;
        }
        String sha = prefs.getString("selectedSha", "");
        String shortSha = sha.length() < 7 ? sha : sha.substring(0, 7);
        selectedInfo.setText("Selected firmware: " + firmware
                + "\nArtifact: " + prefs.getString("selectedArtifactName", "")
                + "\nBranch/commit: " + prefs.getString("selectedBranch", "") + " @ " + shortSha
                + "\nBuild time: " + prefs.getString("selectedCreatedAt", "")
                + "\nReload builds if write controls say selection is missing.");
        if (artifactSummary != null) {
            artifactSummary.setText(prefs.getString("selectedArtifactName", "")
                    + "\n" + prefs.getString("selectedBranch", "") + " @ " + shortSha
                    + "\n" + prefs.getString("selectedCreatedAt", ""));
        }
        if (firmwareSummary != null) {
            firmwareSummary.setText(firmware);
        }
    }

    private void restoreSelectionFromPrefsAndCache() {
        if (selectedBuild != null && selectedFirmware != null) {
            return;
        }
        long artifactId = prefs.getLong("selectedArtifactId", -1);
        String firmwareName = prefs.getString("selectedFirmwareName", "");
        if (artifactId == -1 || firmwareName.isEmpty()) {
            restoreSelectedInfoDisplay();
            return;
        }
        selectedBuild = new FirmwareBuild(
                0,
                prefs.getString("selectedBranch", ""),
                prefs.getString("selectedSha", ""),
                prefs.getString("selectedCreatedAt", ""),
                "",
                "",
                artifactId,
                prefs.getString("selectedArtifactName", ""),
                "");
        List<FirmwareFile> cached = github.cachedFirmwareFiles(getCacheDir(), artifactId);
        if (!cached.isEmpty()) {
            firmwareFiles.clear();
            firmwareFiles.addAll(cached);
            selectedFirmware = restoreSelectedFirmware(firmwareFiles);
            if (firmwareAdapter != null) {
                firmwareAdapter.notifyDataSetChanged();
            }
        }
        updateSelectedInfo();
    }

    private void setBusy(boolean busy) {
        progress.setIndeterminate(busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void setProgressPercent(int percent) {
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgress(Math.max(0, Math.min(100, percent)));
        progress.setVisibility(View.VISIBLE);
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void fail(Exception e) {
        runOnUiThread(() -> {
            setBusy(false);
            setStatus("Error: " + e.getMessage());
        });
    }

    private Exception explain(Exception e) {
        if (e instanceof java.io.IOException) {
            return NetworkDiagnostics.explain((java.io.IOException) e);
        }
        Throwable cause = e.getCause();
        if (cause instanceof java.io.IOException) {
            return NetworkDiagnostics.explain((java.io.IOException) cause);
        }
        return e;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        }
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View current = getCurrentFocus();
        if (imm != null && current != null) {
            imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class BleDeviceItem {
        final String name;
        final String address;
        final int rssi;

        BleDeviceItem(String name, String address, int rssi) {
            this.name = name;
            this.address = address;
            this.rssi = rssi;
        }

        @Override
        public String toString() {
            String signal = rssi == 0 ? "" : "\nRSSI: " + rssi + " dBm";
            return name + "\n" + address + signal;
        }
    }
}
