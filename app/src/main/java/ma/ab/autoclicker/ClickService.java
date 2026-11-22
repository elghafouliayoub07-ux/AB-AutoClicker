package ma.ab.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * خدمة إمكانية الوصول: اللوحة العائمة، النقط، والماكرو.
 *
 * الماكرو = خطوات بالترتيب. كل خطوة فيها نقطة وحدة ولا بزاف (نقر متعدد فنفس اللحظة).
 * كل نقطة عندها: عدد النقرات + الوقت بين نقرة ونقرة.
 * من بعد ما تسالي الخطوة، كيتسنى "الوقت بين الخطوات" ومن بعد كيدوز للخطوة الجاية.
 */
public class ClickService extends AccessibilityService {

    static ClickService instance;

    private static final int BTN_DP = 34;
    private static final int POINT_DP = 44;
    private static final long TAP_MS = 20;
    private static final long MERGE_MS = 30;
    static final long MIN_GAP = 40;
    private static final Object TOKEN = new Object();

    private static final int[] STEP_COLORS = {
            0xFFE5484D, 0xFF3D8BFD, 0xFF22A55B, 0xFFF59E0B,
            0xFFA855F7, 0xFF06B6D4, 0xFFEC4899, 0xFF84CC16
    };

    private WindowManager wm;
    private Handler handler;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView playBtn;
    private final List<View> extraButtons = new ArrayList<>();

    /** النقط ومعلوماتهم */
    private static class Pt {
        PointView view;
        WindowManager.LayoutParams lp;
        boolean joinPrev;   // كتنقر مع النقطة اللي قبلها فنفس اللحظة
        int count = 1;      // عدد النقرات
        long gap = 100;     // الوقت بين نقرة ونقرة (ms)
        int step;           // كيتحسب أوتوماتيكياً
    }

    private final List<Pt> pts = new ArrayList<>();

    private View editor;

    private boolean running = false;
    private int stepIndex = 0;
    private long cyclesDone = 0;
    private long intervalMs = 500;
    private long maxCycles = 0;

    // ───────────── دورة حياة الخدمة ─────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() {
        stopClicking();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hidePanel();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hidePanel();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (handler != null) handler.postDelayed(this::keepAllOnScreen, 300);
    }

    // ───────────── أدوات مساعدة ─────────────

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Point screenSize() {
        Point p = new Point();
        //noinspection deprecation
        wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private void clamp(View v, WindowManager.LayoutParams lp) {
        Point s = screenSize();
        int w = v.getWidth() > 0 ? v.getWidth() : dp(POINT_DP);
        int h = v.getHeight() > 0 ? v.getHeight() : dp(POINT_DP);
        // النقطة تقدر توصل حتى للحافة (الوسط ديالها يوصل للحافة)
        int mx = (v instanceof PointView) ? w / 2 : 0;
        int my = (v instanceof PointView) ? h / 2 : 0;
        lp.x = Math.max(-mx, Math.min(lp.x, s.x - w + mx));
        lp.y = Math.max(-my, Math.min(lp.y, s.y - h + my));
    }

    private void update(View v, WindowManager.LayoutParams lp) {
        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
    }

    private void keepAllOnScreen() {
        if (panel != null) { clamp(panel, panelLp); update(panel, panelLp); }
        for (Pt p : pts) { clamp(p.view, p.lp); update(p.view, p.lp); }
    }

    /** الجر بالصبع؛ لمسة قصيرة بلا حركة = click. */
    @SuppressLint("ClickableViewAccessibility")
    private void makeDraggable(View handleView, View windowView, WindowManager.LayoutParams lp) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        handleView.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x; startY = lp.y;
                        touchX = e.getRawX(); touchY = e.getRawY();
                        moved = false;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - touchX, dy = e.getRawY() - touchY;
                        if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                        if (moved) {
                            lp.x = startX + Math.round(dx);
                            lp.y = startY + Math.round(dy);
                            clamp(windowView, lp);
                            update(windowView, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (!moved) v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        return true;
                }
                return false;
            }
        });
    }

    private TextView circleButton(String label, int bgColor, int sizeDp, float textSp) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(textSp);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(bgColor);
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(p);
        return b;
    }

    // ───────────── اللوحة العائمة ─────────────

    void showPanel() {
        if (panel != null) return;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC16181D);
        bg.setCornerRadius(dp(21));
        panel.setBackground(bg);

        panelLp = overlayParams();
        panelLp.x = dp(6);
        panelLp.y = dp(160);

        playBtn = circleButton("▶", 0xFF22A55B, BTN_DP, 14);
        playBtn.setOnClickListener(v -> toggle());
        TextView addStep = circleButton("+", 0xFF3D8BFD, BTN_DP, 17);
        addStep.setOnClickListener(v -> addPoint(false));
        TextView addMulti = circleButton("++", 0xFFA855F7, BTN_DP, 12);
        addMulti.setOnClickListener(v -> addPoint(true));
        TextView remove = circleButton("−", 0xFFF59E0B, BTN_DP, 17);
        remove.setOnClickListener(v -> { if (!pts.isEmpty()) removePoint(pts.size() - 1); });
        TextView close = circleButton("✕", 0xFF5F6368, BTN_DP, 13);
        close.setOnClickListener(v -> hidePanel());

        extraButtons.clear();
        Collections.addAll(extraButtons, addStep, addMulti, remove, close);

        panel.addView(playBtn);
        for (View b : extraButtons) panel.addView(b);

        makeDraggable(playBtn, panel, panelLp);
        for (View b : extraButtons) makeDraggable(b, panel, panelLp);

        wm.addView(panel, panelLp);
        if (pts.isEmpty()) addPoint(false);
    }

    void hidePanel() {
        stopClicking();
        closeEditor();
        for (Pt p : pts) {
            try { wm.removeView(p.view); } catch (Exception ignored) { }
        }
        pts.clear();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
    }

    private void setPanelCollapsed(boolean collapsed) {
        for (View b : extraButtons) b.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (panel != null) update(panel, panelLp);
    }

    // ───────────── النقط ─────────────

    /** كيعاود يحسب أرقام الخطوات ويحدّث الشكل ديال كل نقطة. */
    private void renumber() {
        int step = 0;
        for (int i = 0; i < pts.size(); i++) {
            Pt p = pts.get(i);
            if (i == 0) p.joinPrev = false;
            if (!p.joinPrev) step++;
            p.step = step;
            p.view.set(step, p.count, STEP_COLORS[(step - 1) % STEP_COLORS.length]);
        }
    }

    private int totalSteps() {
        return pts.isEmpty() ? 0 : pts.get(pts.size() - 1).step;
    }

    private void addPoint(boolean multi) {
        if (running) return;
        Pt p = new Pt();
        p.joinPrev = multi && !pts.isEmpty();
        p.view = new PointView(this);

        int size = dp(POINT_DP);
        p.lp = overlayParams();
        p.lp.width = size;
        p.lp.height = size;
        Point s = screenSize();
        if (p.joinPrev) {
            Pt prev = pts.get(pts.size() - 1);
            p.lp.x = prev.lp.x + dp(48);
            p.lp.y = prev.lp.y;
            p.count = prev.count;
            p.gap = prev.gap;
        } else {
            int n = totalSteps();
            p.lp.x = s.x / 2 - size / 2 + (n % 4) * dp(20) - dp(30);
            p.lp.y = s.y / 2 - size / 2 + (n / 4 % 6) * dp(48) - dp(100);
        }
        clamp(p.view, p.lp);

        p.view.setOnClickListener(v -> { if (!running) openEditor(pts.indexOf(p)); });
        makeDraggable(p.view, p.view, p.lp);
        pts.add(p);
        renumber();
        wm.addView(p.view, p.lp);
    }

    private void removePoint(int i) {
        if (running || i < 0 || i >= pts.size()) return;
        try { wm.removeView(pts.get(i).view); } catch (Exception ignored) { }
        pts.remove(i);
        renumber();
    }

    private void setPointsTouchable(boolean touchable) {
        for (Pt p : pts) {
            if (touchable) p.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else p.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            p.view.setAlpha(touchable ? 1f : 0.4f);
            update(p.view, p.lp);
        }
    }

    // ───────────── نافذة الإعدادات ديال النقطة ─────────────

    private long nextGap(long v, boolean up) {
        long step = v < 100 ? 10 : (v < 1000 ? 50 : 250);
        if (!up && v <= 100 && v > 40) step = 10;
        long r = up ? v + step : v - step;
        return Math.max(MIN_GAP, Math.min(60000, r));
    }

    /** زر كيعاود براسو ملي كتبقى ضاغط عليه. */
    @SuppressLint("ClickableViewAccessibility")
    private void repeating(View b, Runnable action) {
        b.setOnTouchListener(new View.OnTouchListener() {
            int n;
            final Runnable rep = new Runnable() {
                @Override public void run() {
                    action.run();
                    n++;
                    handler.postDelayed(this, n > 15 ? 40 : 90);
                }
            };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        n = 0;
                        action.run();
                        handler.postDelayed(rep, 400);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        handler.removeCallbacks(rep);
                        return true;
                }
                return false;
            }
        });
    }

    private TextView smallText(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout stepperRow(String label, TextView value, Runnable minus, Runnable plus) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(10), 0, 0);
        col.addView(smallText(label, 13, 0xFFB8BCC4));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, 0);
        TextView m = circleButton("−", 0xFF2C313A, 40, 20);
        TextView pl = circleButton("+", 0xFF2C313A, 40, 20);
        repeating(m, minus);
        repeating(pl, plus);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(Color.WHITE);
        value.setTextSize(18);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(m);
        row.addView(value, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(pl);
        col.addView(row);
        return col;
    }

    private TextView pill(String s, int color) {
        TextView b = smallText(s, 14, Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(color);
        b.setBackground(bg);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        return b;
    }

    private void openEditor(int index) {
        closeEditor();
        if (index < 0 || index >= pts.size()) return;
        final Pt p = pts.get(index);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xF21A1D23);
        bg.setStroke(dp(1), 0xFF2C313A);
        card.setBackground(bg);

        TextView title = smallText("", 16, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView countVal = new TextView(this);
        TextView gapVal = new TextView(this);
        Runnable refresh = () -> {
            title.setText("⚙  النقطة " + (pts.indexOf(p) + 1) + "  ·  الخطوة " + p.step);
            countVal.setText(String.valueOf(p.count));
            gapVal.setText(p.gap + " ms");
            renumber();
        };

        card.addView(stepperRow("عدد النقرات فهاد البلاصة", countVal,
                () -> { p.count = Math.max(1, p.count - 1); refresh.run(); },
                () -> { p.count = Math.min(9999, p.count + 1); refresh.run(); }));
        card.addView(stepperRow("الوقت بين نقرة ونقرة", gapVal,
                () -> { p.gap = nextGap(p.gap, false); refresh.run(); },
                () -> { p.gap = nextGap(p.gap, true); refresh.run(); }));

        if (index > 0) {
            TextView join = pill("", 0xFF2C313A);
            Runnable joinText = () -> {
                join.setText((p.joinPrev ? "☑" : "☐") + "  نقر متعدد: مع النقطة اللي قبلها فنفس اللحظة");
                ((GradientDrawable) join.getBackground()).setColor(p.joinPrev ? 0xFF6D28D9 : 0xFF2C313A);
            };
            joinText.run();
            join.setOnClickListener(v -> { p.joinPrev = !p.joinPrev; joinText.run(); refresh.run(); });
            LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            jp.topMargin = dp(14);
            card.addView(join, jp);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView del = pill("حذف", 0xFFB42318);
        del.setOnClickListener(v -> { int i = pts.indexOf(p); closeEditor(); removePoint(i); });
        TextView ok = pill("تم ✓", 0xFF22A55B);
        ok.setOnClickListener(v -> closeEditor());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ap.setMargins(dp(4), 0, dp(4), 0);
        actions.addView(del, ap);
        LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2f);
        ap2.setMargins(dp(4), 0, dp(4), 0);
        actions.addView(ok, ap2);
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        acp.topMargin = dp(16);
        card.addView(actions, acp);

        refresh.run();

        WindowManager.LayoutParams lp = overlayParams();
        lp.width = Math.min(dp(300), screenSize().x - dp(32));
        lp.gravity = Gravity.CENTER;
        lp.x = 0;
        lp.y = 0;
        editor = card;
        wm.addView(card, lp);
    }

    private void closeEditor() {
        if (editor != null) {
            try { wm.removeView(editor); } catch (Exception ignored) { }
            editor = null;
        }
    }

    // ───────────── التشغيل والإيقاف ─────────────

    private void toggle() {
        if (running) stopClicking();
        else startClicking();
    }

    private void startClicking() {
        if (pts.isEmpty()) {
            Toast.makeText(this, "زيد نقطة وحدة على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }
        closeEditor();
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        intervalMs = Math.max(MainActivity.MIN_INTERVAL, prefs.getLong(MainActivity.KEY_INTERVAL, 500));
        maxCycles = Math.max(0, prefs.getLong(MainActivity.KEY_CYCLES, 0));

        int maxStrokes = GestureDescription.getMaxStrokeCount();
        for (int s = 1; s <= totalSteps(); s++) {
            if (pointsOfStep(s).size() > maxStrokes) {
                Toast.makeText(this, "الخطوة " + s + " فيها كثر من " + maxStrokes
                        + " نقط فلحظة وحدة، الزايد ما غاديش يتنقر", Toast.LENGTH_LONG).show();
                break;
            }
        }

        running = true;
        stepIndex = 0;
        cyclesDone = 0;
        setPointsTouchable(false);
        setPanelCollapsed(true);
        playBtn.setText("■");
        ((GradientDrawable) playBtn.getBackground()).setColor(0xFFE5484D);
        post(this::runStep, 150);
    }

    void stopClicking() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(TOKEN);
        if (playBtn != null) {
            playBtn.setText("▶");
            ((GradientDrawable) playBtn.getBackground()).setColor(0xFF22A55B);
        }
        setPanelCollapsed(false);
        if (!pts.isEmpty()) setPointsTouchable(true);
    }

    private void post(Runnable r, long delayMs) {
        handler.postAtTime(r, TOKEN, SystemClock.uptimeMillis() + Math.max(0, delayMs));
    }

    private List<Pt> pointsOfStep(int s) {
        List<Pt> out = new ArrayList<>();
        for (Pt p : pts) if (p.step == s) out.add(p);
        return out;
    }

    /** لحظة وحدة فالوقت فيها نقرة ولا بزاف فنفس الوقت. */
    private static class Bucket {
        long t;
        final List<Pt> pts = new ArrayList<>();
    }

    /**
     * كيحضّر الخطوة: كل نقطة كتنقر count مرات بفاصل gap.
     * النقرات اللي كيطيحو فنفس الوقت (تقريباً) كيتجمعو فحركة وحدة متعددة اللمسات.
     */
    private List<Bucket> buildTimeline(List<Pt> group) {
        List<long[]> events = new ArrayList<>(); // {time, index in group}
        for (int g = 0; g < group.size(); g++) {
            Pt p = group.get(g);
            for (int k = 0; k < p.count; k++) events.add(new long[]{k * p.gap, g});
        }
        Collections.sort(events, (a, b) -> Long.compare(a[0], b[0]));
        List<Bucket> out = new ArrayList<>();
        Bucket cur = null;
        for (long[] e : events) {
            if (cur == null || e[0] - cur.t >= MERGE_MS) {
                cur = new Bucket();
                cur.t = e[0];
                out.add(cur);
            }
            Pt p = group.get((int) e[1]);
            if (!cur.pts.contains(p)) cur.pts.add(p);
        }
        return out;
    }

    private void runStep() {
        if (!running) return;
        int total = totalSteps();
        if (total == 0) { stopClicking(); return; }
        if (stepIndex >= total) stepIndex = 0;

        List<Bucket> timeline = buildTimeline(pointsOfStep(stepIndex + 1));
        runBucket(timeline, 0, 0);
    }

    private void runBucket(List<Bucket> timeline, int i, long prevT) {
        if (!running) return;
        if (i >= timeline.size()) {
            finishStep();
            return;
        }
        Bucket b = timeline.get(i);
        dispatchTaps(b.pts);
        if (i + 1 < timeline.size()) {
            long wait = timeline.get(i + 1).t - b.t;
            post(() -> runBucket(timeline, i + 1, b.t), wait);
        } else {
            post(this::finishStep, TAP_MS + 10);
        }
    }

    private void finishStep() {
        if (!running) return;
        stepIndex++;
        if (stepIndex >= totalSteps()) {
            stepIndex = 0;
            cyclesDone++;
            if (maxCycles > 0 && cyclesDone >= maxCycles) {
                stopClicking();
                return;
            }
        }
        post(this::runStep, Math.max(0, intervalMs - TAP_MS - 10));
    }

    /** نقرة فنفس اللحظة على كل النقط ديال المجموعة (multi-touch). */
    private void dispatchTaps(List<Pt> group) {
        if (group.isEmpty()) return;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        int max = Math.min(group.size(), GestureDescription.getMaxStrokeCount());
        int[] loc = new int[2];
        for (int i = 0; i < max; i++) {
            PointView v = group.get(i).view;
            v.getLocationOnScreen(loc);
            Path path = new Path();
            path.moveTo(Math.max(0, loc[0] + v.getWidth() / 2f),
                    Math.max(0, loc[1] + v.getHeight() / 2f));
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_MS));
        }
        dispatchGesture(builder.build(), null, null);
    }
}
