package com.example.zmkhelper;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.net.UnknownHostException;

public final class NetworkDiagnostics {
    private NetworkDiagnostics() {
    }

    public static String requireInternet(Context context) throws IOException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return "Network state unavailable";
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            throw new IOException("No active network. Connect Wi-Fi or mobile data before contacting GitHub.");
        }
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            throw new IOException("Active network has no internet capability. Check Wi-Fi/mobile data settings.");
        }
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return "Network active but Android has not validated internet access";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi-Fi internet validated";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "Mobile internet validated";
        }
        return "Internet validated";
    }

    public static IOException explain(IOException e) {
        if (e instanceof UnknownHostException || contains(e, "Unable to resolve host") || contains(e, "No address associated with hostname")) {
            return new IOException("Cannot resolve github.com. Check DNS, Private DNS, VPN/ad blocker, captive portal login, or switch between Wi-Fi and mobile data.", e);
        }
        return e;
    }

    private static boolean contains(Throwable e, String text) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains(text.toLowerCase());
    }
}
