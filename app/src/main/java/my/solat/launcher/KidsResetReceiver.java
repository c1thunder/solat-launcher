package my.solat.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Escape hatch for a forgotten PIN. Only adb can send it (it needs the DUMP permission):
 * adb shell am broadcast -n my.solat.launcher/.KidsResetReceiver
 */
public class KidsResetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Kids.setOn(context, false);
        Prefs.sp(context).edit().remove("kids_pin").remove("kids_locked_until").apply();
    }
}
