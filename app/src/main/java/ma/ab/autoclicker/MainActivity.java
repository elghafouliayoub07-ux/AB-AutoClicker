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
import android.widget.Button;
import android.widget.EditText;
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

    private TextView status;
    private EditText intervalInput;
    private EditText cyclesInput;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        root.setBackgroundColor(0xFF121212);

        TextView title = text("AB AutoClicker", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = text("", 15, Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = dp(16);
        root.addView(status, sp);

        root.addView(button("١. فعّل خدمة النقر", 0xFF1A73E8, v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(label("الوقت بين خطوة وخطوة (بالميلي ثانية، 1000 = ثانية)"));
        intervalInput = number(String.valueOf(prefs.getLong(KEY_INTERVAL, 500)));
        root.addView(intervalInput, full());

        root.addView(label("عدد الدورات (0 = بلا توقف)"));
        cyclesInput = number(String.valueOf(prefs.getLong(KEY_CYCLES, 0)));
        root.addView(cyclesInput, full());

        root.addView(button("٢. حفظ وإظهار اللوحة", 0xFF1E8E3E, v -> saveAndShow()));

        TextView help = text(
                "طريقة الاستعمال (ماكرو):\n"
                        + "• ✥ : جر اللوحة لأي بلاصة\n"
                        + "• + : زيد خطوة جديدة (نقطة برقم جديد)\n"
                        + "• ⧉ : زيد نقطة لنفس الخطوة ديال آخر نقطة — كيتنقرو فنفس اللحظة\n"
                        + "• − : حيد آخر نقطة\n"
                        + "• ▶ : تشغيل  /  ■ : إيقاف\n"
                        + "• ✕ : سد اللوحة\n\n"
                        + "مثال: + + + + ⧉  ←  النقط 1، 2، 3، 4، 4\n"
                        + "كينقر 1، من بعد 2، من بعد 3، ومن بعد جوج النقط ديال 4 مع بعض، "
                        + "ومن بعد كيعاود من 1. الوقت اللي حطيتي كيكون بين كل خطوة وخطوة.\n\n"
                        + "ملاحظة Xiaomi: إلا تحبسات الخدمة بوحدها، دخل لمعلومات التطبيق وفعّل "
                        + "«التشغيل التلقائي» وخلي البطارية على «بدون قيود».",
                14, 0xFFBDC1C6);
        LinearLayout.LayoutParams hp = full();
        hp.topMargin = dp(24);
        root.addView(help, hp);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF121212);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = ClickService.instance != null;
        status.setText(on ? "✅ الخدمة مفعّلة" : "⚠️ الخدمة ماشي مفعّلة — فعّلها من الزر اللول");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(on ? 0xFF0D652D : 0xFF8C1D18);
        status.setBackground(bg);
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
            Toast.makeText(this, "فعّل خدمة النقر أولاً", Toast.LENGTH_LONG).show();
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

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 15, Color.WHITE);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private EditText number(String value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setTextSize(18);
        e.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0xFF2D2E31);
        e.setBackground(bg);
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        return e;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(color);
        b.setBackground(bg);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(16);
        p.height = dp(54);
        b.setLayoutParams(p);
        return b;
    }
}
