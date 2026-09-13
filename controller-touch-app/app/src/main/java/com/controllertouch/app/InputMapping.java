package com.controllertouch.app;

/**
 * A single button (or axis-treated-as-button) mapped to a screen tap
 * location. Mirrors the extension's "mapping" entries: one input, one
 * point on screen, fired once on press and once on release.
 *
 * inputId format matches the extension for conceptual consistency:
 * "btn-<androidKeyCode>" for buttons, "axis-<motionEventAxis>-neg"/"-pos"
 * for analog axes used as on/off thresholds (e.g. analog triggers that
 * report as axes rather than discrete keys on some controllers).
 */
public class InputMapping {
    public String inputId;
    public String label;
    public float x;
    public float y;
    public boolean hasPosition = false;

    public InputMapping(String inputId, String label) {
        this.inputId = inputId;
        this.label = label;
    }
}
