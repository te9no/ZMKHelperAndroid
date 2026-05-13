package com.example.zmkhelper;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class BuildCache {
    private BuildCache() {
    }

    public static void save(File cacheDir, RepoConfig config, String branch, List<FirmwareBuild> builds) throws Exception {
        File file = cacheFile(cacheDir, config, branch);
        JSONArray array = new JSONArray();
        for (FirmwareBuild build : builds) {
            array.put(build.toJson());
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(array.toString().getBytes("UTF-8"));
        }
    }

    public static List<FirmwareBuild> load(File cacheDir, RepoConfig config, String branch) {
        List<FirmwareBuild> builds = new ArrayList<>();
        File file = cacheFile(cacheDir, config, branch);
        if (!file.exists() || file.length() == 0) {
            return builds;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            JSONArray array = new JSONArray(out.toString("UTF-8"));
            for (int i = 0; i < array.length(); i++) {
                builds.add(FirmwareBuild.fromJson(array.getJSONObject(i)));
            }
        } catch (Exception ignored) {
            builds.clear();
        }
        return builds;
    }

    private static File cacheFile(File cacheDir, RepoConfig config, String branch) {
        String key = config.owner + "-" + config.repo + "-" + (branch == null ? "" : branch.trim());
        key = key.replaceAll("[^A-Za-z0-9._-]", "_");
        return new File(cacheDir, "builds-" + key + ".json");
    }
}
