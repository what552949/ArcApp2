package com.example.arcapp;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

public class ArcAccessibilityService extends AccessibilityService {

    private static ArcAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static void swipe(float x1, float y1, float x2, float y2, long duration) {
        if (instance == null) return;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        instance.dispatchGesture(gesture, null, null);
    }
}
