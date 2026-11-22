package ma.ab.autoclicker;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

/** الشاشة الرئيسية: تفعيل الخدمة، الإعدادات، وإظهار اللوحة العائمة. */
public class MainActivity extends Activity {

    static final String PREFS = "cfg";
    static final String KEY_INTERVAL = "interval";
    static final String KEY_CYCLES = "cycles";
    static final long MIN_INTERVAL = 20;

    private static final int BG = 0xFF0F1115;
    private static final int CARD = 0xFF1A1D23;
    private static final int FIELD = 0xFF252932;
    private static final int ACCENT = 0xFF3D8BFD;
    private static final int GREEN = 0xFF22A55B;
    private static final int MUTED = 0xFF9AA0A6;

    private TextView statusText;
    private TextView statusDot;
    private LinearLayout statusCard;
    private TextView enableBtn;
    private EditText intervalInput;
    private EditText cyclesInput;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

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

        // ── حالة الخدمة ──
        statusCard = card();
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
        root.addView(statusCard);

        // ── الإعدادات ──
        LinearLayout settings = card();
        settings.addView(sectionTitle("⚙  الإعدادات"));
        settings.addView(label("الوقت بين خطوة وخطوة (ms)"));
        intervalInput = number(String.valueOf(prefs.getLong(KEY_INTERVAL, 500)));
        settings.addView(intervalInput);
        settings.addView(hint("1000 = ثانية · أقل حاجة " + MIN_INTERVAL));
        settings.addView(label("عدد الدورات"));
        cyclesInput = number(String.valueOf(prefs.getLong(KEY_CYCLES, 0)));
        settings.addView(cyclesInput);
        settings.addView(hint("0 = بلا توقف"));
        root.addView(settings);

        TextView start = button("▶   إظهار اللوحة العائمة", GREEN, v -> saveAndShow());
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = dp(16);
        sp.height = dp(56);
        root.addView(start, sp);

        // ── طريقة الاستعمال ──
        LinearLayout help = card();
        help.addView(sectionTitle("📖  طريقة الاستعمال"));
        help.addView(helpRow("+", 0xFF3D8BFD, "زيد خطوة جديدة"));
        help.addView(helpRow("++", 0xFFA855F7, "نقر متعدد: نقطة كتنقر مع اللي قبلها فنفس اللحظة"));
        help.addView(helpRow("−", 0xFFF59E0B, "حيد آخر نقطة"));
        help.addView(helpRow("▶", 0xFF22A55B, "تشغيل / إيقاف — ملي كيخدم كيبقى غير هاد الزر"));
        help.addView(helpRow("✕", 0xFF5F6368, "سد اللوحة"));
        help.addView(hint("\n• النقطة هدف صغير ⊕ والنقرة كتجي بالضبط فالوسط ديالو. جرها للبلاصة اللي بغيتي.\n"
                + "• لمسة قصيرة على النقطة كتحل الإعدادات ديالها: عدد النقرات، الوقت بين نقرة ونقرة، "
                + "والنقر المتعدد مع اللي قبلها.\n"
                + "• النقط اللي عندهم نفس الرقم كيتنقرو فنفس اللحظة.\n"
                + "• جر اللوحة من أي زر فيها."));
        root.addView(help);

        TextView miui = hint("ملاحظة Xiaomi: إلا تحبسات الخدمة بوحدها، دخل لمعلومات التطبيق وفعّل "
                + "«التشغيل التلقائي» وخلي البطارية على «بدون قيود».");
        miui.setPadding(dp(6), dp(16), dp(6), 0);
        root.addView(miui);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = ClickService.instance != null;
        statusText.setText(on ? "الخدمة مفعّلة وواجدة" : "الخدمة ماشي مفعّلة");
        statusDot.setTextColor(on ? GREEN : 0xFFE5484D);
        enableBtn.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void saveAndShow() {
        long interval = parse(intervalInput, 500);
        if (interval < MIN_INTERVAL) {
            interval = MIN_INTERVAL;
            intervalInput.setText(String.valueOf(MIN_INTERVAL));
            Toast.makeText(this, "أقل وقت هو " + MIN_INTERVAL + " ms", Toast.LENGTH_SHORT).show();
        }
        long cycles = Math.max(0, parse(cyclesInput, 0));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_INTERVAL, interval)
                .putLong(KEY_CYCLES, cycles)
                .apply();

        ClickService s = ClickService.instance;
        if (s == null) {
            Toast.makeText(this, "فعّل الخدمة أولاً", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        s.showPanel();
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
        TextView b = text(sym, 14, Color.WHITE);
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
}
