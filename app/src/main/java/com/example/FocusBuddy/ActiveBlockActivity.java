package com.example.FocusBuddy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.moduleinstall.ModuleInstall;
import com.google.android.gms.common.moduleinstall.ModuleInstallClient;
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Screen 4: shown while a block is running (countdown / release time + the QR scan button). */
public class ActiveBlockActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!routeIfNeeded()) {
                render();
                ui.postDelayed(this, 1000);
            }
        }
    };

    private TextView tvStatus, tvCountdown;
    private String blockedSummary = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active_block);

        tvStatus = findViewById(R.id.tvStatus);
        tvCountdown = findViewById(R.id.tvCountdown);
        findViewById(R.id.btnScan).setOnClickListener(v -> scanToUnlock());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The blocklist can't change during a session, so this only needs refreshing here
        blockedSummary = TextUtils.join(", ", BlocklistStore.labels(this));
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    /** Once the block ends, hand back to the options screen. Returns true if we left. */
    private boolean routeIfNeeded() {
        if (!SessionStore.isActive(this)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return true;
        }
        return false;
    }

    private void render() {
        tvStatus.setText("Session active. Blocked: " + blockedSummary);
        long release = SessionStore.scheduledReleaseMs(this);
        if (SessionStore.isUntilScan(this)) {
            // No timer: say how it ends instead of counting down
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
            tvCountdown.setText("Blocked until you scan the QR code");
        } else if (release > 0L) {
            // Scheduled block: say when it lifts rather than counting down
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
            tvCountdown.setText("Blocked until\n" + formatRelease(release));
        } else {
            tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
            long s = SessionStore.remainingMs(this) / 1000;
            tvCountdown.setText(String.format(Locale.US, "%02d:%02d:%02d",
                    s / 3600, (s % 3600) / 60, s % 60));
        }
    }

    /** "12:00 PM" if it's today, otherwise with the weekday ("Tue 2:00 AM"). */
    private String formatRelease(long releaseMs) {
        Date release = new Date(releaseMs);
        String time = DateFormat.getTimeFormat(this).format(release);
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTime(release);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        return today ? time : DateFormat.format("EEE", release) + " " + time;
    }

    // ---------- Ending a session with the in-app scanner ----------
    // (Scanning the printed QR with any camera app also works; it fires the same focusbuddy:// intent.)

    private void scanToUnlock() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);

        // The scanner's UI lives in a Google Play services module that may not be on the phone yet.
        // Make sure it's there (downloading it once if needed) before trying to open it.
        ModuleInstallClient installer = ModuleInstall.getClient(this);
        installer.areModulesAvailable(scanner)
                .addOnSuccessListener(result -> {
                    if (result.areModulesAvailable()) {
                        launchScanner(scanner);
                    } else {
                        Toast.makeText(this, "Downloading the scanner (one time only)...",
                                Toast.LENGTH_LONG).show();
                        installer.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                                .addOnSuccessListener(r -> launchScanner(scanner))
                                .addOnFailureListener(this::scannerUnavailable);
                    }
                })
                .addOnFailureListener(this::scannerUnavailable);
    }

    private void launchScanner(GmsBarcodeScanner scanner) {
        scanner.startScan()
                .addOnSuccessListener(barcode -> {
                    boolean ok = SessionStore.unlockFromLink(this, barcode.getRawValue());
                    Toast.makeText(this, ok ? "Session ended" : "Wrong QR code",
                            Toast.LENGTH_LONG).show();
                    routeIfNeeded();
                })
                .addOnFailureListener(this::scannerUnavailable);
    }

    /** Shows why the built-in scanner couldn't open, and offers the phone's camera app instead. */
    private void scannerUnavailable(Exception e) {
        new AlertDialog.Builder(this)
                .setTitle("Couldn't open the built-in scanner")
                .setMessage(e.getMessage() + "\n\nYou can scan the QR code with your phone's camera app instead.")
                .setPositiveButton("Open camera", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                    } catch (ActivityNotFoundException ex) {
                        Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
