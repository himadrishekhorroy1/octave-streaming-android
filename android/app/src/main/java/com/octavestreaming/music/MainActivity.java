package com.octavestreaming.music;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BridgeActivity {
    private static final String GITHUB_API = "https://api.github.com/repos/himadrishekhorroy1/octave-streaming-android/releases/latest";
    private static final String CHANNEL_ID = "octave_updates";
    private static final int NOTIFICATION_ID = 1001;
    private static final int UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000; // 30 min

    private boolean showingOfflinePage = false;
    private Handler updateHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        createNotificationChannel();

        WebView webView = getBridge().getWebView();
        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // ONLY show offline page if it's a main frame error AND device has no internet
                if (request.isForMainFrame() && !hasRealInternet()) {
                    showingOfflinePage = true;
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                // Do NOT show offline page for HTTP errors (403, 503 etc.)
                // These are server-side issues (like Cloudflare), NOT offline situations.
                // The WebView will show whatever the server returns.
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (showingOfflinePage && !url.startsWith("file:///android_asset/")) {
                    showingOfflinePage = false;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // If showing offline page and internet came back, reload
                if (showingOfflinePage && hasRealInternet()) {
                    view.postDelayed(() -> {
                        if (showingOfflinePage) {
                            showingOfflinePage = false;
                            view.loadUrl("https://music.octavestreaming.com");
                        }
                    }, 2000);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                if (url.equals("file:///android_asset/offline.html")) {
                    return false;
                }

                if (url.contains("music.octavestreaming.com")) {
                    return false;
                }

                // If it's an APK download link (from update), use DownloadManager
                if (url.endsWith(".apk")) {
                    downloadAndInstallApk(url);
                    return true;
                }

                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }
        });
    }

    /**
     * Check if device has REAL internet access (not just connected to WiFi/mobile).
     * Uses a lightweight connection test.
     */
    private boolean hasRealInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    /**
     * Lightweight check - just sees if a network is connected (faster than hasRealInternet)
     */
    private boolean hasNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnectedOrConnecting();
    }

    private void downloadAndInstallApk(String downloadUrl) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri uri = Uri.parse(downloadUrl);

            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle("Octave Update");
            request.setDescription("Downloading new version...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, null, "octave-update.apk");

            long downloadId = dm.enqueue(request);

            Toast.makeText(this, "Update downloading...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed. Try again.", Toast.LENGTH_SHORT).show();
        }
    }

    // ======================== UPDATE CHECKER ========================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "App Updates",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifications for app updates");
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void checkForUpdate() {
        executor.execute(() -> {
            try {
                URL url = new URL(GITHUB_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) return;

                InputStream is = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                String tagName = json.getString("tag_name"); // e.g. "v1.5-20260720"
                String remoteVersion = tagName.replaceFirst("^v", "").replaceAll("-\\d+$", "");
                // remoteVersion = "1.5"

                String currentVersion = "1.5"; // Will match versionName
                try {
                    currentVersion = getPackageManager()
                            .getPackageInfo(getPackageName(), 0).versionName;
                } catch (PackageManager.NameNotFoundException ignored) {}

                if (compareVersions(remoteVersion, currentVersion) > 0) {
                    // New version available!
                    String downloadUrl = null;
                    JSONArray assets = json.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }
                    if (downloadUrl != null) {
                        String finalRemoteVersion = remoteVersion;
                        String finalDownloadUrl = downloadUrl;
                        updateHandler.post(() -> showUpdateNotification(finalRemoteVersion, finalDownloadUrl));
                    }
                }
            } catch (Exception ignored) {
                // Silently fail - don't crash the app
            }
        });
    }

    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int n1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int n2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    private void showUpdateNotification(String newVersion, String downloadUrl) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Also support direct APK install
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        android.app.Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new android.app.Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Octave Update Available")
                    .setContentText("Version " + newVersion + " is ready to install")
                    .setStyle(new android.app.Notification.BigTextStyle()
                            .bigText("A new version of Octave (" + newVersion + ") is available. Tap to download and install."))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(android.app.Notification.PRIORITY_HIGH)
                    .build();
        } else {
            notification = new android.app.Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Octave Update Available")
                    .setContentText("Version " + newVersion + " is ready to install")
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setPriority(android.app.Notification.PRIORITY_HIGH)
                    .build();
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private Runnable updateChecker = new Runnable() {
        @Override
        public void run() {
            if (hasRealInternet()) {
                checkForUpdate();
            }
            updateHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        // If showing offline page and internet is back, reload
        if (showingOfflinePage && hasRealInternet()) {
            WebView webView = getBridge().getWebView();
            if (webView != null) {
                showingOfflinePage = false;
                webView.loadUrl("https://music.octavestreaming.com");
            }
        }
        // Start periodic update check
        updateHandler.post(updateChecker);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop update checks when app is in background
        updateHandler.removeCallbacks(updateChecker);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        updateHandler.removeCallbacks(updateChecker);
        executor.shutdownNow();
    }
}