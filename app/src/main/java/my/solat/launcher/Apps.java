package my.solat.launcher;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Everything that can sit on the home row: installed apps, HDMI inputs and Settings. */
final class Apps {
    static final String ALL_APPS = "all";
    private static final long RECENT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000;

    static final class Entry {
        String key;
        String label;
        Drawable banner;
        Drawable icon;
        Intent intent;
        /** Tile colour for placeholder (demo) tiles; 0 for real apps. */
        int color;
    }

    /** Popular streaming apps (Malaysia first), shown on first run in this order when installed. */
    private static final String[] DEFAULTS = {
            "app:com.google.android.youtube.tv", "app:org.smarttube.stable", "app:com.netflix.ninja",
            "app:in.startv.hotstar.dplus.tv", "app:com.amazon.amazonvideo.livingroom", "app:com.vuclip.viu.tv",
            "app:com.dazn", "app:com.formulaone.production", "app:com.apple.atve.androidtv.appletv",
            "app:com.wbd.stream", "app:com.spotify.tv.android"};
    private static final int MAX_DEFAULTS = 8;

    private Apps() {}

    static List<Entry> all(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Entry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
                Entry e = new Entry();
                e.key = "app:" + pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.banner = ri.activityInfo.loadBanner(pm);
                if (e.banner == null) e.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                e.icon = ri.loadIcon(pm);
                e.intent = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                apps.add(e);
            }
        }
        apps.sort((a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        List<Entry> out = new ArrayList<>(inputs(c));
        out.addAll(apps);
        out.add(settings());
        return out;
    }

    private static List<Entry> inputs(Context c) {
        List<Entry> out = new ArrayList<>();
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim == null) return out;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (!info.isPassthroughInput() || info.getParentId() != null || info.isHidden(c)) continue;
                CharSequence label = info.loadCustomLabel(c);
                if (label == null || label.length() == 0) label = info.loadLabel(c);
                Entry e = new Entry();
                e.key = "input:" + info.getId();
                e.label = String.valueOf(label);
                e.intent = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                out.add(e);
            }
        } catch (RuntimeException ignored) {
            // Some TVs throw when the TV input service isn't ready yet.
        }
        out.sort((a, b) -> a.label.compareTo(b.label));
        return out;
    }

    private static Entry settings() {
        Entry e = new Entry();
        e.key = "settings";
        e.label = "Settings";
        e.intent = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return e;
    }

    static Entry allAppsTile() {
        Entry e = new Entry();
        e.key = ALL_APPS;
        e.label = "All apps";
        return e;
    }

    /**
     * Apps opened in the last two weeks that aren't already on the home row, most recent first.
     * Empty until usage access is granted (adb shell appops set my.solat.launcher GET_USAGE_STATS allow).
     */
    static List<Entry> recents(Context c, List<Entry> all, List<Entry> exclude, int max) {
        List<Entry> out = new ArrayList<>();
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        List<UsageStats> stats;
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - RECENT_WINDOW_MS, now);
        } catch (RuntimeException e) {
            return out;
        }
        if (stats == null) return out;
        Map<String, Long> lastUsed = new HashMap<>();
        for (UsageStats s : stats) {
            if (s.getTotalTimeInForeground() <= 0) continue;
            Long prev = lastUsed.get(s.getPackageName());
            if (prev == null || s.getLastTimeUsed() > prev) lastUsed.put(s.getPackageName(), s.getLastTimeUsed());
        }
        Set<String> skip = new HashSet<>();
        for (Entry e : exclude) skip.add(e.key);
        // The Settings app already has its own tile.
        String settingsPkg = packageOf(c, settings());
        if (settingsPkg != null) skip.add("app:" + settingsPkg);
        List<Entry> candidates = new ArrayList<>();
        for (Entry e : all) {
            if (e.key.startsWith("app:") && !skip.contains(e.key) && lastUsed.containsKey(e.key.substring(4))) {
                candidates.add(e);
            }
        }
        candidates.sort((a, b) -> Long.compare(lastUsed.get(b.key.substring(4)), lastUsed.get(a.key.substring(4))));
        for (int i = 0; i < Math.min(max, candidates.size()); i++) out.add(candidates.get(i));
        return out;
    }

    /** HDMI input id for an "input:" key, or null. */
    static String inputId(Entry e) {
        return e.key.startsWith("input:") ? e.key.substring(6) : null;
    }

    /** True when something is plugged into this input (or the TV can't tell). */
    static boolean inputConnected(Context c, String inputId) {
        TvInputManager tim = c.getSystemService(TvInputManager.class);
        if (tim == null) return true;
        try {
            return tim.getInputState(inputId) != TvInputManager.INPUT_STATE_DISCONNECTED;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** The package an entry opens, so Kids mode knows which screens belong to it. */
    static String packageOf(Context c, Entry e) {
        if (e.intent == null) return null;
        if (e.intent.getComponent() != null) return e.intent.getComponent().getPackageName();
        ResolveInfo ri = c.getPackageManager().resolveActivity(e.intent, 0);
        return ri != null ? ri.activityInfo.packageName : null;
    }

    /** The home row in the saved order; seeds a sensible default on first run. */
    static List<Entry> favorites(Context c, List<Entry> all) {
        Map<String, Entry> byKey = new HashMap<>();
        for (Entry e : all) byKey.put(e.key, e);
        List<String> keys = Prefs.favs(c);
        if (keys == null) {
            keys = new ArrayList<>();
            for (String k : DEFAULTS) {
                if (keys.size() < MAX_DEFAULTS && byKey.containsKey(k)) keys.add(k);
            }
            // None of the usual apps installed: start with whatever is, A–Z.
            for (Entry e : all) {
                if (keys.size() >= 4) break;
                if (e.key.startsWith("app:") && !keys.contains(e.key)) keys.add(e.key);
            }
            // One input, preferring HDMI over old analogue inputs like Composite.
            String input = null;
            for (Entry e : all) {
                if (!e.key.startsWith("input:")) continue;
                if (input == null) input = e.key;
                if (e.label.toUpperCase(Locale.ROOT).contains("HDMI")) {
                    input = e.key;
                    break;
                }
            }
            if (input != null) keys.add(input);
            keys.add("settings");
            Prefs.setFavs(c, keys);
        }
        List<Entry> out = new ArrayList<>();
        for (String k : keys) {
            Entry e = byKey.get(k);
            if (e != null) out.add(e);
        }
        return out;
    }
}
