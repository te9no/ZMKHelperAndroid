package com.example.zmkhelper;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CdcDebugManager {
    interface Listener {
        void onData(String text);

        void onError(Exception error);
    }

    static final class PortInfo {
        final UsbDevice device;
        final int portIndex;
        final String label;

        PortInfo(UsbDevice device, int portIndex, String label) {
            this.device = device;
            this.portIndex = portIndex;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private UsbSerialPort openPort;
    private SerialInputOutputManager ioManager;
    private int openDeviceId = -1;
    private int openPortIndex = -1;

    List<PortInfo> discover(UsbManager usbManager) {
        List<PortInfo> ports = new ArrayList<>();
        for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) {
            UsbDevice device = driver.getDevice();
            String product = device.getProductName();
            String name = product == null || product.trim().isEmpty() ? "USB CDC device" : product;
            for (int i = 0; i < driver.getPorts().size(); i++) {
                String label = name
                        + "\nPort " + (i + 1) + "/" + driver.getPorts().size()
                        + "  VID:PID " + hex(device.getVendorId()) + ":" + hex(device.getProductId())
                        + "  device " + device.getDeviceId();
                ports.add(new PortInfo(device, i, label));
            }
        }
        return ports;
    }

    synchronized void connect(UsbManager usbManager, PortInfo info, Listener listener) throws IOException {
        disconnect();
        UsbSerialPort port = resolvePort(usbManager, info);
        UsbDeviceConnection connection = usbManager.openDevice(info.device);
        if (connection == null) {
            throw new IOException("USB permission was not granted or the CDC device disconnected");
        }
        try {
            port.open(connection);
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setDTR(true);
            SerialInputOutputManager manager = new SerialInputOutputManager(port,
                    new SerialInputOutputManager.Listener() {
                        @Override
                        public void onNewData(byte[] data) {
                            listener.onData(new String(data, StandardCharsets.UTF_8));
                        }

                        @Override
                        public void onRunError(Exception error) {
                            synchronized (CdcDebugManager.this) {
                                if (openPort == port) {
                                    openDeviceId = -1;
                                    openPortIndex = -1;
                                }
                            }
                            listener.onError(error);
                        }
                    });
            openPort = port;
            openDeviceId = info.device.getDeviceId();
            openPortIndex = info.portIndex;
            ioManager = manager;
            manager.start();
        } catch (IOException | RuntimeException error) {
            try {
                port.close();
            } catch (IOException ignored) {
            }
            throw error;
        }
    }

    synchronized PortInfo triggerBootloader(UsbManager usbManager, List<PortInfo> candidates) throws IOException {
        if (candidates.isEmpty()) {
            throw new IOException("No CDC port is available for the bootloader trigger");
        }
        disconnect();
        IOException lastError = null;
        for (PortInfo info : candidates) {
            try {
                touchPortAt1200Baud(usbManager, info);
            } catch (IOException error) {
                if (!isDeviceAttached(usbManager, info.device.getDeviceId())) {
                    return info;
                }
                lastError = error;
                continue;
            }
            if (!isDeviceAttached(usbManager, info.device.getDeviceId())) {
                return info;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("The keyboard stayed connected after trying every CDC port at 1200 baud");
    }

    private void touchPortAt1200Baud(UsbManager usbManager, PortInfo info) throws IOException {
        UsbSerialPort port = resolvePort(usbManager, info);
        UsbDeviceConnection connection = usbManager.openDevice(info.device);
        if (connection == null) {
            throw new IOException("USB permission was not granted or the CDC device disconnected");
        }
        try {
            port.open(connection);
            port.setParameters(1200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setDTR(true);
            waitForFirmwarePoll();
            port.setDTR(false);
            waitForFirmwarePoll();
        } finally {
            try {
                port.close();
            } catch (IOException ignored) {
            }
        }
    }

    synchronized void disconnect() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        if (openPort != null) {
            try {
                openPort.close();
            } catch (IOException ignored) {
            }
            openPort = null;
        }
        openDeviceId = -1;
        openPortIndex = -1;
    }

    synchronized boolean isConnectedTo(PortInfo info) {
        return openPort != null
                && openDeviceId == info.device.getDeviceId()
                && openPortIndex == info.portIndex;
    }

    private UsbSerialPort resolvePort(UsbManager usbManager, PortInfo info) throws IOException {
        for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)) {
            if (driver.getDevice().getDeviceId() == info.device.getDeviceId()) {
                if (info.portIndex >= driver.getPorts().size()) {
                    break;
                }
                return driver.getPorts().get(info.portIndex);
            }
        }
        throw new IOException("The selected CDC port is no longer attached");
    }

    private static String hex(int value) {
        return String.format("%04X", value & 0xFFFF);
    }

    private static void waitForFirmwarePoll() throws IOException {
        try {
            // The ZMK trigger polls line state every 100 ms. Hold each DTR state across two polls.
            Thread.sleep(250);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the CDC bootloader trigger", error);
        }
    }

    private static boolean isDeviceAttached(UsbManager usbManager, int deviceId) {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (device.getDeviceId() == deviceId) {
                return true;
            }
        }
        return false;
    }
}
