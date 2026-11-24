package ma.ab.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * خدمة إمكانية الوصول: اللوحة العائمة، النقط، الماكرو، والإشعار.
 *
 * الماكرو = خطوات بالترتيب. كل خطوة فيها نقطة وحدة ولا بزاف (نقر متعدد فنفس اللحظة).
 * كل نقطة عندها: عدد النقرات + الوقت بين نقرة ونقرة.
 * بين الخطوات: "الوقت بين الخطوات". من بعد آخر خطوة: "الوقت بين الدورات".
 */
public class ClickService extends AccessibilityService {

    static ClickService instance;

    private static final int BTN_DP = 42;
    private static final int POINT_DP = 44;
    private static final long TAP_MS = 20;
    private static final long MERGE_MS = 30;
    static final long MIN_GAP = 50;
    private static final Object TOKEN = new Object();

    private static final String CHANNEL = "panel";
    private static final int NOTIF_ID = 7;
    private static final String ACT_SLEEP = "ma.ab.autoclicker.SLEEP";
    private static final String ACT_PLAY = "ma.ab.autoclicker.PLAY";
    private static final String ACT_SHOW = "ma.ab.autoclicker.SHOW";

    private static final int[] STEP_COLORS = {
            0xFFE5484D, 0xFF3D8BFD, 0xFF22A55B, 0xFFF59E0B,
            0xFFA855F7, 0xFF06B6D4, 0xFFEC4899, 0xFF84CC16
    };

    private WindowManager wm;
    private Handler handler;

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private float panelFx = 0.05f, panelFy = 0.25f;
    private TextView playBtn;
    private TextView sleepBtn;
    private final List<View> extraButtons = new ArrayList<>();

    private static class Pt {
        PointView view;
        WindowManager.LayoutParams lp;
        float fx, fy;       // الوسط كنسبة من الشاشة
        boolean joinPrev;
        int count = 1;
        long gap = 100;
        int step;
    }

    private final List<Pt> pts = new ArrayList<>();

    private View editor;
    private View macrosSheet;

    private boolean running = false;
    private boolean sleeping = false;
    private int stepIndex = 0;
    private long cyclesDone = 0;
    private long intervalMs = 500;
    private long cycleIntervalMs = 500;
    private long maxCycles = 0;

    private final BroadcastReceiver actions = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACT_SLEEP.equals(a)) setSleeping(!sleeping);
            else if (ACT_PLAY.equals(a)) toggle();
            else if (ACT_SHOW.equals(a)) showPanel();
        }
    };

    // ───────────── دورة حياة الخدمة ─────────────

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACT_SLEEP);
            f.addAction(ACT_PLAY);
            f.addAction(ACT_SHOW);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(actions, f, 4 /* RECEIVER_NOT_EXPORTED */);
            else registerReceiver(actions, f);
        } catch (Exception ignored) { }
        updateNotification();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        hidePanel();
        try { unregisterReceiver(actions); } catch (Exception ignored) { }
        try { getSystemService(NotificationManager.class).cancel(NOTIF_ID); } catch (Exception ignored) { }
        instance = null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (handler != null) {
            handler.postDelayed(this::relayout, 350);
            handler.postDelayed(this::relayout, 900); // MIUI أحياناً كيتعطل فتحديث المقاس
        }
    }

    // ───────────── الإشعار (باش التطبيق يبقى حي + تحكم سريع) ─────────────

    private PendingIntent broadcast(String action, int code) {
        Intent i = new Intent(action).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    void updateNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "اللوحة العائمة",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
                b = new Notification.Builder(this, CHANNEL);
            } else {
                //noinspection deprecation
                b = new Notification.Builder(this);
            }
            Intent open = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            String text;
            if (panel == null) text = "واجد · اللوحة مسدودة";
            else if (sleeping) text = "😴 نايم: اللوحة والنقط ما كيتلمسوش";
            else if (running) text = "▶ الماكرو خدام";
            else text = "اللوحة ظاهرة";

            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("AB AutoClicker")
                    .setContentText(text)
                    .setContentIntent(openPi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false);

            Icon ic = Icon.createWithResource(this, R.drawable.ic_stat);
            if (panel == null) {
                b.addAction(new Notification.Action.Builder(ic, "إظهار اللوحة", broadcast(ACT_SHOW, 1)).build());
            } else {
                b.addAction(new Notification.Action.Builder(ic,
                        sleeping ? "☀ إيقاظ" : "☾ نوم", broadcast(ACT_SLEEP, 2)).build());
                b.addAction(new Notification.Action.Builder(ic,
                        running ? "■ إيقاف" : "▶ تشغيل", broadcast(ACT_PLAY, 3)).build());
            }
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) { }
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

    private int w(View v, WindowManager.LayoutParams lp) {
        if (v.getWidth() > 0) return v.getWidth();
        return lp.width > 0 ? lp.width : dp(BTN_DP + 10);
    }

    private int h(View v, WindowManager.LayoutParams lp) {
        if (v.getHeight() > 0) return v.getHeight();
        return lp.height > 0 ? lp.height : dp(BTN_DP + 10);
    }

    private void clamp(View v, WindowManager.LayoutParams lp) {
        Point s = screenSize();
        int w = w(v, lp), h = h(v, lp);
        int mx = (v instanceof PointView) ? w / 2 : 0;
        int my = (v instanceof PointView) ? h / 2 : 0;
        lp.x = Math.max(-mx, Math.min(lp.x, s.x - w + mx));
        lp.y = Math.max(-my, Math.min(lp.y, s.y - h + my));
    }

    private void update(View v, WindowManager.LayoutParams lp) {
        try { wm.updateViewLayout(v, lp); } catch (Exception ignored) { }
    }

    /** كيحط النافذة حسب النسبة (الوسط ديالها) من الشاشة الحالية. */
    private void placeByFraction(View v, WindowManager.LayoutParams lp, float fx, float fy) {
        Point s = screenSize();
        lp.x = Math.round(fx * s.x - w(v, lp) / 2f);
        lp.y = Math.round(fy * s.y - h(v, lp) / 2f);
        clamp(v, lp);
    }

    private float[] fractionOf(View v, WindowManager.LayoutParams lp) {
        Point s = screenSize();
        return new float[]{(lp.x + w(v, lp) / 2f) / s.x, (lp.y + h(v, lp) / 2f) / s.y};
    }

    /** ملي كتدور الشاشة: كل حاجة كترجع لنفس البلاصة النسبية. */
    private void relayout() {
        if (panel != null) {
            placeByFraction(panel, panelLp, panelFx, panelFy);
            update(panel, panelLp);
        }
        for (Pt p : pts) {
            placeByFraction(p.view, p.lp, p.fx, p.fy);
            update(p.view, p.lp);
        }
    }

    /** الجر بالصبع؛ لمسة قصيرة بلا حركة = click. onDrop كيتعيط ملي يتحرر الصبع من بعد الجر. */
    @SuppressLint("ClickableViewAccessibility")
    private void makeDraggable(View handleView, View windowView, WindowManager.LayoutParams lp,
                               Runnable onDrop) {
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
                        else if (onDrop != null) onDrop.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (moved && onDrop != null) onDrop.run();
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

    boolean isPanelShown() { return panel != null; }
    boolean isSleeping() { return sleeping; }

    void showPanel() {
        if (panel != null) return;

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC16181D);
        bg.setCornerRadius(dp(25));
        panel.setBackground(bg);

        panelLp = overlayParams();

        playBtn = circleButton("▶", 0xFF22A55B, BTN_DP, 17);
        playBtn.setOnClickListener(v -> toggle());
        sleepBtn = circleButton("☾", 0xFF334155, BTN_DP, 19);
        sleepBtn.setOnClickListener(v -> setSleeping(!sleeping));
        TextView addStep = circleButton("+", 0xFF3D8BFD, BTN_DP, 21);
        addStep.setOnClickListener(v -> addPoint(false));
        TextView addMulti = circleButton("++", 0xFFA855F7, BTN_DP, 15);
        addMulti.setOnClickListener(v -> addPoint(true));
        TextView remove = circleButton("−", 0xFFF59E0B, BTN_DP, 21);
        remove.setOnClickListener(v -> { if (!pts.isEmpty()) removePoint(pts.size() - 1); });
        TextView macros = circleButton("💾", 0xFF0E7490, BTN_DP, 16);
        macros.setOnClickListener(v -> openMacros());
        TextView close = circleButton("✕", 0xFF5F6368, BTN_DP, 16);
        close.setOnClickListener(v -> hidePanel());

        extraButtons.clear();
        Collections.addAll(extraButtons, addStep, addMulti, remove, macros, close);

        panel.addView(playBtn);
        panel.addView(sleepBtn);
        for (View b : extraButtons) panel.addView(b);

        Runnable onDrop = () -> {
            float[] f = fractionOf(panel, panelLp);
            panelFx = f[0];
            panelFy = f[1];
        };
        makeDraggable(playBtn, panel, panelLp, onDrop);
        makeDraggable(sleepBtn, panel, panelLp, onDrop);
        for (View b : extraButtons) makeDraggable(b, panel, panelLp, onDrop);

        Point s = screenSize();
        panelLp.x = Math.round(panelFx * s.x - dp(BTN_DP + 10) / 2f);
        panelLp.y = Math.round(panelFy * s.y - dp(BTN_DP + 10) / 2f);
        clamp(panel, panelLp);
        wm.addView(panel, panelLp);
        if (pts.isEmpty()) addPoint(false);
        updateNotification();
    }

    void hidePanel() {
        stopClicking();
        closeEditor();
        closeMacros();
        sleeping = false;
        clearPoints();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
        updateNotification();
    }

    private void clearPoints() {
        for (Pt p : pts) {
            try { wm.removeView(p.view); } catch (Exception ignored) { }
        }
        pts.clear();
    }

    /**
     * شنو كيبان فاللوحة:
     *  - نايم: غير زر ☀ (باش توقظ)
     *  - خدام: ▶/■ و ☾
     *  - عادي: كلشي
     */
    private void refreshPanelButtons() {
        if (panel == null) return;
        playBtn.setVisibility(sleeping ? View.GONE : View.VISIBLE);
        for (View b : extraButtons) b.setVisibility(sleeping || running ? View.GONE : View.VISIBLE);
        sleepBtn.setText(sleeping ? "☀" : "☾");
        ((GradientDrawable) sleepBtn.getBackground()).setColor(sleeping ? 0xFFF59E0B : 0xFF334155);
        update(panel, panelLp);
    }

    /** النوم: النقط ما كيتلمسوش، واللوحة كيبقى فيها غير زر ☀ باش توقظ. */
    void setSleeping(boolean s) {
        if (panel == null) s = false;
        if (s) { closeEditor(); closeMacros(); }
        sleeping = s;
        refreshPanelButtons();
        applyTouchability();
        updateNotification();
    }

    private void setTouchable(View v, WindowManager.LayoutParams lp, boolean touchable) {
        if (touchable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        update(v, lp);
    }

    private void applyTouchability() {
        if (panel != null) {
            setTouchable(panel, panelLp, true);
            panel.setAlpha(sleeping ? 0.75f : 1f);
        }
        boolean pointsTouchable = !sleeping && !running;
        for (Pt p : pts) {
            setTouchable(p.view, p.lp, pointsTouchable);
            p.view.setAlpha(pointsTouchable ? 1f : 0.4f);
        }
    }

    // ───────────── النقط ─────────────

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

    private Pt createPoint(float fx, float fy) {
        Pt p = new Pt();
        p.view = new PointView(this);
        int size = dp(POINT_DP);
        p.lp = overlayParams();
        p.lp.width = size;
        p.lp.height = size;
        p.fx = fx;
        p.fy = fy;
        placeByFraction(p.view, p.lp, fx, fy);
        p.view.setOnClickListener(v -> { if (!running && !sleeping) openEditor(pts.indexOf(p)); });
        makeDraggable(p.view, p.view, p.lp, () -> {
            float[] f = fractionOf(p.view, p.lp);
            p.fx = f[0];
            p.fy = f[1];
        });
        return p;
    }

    private void addPoint(boolean multi) {
        if (running) return;
        Point s = screenSize();
        boolean join = multi && !pts.isEmpty();
        float fx, fy;
        Pt prev = pts.isEmpty() ? null : pts.get(pts.size() - 1);
        if (join) {
            fx = prev.fx + dp(48) / (float) s.x;
            fy = prev.fy;
        } else {
            int n = totalSteps();
            fx = 0.5f + ((n % 4) * dp(20) - dp(30)) / (float) s.x;
            fy = 0.5f + ((n / 4 % 6) * dp(48) - dp(100)) / (float) s.y;
        }
        Pt p = createPoint(Math.min(0.97f, fx), Math.min(0.97f, fy));
        p.joinPrev = join;
        if (join) { p.count = prev.count; p.gap = prev.gap; }
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

    // ───────────── حفظ وتحميل الماكرو ─────────────

    private JSONObject currentMacro(String name) throws Exception {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        JSONObject m = new JSONObject();
        m.put("name", name);
        m.put("interval", prefs.getLong(MainActivity.KEY_INTERVAL, 500));
        m.put("cycleInterval", prefs.getLong(MainActivity.KEY_CYCLE_INTERVAL, 500));
        m.put("cycles", prefs.getLong(MainActivity.KEY_CYCLES, 0));
        JSONArray arr = new JSONArray();
        for (Pt p : pts) {
            JSONObject o = new JSONObject();
            o.put("fx", (double) p.fx);
            o.put("fy", (double) p.fy);
            o.put("count", p.count);
            o.put("gap", p.gap);
            o.put("join", p.joinPrev);
            arr.put(o);
        }
        m.put("points", arr);
        return m;
    }

    private void saveCurrentMacro() {
        if (pts.isEmpty()) {
            Toast.makeText(this, "ما كاين حتى نقطة باش تتحفظ", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String name = MacroStore.nextName(this);
            MacroStore.add(this, currentMacro(name));
            Toast.makeText(this, "✓ تحفظ: " + name + " (تقدر تبدل السمية من التطبيق)",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "ما تحفظش", Toast.LENGTH_SHORT).show();
        }
    }

    /** كيحمّل ماكرو محفوظ: النقط + الأوقات. */
    void loadMacro(JSONObject m) {
        if (m == null) return;
        showPanel();
        stopClicking();
        closeEditor();
        closeMacros();
        clearPoints();
        MacroStore.applySettings(this, m);
        JSONArray arr = m.optJSONArray("points");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Pt p = createPoint((float) o.optDouble("fx", 0.5), (float) o.optDouble("fy", 0.5));
                p.count = Math.max(1, o.optInt("count", 1));
                p.gap = Math.max(MIN_GAP, o.optLong("gap", 100));
                p.joinPrev = o.optBoolean("join", false) && !pts.isEmpty();
                pts.add(p);
            }
        }
        renumber();
        for (Pt p : pts) {
            try { wm.addView(p.view, p.lp); } catch (Exception ignored) { }
        }
        applyTouchability();
        Toast.makeText(this, "✓ " + m.optString("name"), Toast.LENGTH_SHORT).show();
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

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0xF21A1D23);
        bg.setStroke(dp(1), 0xFF2C313A);
        card.setBackground(bg);
        return card;
    }

    private void showCentered(View v) {
        WindowManager.LayoutParams lp = overlayParams();
        lp.width = Math.min(dp(300), screenSize().x - dp(32));
        lp.gravity = Gravity.CENTER;
        wm.addView(v, lp);
    }

    private void openMacros() {
        closeEditor();
        closeMacros();
        LinearLayout card = card();
        TextView title = smallText("💾  الماكروات", 16, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView save = pill("+ حفظ الماكرو الحالي", 0xFF0E7490);
        save.setOnClickListener(v -> { saveCurrentMacro(); openMacros(); });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        card.addView(save, sp);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        JSONArray all = MacroStore.all(this);
        if (all.length() == 0) {
            TextView empty = smallText("ما كاين حتى ماكرو محفوظ", 13, 0xFF9AA0A6);
            empty.setPadding(0, dp(12), 0, 0);
            list.addView(empty);
        }
        for (int i = 0; i < all.length(); i++) {
            final int idx = i;
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            JSONArray ptsArr = m.optJSONArray("points");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            TextView name = pill(m.optString("name") + "  ·  " +
                    (ptsArr == null ? 0 : ptsArr.length()) + " نقط", 0xFF2C313A);
            name.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            name.setOnClickListener(v -> loadMacro(MacroStore.get(this, idx)));
            row.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView del = circleButton("🗑", 0xFF3A1F22, 38, 13);
            del.setOnClickListener(v -> { MacroStore.delete(this, idx); openMacros(); });
            row.addView(del);
            list.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        int maxH = Math.min(dp(260), screenSize().y / 2);
        card.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                all.length() > 4 ? maxH : LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView close = pill("سد", 0xFF2C313A);
        close.setOnClickListener(v -> closeMacros());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(14);
        card.addView(close, cp);

        macrosSheet = card;
        showCentered(card);
    }

    private void closeMacros() {
        if (macrosSheet != null) {
            try { wm.removeView(macrosSheet); } catch (Exception ignored) { }
            macrosSheet = null;
        }
    }

    // ───────────── نافذة الإعدادات ديال النقطة ─────────────

    private long nextGap(long v, boolean up) {
        long step = v < 100 ? 10 : (v < 1000 ? 50 : 250);
        if (!up && v <= 100) step = 10;
        long r = up ? v + step : v - step;
        return Math.max(MIN_GAP, Math.min(60000, r));
    }

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

    private void openEditor(int index) {
        closeEditor();
        closeMacros();
        if (index < 0 || index >= pts.size()) return;
        final Pt p = pts.get(index);

        LinearLayout card = card();
        TextView title = smallText("", 16, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView countVal = new TextView(this);
        TextView gapVal = new TextView(this);
        Runnable refresh = () -> {
            renumber();
            title.setText("⚙  النقطة " + (pts.indexOf(p) + 1) + "  ·  الخطوة " + p.step);
            countVal.setText(String.valueOf(p.count));
            gapVal.setText(p.gap + " ms");
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

        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView del = pill("حذف", 0xFFB42318);
        del.setOnClickListener(v -> { int i = pts.indexOf(p); closeEditor(); removePoint(i); });
        TextView ok = pill("تم ✓", 0xFF22A55B);
        ok.setOnClickListener(v -> closeEditor());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ap.setMargins(dp(4), 0, dp(4), 0);
        actionsRow.addView(del, ap);
        LinearLayout.LayoutParams ap2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2f);
        ap2.setMargins(dp(4), 0, dp(4), 0);
        actionsRow.addView(ok, ap2);
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        acp.topMargin = dp(16);
        card.addView(actionsRow, acp);

        refresh.run();
        editor = card;
        showCentered(card);
    }

    private void closeEditor() {
        if (editor != null) {
            try { wm.removeView(editor); } catch (Exception ignored) { }
            editor = null;
        }
    }

    // ───────────── التشغيل والإيقاف ─────────────

    void toggle() {
        if (panel == null) return;
        if (running) stopClicking();
        else startClicking();
    }

    private void startClicking() {
        if (pts.isEmpty()) {
            Toast.makeText(this, "زيد نقطة وحدة على الأقل", Toast.LENGTH_SHORT).show();
            return;
        }
        closeEditor();
        closeMacros();
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        intervalMs = Math.max(MainActivity.MIN_INTERVAL, prefs.getLong(MainActivity.KEY_INTERVAL, 500));
        cycleIntervalMs = Math.max(MainActivity.MIN_INTERVAL,
                prefs.getLong(MainActivity.KEY_CYCLE_INTERVAL, intervalMs));
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
        applyTouchability();
        refreshPanelButtons();
        playBtn.setText("■");
        ((GradientDrawable) playBtn.getBackground()).setColor(0xFFE5484D);
        updateNotification();
        post(this::runStep, 150);
    }

    void stopClicking() {
        boolean was = running;
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(TOKEN);
        if (playBtn != null) {
            playBtn.setText("▶");
            ((GradientDrawable) playBtn.getBackground()).setColor(0xFF22A55B);
        }
        refreshPanelButtons();
        applyTouchability();
        if (was) updateNotification();
    }

    /** كل مهمة مجدولة محمية: إلا وقع شي خطأ كيوقف الماكرو بلا ما يطيح التطبيق. */
    private void post(Runnable r, long delayMs) {
        Runnable safe = () -> {
            try { r.run(); } catch (Exception e) { stopClicking(); }
        };
        handler.postAtTime(safe, TOKEN, SystemClock.uptimeMillis() + Math.max(0, delayMs));
    }

    private List<Pt> pointsOfStep(int s) {
        List<Pt> out = new ArrayList<>();
        for (Pt p : pts) if (p.step == s) out.add(p);
        return out;
    }

    private static class Bucket {
        long t;
        final List<Pt> pts = new ArrayList<>();
    }

    private List<Bucket> buildTimeline(List<Pt> group) {
        List<long[]> events = new ArrayList<>();
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
        runBucket(buildTimeline(pointsOfStep(stepIndex + 1)), 0);
    }

    private void runBucket(List<Bucket> timeline, int i) {
        if (!running) return;
        if (i >= timeline.size()) { finishStep(); return; }
        Bucket b = timeline.get(i);
        dispatchTaps(b.pts);
        if (i + 1 < timeline.size()) {
            post(() -> runBucket(timeline, i + 1), timeline.get(i + 1).t - b.t);
        } else {
            post(this::finishStep, TAP_MS + 10);
        }
    }

    private void finishStep() {
        if (!running) return;
        stepIndex++;
        long wait = intervalMs;
        if (stepIndex >= totalSteps()) {
            stepIndex = 0;
            cyclesDone++;
            if (maxCycles > 0 && cyclesDone >= maxCycles) {
                stopClicking();
                return;
            }
            wait = cycleIntervalMs; // الوقت بين دورة ودورة
        }
        post(this::runStep, Math.max(0, wait - TAP_MS - 10));
    }

    private void dispatchTaps(List<Pt> group) {
        if (group.isEmpty()) return;
        try {
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
        } catch (Exception ignored) { }
    }
}
