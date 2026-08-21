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
    private String lastSignature = "";
    private long lastSignatureAt = 0L;
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
        stopAll();
        instance = null;
        super.onDestroy();
    }

    public void startRecording(String name) {
        stopReplayInternal();
        recording.clear();
        recordingName = TextUtils.isEmpty(name) ? "Ma routine" : name;
        lastRecordedAt = System.currentTimeMillis();
        lastSignature = "";
        lastSignatureAt = 0L;
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

    private void abortReplay(String message) {
        isReplaying = false;
        replayToken++;
        hideOverlay();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void stopReplayInternal() {
        isReplaying = false;
        replayToken++;
        replayActions.clear();
        replayIndex = 0;
    }

    private void stopAll() {
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

        // V2 : on ne mémorise PLUS TYPE_VIEW_SCROLLED.
        // TikTok, Instagram et d'autres applis émettent des scrolls/animations internes
        // sans geste de l'utilisateur, ce qui polluait la routine.
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            recordNodeAction(MacroAction.CLICK, source, pkg);
        } else if (type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            recordNodeAction(MacroAction.LONG_CLICK, source, pkg);
        } else if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (source != null && !source.isPassword()) {
                recordTextAction(source, pkg);
            }
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
        if (isDuplicate(a)) return;
        appendAction(a);
    }

    private boolean isDuplicate(MacroAction a) {
        long now = System.currentTimeMillis();
        int px = Math.round(a.normalizedX * 20f);
        int py = Math.round(a.normalizedY * 20f);
        String sig = a.type + "|" + a.packageName + "|" + a.viewId + "|" + a.text + "|" + a.contentDescription + "|" + px + "|" + py;
        boolean duplicate = sig.equals(lastSignature) && now - lastSignatureAt < 380L;
        if (!duplicate) {
            lastSignature = sig;
            lastSignatureAt = now;
        }
        return duplicate;
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
                    && TextUtils.equals(last.packageName, a.packageName)
                    && distance(last.normalizedX, last.normalizedY, a.normalizedX, a.normalizedY) < 0.08d;
            if (sameField && System.currentTimeMillis() - last.recordedAt < 1800L) {
                a.delayMs = last.delayMs;
                recording.set(recording.size() - 1, a);
                updateOverlay("● J’APPRENDS · " + recording.size());
                lastRecordedAt = a.recordedAt;
                return;
            }
        }
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

    private void recordSwipe(String type) {
        if (!isRecording) return;
        MacroAction a = new MacroAction();
        a.type = type;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        a.packageName = root == null ? "" : safe(root.getPackageName());
        a.recordedAt = System.currentTimeMillis();
        a.delayMs = Math.max(120L, Math.min(8000L, a.recordedAt - lastRecordedAt));
        appendAction(a);
        performSwipe(type, null);
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
                && !a.packageName.equals(currentPackage)) {
            if (waitAttempt < 18) {
                handler.postDelayed(() -> executeCurrent(token, waitAttempt + 1), 350L);
            } else {
                abortReplay("Rejeu arrêté : l’application attendue n’est pas à l’écran.");
            }
            return;
        }

        if (MacroAction.SWIPE_UP.equals(a.type) || MacroAction.SWIPE_DOWN.equals(a.type)) {
            if (performSwipe(a.type, () -> advance(token))) return;
            abortReplay("Rejeu arrêté : impossible d’effectuer le défilement.");
            return;
        }

        AccessibilityNodeInfo node = findStableNode(root, a);
        boolean done = performOnNode(node, a);

        if (!done && (MacroAction.CLICK.equals(a.type) || MacroAction.LONG_CLICK.equals(a.type))) {
            done = performCoordinateGesture(a, () -> advance(token));
            if (done) return;
        }

        if (done) {
            advance(token);
            return;
        }

        if (waitAttempt < 8) {
            handler.postDelayed(() -> executeCurrent(token, waitAttempt + 1), 280L);
            return;
        }

        abortReplay("Rejeu arrêté à l’étape " + (replayIndex + 1) + " : cible introuvable.");
    }

    private void advance(long token) {
        replayIndex++;
        scheduleNext(token, 90L);
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
        return false;
    }

    private AccessibilityNodeInfo findStableNode(AccessibilityNodeInfo root, MacroAction a) {
        if (root == null) return null;

        if (!TextUtils.isEmpty(a.viewId)) {
            try {
                List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(a.viewId);
                AccessibilityNodeInfo best = bestExact(byId, a);
                if (best != null) return best;
            } catch (Exception ignored) {}
        }

        if (!TextUtils.isEmpty(a.text)) {
            try {
                List<AccessibilityNodeInfo> byText = root.findAccessibilityNodeInfosByText(a.text);
                AccessibilityNodeInfo best = bestExact(byText, a);
                if (best != null) return best;
            } catch (Exception ignored) {}
        }

        if (!TextUtils.isEmpty(a.contentDescription)) {
            AccessibilityNodeInfo byDescription = findExactDescription(root, a, 0, null, Double.MAX_VALUE);
            if (byDescription != null) return byDescription;
        }

        if (MacroAction.TEXT.equals(a.type)) {
            Candidate c = findTextCandidate(root, a, 0, new Candidate());
            if (c.node != null && c.score < 0.22d) return c.node;
        }

        return null;
    }

    private AccessibilityNodeInfo bestExact(List<AccessibilityNodeInfo> nodes, MacroAction a) {
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || !n.isVisibleToUser()) continue;
            String id = safe(n.getViewIdResourceName());
            String text = safe(n.getText());
            String desc = safe(n.getContentDescription());
            boolean exact = (!TextUtils.isEmpty(a.viewId) && a.viewId.equals(id))
                    || (!TextUtils.isEmpty(a.text) && a.text.equals(text))
                    || (!TextUtils.isEmpty(a.contentDescription) && a.contentDescription.equals(desc));
            if (!exact) continue;
            double d = nodeDistance(n, a);
            if (d < bestDistance) {
                bestDistance = d;
                best = n;
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findExactDescription(AccessibilityNodeInfo node, MacroAction a, int depth,
                                                       AccessibilityNodeInfo best, double bestDistance) {
        if (node == null || depth > 28) return best;
        if (node.isVisibleToUser() && a.contentDescription.equals(safe(node.getContentDescription()))) {
            double d = nodeDistance(node, a);
            if (d < bestDistance) {
                best = node;
                bestDistance = d;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo candidate = findExactDescription(child, a, depth + 1, best, bestDistance);
            if (candidate != null) {
                double d = nodeDistance(candidate, a);
                if (best == null || d < nodeDistance(best, a)) best = candidate;
            }
        }
        return best;
    }

    private Candidate findTextCandidate(AccessibilityNodeInfo node, MacroAction a, int depth, Candidate best) {
        if (node == null || depth > 28) return best;
        if (node.isVisibleToUser()) {
            String cls = safe(node.getClassName());
            if (TextUtils.isEmpty(a.className) || a.className.equals(cls)) {
                double d = nodeDistance(node, a);
                if (d < best.score) {
                    best.score = d;
                    best.node = node;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) findTextCandidate(child, a, depth + 1, best);
        }
        return best;
    }

    private double nodeDistance(AccessibilityNodeInfo n, MacroAction a) {
        if (n == null || a.normalizedX < 0f || a.normalizedY < 0f) return 0d;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (r.isEmpty() || sw <= 0 || sh <= 0) return Double.MAX_VALUE;
        double nx = ((r.left + r.right) / 2d) / sw;
        double ny = ((r.top + r.bottom) / 2d) / sh;
        return distance((float) nx, (float) ny, a.normalizedX, a.normalizedY);
    }

    private static double distance(float x1, float y1, float x2, float y2) {
        if (x1 < 0f || y1 < 0f || x2 < 0f || y2 < 0f) return Double.MAX_VALUE;
        return Math.hypot(x1 - x2, y1 - y2);
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node, boolean longClick) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 7 && n != null; i++) {
            if (longClick ? n.isLongClickable() : n.isClickable()) return n;
            n = n.getParent();
        }
        return null;
    }

    private boolean performCoordinateGesture(MacroAction a, Runnable onComplete) {
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
        return dispatchGesture(b.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (onComplete != null) onComplete.run();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (isReplaying) abortReplay("Rejeu arrêté : geste annulé.");
            }
        }, null);
    }

    private boolean performSwipe(String type, Runnable onComplete) {
        if (Build.VERSION.SDK_INT < 24) return false;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        float x = sw * 0.5f;
        float top = sh * 0.28f;
        float bottom = sh * 0.76f;
        Path path = new Path();
        if (MacroAction.SWIPE_UP.equals(type)) {
            path.moveTo(x, bottom);
            path.lineTo(x, top);
        } else {
            path.moveTo(x, top);
            path.lineTo(x, bottom);
        }
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, 280L));
        return dispatchGesture(b.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                if (onComplete != null) onComplete.run();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                if (isReplaying) abortReplay("Rejeu arrêté : défilement annulé.");
            }
        }, null);
    }

    private void showOverlay(boolean recordingMode) {
        hideOverlay();
        if (Build.VERSION.SDK_INT < 22) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.setBackgroundColor(0xEE222A35);

        overlayLabel = new TextView(this);
        overlayLabel.setTextColor(0xFFFFFFFF);
        overlayLabel.setTextSize(11f);
        overlayLabel.setSingleLine(true);
        bar.addView(overlayLabel, new LinearLayout.LayoutParams(0, dp(40), 1f));

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

            Button down = miniButton("↓");
            down.setOnClickListener(v -> recordSwipe(MacroAction.SWIPE_UP));
            bar.addView(down);

            Button up = miniButton("↑");
            up.setOnClickListener(v -> recordSwipe(MacroAction.SWIPE_DOWN));
            bar.addView(up);
        }

        Button stop = miniButton("STOP");
        stop.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else if (isReplaying) stopReplay();
        });
        bar.addView(stop);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(recordingMode ? 320 : 190),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = dp(5);
        p.y = dp(48);
        wm.addView(bar, p);
        overlay = bar;
    }

    private Button miniButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(9f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(5), 0, dp(5), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(2), 0, 0, 0);
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

    private static final class Candidate {
        AccessibilityNodeInfo node;
        double score = Double.MAX_VALUE;
    }

    @Override
    public void onInterrupt() {
        stopAll();
    }
}
