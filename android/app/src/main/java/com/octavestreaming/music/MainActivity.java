package com.octavestreaming.music;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Ensure WebView is initialized
        WebView webView = getBridge().getWebView();
        WebSettings settings = webView.getSettings();
        
        // Enable basic features
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // Allow background/auto play
        
        // Custom WebViewClient for better control
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Only load offline page for main frame errors
                if (request.isForMainFrame()) {
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                
                // Allow the main app domain
                if (url.contains("music.octavestreaming.com")) {
                    return false;
                }
                
                // Open external links in external browser or intent
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }
        });
    }

    @Override
    protected void onPause() {
        // Capacitor by default might pause WebView timers/audio. 
        // We don't call super.onPause() or we ensure audio keeps playing.
        // Actually, for music apps, we usually want to keep it running.
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }
}
