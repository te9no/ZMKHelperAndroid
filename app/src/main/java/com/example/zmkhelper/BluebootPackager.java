package com.example.zmkhelper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BluebootPackager {
    public static final int DEFAULT_SD_REQ = 0x0123;
    public static final int DEFAULT_DEV_TYPE = 0x0052;

    private static final int UF2_BLOCK_SIZE = 512;
    private static final int UF2_MAGIC_START_0 = 0x0A324655;
    private static final int UF2_MAGIC_START_1 = 0x9E5D5157;
    private static final int UF2_MAGIC_END = 0x0AB16F30;
    private static final int UF2_FLAG_NO_FLASH = 0x00000001;
    private static final int UICR_START = 0x10000000;

    public FirmwareFile packageUf2(File cacheDir, FirmwareFile uf2Firmware) throws IOException {
        if (uf2Firmware == null || !uf2Firmware.name.toLowerCase(java.util.Locale.US).endsWith(".uf2")) {
            throw new IOException("Blueboot packaging requires a .uf2 firmware file");
        }
        File outDir = uf2Firmware.file.getParentFile() != null ? uf2Firmware.file.getParentFile() : new File(cacheDir, "blueboot");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create Blueboot cache");
        }
        String baseName = stripExtension(uf2Firmware.name);
        File output = new File(outDir, baseName + "-blueboot.zip");
        byte[] application = uf2ToApplicationBin(uf2Firmware.file);
        byte[] initPacket = createInitPacket(application, DEFAULT_DEV_TYPE, 0xFFFF, 0xFFFFFFFF, DEFAULT_SD_REQ);
        writeZip(output, baseName + ".bin", application, baseName + ".dat", initPacket);
        return new FirmwareFile(output.getName(), "Blueboot package from " + uf2Firmware.name, output, output.length());
    }

    private static byte[] uf2ToApplicationBin(File uf2) throws IOException {
        TreeMap<Integer, Integer> image = readUf2(uf2);
        int start = image.firstKey();
        int end = image.lastKey();
        int size = roundUp(end - start + 1, 4);
        byte[] out = new byte[size];
        java.util.Arrays.fill(out, (byte) 0xFF);
        for (Map.Entry<Integer, Integer> entry : image.entrySet()) {
            out[entry.getKey() - start] = (byte) (int) entry.getValue();
        }
        return out;
    }

    private static TreeMap<Integer, Integer> readUf2(File uf2) throws IOException {
        long length = uf2.length();
        if (length == 0 || length % UF2_BLOCK_SIZE != 0) {
            throw new IOException(uf2.getName() + " is not a complete UF2 image");
        }
        TreeMap<Integer, Integer> image = new TreeMap<>();
        byte[] block = new byte[UF2_BLOCK_SIZE];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(uf2))) {
            for (long blockOffset = 0; blockOffset < length; blockOffset += UF2_BLOCK_SIZE) {
                readFully(in, block);
                int magic0 = le32(block, 0);
                int magic1 = le32(block, 4);
                int flags = le32(block, 8);
                int targetAddress = le32(block, 12);
                int payloadSize = le32(block, 16);
                int magicEnd = le32(block, UF2_BLOCK_SIZE - 4);
                if (magic0 != UF2_MAGIC_START_0 || magic1 != UF2_MAGIC_START_1 || magicEnd != UF2_MAGIC_END) {
                    throw new IOException(uf2.getName() + " contains an invalid UF2 block at offset " + blockOffset);
                }
                if ((flags & UF2_FLAG_NO_FLASH) != 0) {
                    continue;
                }
                if (payloadSize < 0 || payloadSize > 476) {
                    throw new IOException(uf2.getName() + " contains an oversized UF2 payload");
                }
                for (int i = 0; i < payloadSize; i++) {
                    int address = targetAddress + i;
                    if (address >= UICR_START) {
                        continue;
                    }
                    int value = block[32 + i] & 0xFF;
                    Integer previous = image.putIfAbsent(address, value);
                    if (previous != null && previous != value) {
                        throw new IOException(uf2.getName() + " contains conflicting data at 0x"
                                + Integer.toHexString(address));
                    }
                }
            }
        }
        if (image.isEmpty()) {
            throw new IOException(uf2.getName() + " has no flashable UF2 data");
        }
        return image;
    }

    private static byte[] createInitPacket(byte[] application, int devType, int devRevision, long appVersion,
            int sdReq) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLe16(out, devType);
        writeLe16(out, devRevision);
        writeLe32(out, appVersion);
        writeLe16(out, 1);
        writeLe16(out, sdReq);
        writeLe16(out, crc16(application));
        return out.toByteArray();
    }

    private static int crc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte datum : data) {
            crc = ((crc >> 8) & 0x00FF) | ((crc << 8) & 0xFF00);
            crc ^= datum & 0xFF;
            crc ^= (crc & 0x00FF) >> 4;
            crc ^= (crc << 8) << 4;
            crc ^= ((crc & 0x00FF) << 4) << 1;
        }
        return crc & 0xFFFF;
    }

    private static void writeZip(File output, String binName, byte[] application, String datName, byte[] initPacket)
            throws IOException {
        String manifest = "{\"manifest\":{\"application\":{\"bin_file\":\"" + jsonEscape(binName)
                + "\",\"dat_file\":\"" + jsonEscape(datName) + "\"}}}";
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            writeEntry(zip, binName, application);
            writeEntry(zip, datName, initPacket);
            writeEntry(zip, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void readFully(BufferedInputStream in, byte[] block) throws IOException {
        int offset = 0;
        while (offset < block.length) {
            int read = in.read(block, offset, block.length - offset);
            if (read == -1) {
                throw new IOException("Unexpected EOF while reading UF2");
            }
            offset += read;
        }
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, long value) {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static int roundUp(int value, int multiple) {
        return ((value + multiple - 1) / multiple) * multiple;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
