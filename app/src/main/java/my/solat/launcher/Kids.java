package my.solat.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kids mode: a PIN-locked home screen that only shows the apps a parent allowed.
 * HomeKeyService reads the same settings to send the kid home from anything else.
 */
final class Kids {
    static final int PIN_LENGTH = 4;
    private static final int MAX_TRIES = 5;
    private static final long LOCKOUT_MS = 60_000L;

    /** Always reachable in Kids mode: us, the system UI, keyboards and Google sign-in sheets. */
    private static final Set<String> SYSTEM = new HashSet<>(Prefs.list(
            "my.solat.launcher", "android", "com.android.systemui",
            "com.google.android.apps.tv.launcherx", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.google.android.inputmethod.latin", "com.google.android.leanback.ime"));

    private Kids() {}

    private static SharedPreferences sp(Context c) {
        return Prefs.sp(c);
    }

    static boolean on(Context c) {
        return sp(c).getBoolean("kids_on", false);
    }

    static void setOn(Context c, boolean on) {
        sp(c).edit().putBoolean("kids_on", on).putInt("kids_fails", 0).apply();
    }

    static boolean hasPin(Context c) {
        return sp(c).getString("kids_pin", null) != null;
    }

    static void setPin(Context c, String pin) {
        sp(c).edit().putString("kids_pin", hash(pin)).apply();
    }

    /** Milliseconds left before another PIN try is allowed, or 0. */
    static long lockedFor(Context c) {
        long until = sp(c).getLong("kids_locked_until", 0);
        return Math.max(0, until - System.currentTimeMillis());
    }

    /** Checks the PIN, counting wrong tries; locks for a minute after too many. */
    static boolean check(Context c, String pin) {
        if (hash(pin).equals(sp(c).getString("kids_pin", ""))) {
            sp(c).edit().putInt("kids_fails", 0).apply();
            return true;
        }
        int fails = sp(c).getInt("kids_fails", 0) + 1;
        SharedPreferences.Editor e = sp(c).edit().putInt("kids_fails", fails);
        if (fails >= MAX_TRIES) {
            e.putInt("kids_fails", 0).putLong("kids_locked_until", System.currentTimeMillis() + LOCKOUT_MS);
        }
        e.apply();
        return false;
    }

    /** Allowed home-row keys (same keys as Apps.Entry), in the order chosen. */
    static List<String> allowed(Context c) {
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString("kids_allowed", null);
        if (raw == null) {
            out.add("app:org.smarttube.stable");
            return out;
        }
        for (String k : raw.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    /** Saves the allowed keys plus the packages they open, for HomeKeyService. */
    static void setAllowed(Context c, List<String> keys, Set<String> packages) {
        sp(c).edit()
                .putString("kids_allowed", String.join("\n", keys))
                .putStringSet("kids_pkgs", new HashSet<>(packages))
                .apply();
    }

    /** Whether a foreground package is fine while Kids mode is on. */
    static boolean packageAllowed(Context c, String pkg) {
        if (SYSTEM.contains(pkg)) return true;
        Set<String> pkgs = sp(c).getStringSet("kids_pkgs", null);
        return pkgs != null && pkgs.contains(pkg);
    }

    private static String hash(String pin) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(("solat-kids:" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
