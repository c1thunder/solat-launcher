package my.solat.launcher;

import android.accessibilityservice.AccessibilityService;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.provider.Settings;
import android.view.KeyEvent;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static my.solat.launcher.Ui.px;

/** The home screen: clock, today's prayer times with a countdown, and one row of apps. */
public class MainActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final long RETRY_FETCH_MS = 60_000L;
    private static final int MAX_RECENTS = 4;
    private static final int WEAK_WIFI_DBM = -75;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH);

    private FrameLayout root;
    private LinearLayout content;
    private TextView clock, ampm, dateLine, hijriLine, chip, nextLine;
    private final LinearLayout[] cards = new LinearLayout[6];
    private final TextView[] cardName = new TextView[6];
    private final TextView[] cardTime = new TextView[6];
    private final TextView[] cardTag = new TextView[6];
    private View nextDot;
    private LinearLayout appRow;
    private View panel;
    private TextView wifiPill, inputsPill, kidsPill, powerPill, exitKidsPill;
    private PinInput pinInput;
    private boolean demo;
    private ConnectivityManager.NetworkCallback netCallback;

    private List<Apps.Entry> allApps = new ArrayList<>();
    private List<Apps.Entry> favs = new ArrayList<>();
    private List<Apps.Entry> rowEntries = new ArrayList<>();
    private boolean fetching;
    private boolean offline;
    private long lastFetchAttempt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000) + 5);
        }
    };

    interface ItemAction {
        void run(int index);
    }

    /** Called with a full PIN; returns null to accept it or a message to show under the dots. */
    interface PinCheck {
        String accept(String pin);
    }

    /** Lets the remote's number keys type into the open PIN pad. */
    interface PinInput {
        void digit(char d);

        void delete();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        dateFormat.setTimeZone(PrayerData.TZ);
        build();
        setContentView(root);
        handleTestIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyMode();
        handler.removeCallbacks(tick);
        tick.run();
        AlarmReceiver.schedule(this);
        fetchIfNeeded(true);
        watchNetwork(true);
        if (!Prefs.zoneChosen(this) && panel == null && !Kids.on(this)) openZonePicker();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        watchNetwork(false);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (pinInput != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                pinInput.digit((char) ('0' + k - KeyEvent.KEYCODE_0));
                return true;
            }
            if (k == KeyEvent.KEYCODE_DEL) {
                pinInput.delete();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: back to the first app.
        closePanel();
        focusTile(0);
        handleTestIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (panel != null) closePanel();
        else focusTile(0);
    }

    /**
     * adb shell am start -n my.solat.launcher/.MainActivity --ez testPopup true
     * adb shell am start -n my.solat.launcher/.MainActivity --ez demo true|false  (placeholder tiles for screenshots)
     */
    private void handleTestIntent(Intent intent) {
        if (intent != null && intent.hasExtra("demo")) {
            demo = intent.getBooleanExtra("demo", false);
            intent.removeExtra("demo");
            reloadApps(0);
        }
        if (intent != null && intent.getBooleanExtra("testPopup", false)) {
            PrayerData.Next n = PrayerData.next(this, Prefs.zone(this), System.currentTimeMillis());
            PopupOverlay.show(this, n != null ? PrayerData.NAMES[n.index] : "Maghrib",
                    n != null ? n.at : System.currentTimeMillis());
            intent.removeExtra("testPopup");
        }
    }

    // ---------------------------------------------------------------- layout

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(px(96), px(64), px(96), px(40));
        content.setClipChildren(false);
        content.setClipToPadding(false);
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Top: clock + dates on the left, location on the right.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setBaselineAligned(true);
        clock = Ui.text(this, "", 168, Ui.serif, Ui.FG);
        clock.setLetterSpacing(-0.012f);
        ampm = Ui.text(this, "", 64, Ui.serif, Ui.MUTED2);
        ampm.setPadding(px(16), 0, 0, 0);
        clockRow.addView(clock);
        clockRow.addView(ampm);
        left.addView(clockRow);
        dateLine = Ui.text(this, "", 32, Ui.sans400, Ui.MUTED);
        dateLine.setPadding(0, px(20), 0, px(8));
        left.addView(dateLine);
        hijriLine = Ui.text(this, "", 28, Ui.sans500, Ui.ACCENT);
        left.addView(hijriLine);
        top.addView(left, new LinearLayout.LayoutParams(0, WRAP, 1f));

        chip = Ui.text(this, "", 26, Ui.sans500, Ui.MUTED2);
        chip.setTextColor(Ui.focusText(Ui.MUTED2, Ui.BG));
        chip.setBackground(Ui.focusBg(0x0FFFFFFF, Ui.ACCENT, px(40)));
        chip.setPadding(px(24), px(14), px(28), px(14));
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setFocusable(true);
        Drawable pin = getDrawable(R.drawable.ic_pin);
        if (pin != null) {
            pin.setBounds(0, 0, px(28), px(28));
            chip.setCompoundDrawables(pin, null, null, null);
            chip.setCompoundDrawablePadding(px(12));
            chip.setCompoundDrawableTintList(Ui.focusText(Ui.MUTED2, Ui.BG));
        }
        chip.setOnClickListener(v -> openZonePicker());

        // Top right: network status, then actions, then location (where it has always been).
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        wifiPill = pill(R.drawable.ic_wifi_on, "");
        wifiPill.setOnClickListener(v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        inputsPill = pill(R.drawable.ic_input, "Inputs");
        inputsPill.setOnClickListener(v -> openInputs());
        kidsPill = pill(R.drawable.ic_kids, "Kids");
        kidsPill.setOnClickListener(v -> openKids());
        powerPill = pill(R.drawable.ic_power, "Power");
        powerPill.setOnClickListener(v -> openPower());
        exitKidsPill = pill(R.drawable.ic_lock, "Kids mode");
        exitKidsPill.setTextColor(Ui.focusText(Ui.ACCENT, Ui.BG));
        exitKidsPill.setCompoundDrawableTintList(Ui.focusText(Ui.ACCENT, Ui.BG));
        exitKidsPill.setBackground(Ui.focusBg(0x1FE3B55B, Ui.ACCENT, px(40)));
        exitKidsPill.setContentDescription("Kids mode is on. Press OK and enter the PIN to leave.");
        exitKidsPill.setOnClickListener(v -> exitKids());
        for (View v : new View[]{wifiPill, inputsPill, kidsPill, powerPill, exitKidsPill, chip}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.leftMargin = px(14);
            actions.addView(v, lp);
        }
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        actionsLp.topMargin = px(24);
        top.addView(actions, actionsLp);
        content.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // Middle: six prayer cards and the countdown line.
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 6; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(px(29), px(25), px(29), px(25));
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            cardName[i] = Ui.text(this, PrayerData.NAMES[i], 26, Ui.sans500, Ui.FG);
            cardName[i].setAlpha(0.9f);
            header.addView(cardName[i], new LinearLayout.LayoutParams(0, WRAP, 1f));
            cardTag[i] = Ui.text(this, "", 20, Ui.sans700, Ui.FG);
            cardTag[i].setLetterSpacing(0.1f);
            header.addView(cardTag[i]);
            cardTime[i] = Ui.text(this, "--:--", 52, Ui.sans700, Ui.FG);
            cardTime[i].setPadding(0, px(10), 0, 0);
            card.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
            card.addView(cardTime[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            if (i > 0) lp.leftMargin = px(20);
            grid.addView(card, lp);
            cards[i] = card;
        }
        content.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout nextRow = new LinearLayout(this);
        nextRow.setOrientation(LinearLayout.HORIZONTAL);
        nextRow.setGravity(Gravity.CENTER_VERTICAL);
        nextDot = new View(this);
        nextDot.setBackground(Ui.round(Ui.GREEN, px(6)));
        nextRow.addView(nextDot, new LinearLayout.LayoutParams(px(12), px(12)));
        nextLine = Ui.text(this, "", 30, Ui.sans400, Ui.MUTED);
        nextLine.setPadding(px(16), 0, 0, 0);
        nextRow.addView(nextLine, new LinearLayout.LayoutParams(0, WRAP, 1f));
        // Credit the source: official JAKIM e-Solat times (fetched via api.waktusolat.app).
        TextView source = Ui.text(this, "Prayer times: JAKIM e-Solat", 22, Ui.sans500, Ui.MUTED2);
        source.setAlpha(0.8f);
        nextRow.addView(source);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        nextLp.topMargin = px(28);
        content.addView(nextRow, nextLp);

        content.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // Bottom: the app row. Extra room so a focused (scaled-up) tile isn't clipped.
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(px(24), px(28), px(24), px(24));
        appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setClipChildren(false);
        appRow.setClipToPadding(false);
        appRow.setGravity(Gravity.BOTTOM);
        appRow.setPadding(0, 0, px(120), 0);
        scroller.addView(appRow, new FrameLayout.LayoutParams(WRAP, MATCH));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(MATCH, px(150) + px(52));
        rowLp.leftMargin = -px(24);
        rowLp.rightMargin = -px(96);
        content.addView(scroller, rowLp);
    }

    /** A rounded status/action button for the top bar; text may be empty for an icon-only pill. */
    private TextView pill(int iconRes, String text) {
        TextView t = Ui.text(this, text, 26, Ui.sans500, Ui.MUTED2);
        t.setTextColor(Ui.focusText(Ui.MUTED2, Ui.BG));
        t.setBackground(Ui.focusBg(0x0FFFFFFF, Ui.ACCENT, px(40)));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setFocusable(true);
        t.setCompoundDrawableTintList(Ui.focusText(Ui.MUTED2, Ui.BG));
        setPillIcon(t, iconRes);
        setPillText(t, text);
        return t;
    }

    private void setPillIcon(TextView t, int iconRes) {
        Drawable d = getDrawable(iconRes);
        if (d == null) return;
        d.setBounds(0, 0, px(30), px(30));
        t.setCompoundDrawables(d, null, null, null);
    }

    private void setPillText(TextView t, String text) {
        t.setText(text);
        boolean iconOnly = text.isEmpty();
        t.setCompoundDrawablePadding(iconOnly ? 0 : px(12));
        t.setPadding(px(iconOnly ? 18 : 22), px(14), px(iconOnly ? 18 : 28), px(14));
    }

    private View tile(Apps.Entry e, View.OnLongClickListener onLongClick) {
        FrameLayout outer = new FrameLayout(this);
        outer.setFocusable(true);
        outer.setClipChildren(false);
        int ring = px(7);
        outer.setPadding(ring, ring, ring, ring);
        GradientDrawable focusRing = new GradientDrawable();
        focusRing.setCornerRadius(px(25));
        focusRing.setStroke(px(4), Ui.ACCENT);
        focusRing.setColor(0);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focusRing);
        bg.addState(new int[]{}, new ColorDrawable(0));
        outer.setBackground(bg);

        FrameLayout inner = new FrameLayout(this);
        if (e.color != 0) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{e.color, (e.color & 0x00FFFFFF) | 0x99000000});
            g.setCornerRadius(px(18));
            inner.setBackground(g);
        } else {
            inner.setBackground(Ui.round(Ui.TILE, px(18)));
        }
        inner.setClipToOutline(true);
        if (e.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.banner);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            inner.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            if (e.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(e.icon);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(px(64), px(64), Gravity.TOP | Gravity.START);
                ilp.leftMargin = px(22);
                ilp.topMargin = px(18);
                inner.addView(iv, ilp);
            }
            TextView label = Ui.text(this, e.label, 28, Ui.sans700, 0xFFFFFFFF);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM);
            label.setPadding(px(22), 0, px(18), px(20));
            inner.addView(label, llp);
        }
        outer.addView(inner, new FrameLayout.LayoutParams(px(240), px(135)));

        outer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX((r - l) / 2f);
            v.setPivotY(b - t);
        });
        outer.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.12f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(140).start();
            v.setTranslationZ(focused ? px(10) : 0);
        });
        outer.setOnClickListener(v -> {
            if (Apps.ALL_APPS.equals(e.key)) openAllApps(-1);
            else launch(e);
        });
        if (onLongClick != null) outer.setOnLongClickListener(onLongClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.rightMargin = px(36);
        outer.setLayoutParams(lp);
        outer.setContentDescription(e.label);
        return outer;
    }

    private void reloadApps(int focusIndex) {
        View focused = getCurrentFocus();
        boolean rowHadFocus = focused == null || focused.getParent() == appRow;
        allApps = Apps.all(this);
        favs = Apps.favorites(this, allApps);
        fillRow();
        if (panel == null && rowHadFocus) focusTile(focusIndex);
    }

    /**
     * Normal mode: favourites, a divider, recently used apps, then All apps.
     * Kids mode: only the allowed apps, in home-row order, with no menus.
     */
    private void fillRow() {
        appRow.removeAllViews();
        rowEntries = new ArrayList<>();
        if (demo) {
            fillDemoRow();
            return;
        }
        if (Kids.on(this)) {
            for (Apps.Entry e : kidsEntries()) {
                appRow.addView(tile(e, null));
                rowEntries.add(e);
            }
            return;
        }
        for (int i = 0; i < favs.size(); i++) {
            final int index = i;
            appRow.addView(tile(favs.get(i), v -> {
                tileMenu(index);
                return true;
            }));
            rowEntries.add(favs.get(i));
        }
        List<Apps.Entry> recents = Apps.recents(this, allApps, favs, MAX_RECENTS);
        if (!recents.isEmpty()) {
            View divider = new View(this);
            divider.setBackgroundColor(0x2EFFFFFF);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(3), px(96));
            dlp.rightMargin = px(36);
            dlp.bottomMargin = px(26);
            appRow.addView(divider, dlp);
            rowEntries.add(null);
            for (Apps.Entry e : recents) {
                appRow.addView(tile(e, v -> {
                    recentMenu(e);
                    return true;
                }));
                rowEntries.add(e);
            }
        }
        appRow.addView(tile(Apps.allAppsTile(), null));
        rowEntries.add(Apps.allAppsTile());
    }

    /** Brand-free placeholder tiles, so promo screenshots don't show other companies' logos. */
    private void fillDemoRow() {
        String[][] home = {{"Movies", "FFC0563B"}, {"Series", "FF3A6EA5"}, {"Sports", "FF2E8B57"},
                {"Quran", "FFB8862B"}, {"Music", "FF7A4FA3"}, {"News", "FF4F5D73"}};
        String[][] recent = {{"Kids TV", "FFD9654B"}, {"Recipes", "FF5B8C5A"}};
        String[][] kids = {{"Kids TV", "FFD9654B"}, {"Cartoons", "FF3A6EA5"}, {"Learn", "FF2E8B57"}};
        boolean kidsOn = Kids.on(this);
        for (String[] d : kidsOn ? kids : home) appRow.addView(tile(demoEntry(d), null));
        if (kidsOn) return;
        View divider = new View(this);
        divider.setBackgroundColor(0x2EFFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(3), px(96));
        dlp.rightMargin = px(36);
        dlp.bottomMargin = px(26);
        appRow.addView(divider, dlp);
        for (String[] d : recent) appRow.addView(tile(demoEntry(d), null));
        appRow.addView(tile(Apps.allAppsTile(), null));
    }

    private static Apps.Entry demoEntry(String[] d) {
        Apps.Entry e = new Apps.Entry();
        e.key = "demo:" + d[0];
        e.label = d[0];
        e.color = (int) Long.parseLong(d[1], 16);
        return e;
    }

    /** Allowed apps that are still installed: home-row order first, then the rest A–Z. */
    private List<Apps.Entry> kidsEntries() {
        Set<String> allowed = new HashSet<>(Kids.allowed(this));
        List<Apps.Entry> out = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (Apps.Entry e : favs) if (allowed.contains(e.key) && added.add(e.key)) out.add(e);
        for (Apps.Entry e : allApps) if (allowed.contains(e.key) && added.add(e.key)) out.add(e);
        return out;
    }

    /** Shows or hides the parent controls to match Kids mode, then rebuilds the row. */
    private void applyMode() {
        boolean kids = Kids.on(this);
        int adult = kids ? View.GONE : View.VISIBLE;
        wifiPill.setVisibility(adult);
        inputsPill.setVisibility(adult);
        kidsPill.setVisibility(adult);
        powerPill.setVisibility(adult);
        exitKidsPill.setVisibility(kids ? View.VISIBLE : View.GONE);
        chip.setFocusable(!kids);
        if (kids) closePanel();
        reloadApps(0);
        updateNetwork();
    }

    private void focusTile(int index) {
        if (appRow.getChildCount() == 0) return;
        int i = Math.max(0, Math.min(index, appRow.getChildCount() - 1));
        View v = appRow.getChildAt(i);
        v.post(v::requestFocus);
    }

    private void launch(Apps.Entry e) {
        if (e.intent == null) return; // demo tile
        try {
            startActivity(e.intent);
        } catch (RuntimeException ex) {
            toast("Couldn't open " + e.label);
        }
    }

    // ---------------------------------------------------------------- clock + prayers

    private void update() {
        long now = System.currentTimeMillis();
        Calendar cal = PrayerData.now();
        cal.setTimeInMillis(now);
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        clock.setText((h % 12 == 0 ? 12 : h % 12) + ":" + (m < 10 ? "0" : "") + m);
        ampm.setText(h >= 12 ? "PM" : "AM");
        dateLine.setText(dateFormat.format(new Date(now)));
        chip.setText(Prefs.label(this) + " · " + Prefs.zone(this));

        String zone = Prefs.zone(this);
        PrayerData.Day d = PrayerData.day(this, zone, cal);
        if (d == null) {
            for (int i = 0; i < 6; i++) {
                cardTime[i].setText("--:--");
                styleCard(i, false, false, false);
            }
            hijriLine.setText("");
            nextLine.setText(offline ? "No internet. Prayer times will appear once the TV is online."
                    : "Loading prayer times…");
            fetchIfNeeded(false);
            return;
        }
        hijriLine.setText(PrayerData.hijriText(d.hijri));

        int nextIdx = -1;
        for (int i = 0; i < 6; i++) {
            if (i != PrayerData.SUNRISE && d.times[i] > now) {
                nextIdx = i;
                break;
            }
        }
        long target = -1;
        boolean tomorrow = false;
        PrayerData.Day shown = d;
        if (nextIdx >= 0) {
            target = d.times[nextIdx];
        } else {
            // After Isha the cards switch to tomorrow, with Fajr up next.
            Calendar t = (Calendar) cal.clone();
            t.add(Calendar.DAY_OF_MONTH, 1);
            PrayerData.Day d2 = PrayerData.day(this, zone, t);
            if (d2 != null) {
                target = d2.times[0];
                shown = d2;
                nextIdx = 0;
            }
            tomorrow = true;
        }
        // The current waktu: the latest prayer that has started. Before Fajr and after Isha it's Isha;
        // between Sunrise and Dhuhr no prayer is current.
        int curIdx = 5;
        if (!tomorrow) {
            for (int i = 0; i < 6; i++) if (d.times[i] <= now) curIdx = i;
        }
        if (curIdx == PrayerData.SUNRISE) curIdx = -1;

        for (int i = 0; i < 6; i++) {
            cardTime[i].setText(PrayerData.hm(shown.times[i], false));
            boolean past = !tomorrow && shown.times[i] <= now && i != curIdx;
            styleCard(i, i == nextIdx, i == curIdx, past);
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (curIdx >= 0) {
            appendStyled(sb, PrayerData.NAMES[curIdx], Ui.ACCENT);
            sb.append(" now   ·   ");
        }
        sb.append(PrayerData.NAMES[nextIdx >= 0 ? nextIdx : 0]).append(tomorrow ? " tomorrow" : "");
        if (target < 0) {
            nextLine.setText(sb);
            fetchIfNeeded(false);
            return;
        }
        long secs = (target - now + 999) / 1000;
        long hrs = secs / 3600;
        long mins = (secs % 3600) / 60;
        String left = hrs > 0 ? hrs + "h " + mins + "m" : Math.max(1, (secs + 59) / 60) + " min";
        sb.append(" in ");
        appendStyled(sb, left, Ui.GREEN);
        nextLine.setText(sb);
    }

    private static void appendStyled(SpannableStringBuilder sb, String s, int color) {
        int start = sb.length();
        sb.append(s);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void styleCard(int i, boolean next, boolean current, boolean past) {
        GradientDrawable bg = Ui.round(current ? Ui.ACCENT : next ? Ui.GREEN_TINT : Ui.CARD, px(20));
        bg.setStroke(px(3), current ? Ui.ACCENT : next ? Ui.GREEN : 0);
        cards[i].setBackground(bg);
        int color = current ? Ui.BG : Ui.FG;
        cardName[i].setTextColor(color);
        cardTime[i].setTextColor(color);
        cardTag[i].setText(current ? "NOW" : next ? "NEXT" : "");
        cardTag[i].setTextColor(current ? Ui.BG : Ui.GREEN);
        cards[i].setAlpha(past ? 0.42f : 1f);
    }

    private void fetchIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (fetching || (!force && now - lastFetchAttempt < RETRY_FETCH_MS)) return;
        fetching = true;
        lastFetchAttempt = now;
        String zone = Prefs.zone(this);
        io.execute(() -> {
            boolean ok = PrayerData.ensure(getApplicationContext(), zone);
            runOnUiThread(() -> {
                fetching = false;
                offline = !ok;
                update();
                AlarmReceiver.schedule(this);
            });
        });
    }

    // ---------------------------------------------------------------- panels

    private void showPanel(String title, String subtitle, List<String> items, int focusIndex,
                           ItemAction onClick, ItemAction onLongClick) {
        closePanel();
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xB805070B);
        dim.setClickable(true);

        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(Ui.PANEL);
        p.setPadding(px(56), px(64), px(56), px(48));
        p.addView(Ui.text(this, title, 44, Ui.sans700, Ui.FG));
        TextView sub = Ui.text(this, subtitle, 24, Ui.sans400, Ui.PANEL_SUB);
        sub.setPadding(0, px(14), 0, px(28));
        p.addView(sub);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            TextView t = Ui.text(this, items.get(i), 28, Ui.sans600, Ui.FG);
            t.setTextColor(Ui.focusText(Ui.FG, Ui.BG));
            t.setBackground(Ui.focusBg(0x00000000, Ui.ACCENT, px(14)));
            t.setPadding(px(28), px(20), px(28), px(20));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setFocusable(true);
            t.setOnClickListener(v -> onClick.run(idx));
            if (onLongClick != null) {
                t.setOnLongClickListener(v -> {
                    onLongClick.run(idx);
                    return true;
                });
            }
            list.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        sv.addView(list);
        p.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        dim.addView(p, new FrameLayout.LayoutParams(px(900), MATCH, Gravity.END));
        View f = list.getChildCount() == 0 ? null
                : list.getChildAt(Math.max(0, Math.min(focusIndex, list.getChildCount() - 1)));
        attachPanel(dim, f);
    }

    /** Puts a panel over the home screen and keeps D-pad focus inside it until it closes. */
    private void attachPanel(View dim, View focus) {
        root.addView(dim, new FrameLayout.LayoutParams(MATCH, MATCH));
        panel = dim;
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (focus != null) focus.post(focus::requestFocus);
    }

    private void closePanel() {
        pinInput = null;
        if (panel == null) return;
        root.removeView(panel);
        panel = null;
        content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    private void openZonePicker() {
        String raw = PrayerData.cachedZones(this);
        if (raw == null) {
            toast("Loading locations…");
            io.execute(() -> {
                try {
                    PrayerData.fetchZones(getApplicationContext());
                    runOnUiThread(this::openZonePicker);
                } catch (Exception e) {
                    runOnUiThread(() -> toast("No internet. Try again in a moment."));
                }
            });
            return;
        }
        try {
            JSONArray zones = new JSONArray(raw);
            List<String> items = new ArrayList<>();
            List<String> codes = new ArrayList<>();
            List<String> districts = new ArrayList<>();
            int current = 0;
            String zone = Prefs.zone(this);
            for (int i = 0; i < zones.length(); i++) {
                JSONObject z = zones.getJSONObject(i);
                String code = z.getString("jakimCode");
                String daerah = z.optString("daerah", "");
                items.add(z.optString("negeri", "") + " · " + daerah + "  (" + code + ")");
                codes.add(code);
                districts.add(daerah.split(",")[0].trim());
                if (code.equals(zone)) current = i;
            }
            boolean firstRun = !Prefs.zoneChosen(this);
            showPanel(firstRun ? "Where do you live?" : "Choose location",
                    (firstRun ? "Welcome to Solat Launcher. " : "")
                            + "Prayer times follow your JAKIM zone. Pick the area you live in.",
                    items, current, i -> {
                        String code = codes.get(i);
                        String label = code.equals(zone) && !firstRun
                                ? Prefs.label(this) : titleCase(districts.get(i));
                        Prefs.setZone(this, code, label);
                        closePanel();
                        chip.requestFocus();
                        offline = false;
                        update();
                        fetchIfNeeded(true);
                        toast("Location saved: " + label);
                    }, null);
        } catch (JSONException e) {
            toast("Couldn't read the location list.");
        }
    }

    private void openAllApps(int focusIndex) {
        Set<String> onHome = new HashSet<>(keys(favs));
        List<String> items = new ArrayList<>();
        for (Apps.Entry e : allApps) items.add(e.label + (onHome.contains(e.key) ? "   · on home" : ""));
        showPanel("All apps", "OK to open. Hold OK to add to or remove from the home row.",
                items, Math.max(focusIndex, 0), i -> {
                    closePanel();
                    launch(allApps.get(i));
                }, i -> {
                    toggleFav(allApps.get(i).key);
                    openAllApps(i);
                });
    }

    private void tileMenu(int index) {
        Apps.Entry e = favs.get(index);
        List<String> items = Prefs.list("Move left", "Move right", "Remove from home");
        showPanel(e.label, "Choose what to do with this tile.", items, 0, i -> {
            List<String> keys = keys(favs);
            int target = index;
            if (i == 0 && index > 0) {
                keys.add(index - 1, keys.remove(index));
                target = index - 1;
            } else if (i == 1 && index < keys.size() - 1) {
                keys.add(index + 1, keys.remove(index));
                target = index + 1;
            } else if (i == 2) {
                keys.remove(index);
            }
            Prefs.setFavs(this, keys);
            favs = Apps.favorites(this, allApps);
            closePanel();
            rebuildRowOnly();
            focusTile(target);
        }, null);
    }

    private void rebuildRowOnly() {
        fillRow();
    }

    private void recentMenu(Apps.Entry e) {
        showPanel(e.label, "You opened this recently. Pin it so it always stays on the home row.",
                Prefs.list("Pin to home row"), 0, i -> {
                    toggleFav(e.key);
                    closePanel();
                    focusTile(favs.size() - 1);
                    toast(e.label + " pinned to home");
                }, null);
    }

    /** Adds a key to the end of the home row, or removes it if it's already there. */
    private void toggleFav(String key) {
        List<String> keys = keys(favs);
        if (keys.contains(key)) keys.remove(key);
        else keys.add(key);
        Prefs.setFavs(this, keys);
        favs = Apps.favorites(this, allApps);
        rebuildRowOnly();
    }

    // ---------------------------------------------------------------- top bar actions

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            if (Settings.ACTION_SETTINGS.equals(action)) toast("Couldn't open Settings");
            else openSettings(Settings.ACTION_SETTINGS);
        }
    }

    private void watchNetwork(boolean on) {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        if (on && netCallback == null) {
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                    runOnUiThread(MainActivity.this::updateNetwork);
                }

                @Override
                public void onLost(Network n) {
                    runOnUiThread(MainActivity.this::updateNetwork);
                }
            };
            try {
                cm.registerDefaultNetworkCallback(netCallback);
            } catch (RuntimeException e) {
                netCallback = null;
            }
        } else if (!on && netCallback != null) {
            try {
                cm.unregisterNetworkCallback(netCallback);
            } catch (RuntimeException ignored) {
                // Already unregistered.
            }
            netCallback = null;
        }
        updateNetwork();
    }

    /** The Wi-Fi pill stays a quiet icon unless something needs attention. */
    private void updateNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        int icon;
        String text;
        String desc;
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            icon = R.drawable.ic_wifi_off;
            text = caps == null ? "Offline" : "No internet";
            desc = text + ". Press OK for network settings.";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            icon = R.drawable.ic_ethernet;
            text = "";
            desc = "Connected by cable. Press OK for network settings.";
        } else {
            icon = R.drawable.ic_wifi_on;
            boolean weak = Build.VERSION.SDK_INT >= 29
                    && caps.getSignalStrength() != Integer.MIN_VALUE
                    && caps.getSignalStrength() < WEAK_WIFI_DBM;
            text = weak ? "Weak Wi-Fi" : "";
            desc = (weak ? "Weak Wi-Fi signal" : "Wi-Fi connected") + ". Press OK for Wi-Fi settings.";
        }
        boolean problem = icon == R.drawable.ic_wifi_off || !text.isEmpty();
        int color = problem ? Ui.ERROR : Ui.MUTED2;
        wifiPill.setTextColor(Ui.focusText(color, Ui.BG));
        wifiPill.setCompoundDrawableTintList(Ui.focusText(color, Ui.BG));
        setPillIcon(wifiPill, icon);
        setPillText(wifiPill, text);
        wifiPill.setContentDescription(desc);
    }

    private void openInputs() {
        List<Apps.Entry> inputs = new ArrayList<>();
        for (Apps.Entry e : allApps) if (Apps.inputId(e) != null) inputs.add(e);
        if (inputs.isEmpty()) {
            toast("This TV didn't report any HDMI inputs.");
            return;
        }
        Set<String> onHome = new HashSet<>(keys(favs));
        List<String> items = new ArrayList<>();
        int focus = -1;
        for (int i = 0; i < inputs.size(); i++) {
            Apps.Entry e = inputs.get(i);
            boolean connected = Apps.inputConnected(this, Apps.inputId(e));
            if (connected && focus < 0) focus = i;
            // This TV reports every HDMI port as connected, so only "nothing plugged in" is trustworthy.
            items.add(e.label + (connected ? "" : "   · nothing plugged in")
                    + (onHome.contains(e.key) ? "   · on home" : ""));
        }
        showPanel("Inputs", "OK to switch to a device. Hold OK to pin an input to the home row.",
                items, Math.max(focus, 0), i -> {
                    closePanel();
                    launch(inputs.get(i));
                }, i -> {
                    toggleFav(inputs.get(i).key);
                    openInputs();
                });
    }

    private void openPower() {
        showPanel("Power & settings", "Restart and power off open the TV's own power menu so you can confirm.",
                Prefs.list("Turn off screen", "Restart or power off…", "TV settings", "Launcher settings"), 0, i -> {
                    if (i == 3) {
                        openLauncherSettings(0);
                        return;
                    }
                    closePanel();
                    if (i == 2) {
                        openSettings(Settings.ACTION_SETTINGS);
                        return;
                    }
                    int action = i == 0 ? AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                            : AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
                    if (!HomeKeyService.globalAction(action)) {
                        toast("Turn on \"" + getString(R.string.home_key_label)
                                + "\" in Settings › Accessibility first.");
                    }
                }, null);
    }

    private void openLauncherSettings(int focus) {
        String version = "";
        try {
            version = " " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // Version is only decoration.
        }
        List<String> items = Prefs.list(
                "YouTube button   · " + buttonTarget(Prefs.BUTTON_YOUTUBE),
                "Prime Video button   · " + buttonTarget(Prefs.BUTTON_PRIME),
                "Change location   · " + Prefs.label(this));
        showPanel("Launcher settings", "Solat Launcher" + version
                        + ". Prayer times are JAKIM e-Solat (via waktusolat.app). Button remapping is made for Sharp and other MediaTek Google TV remotes.",
                items, focus, i -> {
                    if (i == 0) pickButtonApp(Prefs.BUTTON_YOUTUBE, "YouTube button");
                    else if (i == 1) pickButtonApp(Prefs.BUTTON_PRIME, "Prime Video button");
                    else openZonePicker();
                }, null);
    }

    private String buttonTarget(String button) {
        String pkg = Prefs.remap(this, button);
        if (pkg == null) return "TV default";
        for (Apps.Entry e : allApps) if (e.key.equals("app:" + pkg)) return e.label;
        return "app not installed";
    }

    /** Lets the user point a remote app button at any installed app, or back to the TV's default. */
    private void pickButtonApp(String button, String title) {
        List<Apps.Entry> apps = new ArrayList<>();
        for (Apps.Entry e : allApps) if (e.key.startsWith("app:")) apps.add(e);
        String current = Prefs.remap(this, button);
        List<String> items = new ArrayList<>();
        items.add("TV default" + (current == null ? "   · selected" : ""));
        int focus = 0;
        for (int i = 0; i < apps.size(); i++) {
            boolean sel = apps.get(i).key.equals("app:" + current);
            if (sel) focus = i + 1;
            items.add(apps.get(i).label + (sel ? "   · selected" : ""));
        }
        showPanel(title + " opens", "If the button does nothing different on your remote, "
                        + "your TV sends another code. Leave it on TV default.", items, focus, i -> {
                    Prefs.setRemap(this, button, i == 0 ? null : apps.get(i - 1).key.substring(4));
                    openLauncherSettings(Prefs.BUTTON_YOUTUBE.equals(button) ? 0 : 1);
                }, null);
    }

    // ---------------------------------------------------------------- Kids mode

    private void openKids() {
        if (!Kids.hasPin(this)) {
            askPin("Set a Kids mode PIN", "Choose 4 digits. You'll need them to leave Kids mode.", first -> {
                askPin("Confirm your PIN", "Enter the same 4 digits again.", second -> {
                    if (!second.equals(first)) return "Those didn't match. Try again.";
                    Kids.setPin(this, second);
                    chooseKidsApps(true, 0);
                    return null;
                });
                return null;
            });
            return;
        }
        int count = kidsEntries().size();
        showPanel("Kids mode", "Shows only the apps you allow and hides Inputs, Power and Settings. "
                        + "Leaving needs your PIN.",
                Prefs.list("Turn on Kids mode", "Allowed apps   · " + count + " allowed", "Change PIN"), 0, i -> {
                    if (i == 0) {
                        if (count == 0) chooseKidsApps(true, 0);
                        else turnOnKids();
                    } else if (i == 1) {
                        chooseKidsApps(false, 0);
                    } else {
                        askPin("Enter your current PIN", "Then you can choose a new one.", p -> checkPin(p, () ->
                                askPin("New PIN", "Choose 4 new digits.", np -> {
                                    Kids.setPin(this, np);
                                    closePanel();
                                    kidsPill.requestFocus();
                                    toast("PIN changed");
                                    return null;
                                })));
                    }
                }, null);
    }

    /** Checks the parent PIN (with lockout after repeated misses) and runs onSuccess if it matches. */
    private String checkPin(String pin, Runnable onSuccess) {
        long wait = Kids.lockedFor(this);
        if (wait > 0) return "Too many tries. Wait " + ((wait + 999) / 1000) + " seconds.";
        if (!Kids.check(this, pin)) {
            wait = Kids.lockedFor(this);
            return wait > 0 ? "Too many tries. Wait " + ((wait + 999) / 1000) + " seconds."
                    : "Wrong PIN. Try again.";
        }
        onSuccess.run();
        return null;
    }

    private void chooseKidsApps(boolean turnOnWhenDone, int focus) {
        List<Apps.Entry> choices = new ArrayList<>();
        for (Apps.Entry e : allApps) if (!e.key.equals("settings")) choices.add(e);
        List<String> allowed = Kids.allowed(this);
        List<String> items = new ArrayList<>();
        items.add(turnOnWhenDone ? "Done · turn on Kids mode" : "Done");
        List<String> names = new ArrayList<>();
        for (Apps.Entry e : choices) {
            boolean ok = allowed.contains(e.key);
            items.add(e.label + (ok ? "   · allowed" : ""));
            if (ok) names.add(e.label);
        }
        String summary = names.isEmpty() ? "Nothing allowed yet." : "Allowed: " + String.join(", ", names) + ".";
        showPanel("Allowed apps", summary + " OK to allow or block. Settings is never allowed.", items, focus, i -> {
            if (i == 0) {
                if (allowed.isEmpty()) {
                    toast("Allow at least one app first.");
                    return;
                }
                saveKidsAllowed(allowed);
                closePanel();
                if (turnOnWhenDone) turnOnKids();
                else kidsPill.requestFocus();
                return;
            }
            String k = choices.get(i - 1).key;
            if (allowed.contains(k)) allowed.remove(k);
            else allowed.add(k);
            saveKidsAllowed(allowed);
            chooseKidsApps(turnOnWhenDone, i);
        }, null);
    }

    private void saveKidsAllowed(List<String> keys) {
        Set<String> pkgs = new HashSet<>();
        for (Apps.Entry e : allApps) {
            if (!keys.contains(e.key)) continue;
            String p = Apps.packageOf(this, e);
            if (p != null) pkgs.add(p);
        }
        Kids.setAllowed(this, keys, pkgs);
    }

    private void turnOnKids() {
        saveKidsAllowed(Kids.allowed(this));
        Kids.setOn(this, true);
        applyMode();
        toast("Kids mode is on. To leave, choose the lock at the top right.");
    }

    private void exitKids() {
        askPin("Leave Kids mode", "Enter the parent PIN.", p -> checkPin(p, () -> {
            Kids.setOn(this, false);
            closePanel();
            applyMode();
            kidsPill.requestFocus();
            toast("Kids mode is off");
        }));
    }

    /** A centred number pad. Works with the D-pad and with the remote's number keys. */
    private void askPin(String title, String subtitle, PinCheck check) {
        closePanel();
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xD905070B);
        dim.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(Ui.PANEL, px(28)));
        card.setPadding(px(64), px(56), px(64), px(48));
        TextView t = Ui.text(this, title, 40, Ui.sans700, Ui.FG);
        t.setGravity(Gravity.CENTER);
        card.addView(t);
        TextView sub = Ui.text(this, subtitle, 24, Ui.sans400, Ui.PANEL_SUB);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, px(14), 0, px(32));
        card.addView(sub, new LinearLayout.LayoutParams(px(520), WRAP));

        LinearLayout dotsRow = new LinearLayout(this);
        dotsRow.setGravity(Gravity.CENTER_HORIZONTAL);
        View[] dots = new View[Kids.PIN_LENGTH];
        for (int i = 0; i < dots.length; i++) {
            dots[i] = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(24), px(24));
            lp.leftMargin = lp.rightMargin = px(14);
            dotsRow.addView(dots[i], lp);
        }
        card.addView(dotsRow);
        TextView error = Ui.text(this, "", 24, Ui.sans600, Ui.ERROR);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, px(18), 0, px(18));
        card.addView(error, new LinearLayout.LayoutParams(px(520), px(66)));

        StringBuilder entered = new StringBuilder();
        Runnable paintDots = () -> {
            for (int i = 0; i < dots.length; i++) {
                GradientDrawable g = Ui.round(i < entered.length() ? Ui.ACCENT : 0, px(12));
                g.setStroke(px(3), i < entered.length() ? Ui.ACCENT : Ui.MUTED2);
                dots[i].setBackground(g);
            }
        };
        paintDots.run();

        PinInput input = new PinInput() {
            @Override
            public void digit(char d) {
                if (entered.length() >= Kids.PIN_LENGTH) return;
                entered.append(d);
                error.setText("");
                paintDots.run();
                if (entered.length() < Kids.PIN_LENGTH) return;
                String pin = entered.toString();
                handler.postDelayed(() -> {
                    String msg = check.accept(pin);
                    if (msg == null) {
                        if (panel == dim) closePanel();
                        return;
                    }
                    entered.setLength(0);
                    paintDots.run();
                    error.setText(msg);
                    ObjectAnimator.ofFloat(card, "translationX", 0, px(22), -px(22), px(14), -px(14), 0)
                            .setDuration(320).start();
                }, 160);
            }

            @Override
            public void delete() {
                if (entered.length() > 0) entered.setLength(entered.length() - 1);
                paintDots.run();
            }
        };

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "Delete"};
        View first = null;
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setBaselineAligned(false); // keeps "0" level with the smaller "Clear"/"Delete"
            for (int c = 0; c < 3; c++) {
                String k = keys[r * 3 + c];
                boolean word = k.length() > 1;
                TextView key = Ui.text(this, k, word ? 24 : 40, Ui.sans600, Ui.FG);
                key.setTextColor(Ui.focusText(word ? Ui.MUTED2 : Ui.FG, Ui.BG));
                key.setBackground(Ui.focusBg(Ui.TILE, Ui.ACCENT, px(18)));
                key.setGravity(Gravity.CENTER);
                key.setFocusable(true);
                key.setOnClickListener(v -> {
                    if (k.equals("Clear")) {
                        entered.setLength(0);
                        paintDots.run();
                    } else if (k.equals("Delete")) {
                        input.delete();
                    } else {
                        input.digit(k.charAt(0));
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(150), px(96));
                lp.setMargins(px(9), px(9), px(9), px(9));
                row.addView(key, lp);
                if (first == null) first = key;
            }
            card.addView(row);
        }

        dim.addView(card, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        attachPanel(dim, first);
        pinInput = input;
    }

    private static List<String> keys(List<Apps.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Apps.Entry e : entries) out.add(e.key);
        return out;
    }

    private static String titleCase(String s) {
        if (s.isEmpty() || !s.equals(s.toUpperCase(Locale.ROOT))) return s;
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
