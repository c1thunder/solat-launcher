package my.solat.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Saved settings: chosen JAKIM zone, its display name, the home-screen app order and remote buttons. */
final class Prefs {
    /** Shown until the user picks their own zone on first run. */
    static final String DEFAULT_ZONE = "WLY01";
    static final String DEFAULT_LABEL = "Kuala Lumpur";

    /** Remote buttons that can be pointed at another app (see HomeKeyService). */
    static final String BUTTON_YOUTUBE = "youtube";
    static final String BUTTON_PRIME = "prime";

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String zone(Context c) {
        return sp(c).getString("zone", DEFAULT_ZONE);
    }

    static String label(Context c) {
        return sp(c).getString("label", DEFAULT_LABEL);
    }

    /** False until the user has picked a location, so first run can ask. */
    static boolean zoneChosen(Context c) {
        return sp(c).contains("zone");
    }

    static void setZone(Context c, String zone, String label) {
        sp(c).edit().putString("zone", zone).putString("label", label).apply();
    }

    /** Package a remote button should open instead of its usual app, or null to leave it alone. */
    static String remap(Context c, String button) {
        return sp(c).getString("remap_" + button, null);
    }

    static void setRemap(Context c, String button, String pkg) {
        if (pkg == null) sp(c).edit().remove("remap_" + button).apply();
        else sp(c).edit().putString("remap_" + button, pkg).apply();
    }

    /** Ordered home-screen keys, or null if the user has never set them. */
    static List<String> favs(Context c) {
        String raw = sp(c).getString("favs", null);
        if (raw == null) return null;
        List<String> out = new ArrayList<>();
        for (String k : raw.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    static void setFavs(Context c, List<String> keys) {
        sp(c).edit().putString("favs", String.join("\n", keys)).apply();
    }

    static List<String> list(String... keys) {
        return new ArrayList<>(Arrays.asList(keys));
    }
}
