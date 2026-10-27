package com.kerry.zzt;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "ZZTAndroid";
    private static final int REQUEST_WORLDS_FOLDER = 42;
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final String START_URL = APP_ORIGIN + "/assets/www/index.html";
    private static final String PREFS = "zzt-android";
    private static final String PREF_WORLD_TREE_URI = "world-folder-tree-uri";
    private static final String PREF_IMPORTED_DOS_NAMES = "imported-world-dos-names";
    private static final String PREF_LEGACY_IMPORTED_NAME = "imported-world-name";
    private static final String LEGACY_IMPORT_FILE = "IMPORT.ZZT";
    private static final String WORLD_DIRECTORY = "worlds";
    private static final String WORLD_STAGING_DIRECTORY = "worlds-staging";
    private static final String WORLD_BACKUP_DIRECTORY = "worlds-previous";

    private WebView webView;
    private SharedPreferences preferences;
    private final ExecutorService folderExecutor = Executors.newSingleThreadExecutor();
    private volatile List<WorldFile> worldFiles = Collections.emptyList();
    private volatile Map<String, File> worldFilesByName = Collections.emptyMap();
    private volatile List<String> worldFilesToPrune = Collections.emptyList();
    private volatile String folderStatusMessage = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        worldFilesToPrune = previousImportedDosNames();
        migrateLegacySingleFileImport();
        publishWorldFiles(loadWorldFilesFromDisk());

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
        webView.setWebChromeClient(new GameWebChromeClient());
        webView.setWebViewClient(new LocalAssetClient());
        setContentView(webView);
        hideSystemBars();

        String savedTree = preferences.getString(PREF_WORLD_TREE_URI, null);
        if (savedTree == null) {
            loadGame();
        } else {
            Uri treeUri = Uri.parse(savedTree);
            folderStatusMessage = "Refreshing the saved worlds folder…";
            folderExecutor.execute(() -> {
                try {
                    FolderSyncResult result = synchronizeWorldFolder(treeUri);
                    folderStatusMessage = folderMessage(result);
                    Log.i(TAG, "Restored selected worlds folder: " + result.files.size()
                            + " .ZZT file(s), " + result.skipped + " unreadable file(s).");
                } catch (IOException | RuntimeException error) {
                    folderStatusMessage = "World folder unavailable. Using saved world copies.";
                    Log.e(TAG, "Could not refresh the remembered worlds folder; using the last snapshot.", error);
                    publishWorldFiles(loadWorldFilesFromDisk());
                }
                runOnUiThread(this::loadGame);
            });
        }
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
        if (requestCode != REQUEST_WORLDS_FOLDER || resultCode != RESULT_OK || data == null) return;

        Uri treeUri = data.getData();
        if (treeUri == null) {
            showToast("Android did not return a worlds folder.", Toast.LENGTH_LONG);
            return;
        }

        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            showToast("Android did not grant read access to that folder.", Toast.LENGTH_LONG);
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(treeUri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException error) {
            Log.e(TAG, "The selected worlds folder did not grant persistent read access.", error);
            showToast("Android could not keep access to that folder.", Toast.LENGTH_LONG);
            return;
        }

        worldFilesToPrune = previousImportedDosNames();
        folderStatusMessage = "Reading worlds folder…";
        folderExecutor.execute(() -> {
            try {
                FolderSyncResult result = synchronizeWorldFolder(treeUri);
                preferences.edit().putString(PREF_WORLD_TREE_URI, treeUri.toString()).apply();
                folderStatusMessage = folderMessage(result);
                Log.i(TAG, "Selected worlds folder: " + result.files.size()
                        + " .ZZT file(s), " + result.skipped + " unreadable file(s).");
                runOnUiThread(() -> {
                    showToast(folderStatusMessage, Toast.LENGTH_SHORT);
                    loadGame();
                });
            } catch (IOException | RuntimeException error) {
                Log.e(TAG, "Could not import worlds from the selected folder.", error);
                folderStatusMessage = "Could not read that folder. The previous worlds are still available.";
                runOnUiThread(() -> showToast(folderStatusMessage, Toast.LENGTH_LONG));
            }
        });
    }

    private void loadGame() {
        if (webView != null && !isFinishing()) webView.loadUrl(START_URL);
    }

    private void showToast(String message, int duration) {
        runOnUiThread(() -> Toast.makeText(this, message, duration).show());
    }

    private FolderSyncResult synchronizeWorldFolder(Uri treeUri) throws IOException {
        File staging = new File(getFilesDir(), WORLD_STAGING_DIRECTORY);
        File destination = new File(getFilesDir(), WORLD_DIRECTORY);
        File backup = new File(getFilesDir(), WORLD_BACKUP_DIRECTORY);
        deleteDirectory(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Could not create a temporary world folder.");
        }

        int copied = 0;
        int skipped = 0;
        try {
            String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId);
            String[] rootColumns = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor root = getContentResolver().query(rootUri, rootColumns, null, null, null)) {
                if (root == null || !root.moveToFirst()) {
                    throw new IOException("The selected worlds folder no longer exists.");
                }
                int rootTypeColumn = root.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (rootTypeColumn < 0
                        || !DocumentsContract.Document.MIME_TYPE_DIR.equals(root.getString(rootTypeColumn))) {
                    throw new IOException("The saved selection is no longer a folder.");
                }
            }

            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId);
            String[] columns = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            };
            try (Cursor cursor = getContentResolver().query(childrenUri, columns, null, null, null)) {
                if (cursor == null) throw new IOException("The folder provider returned no file listing.");
                int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int typeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (idColumn < 0 || nameColumn < 0 || typeColumn < 0) {
                    throw new IOException("The folder provider did not return the required document fields.");
                }

                while (cursor.moveToNext()) {
                    String mimeType = cursor.getString(typeColumn);
                    String displayName = cursor.getString(nameColumn);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)
                            || displayName == null
                            || !displayName.toLowerCase(Locale.ROOT).endsWith(".zzt")) continue;
                    if (!isSafeDocumentName(displayName)) {
                        skipped++;
                        Log.w(TAG, "Skipped a .ZZT entry with an unsafe filename.");
                        continue;
                    }

                    String documentId = cursor.getString(idColumn);
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    File outputFile = new File(staging, displayName);
                    try (InputStream input = getContentResolver().openInputStream(documentUri);
                         FileOutputStream output = new FileOutputStream(outputFile, false)) {
                        if (input == null) throw new IOException("The provider could not open the world file.");
                        long bytes = copy(input, output);
                        copied++;
                        Log.i(TAG, "Copied world " + displayName + " (" + bytes + " bytes).");
                    } catch (IOException | SecurityException error) {
                        skipped++;
                        Log.e(TAG, "Could not copy world file " + displayName + ".", error);
                        if (outputFile.exists() && !outputFile.delete()) {
                            Log.w(TAG, "Could not remove incomplete staged file " + displayName + ".");
                        }
                    }
                }
            }

            replaceDirectory(staging, destination, backup);
            List<WorldFile> files = loadWorldFilesFromDisk();
            publishWorldFiles(files);
            Set<String> names = new HashSet<>();
            for (WorldFile file : files) names.add(file.dosName);
            preferences.edit().putStringSet(PREF_IMPORTED_DOS_NAMES, names).apply();
            Log.i(TAG, "World folder snapshot ready: copied=" + copied + ", skipped=" + skipped
                    + ", available=" + files.size() + ".");
            return new FolderSyncResult(files, skipped);
        } catch (RuntimeException | IOException error) {
            deleteDirectory(staging);
            throw error;
        }
    }

    private static long copy(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            total += count;
        }
        output.flush();
        return total;
    }

    private static boolean isSafeDocumentName(String name) {
        return !name.isEmpty()
                && !".".equals(name)
                && !"..".equals(name)
                && name.indexOf('/') < 0
                && name.indexOf('\\') < 0
                && name.indexOf('\u0000') < 0;
    }

    private static void replaceDirectory(File staging, File destination, File backup) throws IOException {
        deleteDirectory(backup);
        boolean movedDestination = false;
        if (destination.exists()) {
            if (!destination.renameTo(backup)) throw new IOException("Could not preserve the previous world snapshot.");
            movedDestination = true;
        }
        if (!staging.renameTo(destination)) {
            if (movedDestination && !backup.renameTo(destination)) {
                throw new IOException("Could not restore the previous world snapshot.");
            }
            throw new IOException("Could not activate the new world snapshot.");
        }
        deleteDirectory(backup);
    }

    private static void deleteDirectory(File directory) throws IOException {
        if (!directory.exists()) return;
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Could not list the app's private world folder.");
        for (File child : children) {
            if (child.isDirectory()) deleteDirectory(child);
            else if (!child.delete()) throw new IOException("Could not remove a temporary world file.");
        }
        if (!directory.delete()) throw new IOException("Could not remove a temporary world folder.");
    }

    private List<WorldFile> loadWorldFilesFromDisk() {
        File directory = new File(getFilesDir(), WORLD_DIRECTORY);
        File[] files = directory.listFiles((parent, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".zzt"));
        if (files == null || files.length == 0) return Collections.emptyList();
        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, files);
        sorted.sort(Comparator.comparing((File file) -> file.getName().toUpperCase(Locale.ROOT))
                .thenComparing(File::getName));

        Set<String> usedDosNames = new HashSet<>();
        usedDosNames.add("DEMO.ZZT");
        List<WorldFile> result = new ArrayList<>();
        for (File file : sorted) {
            if (!file.isFile()) continue;
            String dosName = makeDos83WorldName(file.getName(), usedDosNames);
            usedDosNames.add(dosName.toUpperCase(Locale.ROOT));
            result.add(new WorldFile(file.getName(), dosName, file));
            Log.i(TAG, "World available to ZZT: source=" + file.getName() + ", DOS name=" + dosName + ".");
        }
        return Collections.unmodifiableList(result);
    }

    private static String makeDos83WorldName(String fileName, Set<String> usedNames) {
        String upper = fileName.toUpperCase(Locale.ROOT);
        int extensionIndex = upper.lastIndexOf('.');
        String base = extensionIndex < 0 ? upper : upper.substring(0, extensionIndex);
        StringBuilder cleaned = new StringBuilder();
        boolean changed = false;
        for (int i = 0; i < base.length(); i++) {
            char value = base.charAt(i);
            if (isDos83Character(value)) cleaned.append(value);
            else changed = true;
        }
        if (cleaned.length() == 0) cleaned.append("WORLD");

        String candidate = cleaned.toString();
        boolean needsAlias = changed || candidate.length() > 8 || usedNames.contains(candidate + ".ZZT");
        if (needsAlias) {
            for (int suffixNumber = 1; suffixNumber < 1000; suffixNumber++) {
                String suffix = "~" + suffixNumber;
                int prefixLength = Math.max(1, 8 - suffix.length());
                String prefix = cleaned.substring(0, Math.min(prefixLength, cleaned.length()));
                candidate = prefix + suffix;
                if (!usedNames.contains(candidate + ".ZZT")) break;
            }
        }
        if (candidate.length() > 8) candidate = candidate.substring(0, 8);
        return candidate + ".ZZT";
    }

    private static boolean isDos83Character(char value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || "$%'@~!(){}^#&_-".indexOf(value) >= 0;
    }

    private void publishWorldFiles(List<WorldFile> files) {
        Map<String, File> byName = new HashMap<>();
        for (WorldFile file : files) byName.put(file.sourceName, file.file);
        worldFiles = files;
        worldFilesByName = Collections.unmodifiableMap(byName);
    }

    private void migrateLegacySingleFileImport() {
        if (preferences.getString(PREF_WORLD_TREE_URI, null) != null) return;
        File worlds = new File(getFilesDir(), WORLD_DIRECTORY);
        File[] existing = worlds.listFiles((parent, name) -> name.toLowerCase(Locale.ROOT).endsWith(".zzt"));
        if (existing != null && existing.length > 0) return;

        File legacyFile = new File(getFilesDir(), LEGACY_IMPORT_FILE);
        String originalName = preferences.getString(PREF_LEGACY_IMPORTED_NAME, null);
        if (!legacyFile.isFile() || originalName == null || !isSafeDocumentName(originalName)) return;
        if (!worlds.mkdirs() && !worlds.isDirectory()) {
            Log.e(TAG, "Could not create a folder to preserve the previous imported world.");
            return;
        }
        File migrated = new File(worlds, originalName);
        try (InputStream input = new FileInputStream(legacyFile);
             FileOutputStream output = new FileOutputStream(migrated, false)) {
            long bytes = copy(input, output);
            Log.i(TAG, "Migrated the previous imported world " + originalName + " (" + bytes + " bytes).");
        } catch (IOException error) {
            Log.e(TAG, "Could not preserve the previous imported world.", error);
        }
    }

    private List<String> previousImportedDosNames() {
        Set<String> stored = preferences.getStringSet(PREF_IMPORTED_DOS_NAMES, Collections.emptySet());
        Set<String> result = new HashSet<>(stored);
        if (preferences.contains(PREF_LEGACY_IMPORTED_NAME)) result.add(LEGACY_IMPORT_FILE);
        List<String> sorted = new ArrayList<>(result);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return Collections.unmodifiableList(sorted);
    }

    private static String folderMessage(FolderSyncResult result) {
        if (result.files.isEmpty()) return "No .ZZT files found. The bundled demo is still available.";
        if (result.skipped > 0) {
            return "Loaded " + result.files.size() + " world(s); " + result.skipped + " file(s) could not be read.";
        }
        return "Loaded " + result.files.size() + " world(s). Press WORLD to choose in ZZT.";
    }

    private String worldFilesJson() {
        JSONArray array = new JSONArray();
        for (WorldFile file : worldFiles) {
            JSONObject value = new JSONObject();
            try {
                value.put("sourceName", file.sourceName);
                value.put("dosName", file.dosName);
                value.put("bytes", file.file.length());
            } catch (JSONException error) {
                Log.e(TAG, "Could not describe a world file to the game page.", error);
            }
            array.put(value);
        }
        return array.toString();
    }

    private static final class WorldFile {
        final String sourceName;
        final String dosName;
        final File file;

        WorldFile(String sourceName, String dosName, File file) {
            this.sourceName = sourceName;
            this.dosName = dosName;
            this.file = file;
        }
    }

    private static final class FolderSyncResult {
        final List<WorldFile> files;
        final int skipped;

        FolderSyncResult(List<WorldFile> files, int skipped) {
            this.files = files;
            this.skipped = skipped;
        }
    }

    private final class AndroidBridge {
        @JavascriptInterface
        public String getWorldFiles() {
            return worldFilesJson();
        }

        @JavascriptInterface
        public String getWorldFilesToPrune() {
            JSONArray array = new JSONArray();
            for (String name : worldFilesToPrune) array.put(name);
            return array.toString();
        }

        @JavascriptInterface
        public String getFolderStatus() {
            return folderStatusMessage;
        }

        @JavascriptInterface
        public void openWorldFolderPicker() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(intent, REQUEST_WORLDS_FOLDER);
            });
        }
    }

    private final class GameWebView extends WebView {
        GameWebView(Activity activity) {
            super(activity);
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return false;
        }
    }

    private final class GameWebChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            String line = message.sourceId() + ":" + message.lineNumber() + " " + message.message();
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.e(TAG, line);
            else if (message.messageLevel() == ConsoleMessage.MessageLevel.WARNING) Log.w(TAG, line);
            else Log.d(TAG, line);
            return true;
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
            if (relative.startsWith("www/worlds/")) {
                String sourceName = relative.substring("www/worlds/".length());
                File worldFile = worldFilesByName.get(sourceName);
                if (worldFile != null && worldFile.isFile()) {
                    try {
                        return response(new FileInputStream(worldFile), "application/octet-stream", null);
                    } catch (IOException error) {
                        Log.e(TAG, "Could not open imported world " + sourceName + " from app storage.", error);
                    }
                }
                Log.e(TAG, "Zeta requested a missing imported world: " + sourceName + ".");
                return blockedResponse();
            }

            String mime = mimeType(relative);
            try {
                return response(getAssets().open(relative), mime,
                        mime.startsWith("text/") || mime.contains("javascript") ? "UTF-8" : null);
            } catch (IOException error) {
                Log.e(TAG, "Missing bundled ZZT asset: " + relative + ".", error);
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
            return !("https".equals(uri.getScheme())
                    && "appassets.androidplatform.net".equals(uri.getHost()));
        }
    }
}
