package com.example.zmkhelper;

import java.io.File;

public final class FirmwareFile {
    public final String name;
    public final String zipPath;
    public final File file;
    public final long sizeBytes;

    public FirmwareFile(String name, String zipPath, File file, long sizeBytes) {
        this.name = name;
        this.zipPath = zipPath;
        this.file = file;
        this.sizeBytes = sizeBytes;
    }

    @Override
    public String toString() {
        return name + "  (" + (sizeBytes / 1024) + " KiB)\n" + zipPath;
    }
}
