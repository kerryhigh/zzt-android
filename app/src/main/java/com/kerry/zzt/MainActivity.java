package com.kerry.zzt;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_WORLD = 41;
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final String START_URL = APP_ORIGIN + "/assets/www/index.html";
    private static final String PREFS = "zzt-android";
    private static final String PREF_IMPORTED_NAME = "imported-world-name";
    private static final String IMPORT_FILE = "IMPORT.ZZT";

    private WebView webView;
    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        webView = new GameWebView(this);
        webView.setBackgroundColor(0xFF080A0C);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);

        webView.addJavascriptInterface(new AndroidBridge(), "NativeBridge");
        webView.setWebViewClient(new LocalAssetClient());
        setContentView(webView);
        hideSystemBars();
        webView.loadUrl(START_URL);
    }

    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.ZZTControls && window.ZZTControls.back()", null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_WORLD || resultCode != RESULT_OK || data == null) return;

        Uri uri = data.getData();
        if (uri == null) return;

        String displayName = queryDisplayName(uri);
        if (displayName == null || !displayName.toLowerCase(Locale.ROOT).endsWith(".zzt")) {
            Toast.makeText(this, "Choose a .ZZT world file.", Toast.LENGTH_SHORT).show();
            return;
        }

        File target = new File(getFilesDir(), IMPORT_FILE);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target, false)) {
            if (input == null) throw new IOException("Could not read the selected world.");
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            preferences.edit().putString(PREF_IMPORTED_NAME, displayName).apply();
            Toast.makeText(this, "Loaded " + displayName, Toast.LENGTH_SHORT).show();
            reloadGame();
        } catch (IOException error) {
            Toast.makeText(this, "Could not load that world file.", Toast.LENGTH_LONG).show();
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) return cursor.getString(nameIndex);
            }
        } catch (RuntimeException ignored) {
            // Some document providers do not expose a display name; use the URI path below.
        }
        String path = uri.getLastPathSegment();
        return path == null ? null : path;
    }

    private boolean hasImportedWorld() {
        return new File(getFilesDir(), IMPORT_FILE).isFile()
                && preferences.contains(PREF_IMPORTED_NAME);
    }

    private void reloadGame() {
        if (webView != null) webView.post(() -> webView.loadUrl(START_URL));
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public String getWorldFile() {
            return hasImportedWorld() ? IMPORT_FILE : "DEMO.ZZT";
        }

        @JavascriptInterface
        public void openWorldPicker() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream", "application/x-zzt", "application/x-dos-exe"
                });
                startActivityForResult(intent, REQUEST_OPEN_WORLD);
            });
        }

    }

    private static final class GameWebView extends WebView {
        GameWebView(Activity activity) {
            super(activity);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return false;
        }
    }

    private final class LocalAssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return intercept(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return intercept(Uri.parse(url));
        }

        private WebResourceResponse intercept(Uri uri) {
            if (!"https".equals(uri.getScheme())
                    || !"appassets.androidplatform.net".equals(uri.getHost())) {
                return blockedResponse();
            }

            String path = uri.getPath();
            if (path == null || !path.startsWith("/assets/") || path.contains("..") || path.contains("\\")) {
                return blockedResponse();
            }

            String relative = path.substring("/assets/".length());
            if ("www/files/IMPORT.ZZT".equalsIgnoreCase(relative) && hasImportedWorld()) {
                try {
                    return response(new FileInputStream(new File(getFilesDir(), IMPORT_FILE)), "application/octet-stream", null);
                } catch (IOException ignored) {
                    return blockedResponse();
                }
            }

            String mime = mimeType(relative);
            try {
                return response(getAssets().open(relative), mime, mime.startsWith("text/") || mime.contains("javascript") ? "UTF-8" : null);
            } catch (IOException ignored) {
                return blockedResponse();
            }
        }

        private WebResourceResponse response(InputStream stream, String mime, String encoding) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", APP_ORIGIN);
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, encoding, 200, "OK", headers, stream);
        }

        private WebResourceResponse blockedResponse() {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", new HashMap<>(), null);
        }

        private String mimeType(String path) {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".html")) return "text/html";
            if (lower.endsWith(".js")) return "text/javascript";
            if (lower.endsWith(".wasm")) return "application/wasm";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".css")) return "text/css";
            if (lower.endsWith(".json")) return "application/json";
            return "application/octet-stream";
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if ("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())) {
                return false;
            }
            return true;
        }
    }
}
