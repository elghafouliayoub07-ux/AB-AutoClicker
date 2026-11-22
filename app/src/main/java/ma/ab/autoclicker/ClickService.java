package ma.ab.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
 */
public class ClickService extends AccessibilityService {

    static ClickService instance;

    private WindowManager wm;
    private Handler handler;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView playBtn;

    private final List<TextView> points = new ArrayList<>();
    private final List<WindowManager.LayoutParams> pointLps = new ArrayList<>();
    /** رقم الخطوة ديال كل نقطة (النقط اللي عندهم نفس الرقم كيتنقرو مع بعض). */
    private final List<Integer> pointSteps = new ArrayList<>();

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

    // ───────────── أدوات مساعدة ─────────────

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    /** كيخلي أي نافذة عائمة تتجر بالصبع. */
    @SuppressLint("ClickableViewAccessibility")
    private void makeDraggable(View handleView, View windowView, WindowManager.LayoutParams lp) {
        handleView.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY;
            float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + Math.round(e.getRawX() - touchX);
                        lp.y = startY + Math.round(e.getRawY() - touchY);
                        try { wm.updateViewLayout(windowView, lp); } catch (Exception ignored) { }
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
        b.setTextSize(20);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
        p.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(p);
        if (click != null) b.setOnClickListener(click);
        return b;
    }


    // ───────────── اللوحة العائمة ─────────────

    void showPanel() {
        if (panel != null) return;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0202124);
        bg.setCornerRadius(dp(16));
        panel.setBackground(bg);

        TextView drag = panelButton("✥", 0xFF5F6368, null);
        playBtn = panelButton("▶", 0xFF1E8E3E, v -> toggle());
        TextView addStep = panelButton("+", 0xFF1A73E8, v -> addPoint(false));
        TextView addSame = panelButton("⧉", 0xFF8430CE, v -> addPoint(true));
        TextView remove = panelButton("−", 0xFFE37400, v -> removeLastPoint());
        TextView close = panelButton("✕", 0xFFD93025, v -> hidePanel());

        panel.addView(drag);
        panel.addView(playBtn);
        panel.addView(addStep);
        panel.addView(addSame);
        panel.addView(remove);
        panel.addView(close);

        panelLp = overlayParams();
        panelLp.x = dp(8);
        panelLp.y = dp(140);
        makeDraggable(drag, panel, panelLp);
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
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
    }

    // ───────────── النقط والخطوات ─────────────

    /** لون حسب رقم الخطوة، باش النقط ديال نفس الخطوة يبانو بنفس اللون. */
    private static final int[] STEP_COLORS = {
            0xAAD93025, 0xAA1A73E8, 0xAA1E8E3E, 0xAAE37400,
            0xAA8430CE, 0xAA00838F, 0xAAC2185B, 0xAA6D4C41
    };

    private int lastStep() {
        return pointSteps.isEmpty() ? 0 : pointSteps.get(pointSteps.size() - 1);
    }

    /**
     * @param sameStep true = النقطة كتزاد لنفس الخطوة ديال آخر نقطة (كيتنقرو مع بعض)،
     *                 false = خطوة جديدة.
     */
    private void addPoint(boolean sameStep) {
        if (running) return;
        int step = (sameStep && !points.isEmpty()) ? lastStep() : lastStep() + 1;

        TextView p = new TextView(this);
        p.setText(String.valueOf(step));
        p.setTextColor(Color.WHITE);
        p.setTextSize(16);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(STEP_COLORS[(step - 1) % STEP_COLORS.length]);
        circle.setStroke(dp(3), Color.WHITE);
        p.setBackground(circle);

        WindowManager.LayoutParams lp = overlayParams();
        int size = dp(52);
        lp.width = size;
        lp.height = size;
        if (sameStep && !points.isEmpty()) {
            // حدا آخر نقطة باش يبان أنهم مجموعة وحدة
            WindowManager.LayoutParams prev = pointLps.get(pointLps.size() - 1);
            lp.x = prev.x + dp(60);
            lp.y = prev.y;
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int n = step - 1;
            lp.x = dm.widthPixels / 2 - size / 2 + (n % 4) * dp(24) - dp(36);
            lp.y = dm.heightPixels / 2 - size / 2 + (n / 4 % 6) * dp(64) - dp(120);
        }

        makeDraggable(p, p, lp);
        wm.addView(p, lp);
        points.add(p);
        pointLps.add(lp);
        pointSteps.add(step);
    }

    private void removeLastPoint() {
        if (running || points.isEmpty()) return;
        int last = points.size() - 1;
        try { wm.removeView(points.get(last)); } catch (Exception ignored) { }
        points.remove(last);
        pointLps.remove(last);
        pointSteps.remove(last);
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
            points.get(i).setAlpha(touchable ? 1f : 0.45f);
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
            if (pointsOfStep(s).size() > maxStrokes) {
                Toast.makeText(this, "التليفون كيقبل غير " + maxStrokes
                        + " نقرات فلحظة وحدة (الخطوة " + s + ")", Toast.LENGTH_LONG).show();
                break;
            }
        }

        running = true;
        stepIndex = 0;
        cyclesDone = 0;
        setPointsTouchable(false);
        playBtn.setText("■");
        ((GradientDrawable) playBtn.getBackground()).setColor(0xFFD93025);
        // تأخير صغير باش النوافذ يتحدّثو قبل أول نقرة
        handler.postDelayed(step, 150);
    }

    void stopClicking() {
        running = false;
        if (handler != null) handler.removeCallbacks(step);
        if (playBtn != null) {
            playBtn.setText("▶");
            ((GradientDrawable) playBtn.getBackground()).setColor(0xFF1E8E3E);
        }
        if (!points.isEmpty()) setPointsTouchable(true);
    }

    private List<TextView> pointsOfStep(int s) {
        List<TextView> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (pointSteps.get(i) == s) out.add(points.get(i));
        }
        return out;
    }

    /** كل مرة كينفّذ خطوة وحدة: النقط ديالها كاملين كيتنقرو فنفس اللحظة. */
    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            int totalSteps = lastStep();
            if (totalSteps == 0) { stopClicking(); return; }
            if (stepIndex >= totalSteps) stepIndex = 0;

            tapTogether(pointsOfStep(stepIndex + 1));

            stepIndex++;
            if (stepIndex >= totalSteps) {
                stepIndex = 0;
                cyclesDone++;
                if (maxCycles > 0 && cyclesDone >= maxCycles) {
                    stopClicking();
                    return;
                }
            }
            handler.postDelayed(this, intervalMs);
        }
    };

    /** نقرة وحدة ولا بزاف فنفس اللحظة (multi-touch). */
    private void tapTogether(List<TextView> group) {
        if (group.isEmpty()) return;
        GestureDescription.Builder builder = new GestureDescription.Builder();
        int max = Math.min(group.size(), GestureDescription.getMaxStrokeCount());
        int[] loc = new int[2];
        for (int i = 0; i < max; i++) {
            TextView p = group.get(i);
            p.getLocationOnScreen(loc);
            Path path = new Path();
            path.moveTo(Math.max(0, loc[0] + p.getWidth() / 2f),
                    Math.max(0, loc[1] + p.getHeight() / 2f));
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 10));
        }
        dispatchGesture(builder.build(), null, null);
    }
}
