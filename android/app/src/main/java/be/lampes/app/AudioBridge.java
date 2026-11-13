package be.lampes.app;

import android.media.audiofx.Visualizer;
import android.webkit.JavascriptInterface;

import java.util.Locale;

/**
 * Analyse du son que joue le téléphone (Spotify, YouTube… même envoyé vers des enceintes Bluetooth),
 * appelé depuis le JavaScript sous le nom AndroidAudio.
 * Visualizer sur la session 0 = le mélange de sortie global du téléphone.
 */
public class AudioBridge {
    private Visualizer vis;
    private byte[] fft;

    /** Démarre l'écoute. Renvoie "ok" ou un message d'erreur. */
    @JavascriptInterface
    public synchronized String start() {
        stop();
        try {
            vis = new Visualizer(0);
            vis.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            vis.setCaptureSize(Math.min(1024, range[1]));
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            vis.setEnabled(true);
            fft = new byte[vis.getCaptureSize()];
            return "ok";
        } catch (Throwable e) {
            vis = null;
            return "error: " + e.getMessage();
        }
    }

    /** Énergie des basses, médiums et aigus (0 à 1 environ), sous forme "b,m,h". Vide si indisponible. */
    @JavascriptInterface
    public synchronized String bands() {
        if (vis == null) return "";
        try {
            if (vis.getFft(fft) != Visualizer.SUCCESS) return "";
            int n = fft.length;
            double rate = vis.getSamplingRate() / 1000.0;   // en Hz
            double hzPerBin = rate / n;
            return String.format(Locale.US, "%.3f,%.3f,%.3f",
                    band(30, 150, hzPerBin), band(150, 2000, hzPerBin), band(2000, 9000, hzPerBin));
        } catch (Throwable e) {
            return "";
        }
    }

    /** n bandes réparties de 40 Hz à 12 kHz (échelle logarithmique, comme un égaliseur), "v1,v2,…" de 0 à 1. */
    @JavascriptInterface
    public synchronized String spectrum(int n) {
        if (vis == null || n < 1) return "";
        try {
            if (vis.getFft(fft) != Visualizer.SUCCESS) return "";
            double hzPerBin = vis.getSamplingRate() / 1000.0 / fft.length;
            double lo = 40, hi = 12000;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                double f1 = lo * Math.pow(hi / lo, (double) i / n);
                double f2 = lo * Math.pow(hi / lo, (double) (i + 1) / n);
                if (i > 0) sb.append(',');
                sb.append(String.format(Locale.US, "%.3f", band(f1, f2, hzPerBin)));
            }
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    // moyenne des amplitudes entre deux fréquences ; format Visualizer : [DC, Nyquist, re1, im1, re2, im2, …]
    private double band(double from, double to, double hzPerBin) {
        int half = fft.length / 2;
        int a = Math.max(1, (int) Math.floor(from / hzPerBin));
        int b = Math.min(half - 1, (int) Math.ceil(to / hzPerBin));
        double sum = 0;
        int count = 0;
        for (int k = a; k <= b; k++) {
            double re = fft[2 * k], im = fft[2 * k + 1];
            sum += Math.hypot(re, im);
            count++;
        }
        return count == 0 ? 0 : Math.min(1.0, sum / count / 48.0);
    }

    @JavascriptInterface
    public synchronized void stop() {
        if (vis != null) {
            try { vis.setEnabled(false); vis.release(); } catch (Throwable ignored) { }
            vis = null;
        }
    }
}
