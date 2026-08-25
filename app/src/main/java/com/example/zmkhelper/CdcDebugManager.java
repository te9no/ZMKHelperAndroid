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
                            listener.onError(error);
                        }
                    });
            openPort = port;
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

    synchronized void triggerBootloader(UsbManager usbManager, PortInfo info) throws IOException {
        disconnect();
        UsbSerialPort port = resolvePort(usbManager, info);
        UsbDeviceConnection connection = usbManager.openDevice(info.device);
        if (connection == null) {
            throw new IOException("USB permission was not granted or the CDC device disconnected");
        }
        try {
            port.open(connection);
            port.setParameters(1200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try {
                port.setDTR(false);
            } catch (IOException | UnsupportedOperationException ignored) {
            }
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
}
