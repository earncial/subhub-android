package ng.subhub;

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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends Activity {

    private WebView webView;
    private ImageView loadingLogo;
    private SwipeRefreshLayout swipeRefresh;
    private static final String APP_URL = "https://subhub.com.ng/login";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadingLogo = findViewById(R.id.loadingLogo);
        webView = findViewById(R.id.webView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());
        
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
        return false; // bari WebView ya loda shi da kansa
    }
    try {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    } catch (Exception e) {
        // babu app da zai bude wannan link — kada ya rufe app din
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
}
