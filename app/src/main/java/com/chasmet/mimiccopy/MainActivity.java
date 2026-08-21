package com.chasmet.mimiccopy;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private TextView txtServiceStatus;
    private TextView txtRoutineStatus;
    private EditText editRoutineName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtServiceStatus = findViewById(R.id.txtServiceStatus);
        txtRoutineStatus = findViewById(R.id.txtRoutineStatus);
        editRoutineName = findViewById(R.id.editRoutineName);

        Button btnAccessibility = findViewById(R.id.btnAccessibility);
        Button btnRecord = findViewById(R.id.btnRecord);
        Button btnReplay = findViewById(R.id.btnReplay);
        Button btnClear = findViewById(R.id.btnClear);

        btnAccessibility.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        btnRecord.setOnClickListener(v -> {
            MimicAccessibilityService service = MimicAccessibilityService.getInstance();
            if (service == null || !isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Active d’abord ‘Mime & Copie’ dans Accessibilité.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            String name = editRoutineName.getText().toString().trim();
            if (name.isEmpty()) name = "Ma routine";
            service.startRecording(name);
            Toast.makeText(this, "Enregistrement lancé. Montre maintenant l’action.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        });

        btnReplay.setOnClickListener(v -> {
            MimicAccessibilityService service = MimicAccessibilityService.getInstance();
            List<MacroAction> actions = RoutineStore.load(this);
            if (actions.isEmpty()) {
                Toast.makeText(this, "Aucune routine à rejouer.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (service == null || !isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Active d’abord le service d’accessibilité.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            service.startReplay();
            Toast.makeText(this, "Rejeu lancé. Ne touche pas l’écran sauf pour STOP.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        });

        btnClear.setOnClickListener(v -> {
            RoutineStore.clear(this);
            refreshStatus();
            Toast.makeText(this, "Routine effacée.", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        txtServiceStatus.setText(enabled
                ? "Service d’accessibilité : ACTIVÉ"
                : "Service d’accessibilité : DÉSACTIVÉ");
        txtServiceStatus.setTextColor(getResources().getColor(enabled ? R.color.accent : R.color.danger));

        List<MacroAction> actions = RoutineStore.load(this);
        if (actions.isEmpty()) {
            txtRoutineStatus.setText("Aucune routine enregistrée");
        } else {
            txtRoutineStatus.setText(RoutineStore.getName(this) + " · " + actions.size() + " actions");
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String target = getPackageName() + "/" + MimicAccessibilityService.class.getName();
        for (AccessibilityServiceInfo info : services) {
            String id = info.getId();
            if (id != null && (id.equalsIgnoreCase(target) || id.contains(getPackageName()))) {
                return true;
            }
        }
        return false;
    }
}
