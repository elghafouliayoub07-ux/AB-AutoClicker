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
import java.util.List;

/**
 * خدمة إمكانية الوصول: كتعرض اللوحة العائمة والنقط، وكتنفّذ الماكرو.
 * الماكرو = خطوات بالترتيب؛ كل خطوة فيها نقطة وحدة ولا بزاف كيتنقرو فنفس اللحظة.
 * كل نقطة تقدر تكون نقرة وحدة (×1) ولا نقرة مزدوجة (×2).
 */
public class ClickService extends AccessibilityService {

    static ClickService instance;

    // مقاسات صغيرة باش اللوحة ما تعمرش الشاشة
    private static final int BTN_DP = 36;
    private static final int POINT_DP = 42;

    // توقيت النقرة المزدوجة
    private static final long TAP_MS = 30;
    private static final long DOUBLE_GAP_MS = 90;

    private WindowManager wm;
    private Handler handler;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView playBtn;
    private final List<View> extraButtons = new ArrayList<>();

    private final List<TextView> points = new ArrayList<>();
    private final List<WindowManager.LayoutParams> pointLps = new ArrayList<>();
    private final List<Integer> pointSteps = new ArrayList<>();
    private final List<Integer> pointTaps = new ArrayList<>(); // 1 ولا 2

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

    /** ملي كتدور الشاشة: نرجعو اللوحة والنقط لداخل الشاشة. */
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
        int w = v.getWidth() > 0 ? v.getWidth() : dp(BTN_DP);
        int h = v.getHeight() > 0 ? v.getHeight() : dp(BTN_DP);
        lp.x = Math.max(0, Math.min(lp.x, s.x - w));
        lp.y = Math.max(0, Math.min(lp.y, s.y - h));
    }

    private void keepAllOnScreen() {
        if (panel != null) {
            clamp(panel, panelLp);
            try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) { }
        }
        for (int i = 0; i < points.size(); i++) {
            clamp(points.get(i), pointLps.get(i));
            try { wm.updateViewLayout(points.get(i), pointLps.get(i)); } catch (Exception ignored) { }
        }
    }

    /**
     * كيخلي النافذة تتجر بالصبع. إلا كانت لمسة قصيرة بلا حركة، كتحسب click.
     */
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
                        startX = lp.x;
                        startY = lp.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
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
                            try { wm.updateViewLayout(windowView, lp); } catch (Exception ignored) { }
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

    private TextView panelButton(String label, int bgColor, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(bgColor);
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(BTN_DP), dp(BTN_DP));
        p.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(p);
        b.setOnClickListener(click);
        return b;
    }

    // ───────────── اللوحة العائمة ─────────────

    void showPanel() {
        if (panel != null) return;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC1F1F23);
        bg.setCornerRadius(dp(22));
        panel.setBackground(bg);

        panelLp = overlayParams();
        panelLp.x = dp(6);
        panelLp.y = dp(160);

        playBtn = panelButton("▶", 0xFF1E8E3E, v -> toggle());
        TextView addStep = panelButton("+", 0xFF1A73E8, v -> addPoint(false));
        TextView addSame = panelButton("⧉", 0xFF8430CE, v -> addPoint(true));
        TextView remove = panelButton("−", 0xFFE37400, v -> removeLastPoint());
        TextView close = panelButton("✕", 0xFF5F6368, v -> hidePanel());

        extraButtons.clear();
        extraButtons.add(addStep);
        extraButtons.add(addSame);
        extraButtons.add(remove);
        extraButtons.add(close);

        panel.addView(playBtn);
        for (View b : extraButtons) panel.addView(b);

        // اللوحة كتتجر من أي زر فيها
        makeDraggable(playBtn, panel, panelLp);
        for (View b : extraButtons) makeDraggable(b, panel, panelLp);

        wm.addView(panel, panelLp);

        if (points.isEmpty()) addPoint(false);
    }

    void hidePanel() {
        stopClicking();
        for (TextView p : points) {
            try { wm.removeView(p); } catch (Exception ignored) { }
        }
        points.clear();
        pointLps.clear();
        pointSteps.clear();
        pointTaps.clear();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
    }

    /** ملي كيخدم: كيبقى غير زر التشغيل/الإيقاف. */
    private void setPanelCollapsed(boolean collapsed) {
        for (View b : extraButtons) b.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (panel != null) {
            try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) { }
        }
    }

    // ───────────── النقط والخطوات ─────────────

    private static final int[] STEP_COLORS = {
            0xCCD93025, 0xCC1A73E8, 0xCC1E8E3E, 0xCCE37400,
            0xCC8430CE, 0xCC00838F, 0xCCC2185B, 0xCC6D4C41
    };

    private int lastStep() {
        return pointSteps.isEmpty() ? 0 : pointSteps.get(pointSteps.size() - 1);
    }

    private void refreshPoint(int i) {
        TextView p = points.get(i);
        int step = pointSteps.get(i);
        boolean dbl = pointTaps.get(i) == 2;
        p.setText(dbl ? step + "\n×2" : String.valueOf(step));
        p.setTextSize(dbl ? 11 : 14);
        p.setLineSpacing(0, 0.85f);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(STEP_COLORS[(step - 1) % STEP_COLORS.length]);
        circle.setStroke(dp(dbl ? 4 : 2), dbl ? 0xFFFFD600 : Color.WHITE);
        p.setBackground(circle);
    }

    private void addPoint(boolean sameStep) {
        if (running) return;
        boolean joinLast = sameStep && !points.isEmpty();
        int step = joinLast ? lastStep() : lastStep() + 1;

        TextView p = new TextView(this);
        p.setTextColor(Color.WHITE);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setGravity(Gravity.CENTER);

        WindowManager.LayoutParams lp = overlayParams();
        int size = dp(POINT_DP);
        lp.width = size;
        lp.height = size;
        Point s = screenSize();
        if (joinLast) {
            WindowManager.LayoutParams prev = pointLps.get(pointLps.size() - 1);
            lp.x = prev.x + dp(50);
            lp.y = prev.y;
        } else {
            int n = step - 1;
            lp.x = s.x / 2 - size / 2 + (n % 4) * dp(22) - dp(33);
            lp.y = s.y / 2 - size / 2 + (n / 4 % 6) * dp(52) - dp(100);
        }
        lp.x = Math.max(0, Math.min(lp.x, s.x - size));
        lp.y = Math.max(0, Math.min(lp.y, s.y - size));

        points.add(p);
        pointLps.add(lp);
        pointSteps.add(step);
        pointTaps.add(1);
        refreshPoint(points.size() - 1);

        // لمسة قصيرة على النقطة = تبديل بين نقرة وحدة ونقرة مزدوجة
        p.setOnClickListener(v -> {
            if (running) return;
            int i = points.indexOf(p);
            if (i < 0) return;
            pointTaps.set(i, pointTaps.get(i) == 2 ? 1 : 2);
            refreshPoint(i);
        });
        makeDraggable(p, p, lp);
        wm.addView(p, lp);
    }

    private void removeLastPoint() {
        if (running || points.isEmpty()) return;
        int last = points.size() - 1;
        try { wm.removeView(points.get(last)); } catch (Exception ignored) { }
        points.remove(last);
        pointLps.remove(last);
        pointSteps.remove(last);
        pointTaps.remove(last);
    }

    /** ملي كيخدم النقر، النقط كيوليو شفافين للّمس باش النقرة توصل للتطبيق اللي تحتهم. */
    private void setPointsTouchable(boolean touchable) {
        for (int i = 0; i < points.size(); i++) {
            WindowManager.LayoutParams lp = pointLps.get(i);
            if (touchable) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            points.get(i).setAlpha(touchable ? 1f : 0.35f);
            try { wm.updateViewLayout(points.get(i), lp); } catch (Exception ignored) { }
        }
    }

    // ───────────── التشغيل والإيقاف ─────────────

    private void toggle() {
        if (running) stopClicking();
        else startClicking();
    }

    private void startClicking() {
        if (points.isEmpty()) {
            Toast.makeText(this, "زيد نقطة وحدة على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        intervalMs = Math.max(MainActivity.MIN_INTERVAL, prefs.getLong(MainActivity.KEY_INTERVAL, 500));
        maxCycles = Math.max(0, prefs.getLong(MainActivity.KEY_CYCLES, 0));

        int maxStrokes = GestureDescription.getMaxStrokeCount();
        for (int s = 1; s <= lastStep(); s++) {
            if (strokesOfStep(s) > maxStrokes) {
                Toast.makeText(this, "الخطوة " + s + " فيها بزاف ديال النقرات فلحظة وحدة (الحد: "
                        + maxStrokes + ")", Toast.LENGTH_LONG).show();
                break;
            }
        }

        running = true;
        stepIndex = 0;
        cyclesDone = 0;
        setPointsTouchable(false);
        setPanelCollapsed(true);
        playBtn.setText("■");
        ((GradientDrawable) playBtn.getBackground()).setColor(0xFFD93025);
        handler.postDelayed(step, 150);
    }

    void stopClicking() {
        running = false;
        if (handler != null) handler.removeCallbacks(step);
        if (playBtn != null) {
            playBtn.setText("▶");
            ((GradientDrawable) playBtn.getBackground()).setColor(0xFF1E8E3E);
        }
        setPanelCollapsed(false);
        if (!points.isEmpty()) setPointsTouchable(true);
    }

    private List<Integer> indexesOfStep(int s) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (pointSteps.get(i) == s) out.add(i);
        }
        return out;
    }

    private int strokesOfStep(int s) {
        int n = 0;
        for (int i : indexesOfStep(s)) n += pointTaps.get(i);
        return n;
    }

    /** كل مرة كينفّذ خطوة وحدة: النقط ديالها كاملين كيتنقرو فنفس اللحظة. */
    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            int totalSteps = lastStep();
            if (totalSteps == 0) { stopClicking(); return; }
            if (stepIndex >= totalSteps) stepIndex = 0;

            long gestureMs = tapTogether(indexesOfStep(stepIndex + 1));

            stepIndex++;
            if (stepIndex >= totalSteps) {
                stepIndex = 0;
                cyclesDone++;
                if (maxCycles > 0 && cyclesDone >= maxCycles) {
                    handler.postDelayed(ClickService.this::stopClicking, gestureMs + 20);
                    return;
                }
            }
            // ما نبداوش النقرة الجاية قبل ما تسالي هادي (باش ما تتقطعش النقرة المزدوجة)
            handler.postDelayed(this, Math.max(intervalMs, gestureMs + 20));
        }
    };

    /**
     * كينقر على مجموعة ديال النقط فنفس اللحظة (multi-touch)، مع دعم النقرة المزدوجة.
     * @return المدة ديال الحركة بالميلي ثانية
     */
    private long tapTogether(List<Integer> idx) {
        if (idx.isEmpty()) return 0;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        int maxStrokes = GestureDescription.getMaxStrokeCount();
        int used = 0;
        long duration = TAP_MS;
        int[] loc = new int[2];
        for (int i : idx) {
            TextView p = points.get(i);
            p.getLocationOnScreen(loc);
            float x = Math.max(0, loc[0] + p.getWidth() / 2f);
            float y = Math.max(0, loc[1] + p.getHeight() / 2f);
            int taps = pointTaps.get(i);
            for (int t = 0; t < taps && used < maxStrokes; t++, used++) {
                Path path = new Path();
                path.moveTo(x, y);
                long start = t * (TAP_MS + DOUBLE_GAP_MS);
                builder.addStroke(new GestureDescription.StrokeDescription(path, start, TAP_MS));
                duration = Math.max(duration, start + TAP_MS);
            }
        }
        dispatchGesture(builder.build(), null, null);
        return duration;
    }
}
