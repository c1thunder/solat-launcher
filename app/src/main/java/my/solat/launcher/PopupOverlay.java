package my.solat.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The "It's time for Maghrib" banner. Drawn over any app (needs "display over other apps"),
 * never takes focus, so the remote keeps controlling whatever is playing.
 */
final class PopupOverlay {
    private static final int SHOW_SECONDS = 60;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View current;
    private static Runnable ticker;

    private PopupOverlay() {}

    static void show(Context ctx, String name, long at) {
        Context c = ctx.getApplicationContext();
        MAIN.post(() -> showOnMain(c, name, at));
    }

    private static void showOnMain(Context c, String name, long at) {
        if (!Settings.canDrawOverlays(c)) return;
        Ui.init(c);
        WindowManager wm = c.getSystemService(WindowManager.class);
        dismiss(wm);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.px(36), Ui.px(32), Ui.px(44), Ui.px(32));
        card.setBackground(Ui.round(0xFF111A2D, Ui.px(24)));
        card.setElevation(Ui.px(20));

        View badge = new CrescentBadge(c);
        card.addView(badge, new LinearLayout.LayoutParams(Ui.px(88), Ui.px(88)));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.px(28), 0, 0, 0);
        texts.addView(Ui.text(c, "It's time for", 24, Ui.sans500, Ui.MUTED));
        TextView title = Ui.text(c, name + " · " + PrayerData.hm(at, true), 52, Ui.sans700, Ui.FG);
        title.setPadding(0, Ui.px(8), 0, Ui.px(8));
        texts.addView(title);
        TextView sub = Ui.text(c, "", 22, Ui.sans400, Ui.MUTED2);
        texts.addView(sub);
        card.addView(texts);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not FLAG_NOT_TOUCHABLE: Android 12+ caps pass-through overlays at 80% opacity, which
                // made the card see-through. TVs have no touch; NOT_FOCUSABLE keeps remote keys going
                // to the app underneath.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Ui.px(72);
        lp.y = Ui.px(56);
        lp.windowAnimations = android.R.style.Animation_Toast;
        try {
            wm.addView(card, lp);
        } catch (RuntimeException e) {
            return;
        }
        current = card;

        String place = Prefs.label(c);
        final long end = System.currentTimeMillis() + SHOW_SECONDS * 1000L;
        ticker = new Runnable() {
            @Override
            public void run() {
                long left = (end - System.currentTimeMillis() + 999) / 1000;
                if (left <= 0) {
                    dismiss(wm);
                    return;
                }
                sub.setText(place + " · closes in " + left + "s");
                MAIN.postDelayed(this, 1000);
            }
        };
        ticker.run();
    }

    private static void dismiss(WindowManager wm) {
        if (ticker != null) MAIN.removeCallbacks(ticker);
        ticker = null;
        if (current != null) {
            try {
                wm.removeView(current);
            } catch (RuntimeException ignored) {
                // already gone
            }
            current = null;
        }
    }

    /** Gold circle with a navy crescent. */
    static final class CrescentBadge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path cut = new Path();

        CrescentBadge(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
            paint.setColor(Ui.ACCENT);
            canvas.drawCircle(w / 2f, h / 2f, r, paint);
            float cr = r * 0.52f;
            path.reset();
            cut.reset();
            path.addCircle(w / 2f - r * 0.06f, h / 2f, cr, Path.Direction.CW);
            cut.addCircle(w / 2f + r * 0.18f, h / 2f - r * 0.16f, cr * 0.86f, Path.Direction.CW);
            path.op(cut, Path.Op.DIFFERENCE);
            paint.setColor(Ui.BG);
            canvas.drawPath(path, paint);
        }
    }
}
