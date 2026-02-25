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
    private static final int FILE_CHOOSER_REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.parseColor("#0a0a14"));
        window.setNavigationBarColor(Color.parseColor("#0a0a14"));
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.addJavascriptInterface(new Bridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/public/index.html");
    }

    private String getRealFileName(Uri uri) {
        // Method 1: OpenableColumns.DISPLAY_NAME
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) {}

        // Method 2: last path segment
        String last = uri.getLastPathSegment();
        if (last != null && last.contains(".")) return last;

        // Method 3: from path
        String path = uri.getPath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0 && slash < path.length() - 1)
                return path.substring(slash + 1);
        }

        return "image.png";
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
        if (resultCode != Activity.RESULT_OK || data == null) return;

        Uri[] uris = null;
        if (data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            uris = new Uri[count];
            for (int i = 0; i < count; i++)
                uris[i] = data.getClipData().getItemAt(i).getUri();
        } else if (data.getData() != null) {
            uris = new Uri[]{ data.getData() };
        }
        if (uris == null) return;

        final Uri[] finalUris = uris;
        new Thread(() -> {
            for (Uri uri : finalUris) {
                try {
                    String realName = getRealFileName(uri);
                    String mime = getContentResolver().getType(uri);
                    if (mime == null) mime = "image/jpeg";
                    byte[] bytes = readBytes(uri);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    String dataUrl = "data:" + mime + ";base64," + b64;
                    // Escape special chars for JS string
                    String safeName = realName
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "")
                        .replace("\r", "");
                    String js = "receiveImage(\"" + dataUrl + "\",\"" + safeName + "\");";
                    runOnUiThread(() -> webView.evaluateJavascript(js, null));
                    Thread.sleep(80);
                } catch (Exception ignored) {}
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
        public void openPicker() {
            // Must run on UI thread - JavascriptInterface runs on background thread
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(
                    Intent.createChooser(intent, "Select Images"),
                    FILE_CHOOSER_REQUEST
                );
            });
        }

        @JavascriptInterface
        public void saveBase64Image(String base64Data, String filename) {
            try {
                String safeName = filename
                    .replaceAll("[^a-zA-Z0-9._\\-() ]", "_")
                    .trim();
                if (safeName.isEmpty()) safeName = "image.png";
                if (!safeName.toLowerCase().endsWith(".png"))
                    safeName = safeName.replaceAll("\\.[^.]+$", "") + ".png";

                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                File dir = new File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "Bleed Edge");
                if (!dir.exists()) dir.mkdirs();

                File out = new File(dir, safeName);
                int i = 1;
                while (out.exists())
                    out = new File(dir, safeName.replace(".png","") + "_" + i++ + ".png");

                FileOutputStream fos = new FileOutputStream(out);
                fos.write(bytes);
                fos.flush();
                fos.close();
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void showToast(final String msg) {
            runOnUiThread(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
        }
    }
}
