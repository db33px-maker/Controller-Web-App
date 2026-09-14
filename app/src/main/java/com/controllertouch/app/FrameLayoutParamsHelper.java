package com.controllertouch.app;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Small helper so MappingDialog doesn't need to import/construct
 * FrameLayout.LayoutParams inline in several places. root_container in
 * activity_main.xml is a FrameLayout, which is what makes an
 * absolutely-positioned top banner straightforward here.
 */
public class FrameLayoutParamsHelper {
    public static void applyTopBanner(View view) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.topMargin = 16;
        params.leftMargin = 16;
        params.rightMargin = 16;
        view.setLayoutParams(params);
        view.setElevation(30);
    }
}
