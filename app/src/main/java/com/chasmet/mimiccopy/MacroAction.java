package com.chasmet.mimiccopy;

import org.json.JSONException;
import org.json.JSONObject;

public class MacroAction {
    public static final String CLICK = "CLICK";
    public static final String LONG_CLICK = "LONG_CLICK";
    public static final String TEXT = "TEXT";
    public static final String SCROLL_FORWARD = "SCROLL_FORWARD";
    public static final String SCROLL_BACKWARD = "SCROLL_BACKWARD";
    public static final String GLOBAL_BACK = "GLOBAL_BACK";
    public static final String GLOBAL_HOME = "GLOBAL_HOME";

    public String type = "";
    public String packageName = "";
    public String className = "";
    public String viewId = "";
    public String text = "";
    public String contentDescription = "";
    public float normalizedX = -1f;
    public float normalizedY = -1f;
    public long delayMs = 350L;
    public long recordedAt = 0L;

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        o.put("packageName", packageName);
        o.put("className", className);
        o.put("viewId", viewId);
        o.put("text", text);
        o.put("contentDescription", contentDescription);
        o.put("normalizedX", normalizedX);
        o.put("normalizedY", normalizedY);
        o.put("delayMs", delayMs);
        o.put("recordedAt", recordedAt);
        return o;
    }

    public static MacroAction fromJson(JSONObject o) {
        MacroAction a = new MacroAction();
        a.type = o.optString("type", "");
        a.packageName = o.optString("packageName", "");
        a.className = o.optString("className", "");
        a.viewId = o.optString("viewId", "");
        a.text = o.optString("text", "");
        a.contentDescription = o.optString("contentDescription", "");
        a.normalizedX = (float) o.optDouble("normalizedX", -1d);
        a.normalizedY = (float) o.optDouble("normalizedY", -1d);
        a.delayMs = o.optLong("delayMs", 350L);
        a.recordedAt = o.optLong("recordedAt", 0L);
        return a;
    }
}
