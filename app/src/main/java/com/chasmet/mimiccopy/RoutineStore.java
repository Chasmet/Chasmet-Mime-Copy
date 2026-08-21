package com.chasmet.mimiccopy;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class RoutineStore {
    private static final String PREFS = "mimic_store";
    private static final String KEY_NAME = "routine_name";
    private static final String KEY_ACTIONS = "routine_actions";

    private RoutineStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void save(Context c, String name, List<MacroAction> actions) {
        JSONArray arr = new JSONArray();
        for (MacroAction action : actions) {
            try { arr.put(action.toJson()); } catch (Exception ignored) {}
        }
        prefs(c).edit()
                .putString(KEY_NAME, name == null ? "Ma routine" : name)
                .putString(KEY_ACTIONS, arr.toString())
                .apply();
    }

    public static synchronized List<MacroAction> load(Context c) {
        List<MacroAction> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY_ACTIONS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(MacroAction.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static String getName(Context c) {
        return prefs(c).getString(KEY_NAME, "Ma routine");
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(KEY_NAME).remove(KEY_ACTIONS).apply();
    }
}
