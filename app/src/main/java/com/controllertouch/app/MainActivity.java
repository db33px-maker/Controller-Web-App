package com.controllertouch.app;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private EditText urlInput;
    private TextView toastBadge;
    private TouchInjector touchInjector;
    private MappingStore store;
    private MappingDialog mappingDialog;

    // Live mapping state for whatever hostname is currently loaded.
    private List<InputMapping> buttonMappings;
    private List<StickZone> stickZones;
    private String currentHostname = "";

    // pointerId bookkeeping: one synthetic pointer id per active
    // button-tap dispatch, plus one per active stick zone, so they can
    // all be tracked independently by TouchInjector's multi-touch model.
    private final Map<String, Integer> activeButtonPointers = new HashMap<>();
    private final Map<String, Integer> activeStickPointers = new HashMap<>();
    private int nextPointerId = 1;

    // Toggle-input edge detection (press/release) and latch state, same
    // concept as the extension's prevButtonState/prevAxisState maps.
    private final Map<Integer, Boolean> keyDownState = new HashMap<>();
    private final Map<String, Boolean> stickLatchedOn = new HashMap<>();

    private static final float AXIS_DEADZONE = 0.35f;

    // Whether the mapping dialog is currently open — while true, controller
    // input drives the mapping UI (capture clicks, bind-key listening)
    // instead of dispatching gameplay touches, same as the extension's
    // editModeActive flag.
    private boolean mappingModeActive = false;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.game_webview);
        urlInput = findViewById(R.id.url_input);
        toastBadge = findViewById(R.id.toast_badge);
        Button goButton = findViewById(R.id.go_button);
        Button settingsButton = findViewById(R.id.settings_button);

        store = new MappingStore(this);
        touchInjector = new TouchInjector(webView);
        mappingDialog = new MappingDialog(this, store, this::onMappingChanged);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                loadMappingsForCurrentUrl();
            }
        });

        goButton.setOnClickListener(v -> loadUrlFromInput());
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            loadUrlFromInput();
            return true;
        });
        settingsButton.setOnClickListener(v -> mappingDialog.show(currentHostname, buttonMappings, stickZones));

        String lastUrl = store.getLastUrl();
        if (!lastUrl.isEmpty()) {
            urlInput.setText(lastUrl);
            webView.loadUrl(lastUrl);
        }

        // Drive the stick-zone continuous dispatch loop the same way the
        // extension polls via requestAnimationFrame — here, a Handler
        // posting to itself roughly every 16ms (~60fps).
        startPollLoop();
    }

    private void loadUrlFromInput() {
        String raw = urlInput.getText().toString().trim();
        if (raw.isEmpty()) return;
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://" + raw;
        }
        store.setLastUrl(raw);
        webView.loadUrl(raw);
    }

    private void loadMappingsForCurrentUrl() {
        String url = webView.getUrl();
        if (url == null) return;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        currentHostname = host != null ? host : "unknown-host";
        buttonMappings = store.loadButtonMappings(currentHostname);
        stickZones = store.loadStickZones(currentHostname);
        showBadge("Loaded mappings for " + currentHostname);
    }

    private void onMappingChanged() {
        // Called by MappingDialog whenever it adds/edits/removes something,
        // so MainActivity's live lists (used by the input-handling code
        // below) stay in sync with what's persisted and what the dialog
        // itself is showing.
        buttonMappings = store.loadButtonMappings(currentHostname);
        stickZones = store.loadStickZones(currentHostname);
    }

    // ---- Controller button input --------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isGamepadSource(event.getDevice()) || isGamepadKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                handleButtonDown(event.getKeyCode());
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handleButtonUp(event.getKeyCode());
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isGamepadSource(InputDevice device) {
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private boolean isGamepadKey(int keyCode) {
        return keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE;
    }

    private void handleButtonDown(int keyCode) {
        boolean wasDown = Boolean.TRUE.equals(keyDownState.get(keyCode));
        keyDownState.put(keyCode, true);
        if (wasDown) return; // ignore key-repeat

        String inputId = "btn-" + keyCode;

        if (mappingModeActive) {
            mappingDialog.onControllerButtonPressed(inputId, keyLabel(keyCode));
            return;
        }

        InputMapping mapping = findButtonMapping(inputId);
        if (mapping != null && mapping.hasPosition) {
            fireButtonDown(mapping);
        }

        handleStickTogglePress(inputId);
    }

    private void handleButtonUp(int keyCode) {
        keyDownState.put(keyCode, false);
        String inputId = "btn-" + keyCode;

        if (mappingModeActive) return;

        InputMapping mapping = findButtonMapping(inputId);
        if (mapping != null && mapping.hasPosition) {
            fireButtonUp(mapping);
        }

        handleStickToggleRelease(inputId);
    }

    private InputMapping findButtonMapping(String inputId) {
        if (buttonMappings == null) return null;
        for (InputMapping m : buttonMappings) {
            if (m.inputId.equals(inputId)) return m;
        }
        return null;
    }

    private void fireButtonDown(InputMapping mapping) {
        int pointerId = nextPointerId++;
        activeButtonPointers.put(mapping.inputId, pointerId);
        touchInjector.dispatchDown(pointerId, mapping.x, mapping.y);
    }

    private void fireButtonUp(InputMapping mapping) {
        Integer pointerId = activeButtonPointers.remove(mapping.inputId);
        if (pointerId == null) return;
        touchInjector.dispatchUp(pointerId, mapping.x, mapping.y);
    }

    // ---- Stick zone toggle handling (button-based toggles) -------------------
    private void handleStickTogglePress(String toggleInputId) {
        if (stickZones == null) return;
        for (StickZone zone : stickZones) {
            if (!toggleInputId.equals(zone.toggleInputId)) continue;
            if ("latch".equals(zone.toggleMode)) {
                boolean nowOn = !Boolean.TRUE.equals(stickLatchedOn.get(zone.id));
                stickLatchedOn.put(zone.id, nowOn);
                if (nowOn) startStick(zone); else endStick(zone);
            } else {
                startStick(zone);
            }
        }
    }

    private void handleStickToggleRelease(String toggleInputId) {
        if (stickZones == null) return;
        for (StickZone zone : stickZones) {
            if (!toggleInputId.equals(zone.toggleInputId)) continue;
            if ("hold".equals(zone.toggleMode)) {
                endStick(zone);
            }
        }
    }

    private void startStick(StickZone zone) {
        if (!zone.hasPosition) return;
        if (activeStickPointers.containsKey(zone.id)) return;
        int pointerId = nextPointerId++;
        activeStickPointers.put(zone.id, pointerId);
        touchInjector.dispatchDown(pointerId, zone.centerX, zone.centerY);
    }

    private void endStick(StickZone zone) {
        Integer pointerId = activeStickPointers.remove(zone.id);
        if (pointerId == null) return;
        touchInjector.dispatchUp(pointerId, zone.centerX, zone.centerY);
    }

    // ---- Analog axis input (sticks, and triggers that report as axes) --------
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK
                || event.getAction() != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event);
        }

        if (mappingModeActive) {
            // While binding, treat any axis crossing the deadzone as a
            // "this is the toggle input" signal too, same as a button —
            // useful for controllers whose triggers report as axes rather
            // than digital buttons (a real, documented gap on some
            // clone-controller hardware, per the browser-extension work).
            reportAxesForBinding(event);
        }

        lastMotionEvent = event; // consumed by the poll loop below for stick-zone live tracking
        return true;
    }

    private MotionEvent lastMotionEvent;

    private void reportAxesForBinding(MotionEvent event) {
        int[] axesToCheck = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
                MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        };
        for (int axis : axesToCheck) {
            float v = event.getAxisValue(axis);
            if (Math.abs(v) > AXIS_DEADZONE) {
                String sign = v < 0 ? "neg" : "pos";
                String inputId = "axis-" + axis + "-" + sign;
                mappingDialog.onControllerButtonPressed(inputId, axisLabel(axis) + " (" + sign + ")");
                return; // one at a time; avoids flooding the dialog every frame
            }
        }
    }

    // ---- Poll loop: continuous stick-zone dispatch, mirrors the
    // extension's requestAnimationFrame loop reading navigator.getGamepads() -
    private void startPollLoop() {
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable loop = new Runnable() {
            @Override
            public void run() {
                if (!mappingModeActive) pollSticks();
                handler.postDelayed(this, 16);
            }
        };
        handler.post(loop);
    }

    private void pollSticks() {
        if (stickZones == null || lastMotionEvent == null) return;
        for (StickZone zone : stickZones) {
            if (!zone.hasPosition) continue;
            Integer pointerId = activeStickPointers.get(zone.id);

            // No-toggle sticks: active purely based on deflection, so we
            // must evaluate start/stop here every frame rather than only
            // on a button edge.
            if (zone.toggleInputId == null) {
                float ax = lastMotionEvent.getAxisValue(zone.axisX);
                float ay = lastMotionEvent.getAxisValue(zone.axisY);
                boolean deflected = Math.hypot(ax, ay) > AXIS_DEADZONE;
                if (deflected && pointerId == null) {
                    startStick(zone);
                    pointerId = activeStickPointers.get(zone.id);
                } else if (!deflected && pointerId != null) {
                    endStick(zone);
                    continue;
                }
            }

            pointerId = activeStickPointers.get(zone.id);
            if (pointerId == null) continue;

            float axisX = lastMotionEvent.getAxisValue(zone.axisX);
            float axisY = lastMotionEvent.getAxisValue(zone.axisY);
            double mag = Math.min(1.0, Math.hypot(axisX, axisY));
            double angle = Math.atan2(axisY, axisX);
            float x = (float) (zone.centerX + Math.cos(angle) * mag * zone.radius);
            float y = (float) (zone.centerY + Math.sin(angle) * mag * zone.radius);
            touchInjector.dispatchMove(pointerId, x, y);
        }
    }

    // ---- Mapping-mode toggle, called by MappingDialog show/hide -------------
    public void setMappingModeActive(boolean active) {
        mappingModeActive = active;
    }

    public WebView getWebViewRef() {
        return webView;
    }

    public TouchInjector getTouchInjectorRef() {
        return touchInjector;
    }

    // ---- Small UI helpers ------------------------------------------------------
    public void showBadge(String message) {
        runOnUiThread(() -> {
            toastBadge.setText(message);
            toastBadge.setVisibility(View.VISIBLE);
            toastBadge.postDelayed(() -> toastBadge.setVisibility(View.GONE), 2200);
        });
    }

    private String keyLabel(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A / Cross";
            case KeyEvent.KEYCODE_BUTTON_B: return "B / Circle";
            case KeyEvent.KEYCODE_BUTTON_X: return "X / Square";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y / Triangle";
            case KeyEvent.KEYCODE_BUTTON_L1: return "Left Bumper (L1)";
            case KeyEvent.KEYCODE_BUTTON_R1: return "Right Bumper (R1)";
            case KeyEvent.KEYCODE_BUTTON_L2: return "Left Trigger (L2)";
            case KeyEvent.KEYCODE_BUTTON_R2: return "Right Trigger (R2)";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "Left Stick Click";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "Right Stick Click";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
            case KeyEvent.KEYCODE_DPAD_UP: return "D-Pad Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D-Pad Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "D-Pad Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "D-Pad Right";
            default: return "Button (code " + keyCode + ")";
        }
    }

    private String axisLabel(int axis) {
        switch (axis) {
            case MotionEvent.AXIS_X: return "Left Stick X";
            case MotionEvent.AXIS_Y: return "Left Stick Y";
            case MotionEvent.AXIS_Z: return "Right Stick X";
            case MotionEvent.AXIS_RZ: return "Right Stick Y";
            case MotionEvent.AXIS_LTRIGGER: return "Left Trigger (analog)";
            case MotionEvent.AXIS_RTRIGGER: return "Right Trigger (analog)";
            case MotionEvent.AXIS_BRAKE: return "Brake Axis";
            case MotionEvent.AXIS_GAS: return "Gas Axis";
            default: return "Axis " + axis;
        }
    }
}
