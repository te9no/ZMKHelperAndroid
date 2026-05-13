package com.example.zmkhelper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;

public final class GitHubOAuthClient {
    public static final String DEVICE_LOGIN_URL = "https://github.com/login/device";
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    public DeviceCode requestDeviceCode(String clientId, String scope) throws Exception {
        String body = "client_id=" + form(clientId) + "&scope=" + form(scope);
        JSONObject json = postForm(DEVICE_CODE_URL, body);
        return new DeviceCode(
                json.getString("device_code"),
                json.getString("user_code"),
                json.optString("verification_uri", DEVICE_LOGIN_URL),
                json.optInt("expires_in", 900),
                json.optInt("interval", 5));
    }

    public TokenResult pollForToken(String clientId, DeviceCode deviceCode, PollCallback callback) throws Exception {
        int intervalSeconds = Math.max(1, deviceCode.intervalSeconds);
        long expiresAt = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L;
        while (System.currentTimeMillis() < expiresAt) {
            callback.onWaiting(intervalSeconds);
            Thread.sleep(intervalSeconds * 1000L);
            String body = "client_id=" + form(clientId)
                    + "&device_code=" + form(deviceCode.deviceCode)
                    + "&grant_type=" + form(DEVICE_GRANT_TYPE);
            JSONObject json = postForm(ACCESS_TOKEN_URL, body);
            String accessToken = json.optString("access_token", "");
            if (!accessToken.isEmpty()) {
                return new TokenResult(accessToken, json.optString("scope", ""), json.optString("token_type", "bearer"));
            }
            String error = json.optString("error", "");
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                intervalSeconds = json.has("interval") ? json.optInt("interval", intervalSeconds + 5) : intervalSeconds + 5;
                continue;
            }
            if ("expired_token".equals(error) || "token_expired".equals(error)) {
                throw new IOException("GitHub login code expired. Start login again.");
            }
            if ("access_denied".equals(error)) {
                throw new IOException("GitHub login was cancelled.");
            }
            if (!error.isEmpty()) {
                throw new IOException("GitHub login failed: " + error + " " + json.optString("error_description", ""));
            }
            throw new IOException("GitHub login failed: " + json);
        }
        throw new IOException("GitHub login code expired. Start login again.");
    }

    private JSONObject postForm(String url, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "ZMK-Helper-Android");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes("UTF-8"));
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = readFully(stream);
        if (code < 200 || code >= 300) {
            if (code == 400 && response.toLowerCase().contains("device flow")) {
                throw new IOException("GitHub OAuth App has Device Flow disabled. Enable Device Flow in the OAuth App settings for client_id.");
            }
            throw new IOException("GitHub OAuth HTTP " + code + ": " + response);
        }
        return new JSONObject(response);
    }

    private static String form(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
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

    public interface PollCallback {
        void onWaiting(int intervalSeconds);
    }

    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUri;
        public final int expiresInSeconds;
        public final int intervalSeconds;

        public DeviceCode(String deviceCode, String userCode, String verificationUri, int expiresInSeconds, int intervalSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresInSeconds = expiresInSeconds;
            this.intervalSeconds = intervalSeconds;
        }
    }

    public static final class TokenResult {
        public final String accessToken;
        public final String scope;
        public final String tokenType;

        public TokenResult(String accessToken, String scope, String tokenType) {
            this.accessToken = accessToken;
            this.scope = scope;
            this.tokenType = tokenType;
        }
    }
}
