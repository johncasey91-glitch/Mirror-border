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
import java.io.ByteArrayOutputStream;
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
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.addJavascriptInterface(new Bridge(this), "AndroidBridge");
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

    private String getRealFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception e) { /* ignore */ }
        String last = uri.getLastPathSegment();
        return (last != null) ? last : "image.png";
    }

    private byte[] readBytes(Uri uri) throws Exception {
        InputStream is = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        is.close();
        return buf.toByteArray();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST) return;

        // Always resolve the callback, even on cancel
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (filePathCallback != null) { filePathCallback.onReceiveValue(null); filePathCallback = null; }
            return;
        }

        Uri[] uris = null;
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            uris = new Uri[count];
            for (int i = 0; i < count; i++)
                uris[i] = data.getClipData().getItemAt(i).getUri();
        } else if (data.getData() != null) {
            uris = new Uri[]{ data.getData() };
        }

        if (uris == null) {
            if (filePathCallback != null) { filePathCallback.onReceiveValue(null); filePathCallback = null; }
            return;
        }

        // Read real names and file data in background, then send to JS
        final Uri[] finalUris = uris;
        final ValueCallback<Uri[]> cb = filePathCallback;
        filePathCallback = null;

        // First resolve the callback with null so WebView doesn't hang
        // We bypass the WebView file input and handle everything via JS bridge
        cb.onReceiveValue(null);

        new Thread(() -> {
            for (Uri uri : finalUris) {
                try {
                    String realName = getRealFileName(uri);
                    String mimeType = getContentResolver().getType(uri);
                    if (mimeType == null) mimeType = "image/jpeg";
                    byte[] bytes = readBytes(uri);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    String dataUrl = "data:" + mimeType + ";base64," + b64;
                    String safeName = realName.replace("\\","\\\\").replace("\"","\\\"").replace("\n","").replace("\r","");
                    String js = "receiveImageFromJava(\"" + dataUrl + "\",\"" + safeName + "\");";
                    runOnUiThread(() -> webView.evaluateJavascript(js, null));
                    Thread.sleep(50);
                } catch (Exception e) { /* skip */ }
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    public class Bridge {
        private final Context ctx;
        Bridge(Context ctx) { this.ctx = ctx; }

        @JavascriptInterface
        public void saveBase64Image(String base64Data, String filename) {
            try {
                String safeName = filename.replaceAll("[^a-zA-Z0-9._\\-() ]", "_").trim();
                if (safeName.isEmpty()) safeName = "image.png";
                if (!safeName.toLowerCase().endsWith(".png"))
                    safeName = safeName.replaceAll("\\.[^.]+$", "") + ".png";

                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "Bleed Edge"
                );
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, safeName);
                int counter = 1;
                while (outFile.exists()) {
                    outFile = new File(dir, safeName.replace(".png","") + "_" + counter + ".png");
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
