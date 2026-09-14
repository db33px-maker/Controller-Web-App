package com.controllertouch.app;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Settings UI for creating/editing button mappings and stick zones.
 *
 * Position capture works by temporarily shrinking the dialog to a small
 * corner banner and listening for the NEXT touch on the underlying
 * activity's root view — conceptually identical to the browser
 * extension's "hide the panel from hit-testing, wait for the next page
 * click" approach, just implemented with real Android views instead of
 * CSS pointer-events and a capture-phase DOM listener.
 */
public class MappingDialog {

    public interface OnChangedListener {
        void onChanged();
    }

    private final MainActivity activity;
    private final MappingStore store;
    private final OnChangedListener onChanged;

    private Dialog dialog;
    private LinearLayout listContainer;
    private String hostname;
    private List<InputMapping> buttonMappings;
    private List<StickZone> stickZones;

    // Capture state
    private InputMapping pendingButtonCapture;
    private StickZone pendingStickCapture;
    private String stickCaptureStage; // "center" | "radius"
    private float stickCaptureCenterX, stickCaptureCenterY;

    // Bind-by-press state: when non-null, the next controller button/axis
    // event MainActivity sees gets routed here instead of dispatching
    // gameplay touches (see MainActivity.mappingModeActive).
    private InputMapping pendingButtonBind;
    private StickZone pendingStickToggleBind;

    private int stickIdCounter = 1;

    public MappingDialog(MainActivity activity, MappingStore store, OnChangedListener onChanged) {
        this.activity = activity;
        this.store = store;
        this.onChanged = onChanged;
    }

    public void show(String hostname, List<InputMapping> buttonMappings, List<StickZone> stickZones) {
        this.hostname = hostname;
        this.buttonMappings = buttonMappings;
        this.stickZones = stickZones;
        activity.setMappingModeActive(true);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LayoutInflater inflater = LayoutInflater.from(activity);
        View root = inflater.inflate(R.layout.dialog_mapping, null);
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> activity.setMappingModeActive(false));

        listContainer = root.findViewById(R.id.mapping_list);

        Button addButtonMapping = root.findViewById(R.id.add_button_mapping);
        Button addStickMapping = root.findViewById(R.id.add_stick_mapping);
        Button closeButton = root.findViewById(R.id.close_mapping);

        addButtonMapping.setOnClickListener(v -> addButtonMapping());
        addStickMapping.setOnClickListener(v -> addStickZone());
        closeButton.setOnClickListener(v -> dialog.dismiss());

        renderList();
        dialog.show();
    }

    private void addButtonMapping() {
        InputMapping m = new InputMapping("unbound-" + System.currentTimeMillis(), "(press a button to bind)");
        buttonMappings.add(m);
        renderList();
        beginButtonBind(m);
    }

    private void addStickZone() {
        StickZone z = new StickZone(
                "stick-" + System.currentTimeMillis() + "-" + stickIdCounter++,
                "Stick " + (stickZones.size() + 1),
                MotionEvent.AXIS_X,
                MotionEvent.AXIS_Y);
        stickZones.add(z);
        renderList();
        beginStickPositionCapture(z);
    }

    private void renderList() {
        listContainer.removeAllViews();

        TextView buttonsHeader = sectionHeader("Buttons");
        listContainer.addView(buttonsHeader);
        for (InputMapping m : buttonMappings) {
            listContainer.addView(buildButtonRow(m));
        }

        TextView sticksHeader = sectionHeader("Stick Zones");
        listContainer.addView(sticksHeader);
        for (StickZone z : stickZones) {
            listContainer.addView(buildStickRow(z));
        }
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#BCD6FF"));
        tv.setTextSize(13);
        tv.setPadding(0, 24, 0, 8);
        return tv;
    }

    private View buildButtonRow(InputMapping m) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 6, 0, 6);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(activity);
        label.setText(m.label + (m.hasPosition ? "  (" + Math.round(m.x) + "," + Math.round(m.y) + ")" : "  — no position"));
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        Button rebind = smallButton("Bind key");
        rebind.setOnClickListener(v -> beginButtonBind(m));
        row.addView(rebind);

        Button place = smallButton("Set pos");
        place.setOnClickListener(v -> beginButtonPositionCapture(m));
        row.addView(place);

        Button remove = smallButton("×");
        remove.setOnClickListener(v -> {
            buttonMappings.remove(m);
            saveButtons();
            renderList();
        });
        row.addView(remove);

        return row;
    }

    private View buildStickRow(StickZone z) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 6, 0, 10);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(activity);
        String posText = z.hasPosition
                ? "  (" + Math.round(z.centerX) + "," + Math.round(z.centerY) + ") r=" + Math.round(z.radius)
                : "  — not placed";
        label.setText(z.label + posText);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(label);

        Button place = smallButton(z.hasPosition ? "Re-place" : "Place");
        place.setOnClickListener(v -> beginStickPositionCapture(z));
        top.addView(place);

        Button remove = smallButton("×");
        remove.setOnClickListener(v -> {
            stickZones.remove(z);
            saveSticks();
            renderList();
        });
        top.addView(remove);

        col.addView(top);

        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);

        TextView toggleLabel = new TextView(activity);
        toggleLabel.setText(z.toggleInputId == null ? "Toggle: none (always on)" : "Toggle: " + z.toggleInputId);
        toggleLabel.setTextColor(Color.parseColor("#9AA0AB"));
        toggleLabel.setTextSize(11);
        toggleLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(toggleLabel);

        Button bindToggle = smallButton("Bind toggle");
        bindToggle.setOnClickListener(v -> beginStickToggleBind(z));
        bottom.addView(bindToggle);

        Button modeToggle = smallButton("hold".equals(z.toggleMode) ? "Mode: Hold" : "Mode: Latch");
        modeToggle.setOnClickListener(v -> {
            z.toggleMode = "hold".equals(z.toggleMode) ? "latch" : "hold";
            saveSticks();
            renderList();
        });
        bottom.addView(modeToggle);

        col.addView(bottom);
        return col;
    }

    private Button smallButton(String text) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextSize(11);
        b.setPadding(12, 4, 12, 4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(6);
        b.setLayoutParams(lp);
        return b;
    }

    // ---- Bind-by-press flow ---------------------------------------------------
    private void beginButtonBind(InputMapping m) {
        pendingButtonBind = m;
        pendingStickToggleBind = null;
        toast("Press any controller button to bind \"" + m.label + "\"");
    }

    private void beginStickToggleBind(StickZone z) {
        pendingStickToggleBind = z;
        pendingButtonBind = null;
        toast("Press any controller button/axis to toggle \"" + z.label + "\"");
    }

    /** Called by MainActivity when a controller button/axis fires while the dialog is open. */
    public void onControllerButtonPressed(String inputId, String label) {
        if (pendingButtonBind != null) {
            pendingButtonBind.inputId = inputId;
            pendingButtonBind.label = label;
            pendingButtonBind = null;
            saveButtons();
            activity.runOnUiThread(this::renderList);
            toast("Bound: " + label);
            return;
        }
        if (pendingStickToggleBind != null) {
            pendingStickToggleBind.toggleInputId = inputId;
            pendingStickToggleBind = null;
            saveSticks();
            activity.runOnUiThread(this::renderList);
            toast("Toggle bound: " + label);
        }
    }

    // ---- Position capture flow -------------------------------------------------
    private View captureOverlay;

    private void beginButtonPositionCapture(InputMapping m) {
        pendingButtonCapture = m;
        pendingStickCapture = null;
        showCaptureOverlay("Tap where \"" + m.label + "\" should touch");
    }

    private void beginStickPositionCapture(StickZone z) {
        pendingStickCapture = z;
        pendingButtonCapture = null;
        stickCaptureStage = "center";
        showCaptureOverlay("Tap the stick's resting center for \"" + z.label + "\"");
    }

    private void showCaptureOverlay(String message) {
        dialog.hide(); // hide (not dismiss) so mappingModeActive stays true and state is preserved

        ViewGroup root = activity.findViewById(R.id.root_container);
        LinearLayout banner = new LinearLayout(activity);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setBackgroundColor(Color.parseColor("#4C8FFF"));
        banner.setPadding(20, 16, 20, 16);
        banner.setGravity(Gravity.CENTER_VERTICAL);

        TextView text = new TextView(activity);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(13);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        banner.addView(text);

        Button cancel = new Button(activity);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> cancelCapture());
        banner.addView(cancel);

        FrameLayoutParamsHelper.applyTopBanner(banner);
        root.addView(banner);
        captureOverlay = banner;

        // Listen for the next tap anywhere on the WebView itself. This
        // reuses the real WebView touch path (onTouchEvent) rather than a
        // synthetic listener, since we specifically want real screen
        // coordinates in the WebView's own coordinate space.
        activity.getWebViewRef().setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                handleCaptureTap(event.getX(), event.getY());
                return true; // consume so it doesn't also reach the page while capturing
            }
            return false;
        });
    }

    private void handleCaptureTap(float x, float y) {
        if (pendingButtonCapture != null) {
            pendingButtonCapture.x = x;
            pendingButtonCapture.y = y;
            pendingButtonCapture.hasPosition = true;
            saveButtons();
            pendingButtonCapture = null;
            finishCapture();
            return;
        }
        if (pendingStickCapture != null) {
            if ("center".equals(stickCaptureStage)) {
                stickCaptureCenterX = x;
                stickCaptureCenterY = y;
                stickCaptureStage = "radius";
                ((TextView) ((LinearLayout) captureOverlay).getChildAt(0))
                        .setText("Tap how far the stick should reach for \"" + pendingStickCapture.label + "\"");
                return;
            }
            // stage == "radius"
            float radius = (float) Math.max(20, Math.hypot(x - stickCaptureCenterX, y - stickCaptureCenterY));
            pendingStickCapture.centerX = stickCaptureCenterX;
            pendingStickCapture.centerY = stickCaptureCenterY;
            pendingStickCapture.radius = radius;
            pendingStickCapture.hasPosition = true;
            saveSticks();
            pendingStickCapture = null;
            finishCapture();
        }
    }

    private void cancelCapture() {
        pendingButtonCapture = null;
        pendingStickCapture = null;
        stickCaptureStage = null;
        finishCapture();
    }

    private void finishCapture() {
        activity.getWebViewRef().setOnTouchListener(null);
        ViewGroup root = activity.findViewById(R.id.root_container);
        if (captureOverlay != null) {
            root.removeView(captureOverlay);
            captureOverlay = null;
        }
        dialog.show();
        renderList();
        onChanged.onChanged();
    }

    private void saveButtons() {
        store.saveButtonMappings(hostname, buttonMappings);
        onChanged.onChanged();
    }

    private void saveSticks() {
        store.saveStickZones(hostname, stickZones);
        onChanged.onChanged();
    }

    private void toast(String msg) {
        activity.runOnUiThread(() -> Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }
}
