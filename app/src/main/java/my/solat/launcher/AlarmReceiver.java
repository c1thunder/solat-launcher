package my.solat.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires at each prayer time, shows the pop-up, then schedules the next one. */
public class AlarmReceiver extends BroadcastReceiver {
    private static final long RETRY_MS = 60 * 60 * 1000L;
    private static final long LATE_LIMIT_MS = 5 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        int index = intent.getIntExtra("index", -1);
        long at = intent.getLongExtra("at", 0);
        // Don't announce a prayer the TV slept through.
        if (index >= 0 && Math.abs(System.currentTimeMillis() - at) < LATE_LIMIT_MS) {
            PopupOverlay.show(app, PrayerData.NAMES[index], at);
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                PrayerData.ensure(app, Prefs.zone(app));
                schedule(app);
            } finally {
                pending.finish();
            }
        }).start();
    }

    /** Sets one exact alarm for the next prayer (Sunrise excluded). Uses cached times only. */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        long now = System.currentTimeMillis();
        PrayerData.Next next = PrayerData.next(c, Prefs.zone(c), now + 1000);
        Intent i = new Intent(c, AlarmReceiver.class);
        long when;
        if (next != null) {
            i.putExtra("index", next.index).putExtra("at", next.at);
            when = next.at;
        } else {
            when = now + RETRY_MS; // no times cached yet: wake up later and try to fetch
        }
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }
}
