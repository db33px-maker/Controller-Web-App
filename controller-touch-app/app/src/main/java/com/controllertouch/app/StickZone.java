package com.controllertouch.app;

/**
 * A stick zone: center point + radius on screen, driven continuously by a
 * real analog stick's two axes, gated on/off by a separate toggle input.
 * Same concept as the browser extension's stick zones, reimplemented for
 * native MotionEvent axes instead of the Gamepad API's axes array.
 */
public class StickZone {
    public String id;
    public String label;

    // Which MotionEvent axis constants drive this zone (e.g.
    // MotionEvent.AXIS_X / AXIS_Y for the left stick, AXIS_Z / AXIS_RZ for
    // the right stick on most Android-recognized gamepads).
    public int axisX;
    public int axisY;

    public float centerX;
    public float centerY;
    public float radius;
    public boolean hasPosition = false;

    // "hold" = active only while toggle input is held.
    // "latch" = each press of the toggle flips it on/off.
    // null toggleInputId = always active whenever axes leave the deadzone.
    public String toggleInputId;
    public String toggleMode = "hold";

    public StickZone(String id, String label, int axisX, int axisY) {
        this.id = id;
        this.label = label;
        this.axisX = axisX;
        this.axisY = axisY;
    }
}
