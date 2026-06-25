package ng.subhub.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;

public class MainActivity extends Activity {

    private WebView webView;
    private ImageView loadingLogo;
    private boolean isFirstLoad = true;
    private static final String APP_URL = "https://subhub.com.ng/login";
    private static final String OFFLINE_URL = "https://subhub.com.ng/offline";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadingLogo = findViewById(R.id.loadingLogo);
        webView = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setDatabaseEnabled(true);

        if (isNetworkAvailable()) {
            settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        } else {
            settings.setCacheMode(WebSettings.LOAD_CACHE_ONLY);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (isFirstLoad) {
                    loadingLogo.setVisibility(View.VISIBLE);
                    startPulseLogo();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isFirstLoad) {
                    loadingLogo.setVisibility(View.GONE);
                    loadingLogo.clearAnimation();
                    isFirstLoad = false;
                }
            }

            @Override
public void onReceivedError(WebView view, WebResourceRequest request,
                            WebResourceError error) {
    if (request.isForMainFrame()) {
        loadingLogo.setVisibility(View.GONE);
        loadingLogo.clearAnimation();
        isFirstLoad = false;
        view.loadUrl("file:///android_asset/offline.html");
    }
}
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith("https://subhub.com.ng")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                return false;
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
}
