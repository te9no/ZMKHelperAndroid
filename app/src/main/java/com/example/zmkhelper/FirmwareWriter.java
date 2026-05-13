package com.example.zmkhelper;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public final class FirmwareWriter {
    public void writeToBootloader(Context context, Uri folderUri, File firmware, String destinationName,
            ProgressCallback callback) throws Exception {
        String mime = destinationName.endsWith(".uf2") ? "application/octet-stream" : "application/octet-stream";
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri));
        Uri doc = DocumentsContract.createDocument(
                context.getContentResolver(),
                parent,
                mime,
                destinationName);
        if (doc == null) {
            throw new IllegalStateException("Could not create firmware file on selected bootloader volume");
        }
        try (FileInputStream in = new FileInputStream(firmware);
             OutputStream out = context.getContentResolver().openOutputStream(doc, "w")) {
            if (out == null) {
                throw new IllegalStateException("Could not open bootloader firmware destination");
            }
            byte[] buffer = new byte[8192];
            int read;
            long total = Math.max(1L, firmware.length());
            long written = 0L;
            if (callback != null) {
                callback.onProgress(0L, total);
            }
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                written += read;
                if (callback != null) {
                    callback.onProgress(written, total);
                }
            }
            out.flush();
        }
    }

    public interface ProgressCallback {
        void onProgress(long writtenBytes, long totalBytes);
    }
}
