package com.example.zmkhelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GitHubActionsClient {
    private static final String API = "https://api.github.com";

    public List<String> listBranches(RepoConfig config) throws Exception {
        JSONArray array = getArray(config, API + "/repos/" + config.owner + "/" + config.repo + "/branches?per_page=100");
        List<String> branches = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject branch = array.getJSONObject(i);
            String name = branch.optString("name", "");
            if (!name.isEmpty()) {
                branches.add(name);
            }
        }
        return branches;
    }

    public List<String> listBranchesFromWorkflowRuns(RepoConfig config, int limit) throws Exception {
        JSONObject root = getJson(config, API + "/repos/" + config.owner + "/" + config.repo
                + "/actions/runs?per_page=" + limit);
        JSONArray runs = root.getJSONArray("workflow_runs");
        Set<String> branches = new LinkedHashSet<>();
        for (int i = 0; i < runs.length(); i++) {
            String branch = runs.getJSONObject(i).optString("head_branch", "");
            if (!branch.isEmpty()) {
                branches.add(branch);
            }
        }
        return new ArrayList<>(branches);
    }

    public List<FirmwareBuild> listFirmwareBuilds(RepoConfig config, String branch, int limit) throws Exception {
        String url = API + "/repos/" + config.owner + "/" + config.repo + "/actions/runs?status=success&per_page=" + limit;
        if (branch != null && !branch.trim().isEmpty()) {
            url += "&branch=" + encodePath(branch.trim());
        }
        JSONObject root = getJson(config, url);
        JSONArray runs = root.getJSONArray("workflow_runs");
        List<FirmwareBuild> result = new ArrayList<>();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.getJSONObject(i);
            long runId = run.getLong("id");
            String runBranch = run.optString("head_branch", "");
            String sha = run.optString("head_sha", "");
            String createdAt = run.optString("created_at", "");
            String title = run.optString("display_title", run.optString("name", ""));
            String conclusion = run.optString("conclusion", "");
            JSONArray artifacts = getJson(config, API + "/repos/" + config.owner + "/" + config.repo + "/actions/runs/" + runId + "/artifacts")
                    .getJSONArray("artifacts");
            for (int j = 0; j < artifacts.length(); j++) {
                JSONObject artifact = artifacts.getJSONObject(j);
                if (artifact.optBoolean("expired", false)) {
                    continue;
                }
                String name = artifact.optString("name", "artifact");
                String ext = inferFirmwareExtension(name);
                result.add(new FirmwareBuild(runId, runBranch, sha, createdAt, title, conclusion,
                        artifact.getLong("id"), name, ext));
            }
        }
        return result;
    }

    public List<FirmwareFile> downloadFirmwareFiles(RepoConfig config, FirmwareBuild build, File cacheDir) throws Exception {
        File zip = new File(cacheDir, "artifact-" + build.artifactId + ".zip");
        List<FirmwareFile> cached = readCachedFirmwareFiles(cacheDir, build);
        if (!cached.isEmpty()) {
            return cached;
        }
        if (!zip.exists() || zip.length() == 0) {
            if (config.token == null || config.token.isEmpty()) {
                throw new IOException("Artifact download requires GitHub authentication. Tap Login with GitHub first.");
            }
            downloadToFile(config, API + "/repos/" + config.owner + "/" + config.repo + "/actions/artifacts/" + build.artifactId + "/zip", zip);
        }
        List<FirmwareFile> extracted = extractFirmwareFiles(zip, cacheDir, build);
        if (extracted.isEmpty()) {
            throw new IOException("Artifact did not contain .uf2, .bin, or .hex firmware");
        }
        return extracted;
    }

    public List<FirmwareFile> cachedFirmwareFiles(File cacheDir, long artifactId) {
        return readCachedFirmwareFiles(cacheDir, artifactId);
    }

    private JSONObject getJson(RepoConfig config, String url) throws Exception {
        HttpURLConnection conn = open(config, url);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readFully(stream);
        if (code < 200 || code >= 300) {
            throw apiException(code, body);
        }
        return new JSONObject(body);
    }

    private JSONArray getArray(RepoConfig config, String url) throws Exception {
        HttpURLConnection conn = open(config, url);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readFully(stream);
        if (code < 200 || code >= 300) {
            throw apiException(code, body);
        }
        return new JSONArray(body);
    }

    private IOException apiException(int code, String body) {
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return new IOException("GitHub API 401: login with GitHub again. For private repos, the OAuth token must have repo access.");
        }
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            return new IOException("GitHub API 404: repository not found or token has no access. Check owner/repo and GitHub login.");
        }
        if (code == 403) {
            return new IOException("GitHub API 403: access denied or rate limited. Login with GitHub and try again. " + body);
        }
        return new IOException("GitHub API " + code + ": " + body);
    }

    private void downloadToFile(RepoConfig config, String url, File out) throws Exception {
        HttpURLConnection conn = open(config, url, true);
        conn.setInstanceFollowRedirects(false);
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == 307) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = open(config, location, false);
            conn.setInstanceFollowRedirects(true);
            code = conn.getResponseCode();
        }
        if (code < 200 || code >= 300) {
            String body = readFully(conn.getErrorStream());
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new IOException("Artifact download failed: HTTP 401. Login with GitHub again and ensure the OAuth token has repo access to this repository. " + body);
            }
            throw new IOException("Artifact download failed: HTTP " + code + " " + body);
        }
        try (InputStream in = new BufferedInputStream(conn.getInputStream()); FileOutputStream file = new FileOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                file.write(buffer, 0, read);
            }
        }
    }

    private HttpURLConnection open(RepoConfig config, String url) throws IOException {
        return open(config, url, true);
    }

    private HttpURLConnection open(RepoConfig config, String url, boolean includeAuth) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "ZMK-Helper-Android");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (includeAuth && config.token != null && !config.token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + config.token);
        }
        return conn;
    }

    private List<FirmwareFile> extractFirmwareFiles(File zip, File cacheDir, FirmwareBuild build) throws IOException {
        File outDir = new File(cacheDir, "firmware-" + build.artifactId);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create firmware cache");
        }
        List<FirmwareFile> files = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !isFirmwareName(entry.getName())) {
                    continue;
                }
                String displayName = new File(entry.getName()).getName();
                String safeName = files.size() + "-" + displayName;
                File out = new File(outDir, safeName);
                try (FileOutputStream file = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        file.write(buffer, 0, read);
                    }
                }
                files.add(new FirmwareFile(displayName, entry.getName(), out, out.length()));
            }
        }
        return files;
    }

    private List<FirmwareFile> readCachedFirmwareFiles(File cacheDir, FirmwareBuild build) {
        return readCachedFirmwareFiles(cacheDir, build.artifactId);
    }

    private List<FirmwareFile> readCachedFirmwareFiles(File cacheDir, long artifactId) {
        File outDir = new File(cacheDir, "firmware-" + artifactId);
        List<FirmwareFile> files = new ArrayList<>();
        File[] cached = outDir.listFiles();
        if (cached == null) {
            return files;
        }
        for (File file : cached) {
            if (!file.isFile() || !isFirmwareName(file.getName())) {
                continue;
            }
            String displayName = file.getName();
            int dash = displayName.indexOf('-');
            if (dash >= 0 && dash + 1 < displayName.length()) {
                displayName = displayName.substring(dash + 1);
            }
            files.add(new FirmwareFile(displayName, displayName, file, file.length()));
        }
        return files;
    }

    private static String inferFirmwareExtension(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.contains("uf2")) return ".uf2";
        if (lower.contains("bin")) return ".bin";
        if (lower.contains("hex")) return ".hex";
        if (lower.contains("zip")) return ".zip";
        return "";
    }

    private static boolean isFirmwareName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".uf2") || lower.endsWith(".bin") || lower.endsWith(".hex") || lower.endsWith(".zip");
    }

    private static String encodePath(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return value.replace(" ", "%20");
        }
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }
}
