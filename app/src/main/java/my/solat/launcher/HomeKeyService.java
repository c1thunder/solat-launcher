package my.solat.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Google TV always sends the Home button to its own launcher. This service catches the
 * Home key first and opens Solat Launcher instead, so the stock launcher can stay installed.
 * In Kids mode it also sends the kid home from any app that isn't allowed.
 */
public class HomeKeyService extends AccessibilityService {
    /**
     * App buttons on the Sharp / MediaTek Google TV remote ("GTV_RC_REC"), by Linux scan code.
     * Other remotes may send different codes, so remapping is off until the user picks an app.
     */
    private static final int SCAN_YOUTUBE = 74;   // KEY_KPMINUS
    private static final int SCAN_PRIME = 183;    // KEY_F13
    private static final int SCAN_NETFLIX = 87;   // KEY_F11, opened by the system
    private static final String STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx";
    private static final String NETFLIX = "com.netflix.ninja";
    private static final long BOOT_GRACE_MS = 3 * 60_000L;

    private static HomeKeyService instance;
    private long lastBounce;

    /** Runs an AccessibilityService global action (power menu, screen off); false if unavailable. */
    static boolean globalAction(int action) {
        return instance != null && instance.performGlobalAction(action);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // At boot the Google TV home screen appears before this service starts, so we miss its
        // window event. Only right after boot, so an app update doesn't interrupt what's playing.
        if (SystemClock.elapsedRealtime() < BOOT_GRACE_MS) goHome();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String remap = null;
        if (event.getScanCode() == SCAN_YOUTUBE) remap = Prefs.remap(this, Prefs.BUTTON_YOUTUBE);
        else if (event.getScanCode() == SCAN_PRIME) remap = Prefs.remap(this, Prefs.BUTTON_PRIME);
        else if (event.getScanCode() == SCAN_NETFLIX && Kids.on(this) && !Kids.packageAllowed(this, NETFLIX)) {
            return true; // Netflix isn't allowed in Kids mode: swallow the button
        }
        if (remap != null) {
            if (Kids.on(this) && !Kids.packageAllowed(this, remap)) return true;
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(remap);
            if (launch == null) return false; // app not installed: let the TV do its default
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }

        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) goHome();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        String p = pkg.toString();

        // Some TVs handle Home before it reaches us; replace the Google TV home screen when it appears.
        if (p.equals(STOCK_LAUNCHER)) {
            if (cls.toString().endsWith(".HomeActivity")) goHome();
            return;
        }

        if (Kids.on(this) && !Kids.packageAllowed(this, p) && isActivity(p, cls.toString())) {
            goHome();
            long now = System.currentTimeMillis();
            if (now - lastBounce > 3000) {
                Toast.makeText(this, "Kids mode is on. Ask a parent to open this.", Toast.LENGTH_SHORT).show();
            }
            lastBounce = now;
        }
    }

    /** Only full screens count; volume bars, toasts and pop-ups from other apps are left alone. */
    private boolean isActivity(String pkg, String cls) {
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void goHome() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    public void onInterrupt() {
    }
}
