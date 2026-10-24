package my.solat.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * JAKIM prayer times from api.waktusolat.app, cached per zone and month so the
 * home screen and alarms keep working offline.
 */
final class PrayerData {
    static final String[] KEYS = {"fajr", "syuruk", "dhuhr", "asr", "maghrib", "isha"};
    static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final int SUNRISE = 1;
    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Kuala_Lumpur");

    private static final String API = "https://api.waktusolat.app";
    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] HIJRI = {"Muharram", "Safar", "Rabi al-Awwal", "Rabi al-Thani", "Jumada al-Awwal",
            "Jumada al-Thani", "Rajab", "Shaban", "Ramadan", "Shawwal", "Dhu al-Qadah", "Dhu al-Hijjah"};

    static final class Day {
        final long[] times = new long[6];
        String hijri;
    }

    static final class Next {
        int index;
        long at;
    }

    private static String parsedKey;
    private static JSONArray parsed;

    private PrayerData() {}

    private static SharedPreferences cache(Context c) {
        return c.getApplicationContext().getSharedPreferences("prayer_cache", Context.MODE_PRIVATE);
    }

    private static String key(String zone, int year, int month1) {
        return "m_" + zone + "_" + year + "_" + month1;
    }

    static Calendar now() {
        return Calendar.getInstance(TZ);
    }

    /** Times for the calendar day of {@code cal}, or null when that month isn't cached yet. */
    static synchronized Day day(Context c, String zone, Calendar cal) {
        String k = key(zone, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
        try {
            if (!k.equals(parsedKey)) {
                String raw = cache(c).getString(k, null);
                if (raw == null) return null;
                parsed = new JSONObject(raw).getJSONArray("prayers");
                parsedKey = k;
            }
            int dom = cal.get(Calendar.DAY_OF_MONTH);
            for (int i = 0; i < parsed.length(); i++) {
                JSONObject o = parsed.getJSONObject(i);
                if (o.getInt("day") != dom) continue;
                Day d = new Day();
                for (int j = 0; j < KEYS.length; j++) d.times[j] = o.getLong(KEYS[j]) * 1000L;
                d.hijri = o.optString("hijri", "");
                return d;
            }
        } catch (JSONException e) {
            cache(c).edit().remove(k).apply();
            parsedKey = null;
        }
        return null;
    }

    /** Next prayer after {@code nowMs}, skipping Sunrise. Looks at today and tomorrow. */
    static Next next(Context c, String zone, long nowMs) {
        Calendar cal = now();
        cal.setTimeInMillis(nowMs);
        for (int dayOffset = 0; dayOffset < 2; dayOffset++) {
            Day d = day(c, zone, cal);
            if (d != null) {
                for (int i = 0; i < 6; i++) {
                    if (i == SUNRISE) continue;
                    if (d.times[i] > nowMs) {
                        Next n = new Next();
                        n.index = i;
                        n.at = d.times[i];
                        return n;
                    }
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
    }

    /** Makes sure this month (and next month near the end) is cached. Call off the main thread. */
    static boolean ensure(Context c, String zone) {
        Calendar cal = now();
        boolean ok = ensureMonth(c, zone, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
        if (cal.get(Calendar.DAY_OF_MONTH) >= 20) {
            cal.add(Calendar.MONTH, 1);
            ensureMonth(c, zone, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
        }
        return ok;
    }

    private static boolean ensureMonth(Context c, String zone, int year, int month1) {
        if (cache(c).contains(key(zone, year, month1))) return true;
        try {
            JSONObject o = new JSONObject(http(API + "/v2/solat/" + zone + "?year=" + year + "&month=" + month1));
            JSONArray prayers = o.getJSONArray("prayers");
            if (prayers.length() < 28) return false;
            // Store under the month the server actually returned.
            int gotYear = o.optInt("year", year);
            int gotMonth = month1;
            String m = o.optString("month", "");
            for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].equalsIgnoreCase(m)) gotMonth = i + 1;
            cache(c).edit().putString(key(zone, gotYear, gotMonth), o.toString()).apply();
            return gotYear == year && gotMonth == month1;
        } catch (IOException | JSONException e) {
            return false;
        }
    }

    static String cachedZones(Context c) {
        return cache(c).getString("zones", null);
    }

    static void fetchZones(Context c) throws IOException, JSONException {
        JSONArray z = new JSONArray(http(API + "/zones"));
        if (z.length() > 0) cache(c).edit().putString("zones", z.toString()).apply();
    }

    private static String http(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(15000);
        con.setRequestProperty("Accept", "application/json");
        try (InputStream in = con.getInputStream()) {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            con.disconnect();
        }
    }

    /** "7:02" style 12-hour time. */
    static String hm(long ms, boolean withAmPm) {
        Calendar cal = now();
        cal.setTimeInMillis(ms);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        String s = (h % 12 == 0 ? 12 : h % 12) + ":" + (m < 10 ? "0" : "") + m;
        return withAmPm ? s + (h >= 12 ? " PM" : " AM") : s;
    }

    /** "1448-05-13" -> "13 Jumada al-Awwal 1448". */
    static String hijriText(String iso) {
        try {
            String[] p = iso.split("-");
            return Integer.parseInt(p[2]) + " " + HIJRI[Integer.parseInt(p[1]) - 1] + " " + p[0];
        } catch (RuntimeException e) {
            return "";
        }
    }
}
