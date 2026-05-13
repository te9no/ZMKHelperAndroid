package com.example.zmkhelper;

import android.content.Context;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

public final class GitHubNetworkDiagnostic {
    private GitHubNetworkDiagnostic() {
    }

    public static String run(Context context) {
        StringBuilder out = new StringBuilder();
        try {
            out.append("Network: ").append(NetworkDiagnostics.requireInternet(context)).append('\n');
        } catch (Exception e) {
            out.append("Network: ").append(e.getMessage()).append('\n');
        }
        checkDns(out, "github.com");
        checkDns(out, "api.github.com");
        checkHttps(out, "https://github.com");
        checkHttps(out, "https://api.github.com/rate_limit");
        return out.toString().trim();
    }

    private static void checkDns(StringBuilder out, String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            out.append("DNS ").append(host).append(": ok");
            if (addresses.length > 0) {
                out.append(" -> ").append(addresses[0].getHostAddress());
            }
            out.append('\n');
        } catch (Exception e) {
            out.append("DNS ").append(host).append(": failed: ").append(e.getMessage()).append('\n');
        }
    }

    private static void checkHttps(StringBuilder out, String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "ZMK-Helper-Android");
            int code = conn.getResponseCode();
            try (InputStream ignored = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                out.append("HTTPS ").append(url).append(": HTTP ").append(code).append('\n');
            }
        } catch (Exception e) {
            out.append("HTTPS ").append(url).append(": failed: ").append(e.getMessage()).append('\n');
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
