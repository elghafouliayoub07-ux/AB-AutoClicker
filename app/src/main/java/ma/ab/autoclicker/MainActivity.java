package ma.ab.autoclicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** الشاشة الرئيسية: الحالة، الإعدادات، الماكروات المحفوظة، وطريقة الاستعمال. */
public class MainActivity extends Activity {

    static final String PREFS = "cfg";
    static final String KEY_INTERVAL = "interval";
    static final String KEY_CYCLE_INTERVAL = "cycleInterval";
    static final String KEY_CYCLES = "cycles";
    static final long MIN_INTERVAL = 30;

    private static final int BG = 0xFF0F1115;
    private static final int CARD = 0xFF1A1D23;
    private static final int FIELD = 0xFF252932;
    private static final int ACCENT = 0xFF3D8BFD;
    private static final int GREEN = 0xFF22A55B;
    private static final int RED = 0xFFE5484D;
    private static final int MUTED = 0xFF9AA0A6;

    private TextView statusText;
    private TextView statusDot;
    private TextView enableBtn;
    private TextView wakeBtn;
    private EditText intervalInput;
    private EditText cycleIntervalInput;
    private EditText cyclesInput;
    private LinearLayout macrosList;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** واش الخدمة مفعّلة فإعدادات أندرويد (حتى إلا كانت مازال ما تربطاتش). */
    static boolean serviceEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(18), dp(24), dp(18), dp(28));

        // ── الرأس ──
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_launcher_round);
        header.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, dp(14), 0);
        TextView title = text("AB AutoClicker", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);
        titles.addView(text("نقر أوتوماتيكي ذكي بالماكرو", 13, MUTED));
        header.addView(titles);
        root.addView(header);

        // ── الحالة ──
        LinearLayout statusCard = card();
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 18, GREEN);
        statusRow.addView(statusDot);
        statusText = text("", 15, Color.WHITE);
        statusText.setPadding(dp(10), 0, dp(10), 0);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusCard.addView(statusRow);
        enableBtn = button("فعّل الخدمة", ACCENT, v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        statusCard.addView(enableBtn);
        wakeBtn = button("☀  إيقاظ اللوحة", 0xFFF59E0B, v -> {
            ClickService s = ClickService.instance;
            if (s != null) s.setSleeping(false);
            refreshStatus();
        });
        statusCard.addView(wakeBtn);
        root.addView(statusCard);

        // ── الإعدادات ──
        LinearLayout settings = card();
        settings.addView(sectionTitle("⚙  الإعدادات"));
        settings.addView(label("الوقت بين خطوة وخطوة (ms)"));
        intervalInput = number(String.valueOf(prefs.getLong(KEY_INTERVAL, 500)));
        settings.addView(intervalInput);
        settings.addView(label("الوقت بين دورة ودورة (بالثواني)"));
        cycleIntervalInput = number(secondsText(prefs.getLong(KEY_CYCLE_INTERVAL,
                prefs.getLong(KEY_INTERVAL, 500))));
        cycleIntervalInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        settings.addView(cycleIntervalInput);
        settings.addView(hint("من بعد آخر خطوة، كيتسنى هاد الوقت قبل ما يرجع للخطوة 1 (مثلا 2 ولا 0.5)"));
        settings.addView(label("عدد الدورات"));
        cyclesInput = number(String.valueOf(prefs.getLong(KEY_CYCLES, 0)));
        settings.addView(cyclesInput);
        settings.addView(hint("0 = بلا توقف · 1000 ms = ثانية"));
        settings.addView(hint("✓ كيتحفظو أوتوماتيكياً ملي كتكتب"));
        root.addView(settings);

        // حفظ أوتوماتيكي ملي كيتبدل أي رقم
        android.text.TextWatcher autosave = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) { saveSettings(); }
        };
        intervalInput.addTextChangedListener(autosave);
        cycleIntervalInput.addTextChangedListener(autosave);
        cyclesInput.addTextChangedListener(autosave);

        TextView start = button("▶   إظهار اللوحة العائمة", GREEN, v -> saveAndShow());
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = dp(16);
        sp.height = dp(56);
        root.addView(start, sp);

        // ── الماكروات المحفوظة ──
        LinearLayout macros = card();
        macros.addView(sectionTitle("💾  الماكروات المحفوظة"));
        macrosList = new LinearLayout(this);
        macrosList.setOrientation(LinearLayout.VERTICAL);
        macros.addView(macrosList);
        root.addView(macros);

        // ── طريقة الاستعمال ──
        LinearLayout help = card();
        help.addView(sectionTitle("📖  طريقة الاستعمال"));
        help.addView(helpRow("▶", 0xFF22A55B, "تشغيل / إيقاف"));
        help.addView(helpRow("☾", 0xFF334155, "نوم: النقط ما كيتلمسوش، وكيبقى غير زر ☀ باش توقظ"));
        help.addView(helpRow("+", 0xFF3D8BFD, "زيد خطوة جديدة"));
        help.addView(helpRow("++", 0xFFA855F7, "نقر متعدد: نقطة كتنقر مع اللي قبلها فنفس اللحظة"));
        help.addView(helpRow("−", 0xFFF59E0B, "حيد آخر نقطة"));
        help.addView(helpRow("💾", 0xFF0E7490, "حفظ الماكرو الحالي ولا تحميل واحد محفوظ"));
        help.addView(helpRow("✕", 0xFF5F6368, "سد اللوحة"));
        help.addView(hint("\n• لمسة قصيرة على النقطة كتحل الإعدادات ديالها: عدد النقرات، الوقت بين نقرة ونقرة، "
                + "والنقر المتعدد.\n"
                + "• باش توقظ: ضغط ☀ فاللوحة (ولا من الإشعار).\n"
                + "• ملي كيخدم الماكرو كيبقاو غير ▶ و ☾.\n"
                + "• جر اللوحة من أي زر فيها."));
        root.addView(help);

        // ── Xiaomi ──
        LinearLayout miui = card();
        miui.addView(sectionTitle("📱  باش ما تطفاش الخدمة (Xiaomi)"));
        miui.addView(hint("MIUI كيسد التطبيقات فالخلفية، وملي كيسدها كتطفا الخدمة وكيطلب منك الإذن من جديد. "
                + "دير هاد 3 حوايج مرة وحدة:\n"
                + "١. معلومات التطبيق ← التشغيل التلقائي: شعّلو\n"
                + "٢. معلومات التطبيق ← توفير البطارية: بدون قيود\n"
                + "٣. فالتطبيقات الأخيرة، ضغط مطول على AB AutoClicker وقفلو 🔒"));
        miui.addView(button("فتح معلومات التطبيق", 0xFF2C313A, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
        }));
        root.addView(miui);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // نرجعو القيم من الحفظ (ممكن تبدلو من اللوحة ملي تحمّل ماكرو)
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        intervalInput.setText(String.valueOf(prefs.getLong(KEY_INTERVAL, 500)));
        cycleIntervalInput.setText(secondsText(prefs.getLong(KEY_CYCLE_INTERVAL, 500)));
        cyclesInput.setText(String.valueOf(prefs.getLong(KEY_CYCLES, 0)));
        refreshStatus();
        refreshMacros();
    }

    private void refreshStatus() {
        boolean enabled = serviceEnabled(this);
        ClickService s = ClickService.instance;
        if (enabled && s != null) {
            statusText.setText("الخدمة مفعّلة وواجدة");
            statusDot.setTextColor(GREEN);
        } else if (enabled) {
            statusText.setText("الخدمة مفعّلة ولكن ما خداماش — طفيها وعاود شعلها");
            statusDot.setTextColor(0xFFF59E0B);
        } else {
            statusText.setText("الخدمة ماشي مفعّلة");
            statusDot.setTextColor(RED);
        }
        enableBtn.setVisibility(enabled && s != null ? View.GONE : View.VISIBLE);
        wakeBtn.setVisibility(s != null && s.isSleeping() ? View.VISIBLE : View.GONE);
    }

    private void refreshMacros() {
        macrosList.removeAllViews();
        JSONArray all = MacroStore.all(this);
        if (all.length() == 0) {
            macrosList.addView(hint("ما كاين حتى ماكرو. صاوب النقط ومن بعد ضغط 💾 فاللوحة ← حفظ."));
            return;
        }
        for (int i = 0; i < all.length(); i++) {
            final int idx = i;
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            JSONArray p = m.optJSONArray("points");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, 0);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(m.optString("name"), 15, Color.WHITE);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            info.addView(name);
            info.addView(text((p == null ? 0 : p.length()) + " نقط · "
                    + m.optLong("interval") + " ms بين الخطوات · "
                    + secondsText(m.optLong("cycleInterval")) + " ث بين الدورات", 12, MUTED));
            row.addView(info, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(smallButton("▶", GREEN, v -> loadMacro(idx)));
            row.addView(smallButton("✎", 0xFF2C313A, v -> renameMacro(idx, m.optString("name"))));
            row.addView(smallButton("🗑", 0xFF3A1F22, v -> {
                MacroStore.delete(this, idx);
                refreshMacros();
            }));
            macrosList.addView(row);
        }
    }

    private void loadMacro(int idx) {
        JSONObject m = MacroStore.get(this, idx);
        if (m == null) return;
        MacroStore.applySettings(this, m);
        intervalInput.setText(String.valueOf(m.optLong("interval", 500)));
        cycleIntervalInput.setText(secondsText(m.optLong("cycleInterval", 500)));
        cyclesInput.setText(String.valueOf(m.optLong("cycles", 0)));
        ClickService s = ClickService.instance;
        if (s == null) {
            Toast.makeText(this, "فعّل الخدمة أولاً", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        s.loadMacro(m);
        moveTaskToBack(true);
    }

    private void renameMacro(int idx, String current) {
        EditText e = new EditText(this);
        e.setText(current);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("سمية الماكرو")
                .setView(e)
                .setPositiveButton("حفظ", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) MacroStore.rename(this, idx, n);
                    refreshMacros();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    /** 1500 ms ← "1.5" */
    static String secondsText(long ms) {
        if (ms % 1000 == 0) return String.valueOf(ms / 1000);
        String s = String.valueOf(ms / 1000.0);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private long parseSeconds(EditText e, long defMs) {
        try {
            String t = e.getText().toString().trim().replace(',', '.');
            return Math.round(Double.parseDouble(t) * 1000);
        } catch (Exception ex) {
            return defMs;
        }
    }

    /** كيحفظ الإعدادات مباشرة (الماكرو كيقراهم ملي كتضغط ▶). */
    private void saveSettings() {
        long interval = Math.max(MIN_INTERVAL, parse(intervalInput, 500));
        long cycleInterval = Math.max(MIN_INTERVAL, parseSeconds(cycleIntervalInput, interval));
        long cycles = Math.max(0, parse(cyclesInput, 0));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_INTERVAL, interval)
                .putLong(KEY_CYCLE_INTERVAL, cycleInterval)
                .putLong(KEY_CYCLES, cycles)
                .apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
    }

    private void saveAndShow() {
        saveSettings();

        ClickService s = ClickService.instance;
        if (s == null) {
            Toast.makeText(this, "فعّل الخدمة أولاً", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        s.showPanel();
        s.setSleeping(false);
        moveTaskToBack(true);
    }

    // ───── أدوات الواجهة ─────

    private long parse(EditText e, long def) {
        try { return Long.parseLong(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(radiusDp));
        bg.setColor(color);
        return bg;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackground(rounded(CARD, 18));
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(16);
        c.setLayoutParams(p);
        return c;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 16, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, Color.WHITE);
        t.setPadding(0, dp(12), 0, dp(6));
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 12.5f, MUTED);
        t.setPadding(0, dp(4), 0, 0);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private LinearLayout helpRow(String sym, int color, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);
        TextView b = text(sym, 13, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color);
        b.setBackground(oval);
        row.addView(b, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView d = text(desc, 13.5f, 0xFFDADCE0);
        d.setPadding(dp(12), 0, dp(12), 0);
        row.addView(d, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private EditText number(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setTextSize(17);
        e.setGravity(Gravity.CENTER);
        e.setBackground(rounded(FIELD, 12));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setLayoutParams(full());
        return e;
    }

    private TextView button(String s, int color, View.OnClickListener l) {
        TextView b = text(s, 16, Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(color, 14));
        b.setOnClickListener(l);
        b.setClickable(true);
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(12);
        p.height = dp(48);
        b.setLayoutParams(p);
        return b;
    }

    private TextView smallButton(String s, int color, View.OnClickListener l) {
        TextView b = text(s, 15, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(color);
        b.setBackground(oval);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        p.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(p);
        return b;
    }
}
