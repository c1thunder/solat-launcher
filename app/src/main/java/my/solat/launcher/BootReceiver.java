package my.solat.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the prayer alarm after a reboot, a clock change or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                PrayerData.ensure(app, Prefs.zone(app));
                AlarmReceiver.schedule(app);
            } finally {
                pending.finish();
            }
        }).start();
    }
}
