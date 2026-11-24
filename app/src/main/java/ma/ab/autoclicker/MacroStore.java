package ma.ab.autoclicker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * حفظ الماكروات فالتليفون (SharedPreferences بصيغة JSON).
 * كل ماكرو: {name, interval, cycleInterval, cycles, points:[{fx, fy, count, gap, join}]}
 * fx/fy = بلاصة النقطة كنسبة من عرض/طول الشاشة، باش تبقى صحيحة حتى ملي كتدور الشاشة.
 */
final class MacroStore {

    private static final String KEY = "macros";

    private MacroStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static JSONArray all(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray arr) {
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    static String nextName(Context c) {
        return "ماكرو " + (all(c).length() + 1);
    }

    static void add(Context c, JSONObject macro) {
        JSONArray arr = all(c);
        arr.put(macro);
        write(c, arr);
    }

    static JSONObject get(Context c, int i) {
        JSONArray arr = all(c);
        return (i >= 0 && i < arr.length()) ? arr.optJSONObject(i) : null;
    }

    static void delete(Context c, int i) {
        JSONArray arr = all(c), out = new JSONArray();
        for (int k = 0; k < arr.length(); k++) if (k != i) out.put(arr.opt(k));
        write(c, out);
    }

    static void rename(Context c, int i, String name) {
        JSONArray arr = all(c);
        JSONObject m = arr.optJSONObject(i);
        if (m == null) return;
        try { m.put("name", name); } catch (Exception ignored) { }
        write(c, arr);
    }

    /** كيرجع إعدادات الماكرو (الأوقات والدورات) للإعدادات العامة. */
    static void applySettings(Context c, JSONObject m) {
        prefs(c).edit()
                .putLong(MainActivity.KEY_INTERVAL, m.optLong("interval", 500))
                .putLong(MainActivity.KEY_CYCLE_INTERVAL, m.optLong("cycleInterval", 0))
                .putLong(MainActivity.KEY_CYCLES, m.optLong("cycles", 0))
                .apply();
    }
}
