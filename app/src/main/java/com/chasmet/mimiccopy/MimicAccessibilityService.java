package com.chasmet.mimiccopy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MimicAccessibilityService extends AccessibilityService {
    private static MimicAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MacroAction> recording = new ArrayList<>();
    private boolean isRecording = false;
    private boolean isReplaying = false;
    private String recordingName = "Ma routine";
    private long lastRecordedAt = 0L;
    private long lastScrollAt = 0L;
    private View overlay;
    private TextView overlayLabel;
    private List<MacroAction> replayActions = new ArrayList<>();
    private int replayIndex = 0;
    private long replayToken = 0L;

    public static MimicAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        stopAll("Service arrêté");
        instance = null;
        super.onDestroy();
    }

    public void startRecording(String name) {
        stopReplayInternal();
        recording.clear();
        recordingName = TextUtils.isEmpty(name) ? "Ma routine" : name;
        lastRecordedAt = System.currentTimeMillis();
        isRecording = true;
        showOverlay(true);
        updateOverlay("● J’APPRENDS · 0");
    }

    public void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        RoutineStore.save(this, recordingName, recording);
        hideOverlay();
        Toast.makeText(this, "Routine enregistrée : " + recording.size() + " actions", Toast.LENGTH_LONG).show();
    }

    public void startReplay() {
        if (isRecording) stopRecording();
        replayActions = RoutineStore.load(this);
        if (replayActions.isEmpty()) {
            Toast.makeText(this, "Aucune action enregistrée.", Toast.LENGTH_SHORT).show();
            return;
        }
        isReplaying = true;
        replayIndex = 0;
        replayToken++;
        showOverlay(false);
        updateOverlay("▶ COPIE · 0/" + replayActions.size());
        scheduleNext(replayToken, 900L);
    }

    public void stopReplay() {
        stopReplayInternal();
        hideOverlay();
        Toast.makeText(this, "Rejeu arrêté.", Toast.LENGTH_SHORT).show();
    }

    private void stopReplayInternal() {
        isReplaying = false;
        replayToken++;
        replayActions.clear();
        replayIndex = 0;
    }

    private void stopAll(String reason) {
        if (isRecording) {
            isRecording = false;
            RoutineStore.save(this, recordingName, recording);
        }
        stopReplayInternal();
        hideOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isRecording || event == null) return;
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (pkg.equals(getPackageName())) return;

        int type = event.getEventType();
        AccessibilityNodeInfo source = event.getSource();

        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            recordNodeAction(MacroAction.CLICK, source, pkg);
        } else if (type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            recordNodeAction(MacroAction.LONG_CLICK, source, pkg);
        } else if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (source != null && !source.isPassword()) {
                recordTextAction(source, pkg);
            }
        } else if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            recordScroll(event, source, pkg);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!isRecording || event == null || event.getAction() != KeyEvent.ACTION_UP) return false;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            recordGlobal(MacroAction.GLOBAL_BACK);
        }
        return false;
    }

    private void recordNodeAction(String type, AccessibilityNodeInfo node, String pkg) {
        if (node == null || node.isPassword()) return;
        MacroAction a = baseAction(type, node, pkg);
        appendAction(a);
    }

    private void recordTextAction(AccessibilityNodeInfo node, String pkg) {
        CharSequence value = node.getText();
        if (value == null) return;
        MacroAction a = baseAction(MacroAction.TEXT, node, pkg);
        a.text = value.toString();

        if (!recording.isEmpty()) {
            MacroAction last = recording.get(recording.size() - 1);
            boolean sameField = MacroAction.TEXT.equals(last.type)
                    && TextUtils.equals(last.viewId, a.viewId)
                    && TextUtils.equals(last.packageName, a.packageName);
            if (sameField && System.currentTimeMillis() - last.recordedAt < 1400L) {
                a.delayMs = last.delayMs;
                recording.set(recording.size() - 1, a);
                updateOverlay("● J’APPRENDS · " + recording.size());
                lastRecordedAt = a.recordedAt;
                return;
            }
        }
        appendAction(a);
    }

    private void recordScroll(AccessibilityEvent event, AccessibilityNodeInfo node, String pkg) {
        long now = System.currentTimeMillis();
        if (now - lastScrollAt < 550L) return;
        int direction = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            int dy = event.getScrollDeltaY();
            if (dy > 0) direction = 1;
            else if (dy < 0) direction = -1;
        }
        if (direction == 0 && event.getFromIndex() >= 0 && event.getToIndex() >= 0) {
            direction = 1;
        }
        if (direction == 0) return;
        lastScrollAt = now;
        MacroAction a = baseAction(direction > 0 ? MacroAction.SCROLL_FORWARD : MacroAction.SCROLL_BACKWARD, node, pkg);
        appendAction(a);
    }

    private MacroAction baseAction(String type, AccessibilityNodeInfo node, String pkg) {
        MacroAction a = new MacroAction();
        a.type = type;
        a.packageName = pkg;
        if (node != null) {
            a.className = safe(node.getClassName());
            a.viewId = safe(node.getViewIdResourceName());
            a.text = safe(node.getText());
            a.contentDescription = safe(node.getContentDescription());
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            if (sw > 0 && sh > 0 && !r.isEmpty()) {
                a.normalizedX = ((r.left + r.right) / 2f) / sw;
                a.normalizedY = ((r.top + r.bottom) / 2f) / sh;
            }
        }
        long now = System.currentTimeMillis();
        a.delayMs = Math.max(120L, Math.min(8000L, now - lastRecordedAt));
        a.recordedAt = now;
        return a;
    }

    private void recordGlobal(String type) {
        MacroAction a = new MacroAction();
        a.type = type;
        a.recordedAt = System.currentTimeMillis();
        a.delayMs = Math.max(120L, Math.min(8000L, a.recordedAt - lastRecordedAt));
        appendAction(a);
    }

    private void appendAction(MacroAction a) {
        recording.add(a);
        lastRecordedAt = a.recordedAt;
        updateOverlay("● J’APPRENDS · " + recording.size());
    }

    private void scheduleNext(long token, long extraDelay) {
        if (!isReplaying || token != replayToken) return;
        if (replayIndex >= replayActions.size()) {
            isReplaying = false;
            hideOverlay();
            Toast.makeText(this, "Routine terminée.", Toast.LENGTH_LONG).show();
            return;
        }
        MacroAction a = replayActions.get(replayIndex);
        long delay = Math.max(100L, Math.min(8000L, a.delayMs)) + extraDelay;
        handler.postDelayed(() -> executeCurrent(token, 0), delay);
    }

    private void executeCurrent(long token, int waitAttempt) {
        if (!isReplaying || token != replayToken || replayIndex >= replayActions.size()) return;
        MacroAction a = replayActions.get(replayIndex);
        updateOverlay("▶ COPIE · " + (replayIndex + 1) + "/" + replayActions.size());

        if (MacroAction.GLOBAL_BACK.equals(a.type)) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            advance(token);
            return;
        }
        if (MacroAction.GLOBAL_HOME.equals(a.type)) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            advance(token);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        String currentPackage = root == null ? "" : safe(root.getPackageName());
        if (!TextUtils.isEmpty(a.packageName)
                && !TextUtils.isEmpty(currentPackage)
                && !a.packageName.equals(currentPackage)
                && waitAttempt < 12) {
            handler.postDelayed(() -> executeCurrent(token, waitAttempt + 1), 350L);
            return;
        }

        AccessibilityNodeInfo node = findBestNode(root, a);
        boolean done = performOnNode(node, a);
        if (!done && (MacroAction.CLICK.equals(a.type) || MacroAction.LONG_CLICK.equals(a.type))) {
            done = performCoordinateGesture(a);
        }

        if (!done && waitAttempt < 6) {
            handler.postDelayed(() -> executeCurrent(token, waitAttempt + 1), 300L);
            return;
        }
        advance(token);
    }

    private void advance(long token) {
        replayIndex++;
        scheduleNext(token, 70L);
    }

    private boolean performOnNode(AccessibilityNodeInfo node, MacroAction a) {
        if (node == null) return false;
        if (MacroAction.CLICK.equals(a.type)) {
            AccessibilityNodeInfo target = clickableAncestor(node, false);
            return target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        if (MacroAction.LONG_CLICK.equals(a.type)) {
            AccessibilityNodeInfo target = clickableAncestor(node, true);
            return target != null && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
        }
        if (MacroAction.TEXT.equals(a.type)) {
            if (node.isPassword()) return false;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.text);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        if (MacroAction.SCROLL_FORWARD.equals(a.type) || MacroAction.SCROLL_BACKWARD.equals(a.type)) {
            AccessibilityNodeInfo scrollable = scrollableAncestor(node);
            if (scrollable == null) scrollable = node;
            int action = MacroAction.SCROLL_FORWARD.equals(a.type)
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            return scrollable.performAction(action);
        }
        return false;
    }

    private AccessibilityNodeInfo findBestNode(AccessibilityNodeInfo root, MacroAction a) {
        if (root == null) return null;
        if (!TextUtils.isEmpty(a.viewId)) {
            try {
                List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(a.viewId);
                AccessibilityNodeInfo best = bestFromList(byId, a);
                if (best != null) return best;
            } catch (Exception ignored) {}
        }
        if (!TextUtils.isEmpty(a.text)) {
            try {
                List<AccessibilityNodeInfo> byText = root.findAccessibilityNodeInfosByText(a.text);
                AccessibilityNodeInfo best = bestFromList(byText, a);
                if (best != null) return best;
            } catch (Exception ignored) {}
        }
        return findRecursive(root, a, 0, new BestCandidate());
    }

    private AccessibilityNodeInfo bestFromList(List<AccessibilityNodeInfo> nodes, MacroAction a) {
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo best = null;
        double bestScore = Double.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || !n.isVisibleToUser()) continue;
            double score = nodeScore(n, a);
            if (score < bestScore) {
                best = n;
                bestScore = score;
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findRecursive(AccessibilityNodeInfo node, MacroAction a, int depth, BestCandidate best) {
        if (node == null || depth > 28) return best.node;
        if (node.isVisibleToUser()) {
            double score = nodeScore(node, a);
            if (score < best.score) {
                best.score = score;
                best.node = node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) findRecursive(child, a, depth + 1, best);
        }
        return best.node;
    }

    private double nodeScore(AccessibilityNodeInfo n, MacroAction a) {
        double score = 0d;
        String id = safe(n.getViewIdResourceName());
        String text = safe(n.getText());
        String desc = safe(n.getContentDescription());
        String cls = safe(n.getClassName());

        if (!TextUtils.isEmpty(a.viewId)) score += a.viewId.equals(id) ? -8d : 8d;
        if (!TextUtils.isEmpty(a.text)) score += a.text.equals(text) ? -5d : 4d;
        if (!TextUtils.isEmpty(a.contentDescription)) score += a.contentDescription.equals(desc) ? -4d : 3d;
        if (!TextUtils.isEmpty(a.className)) score += a.className.equals(cls) ? -2d : 1.5d;

        if (a.normalizedX >= 0f && a.normalizedY >= 0f) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            if (!r.isEmpty() && sw > 0 && sh > 0) {
                double nx = ((r.left + r.right) / 2d) / sw;
                double ny = ((r.top + r.bottom) / 2d) / sh;
                score += Math.hypot(nx - a.normalizedX, ny - a.normalizedY) * 6d;
            }
        }
        return score;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node, boolean longClick) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 7 && n != null; i++) {
            if (longClick ? n.isLongClickable() : n.isClickable()) return n;
            n = n.getParent();
        }
        return node;
    }

    private AccessibilityNodeInfo scrollableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 9 && n != null; i++) {
            if (n.isScrollable()) return n;
            n = n.getParent();
        }
        return null;
    }

    private boolean performCoordinateGesture(MacroAction a) {
        if (Build.VERSION.SDK_INT < 24 || a.normalizedX < 0f || a.normalizedY < 0f) return false;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        float x = Math.max(1f, Math.min(sw - 1f, a.normalizedX * sw));
        float y = Math.max(1f, Math.min(sh - 1f, a.normalizedY * sh));
        Path path = new Path();
        path.moveTo(x, y);
        long duration = MacroAction.LONG_CLICK.equals(a.type) ? 800L : 60L;
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGesture(b.build(), null, null);
    }

    private void showOverlay(boolean recordingMode) {
        hideOverlay();
        if (Build.VERSION.SDK_INT < 22) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(5), dp(8), dp(5));
        bar.setBackgroundColor(0xE6222A35);

        overlayLabel = new TextView(this);
        overlayLabel.setTextColor(0xFFFFFFFF);
        overlayLabel.setTextSize(12f);
        bar.addView(overlayLabel, new LinearLayout.LayoutParams(0, dp(42), 1f));

        if (recordingMode) {
            Button back = miniButton("←");
            back.setOnClickListener(v -> {
                recordGlobal(MacroAction.GLOBAL_BACK);
                performGlobalAction(GLOBAL_ACTION_BACK);
            });
            bar.addView(back);

            Button home = miniButton("⌂");
            home.setOnClickListener(v -> {
                recordGlobal(MacroAction.GLOBAL_HOME);
                performGlobalAction(GLOBAL_ACTION_HOME);
            });
            bar.addView(home);
        }

        Button stop = miniButton("STOP");
        stop.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else if (isReplaying) stopReplay();
        });
        bar.addView(stop);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(recordingMode ? 250 : 185),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = dp(8);
        p.y = dp(54);
        wm.addView(bar, p);
        overlay = bar;
    }

    private Button miniButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(7), 0, dp(7), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, dp(42));
        lp.setMargins(dp(3), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void updateOverlay(String text) {
        handler.post(() -> {
            if (overlayLabel != null) overlayLabel.setText(text);
        });
    }

    private void hideOverlay() {
        if (overlay == null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null) wm.removeView(overlay);
        } catch (Exception ignored) {}
        overlay = null;
        overlayLabel = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static final class BestCandidate {
        AccessibilityNodeInfo node;
        double score = Double.MAX_VALUE;
    }

    @Override
    public void onInterrupt() {
        stopAll("Interrompu");
    }
}
