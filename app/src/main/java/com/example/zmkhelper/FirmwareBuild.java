package com.example.zmkhelper;

import org.json.JSONException;
import org.json.JSONObject;

public final class FirmwareBuild {
    public final long runId;
    public final String branch;
    public final String sha;
    public final String createdAt;
    public final String title;
    public final String conclusion;
    public final long artifactId;
    public final String artifactName;
    public final String fileExtension;

    public FirmwareBuild(long runId, String branch, String sha, String createdAt, String title,
            String conclusion, long artifactId, String artifactName, String fileExtension) {
        this.runId = runId;
        this.branch = branch;
        this.sha = sha;
        this.createdAt = createdAt;
        this.title = title;
        this.conclusion = conclusion;
        this.artifactId = artifactId;
        this.artifactName = artifactName;
        this.fileExtension = fileExtension;
    }

    @Override
    public String toString() {
        String shortSha = sha == null || sha.length() < 7 ? sha : sha.substring(0, 7);
        return branch + " @ " + shortSha + "  " + createdAt + "\n" + artifactName + "  " + title;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("runId", runId);
        json.put("branch", branch);
        json.put("sha", sha);
        json.put("createdAt", createdAt);
        json.put("title", title);
        json.put("conclusion", conclusion);
        json.put("artifactId", artifactId);
        json.put("artifactName", artifactName);
        json.put("fileExtension", fileExtension);
        return json;
    }

    public static FirmwareBuild fromJson(JSONObject json) {
        return new FirmwareBuild(
                json.optLong("runId"),
                json.optString("branch", ""),
                json.optString("sha", ""),
                json.optString("createdAt", ""),
                json.optString("title", ""),
                json.optString("conclusion", ""),
                json.optLong("artifactId"),
                json.optString("artifactName", ""),
                json.optString("fileExtension", ""));
    }
}
