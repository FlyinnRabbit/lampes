package be.lampes.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * L'app affiche la même page que sur le PC (assets/index.html), servie en https local
 * pour que le micro et le stockage fonctionnent. Le Bluetooth et l'analyse du son passent
 * par deux ponts natifs : AndroidBle et AndroidAudio.
 */
public class MainActivity extends Activity {
    private WebView web;
    private BleBridge ble;
    private AudioBridge audio;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // les animations sont jouées par l'app : l'écran reste allumé
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#111318"));
        getWindow().setNavigationBarColor(Color.parseColor("#111318"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#111318"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // micro demandé par la page (source « Micro » du mode musique)
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        ble = new BleBridge(this, web);
        audio = new AudioBridge();
        web.addJavascriptInterface(ble, "AndroidBle");
        web.addJavascriptInterface(audio, "AndroidAudio");

        askPermissions();
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        need.add(Manifest.permission.RECORD_AUDIO);
        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // la page retente la reconnexion des lampes une fois les autorisations accordées
        web.evaluateJavascript("window.__androidPermissionsChanged && window.__androidPermissionsChanged()", null);
    }

    @Override
    public void onBackPressed() {
        // retour = mettre l'app en arrière-plan (pas la fermer, sinon les lampes se déconnectent)
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (ble != null) ble.closeAll();
        if (audio != null) audio.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
