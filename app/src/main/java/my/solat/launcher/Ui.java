package my.solat.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** Design A ("Malam") tokens and small view helpers. Sizes are authored for a 1920px-wide screen. */
final class Ui {
    static final int BG = 0xFF0C1424;
    static final int FG = 0xFFF3EEE3;
    static final int MUTED = 0xFFCFC7B6;
    static final int MUTED2 = 0xFFB9B2A3;
    static final int ACCENT = 0xFFE3B55B;
    static final int GREEN = 0xFF5CC79A;
    static final int GREEN_TINT = 0x1A5CC79A;
    static final int ERROR = 0xFFEF8A7A;
    static final int CARD = 0x0DFFFFFF;
    static final int TILE = 0xFF26324A;
    static final int PANEL = 0xFF151C2B;
    static final int PANEL_SUB = 0xFF9AA3B2;

    static float S = 1f;
    static Typeface serif, sans400, sans500, sans600, sans700;

    private Ui() {}

    static void init(Context c) {
        S = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        if (serif != null) return;
        AssetManager am = c.getAssets();
        try {
            serif = Typeface.createFromAsset(am, "fonts/DMSerifDisplay-Regular.ttf");
        } catch (RuntimeException e) {
            serif = Typeface.SERIF;
        }
        sans400 = sans(am, 400);
        sans500 = sans(am, 500);
        sans600 = sans(am, 600);
        sans700 = sans(am, 700);
    }

    private static Typeface sans(AssetManager am, int weight) {
        Typeface t = new Typeface.Builder(am, "fonts/PlusJakartaSans.ttf")
                .setFontVariationSettings("'wght' " + weight)
                .setWeight(weight)
                .setFallback("sans-serif")
                .build();
        return t != null ? t : Typeface.create(Typeface.SANS_SERIF, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    static int px(float v) {
        return Math.round(v * S);
    }

    static TextView text(Context c, CharSequence s, float size, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * S);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static StateListDrawable focusBg(int normal, int focused, float radiusPx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(focused, radiusPx));
        s.addState(new int[]{}, round(normal, radiusPx));
        return s;
    }

    static ColorStateList focusText(int normal, int focused) {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{focused, normal});
    }
}
