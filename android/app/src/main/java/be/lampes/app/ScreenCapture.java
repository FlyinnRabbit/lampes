package be.lampes.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;

import java.nio.ByteBuffer;

/**
 * Ambilight : capture de l'écran du téléphone (MediaProjection) en toute petite image (32 pixels de large).
 * La page lit l'image avec AndroidScreen.frame() et en tire la couleur dominante.
 * Android demande l'autorisation à chaque démarrage ; les apps protégées (Netflix…) apparaissent noires.
 */
public class ScreenCapture {
    static final int W = 32;
    static volatile String state = "off";        // off | asking | on | denied | error
    static volatile String lastFrame = "";       // "w,h,base64(RGB)"
    static volatile long frameAt = 0;
    private static MediaProjection projection;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static HandlerThread thread;

    /** Pont JavaScript : AndroidScreen.start() / stop() / state() / frame() */
    static class Bridge {
        private final MainActivity act;
        Bridge(MainActivity act) { this.act = act; }
        @JavascriptInterface public void start() { state = "asking"; act.runOnUiThread(act::askScreenCapture); }
        @JavascriptInterface public void stop() { ScreenCapture.stop(act); }
        @JavascriptInterface public String state() { return state; }
        @JavascriptInterface public String frame() { return lastFrame; }
    }

    static boolean running() { return projection != null; }

    /** Appelé par le service, déjà au premier plan avec le type « mediaProjection ». */
    static void begin(Context ctx, int resultCode, Intent data) {
        stopCapture();
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            if (projection == null) { state = "error"; return; }
            thread = new HandlerThread("lampes-ecran");
            thread.start();
            Handler h = new Handler(thread.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopCapture(); state = "off"; }
            }, h);
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int sw = Math.max(dm.widthPixels, dm.heightPixels), sh = Math.min(dm.widthPixels, dm.heightPixels);
            boolean portrait = dm.heightPixels > dm.widthPixels;
            int cw = 128, ch = Math.max(8, Math.round(128f * sh / sw));
            if (portrait) { int t = cw; cw = ch; ch = t; }
            reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    long now = System.currentTimeMillis();
                    if (now - frameAt < 90) return;         // ~10 images/s suffisent
                    frameAt = now;
                    lastFrame = shrink(img);
                } catch (Exception ignored) {
                } finally { if (img != null) img.close(); }
            }, h);
            display = projection.createVirtualDisplay("lampes-ambilight", cw, ch, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
            state = "on";
        } catch (Exception e) {
            stopCapture();
            state = "error";
        }
    }

    /** Réduit l'image capturée à W pixels de large, en RGB, encodée en base64. */
    private static String shrink(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int iw = img.getWidth(), ih = img.getHeight(), rs = p.getRowStride(), ps = p.getPixelStride();
        int ow = W, oh = Math.max(4, Math.round((float) W * ih / iw));
        byte[] out = new byte[ow * oh * 3];
        for (int y = 0; y < oh; y++) for (int x = 0; x < ow; x++) {
            int r = 0, g = 0, b = 0, n = 0;
            int x0 = x * iw / ow, x1 = Math.max(x0 + 1, (x + 1) * iw / ow);
            int y0 = y * ih / oh, y1 = Math.max(y0 + 1, (y + 1) * ih / oh);
            for (int yy = y0; yy < y1; yy += 2) for (int xx = x0; xx < x1; xx += 2) {
                int o = yy * rs + xx * ps;
                r += buf.get(o) & 0xff; g += buf.get(o + 1) & 0xff; b += buf.get(o + 2) & 0xff; n++;
            }
            int k = (y * ow + x) * 3;
            out[k] = (byte) (r / n); out[k + 1] = (byte) (g / n); out[k + 2] = (byte) (b / n);
        }
        return ow + "," + oh + "," + Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private static void stopCapture() {
        try { if (display != null) display.release(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        try { if (thread != null) thread.quitSafely(); } catch (Exception ignored) { }
        display = null; reader = null; projection = null; thread = null; lastFrame = "";
    }

    static void stop(Context ctx) {
        stopCapture();
        state = "off";
        LampesService.start(ctx);                // le service repasse sans le type « capture d'écran »
    }
}
