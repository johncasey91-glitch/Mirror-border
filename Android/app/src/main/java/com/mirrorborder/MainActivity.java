package com.mirrorborder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Window;
import android.graphics.Color;
import android.widget.Toast;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#0a0a14"));
        window.setNavigationBarColor(Color.parseColor("#0a0a14"));

        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.addJavascriptInterface(new SaveBridge(this), "AndroidDownload");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(intent, "Select Images"), FILE_CHOOSER_REQUEST);
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/public/index.html");
    }

    // ── Read real filename from content URI ───────────────────────────────
    private String getRealFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            } catch (Exception e) { /* ignore */ }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "image.png";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++)
                        results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{ data.getData() };
                }
            }

            // Before passing URIs to WebView, inject real filenames into JS
            if (results != null) {
                // Build a JS array of real filenames and call a JS function
                StringBuilder js = new StringBuilder("window._realFileNames = [");
                for (int i = 0; i < results.length; i++) {
                    String name = getRealFileName(results[i]);
                    js.append("\"").append(name.replace("\"", "_")).append("\"");
                    if (i < results.length - 1) js.append(",");
                }
                js.append("];");
                final String jsCode = js.toString();
                webView.post(() -> webView.evaluateJavascript(jsCode, null));
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    public class SaveBridge {
        private final Context ctx;
        SaveBridge(Context ctx) { this.ctx = ctx; }

        @JavascriptInterface
        public void saveBase64Image(String base64Data, String filename) {
            try {
                // Sanitize but preserve original name
                String safeName = filename.replaceAll("[^a-zA-Z0-9._\\-() ]", "_").trim();
                if (safeName.isEmpty() || safeName.equals(".png")) safeName = "image.png";
                if (!safeName.toLowerCase().endsWith(".png")) {
                    safeName = safeName.replaceAll("\\.[^.]+$", "") + ".png";
                }

                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);

                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Bleed Edge"
                );
                if (!dir.exists()) dir.mkdirs();

                File outFile = new File(dir, safeName);
                int counter = 1;
                while (outFile.exists()) {
                    String base = safeName.replace(".png", "");
                    outFile = new File(dir, base + "_" + counter + ".png");
                    counter++;
                }

                FileOutputStream fos = new FileOutputStream(outFile);
                fos.write(bytes);
                fos.flush();
                fos.close();

            } catch (Exception e) { /* silent */ }
        }

        @JavascriptInterface
        public void showToast(final String msg) {
            runOnUiThread(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
        }
    }
}
