package be.lampes.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Service de premier plan : garde l'app vivante en arrière-plan (comme un lecteur de musique),
 * pour que les animations, le mode musique et le spectre continuent quand l'app n'est pas affichée.
 * Une notification permanente permet de revenir à l'app ou de tout arrêter.
 */
public class LampesService extends Service {
    static final String STOP = "be.lampes.app.STOP";
    static final String CAPTURE = "be.lampes.app.CAPTURE";
    private static final String CHANNEL = "lampes";
    private PowerManager.WakeLock wake;

    static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, LampesService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Exception ignored) { }
    }

    static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, LampesService.class)); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            // bouton « Arrêter » de la notification : ferme aussi l'app
            sendBroadcast(new Intent(STOP).setPackage(getPackageName()));
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Lamps in the background", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps animations and music mode running when the app is not shown");
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, LampesService.class).setAction(STOP), piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Lamps is controlling your lights")
                .setContentText("Animations and music running in the background")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                // le micro (analyse du son de Tidal) n'est autorisé en arrière-plan qu'avec ce type
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                // ambilight : la capture d'écran exige ce type, déclaré avant d'ouvrir la capture
                boolean capture = intent != null && CAPTURE.equals(intent.getAction());
                if (capture || ScreenCapture.running()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                startForeground(1, n, type);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            try { startForeground(1, n); } catch (Exception ignored) { stopSelf(); return START_NOT_STICKY; }
        }
        if (intent != null && CAPTURE.equals(intent.getAction())) {
            Intent data = intent.getParcelableExtra("data");
            int code = intent.getIntExtra("code", 0);
            if (data != null) ScreenCapture.begin(this, code, data); else ScreenCapture.state = "error";
        }
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lampes:arriere-plan");
            wake.acquire();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
