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
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl("file:///android_asset/public/index.html");
    }

    // Java bridge callable from JS
    public class Bridge {
        private final Context ctx;
        Bridge(Context ctx) { this.ctx = ctx; }

        // JS calls this to open the file picker
        @JavascriptInterface
        public void openFilePicker() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(intent, "Select Images"), FILE_CHOOSER_REQUEST);
        }

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
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) buffer.write(chunk, 0, n);
        is.close();
        return buffer.toByteArray();
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

        // Process each file: read bytes + real name, pass to JS as base64
        final Uri[] finalUris = uris;
        new Thread(() -> {
            for (Uri uri : finalUris) {
                try {
                    String realName = getRealFileName(uri);
                    String mimeType = getContentResolver().getType(uri);
                    if (mimeType == null) mimeType = "image/png";
                    byte[] bytes = readBytes(uri);
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    String dataUrl = "data:" + mimeType + ";base64," + b64;

                    // Escape for JS
                    String safeName = realName.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "");
                    String js = "addFileFromJava(\"" + dataUrl + "\",\"" + safeName + "\");";
                    runOnUiThread(() -> webView.evaluateJavascript(js, null));

                } catch (Exception e) {
                    // skip this file
                }
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
