package com.controllertouch.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists button/axis mappings and stick zones per-hostname, same design
 * as the browser extension's chrome.storage.local keyed by hostname —
 * different games (different URLs) get independent layouts.
 */
public class MappingStore {
    private static final String PREFS_NAME = "controller_to_touch_prefs";
    private final SharedPreferences prefs;

    public MappingStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private String buttonKey(String hostname) {
        return "buttons_" + hostname;
    }

    private String stickKey(String hostname) {
        return "sticks_" + hostname;
    }

    public List<InputMapping> loadButtonMappings(String hostname) {
        List<InputMapping> result = new ArrayList<>();
        String raw = prefs.getString(buttonKey(hostname), null);
        if (raw == null) return result;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                InputMapping m = new InputMapping(o.getString("inputId"), o.getString("label"));
                m.hasPosition = o.optBoolean("hasPosition", false);
                m.x = (float) o.optDouble("x", 0);
                m.y = (float) o.optDouble("y", 0);
                result.add(m);
            }
        } catch (JSONException e) {
            // Corrupt or old-format data — treat as empty rather than crash.
        }
        return result;
    }

    public void saveButtonMappings(String hostname, List<InputMapping> mappings) {
        try {
            JSONArray arr = new JSONArray();
            for (InputMapping m : mappings) {
                JSONObject o = new JSONObject();
                o.put("inputId", m.inputId);
                o.put("label", m.label);
                o.put("hasPosition", m.hasPosition);
                o.put("x", m.x);
                o.put("y", m.y);
                arr.put(o);
            }
            prefs.edit().putString(buttonKey(hostname), arr.toString()).apply();
        } catch (JSONException e) {
            // Serialization of our own known-shape data shouldn't fail;
            // if it somehow does, we simply don't persist this save.
        }
    }

    public List<StickZone> loadStickZones(String hostname) {
        List<StickZone> result = new ArrayList<>();
        String raw = prefs.getString(stickKey(hostname), null);
        if (raw == null) return result;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                StickZone z = new StickZone(
                        o.getString("id"),
                        o.getString("label"),
                        o.getInt("axisX"),
                        o.getInt("axisY"));
                z.hasPosition = o.optBoolean("hasPosition", false);
                z.centerX = (float) o.optDouble("centerX", 0);
                z.centerY = (float) o.optDouble("centerY", 0);
                z.radius = (float) o.optDouble("radius", 60);
                z.toggleInputId = o.isNull("toggleInputId") ? null : o.optString("toggleInputId", null);
                z.toggleMode = o.optString("toggleMode", "hold");
                result.add(z);
            }
        } catch (JSONException e) {
            // Corrupt or old-format data — treat as empty rather than crash.
        }
        return result;
    }

    public void saveStickZones(String hostname, List<StickZone> zones) {
        try {
            JSONArray arr = new JSONArray();
            for (StickZone z : zones) {
                JSONObject o = new JSONObject();
                o.put("id", z.id);
                o.put("label", z.label);
                o.put("axisX", z.axisX);
                o.put("axisY", z.axisY);
                o.put("hasPosition", z.hasPosition);
                o.put("centerX", z.centerX);
                o.put("centerY", z.centerY);
                o.put("radius", z.radius);
                o.put("toggleInputId", z.toggleInputId);
                o.put("toggleMode", z.toggleMode);
                arr.put(o);
            }
            prefs.edit().putString(stickKey(hostname), arr.toString()).apply();
        } catch (JSONException e) {
            // Same reasoning as saveButtonMappings above.
        }
    }

    public String getLastUrl() {
        return prefs.getString("last_url", "");
    }

    public void setLastUrl(String url) {
        prefs.edit().putString("last_url", url).apply();
    }
}
