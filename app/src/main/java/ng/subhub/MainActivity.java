package ng.subhub;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.FileProvider;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ImageView loadingLogo;
    private SwipeRefreshLayout swipeRefresh;
    private static final String APP_URL = "https://app.subhub.com.ng/login";
    private static final int STORAGE_PERMISSION_CODE = 1001;
    private static final String KEYSTORE_ALIAS_PREFIX = "subhub_biometric_";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String PREFS_NAME = "subhub_secure_prefs";

    private AppUpdateManager appUpdateManager;
    private ActivityResultLauncher<IntentSenderRequest> updateLauncher;
    private InstallStateUpdatedListener installStateListener;

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
        webView.addJavascriptInterface(new AuthInterface(), "AndroidAuth");
        webView.addJavascriptInterface(new ShareInterface(), "AndroidShare");
        webView.addJavascriptInterface(new PushInterface(), "AndroidPush");
        
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
                if (url.startsWith("https://app.subhub.com.ng") || url.startsWith("file://")) {
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

        setupInAppUpdate();
    }

    private void setupInAppUpdate() {
        appUpdateManager = AppUpdateManagerFactory.create(this);

        updateLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK) {
                        // User declined or cancelled the update; nothing to do,
                        // we'll offer it again next time the app opens.
                    }
                });

        installStateListener = state -> {
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                Toast.makeText(getApplicationContext(),
                        "Update downloaded. Restarting to install…",
                        Toast.LENGTH_LONG).show();
                appUpdateManager.completeUpdate();
            }
        };
        appUpdateManager.registerListener(installStateListener);

        checkForUpdate();
    }

    private void checkForUpdate() {
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(appUpdateInfo -> {
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                            appUpdateInfo,
                            updateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build());
                } catch (Exception e) {
                    // Ignore; we'll simply prompt again next launch.
                }
            } else if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                Toast.makeText(getApplicationContext(),
                        "Update downloaded. Restarting to install…",
                        Toast.LENGTH_LONG).show();
                appUpdateManager.completeUpdate();
            }
        });
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

    @Override
    protected void onResume() {
        super.onResume();
        if (appUpdateManager != null) {
            appUpdateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    Toast.makeText(getApplicationContext(),
                            "Update downloaded. Restarting to install…",
                            Toast.LENGTH_LONG).show();
                    appUpdateManager.completeUpdate();
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (appUpdateManager != null && installStateListener != null) {
            appUpdateManager.unregisterListener(installStateListener);
        }
    }

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

    /**
 * Bridge exposed to the web page as window.AndroidShare.
 * Receives a base64-encoded image (e.g. a receipt captured with
 * html2canvas) and hands it to the native Android share sheet so the
 * user can send it straight to WhatsApp, Telegram, or any other app.
 */
private class ShareInterface {
    @JavascriptInterface
    public void shareImage(String base64Data, String fileName) {
        runOnUiThread(() -> shareImageFile(base64Data, fileName));
    }
}

/**
 * Bridge exposed to the web page as window.AndroidPush.
 * The web page calls window.AndroidPush.getFcmToken(callbackId) after a
 * successful login, then POSTs the returned token to its own backend
 * to save it against the logged-in user (e.g. the user.fcmToken field).
 */
private class PushInterface {
    @JavascriptInterface
    public void getFcmToken(String callbackId) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            String token = task.isSuccessful() ? task.getResult() : null;
            String tokenJs = token == null ? "null" : JSONObject.quote(token);
            String js = "window.__subhubFcmTokenCallback && window.__subhubFcmTokenCallback("
                    + "'" + callbackId + "', " + tokenJs + ");";
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        });
    }
}
    
private void shareImageFile(String base64Data, String fileName) {
    try {
        byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);

        File cacheDir = new File(getCacheDir(), "shared_images");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File file = new File(cacheDir, fileName);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }

        Uri contentUri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", file);

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/png");
        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Share receipt"));
    } catch (Exception e) {
        Toast.makeText(this, "Could not share receipt", Toast.LENGTH_SHORT).show();
    }
}
    
    private class AuthInterface {
        @JavascriptInterface
        public void requestBiometric(String callbackId) {
            runOnUiThread(() -> showBiometricPrompt(callbackId));
        }

        @JavascriptInterface
        public boolean isBiometricAvailable() {
            return BiometricManager.from(MainActivity.this)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        }

        @JavascriptInterface
        public boolean hasBiometricCredential(String slot) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            return prefs.contains(credKey(slot));
        }

        @JavascriptInterface
        public void enableBiometricCredential(String slot, String credential,
                                               String promptTitle, String promptSubtitle) {
            runOnUiThread(() ->
                    encryptAndSaveCredential(slot, credential, promptTitle, promptSubtitle));
        }

        @JavascriptInterface
        public void getBiometricCredential(String slot, String callbackId,
                                            String promptTitle, String promptSubtitle) {
            runOnUiThread(() ->
                    decryptCredentialWithBiometric(slot, callbackId, promptTitle, promptSubtitle));
        }

        @JavascriptInterface
        public void disableBiometricCredential(String slot) {
            runOnUiThread(() -> getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .remove(credKey(slot))
                    .remove(ivKey(slot))
                    .apply());
        }
    }

    private String credKey(String slot) {
        return "encrypted_" + slot;
    }

    private String ivKey(String slot) {
        return "iv_" + slot;
    }

    private SecretKey getOrCreateSecretKey(String slot) throws Exception {
        String alias = KEYSTORE_ALIAS_PREFIX + slot;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(alias)) {
            return (SecretKey) keyStore.getKey(alias, null);
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .build();

        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }

    private void encryptAndSaveCredential(String slot, String credential,
                                           String promptTitle, String promptSubtitle) {
        try {
            SecretKey key = getOrCreateSecretKey(slot);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);

            BiometricPrompt.CryptoObject cryptoObject = new BiometricPrompt.CryptoObject(cipher);
            Executor executor = ContextCompat.getMainExecutor(this);
            BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            try {
                                Cipher authedCipher = result.getCryptoObject().getCipher();
                                byte[] encrypted = authedCipher.doFinal(
                                        credential.getBytes(StandardCharsets.UTF_8));
                                byte[] iv = authedCipher.getIV();

                                SharedPreferences prefs =
                                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                                prefs.edit()
                                        .putString(credKey(slot),
                                                Base64.encodeToString(encrypted, Base64.DEFAULT))
                                        .putString(ivKey(slot),
                                                Base64.encodeToString(iv, Base64.DEFAULT))
                                        .apply();

                                runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                                        "Fingerprint enabled", Toast.LENGTH_SHORT).show());
                            } catch (Exception e) {
                                runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                                        "Could not enable fingerprint", Toast.LENGTH_SHORT).show());
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                                    "Fingerprint setup cancelled", Toast.LENGTH_SHORT).show());
                        }
                    });

            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(promptTitle != null ? promptTitle : "Enable fingerprint")
                    .setSubtitle(promptSubtitle != null ? promptSubtitle : "Confirm your fingerprint")
                    .setNegativeButtonText("Not now")
                    .build();

            biometricPrompt.authenticate(promptInfo, cryptoObject);
        } catch (Exception e) {
            Toast.makeText(this, "Could not set up fingerprint", Toast.LENGTH_SHORT).show();
        }
    }

    private void decryptCredentialWithBiometric(String slot, String callbackId,
                                                 String promptTitle, String promptSubtitle) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String encB64 = prefs.getString(credKey(slot), null);
            String ivB64 = prefs.getString(ivKey(slot), null);
            if (encB64 == null || ivB64 == null) {
                notifyCredentialCallback(callbackId, false, null, "not-enrolled");
                return;
            }

            byte[] encrypted = Base64.decode(encB64, Base64.DEFAULT);
            byte[] iv = Base64.decode(ivB64, Base64.DEFAULT);

            SecretKey key = getOrCreateSecretKey(slot);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));

            BiometricPrompt.CryptoObject cryptoObject = new BiometricPrompt.CryptoObject(cipher);
            Executor executor = ContextCompat.getMainExecutor(this);
            BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                BiometricPrompt.AuthenticationResult result) {
                            super.onAuthenticationSucceeded(result);
                            try {
                                Cipher authedCipher = result.getCryptoObject().getCipher();
                                byte[] decrypted = authedCipher.doFinal(encrypted);
                                String credential = new String(decrypted, StandardCharsets.UTF_8);
                                notifyCredentialCallback(callbackId, true, credential, "success");
                            } catch (Exception e) {
                                notifyCredentialCallback(callbackId, false, null, "decrypt-error");
                            }
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            super.onAuthenticationError(errorCode, errString);
                            notifyCredentialCallback(callbackId, false, null, "error");
                        }
                    });

            BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(promptTitle != null ? promptTitle : "Verify it's you")
                    .setSubtitle(promptSubtitle != null ? promptSubtitle : "Use your fingerprint to continue")
                    .setNegativeButtonText("Cancel")
                    .build();

            biometricPrompt.authenticate(promptInfo, cryptoObject);
        } catch (Exception e) {
            notifyCredentialCallback(callbackId, false, null, "error");
        }
    }

    private void notifyCredentialCallback(String callbackId, boolean success,
                                           String credential, String reason) {
        String credentialJs = credential == null ? "null" : JSONObject.quote(credential);
        String js = "window.__subhubCredentialCallback && window.__subhubCredentialCallback("
                + "'" + callbackId + "', " + success + ", " + credentialJs + ", '" + reason + "');";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void showBiometricPrompt(String callbackId) {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            notifyBiometricResult(callbackId, false, "unavailable");
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        notifyBiometricResult(callbackId, true, "success");
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        notifyBiometricResult(callbackId, false, "error");
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        super.onAuthenticationFailed();
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify it's you")
                .setSubtitle("Use your fingerprint to continue")
                .setNegativeButtonText("Cancel")
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void notifyBiometricResult(String callbackId, boolean success, String reason) {
        String js = "window.__subhubBiometricCallback && window.__subhubBiometricCallback("
                + "'" + callbackId + "', " + success + ", '" + reason + "');";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }
}
