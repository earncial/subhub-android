package ng.subhub;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private ImageView loadingLogo;
    private SwipeRefreshLayout swipeRefresh;
    private static final String APP_URL = "https://subhub.com.ng/login";
    private static final int STORAGE_PERMISSION_CODE = 1001;

    // Injected on every page load. Intercepts <a download> links whose href
    // is a blob: URL (created via URL.createObjectURL in the web page) and
    // forwards the decoded file to the native side, since WebView cannot
    // resolve blob: URLs the way a normal browser tab can.
    private static final String BLOB_DOWNLOAD_BRIDGE_JS =
            "(function() {" +
            "  if (window.__subhubBlobHooked) return;" +
            "  window.__subhubBlobHooked = true;" +
            "  document.addEventListener('click', function(e) {" +
            "    var a = e.target.closest ? e.target.closest('a[download]') : null;" +
            "    if (!a) return;" +
            "    var href = a.getAttribute('href') || '';" +
            "    if (href.indexOf('blob:') !== 0) return;" +
            "    e.preventDefault();" +
            "    fetch(href).then(function(res) { return res.blob(); }).then(function(blob) {" +
            "      var reader = new FileReader();" +
            "      reader.onloadend = function() {" +
            "        var base64 = reader.result.split(',')[1];" +
            "        var fileName = a.getAttribute('download') || 'download';" +
            "        if (window.AndroidDownload && window.AndroidDownload.saveFile) {" +
            "          window.AndroidDownload.saveFile(base64, fileName, blob.type || 'application/octet-stream');" +
            "        }" +
            "      };" +
            "      reader.readAsDataURL(blob);" +
            "    });" +
            "  }, true);" +
            "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadingLogo = findViewById(R.id.loadingLogo);
        webView = findViewById(R.id.webView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        webView.addJavascriptInterface(new DownloadInterface(), "AndroidDownload");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loadingLogo.setVisibility(View.VISIBLE);
                startPulseLogo();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loadingLogo.setVisibility(View.GONE);
                loadingLogo.clearAnimation();
                swipeRefresh.setRefreshing(false);
                view.evaluateJavascript(BLOB_DOWNLOAD_BRIDGE_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    loadingLogo.setVisibility(View.GONE);
                    loadingLogo.clearAnimation();
                    swipeRefresh.setRefreshing(false);
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://subhub.com.ng") || url.startsWith("file://")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    // No app available to handle this link; ignore instead of crashing.
                }
                return true;
            }
        });

        webView.loadUrl(APP_URL);
    }

    private void startPulseLogo() {
        android.view.animation.Animation pulse =
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse);
        loadingLogo.startAnimation(pulse);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Bridge exposed to the web page as window.AndroidDownload.
     * Receives a base64-encoded file (converted from a Blob in JS) and
     * writes it to the device's public Downloads folder.
     */
    private class DownloadInterface {
        @JavascriptInterface
        public void saveFile(String base64Data, String fileName, String mimeType) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && ContextCompat.checkSelfPermission(MainActivity.this,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_CODE);
                runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                        "Storage permission required, please try again", Toast.LENGTH_LONG).show());
                return;
            }
            try {
                byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);
                String finalMime = (mimeType == null || mimeType.isEmpty())
                        ? "application/octet-stream" : mimeType;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, finalMime);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = getContentResolver()
                            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                            if (out != null) out.write(fileBytes);
                        }
                        values.clear();
                        values.put(MediaStore.Downloads.IS_PENDING, 0);
                        getContentResolver().update(uri, values, null, null);
                    }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, fileName);
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(fileBytes);
                    }
                }

                runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                        "Saved to Downloads: " + fileName, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                        "Could not save file", Toast.LENGTH_LONG).show());
            }
        }
    }
}
