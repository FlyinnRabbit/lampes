package be.lampes.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
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
    private BroadcastReceiver stopReceiver;

    /** WebView qui reste « visible » quand l'app passe en arrière-plan : la page n'est ni ralentie ni mise en pause. */
    static class KeepAliveWebView extends WebView {
        KeepAliveWebView(Context c) { super(c); }
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            if (visibility != View.GONE) super.onWindowVisibilityChanged(visibility);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // les animations sont jouées par l'app : l'écran reste allumé
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#111318"));
        getWindow().setNavigationBarColor(Color.parseColor("#111318"));

        web = new KeepAliveWebView(this);
        if (Build.VERSION.SDK_INT >= 26) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
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
        web.addJavascriptInterface(new ScreenCapture.Bridge(this), "AndroidScreen");

        // bouton « Arrêter » de la notification
        stopReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { finishAndRemoveTask(); }
        };
        IntentFilter f = new IntentFilter(LampesService.STOP);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stopReceiver, f);

        askPermissions();
        LampesService.start(this);
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
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        LampesService.start(this);   // relancé avec le micro autorisé, pour le mode musique en arrière-plan
        // la page retente la reconnexion des lampes une fois les autorisations accordées
        web.evaluateJavascript("window.__androidPermissionsChanged && window.__androidPermissionsChanged()", null);
    }

    private static final int REQ_CAPTURE = 42;

    /** Ambilight : Android affiche sa fenêtre « Commencer l'enregistrement / le partage de l'écran ». */
    void askScreenCapture() {
        try {
            android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            ScreenCapture.state = "asking";
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) { ScreenCapture.state = "error"; }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) { ScreenCapture.state = "denied"; return; }
        Intent i = new Intent(this, LampesService.class).setAction(LampesService.CAPTURE)
                .putExtra("code", resultCode).putExtra("data", data);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) { ScreenCapture.state = "error"; }
    }

    @Override
    public void onBackPressed() {
        // retour = mettre l'app en arrière-plan : tout continue grâce au service (notification « Lampes »)
        moveTaskToBack(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // volontairement pas de web.onPause() : la page doit continuer en arrière-plan
    }

    @Override
    protected void onDestroy() {
        LampesService.stop(this);
        if (stopReceiver != null) { try { unregisterReceiver(stopReceiver); } catch (Exception ignored) { } }
        if (ble != null) ble.closeAll();
        if (audio != null) audio.stop();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
