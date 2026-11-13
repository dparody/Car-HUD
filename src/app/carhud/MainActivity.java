package app.carhud;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements LocationListener {
    private static final int ASK_LOCATION = 1;
    private static final int GREEN = 0xff62ff9a;
    private static final int MINT = 0xff9ed2b3;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService altitudeWorker = Executors.newSingleThreadExecutor();
    private volatile Geoid geoid;
    private Location waitingAltitude;
    private LocationManager gps;
    private Telemetry telemetry;
    private HudView hud;
    private MapView map;
    private LinearLayout root;
    private FrameLayout instrument;
    private FrameLayout mapPanel;
    private FrameLayout mapContent;
    private View mapSeparator;
    private android.widget.Switch mapSwitch;
    private boolean mapEnabled;
    private LinearLayout calibration;
    private Button go;
    private Button zero;
    private Button locationButton;
    private TextView instructions;
    private boolean locationStarted;
    private boolean active;
    private boolean running;
    private boolean requestedPermission;
    private boolean permitted;
    private boolean locationEnabled;
    private long nextLocationStatus;
    private int calibratedRotation;
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            render();
            ui.postDelayed(this, 40);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        gps = getSystemService(LocationManager.class);
        telemetry = new Telemetry(getSystemService(SensorManager.class));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        createLayout();
        immersive();
        altitudeWorker.execute(() -> {
            try {
                geoid = new Geoid(getApplicationContext());
                ui.post(() -> {
                    if (active && waitingAltitude != null) updateAltitude(waitingAltitude);
                    waitingAltitude = null;
                });
            } catch (IOException e) {
                android.util.Log.w("CarHUD", "Elevation grid unavailable", e);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        nextLocationStatus = 0;
        telemetry.start();
        if (running) startLocation();
        immersive();
        ui.post(frame);
    }

    @Override protected void onPause() {
        active = false;
        ui.removeCallbacks(frame);
        if (locationStarted) gps.removeUpdates(this);
        locationStarted = false;
        waitingAltitude = null;
        telemetry.stop();
        map.update(null, Double.NaN, "Waiting for a GNSS fix");
        super.onPause();
    }

    @Override protected void onDestroy() {
        map.close();
        altitudeWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyOrientation(configuration.orientation);
        immersive();
    }

    private void applyOrientation(int orientation) {
        boolean portrait = orientation != Configuration.ORIENTATION_LANDSCAPE;
        hud.setPortrait(portrait);
        root.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        instrument.setLayoutParams(new LinearLayout.LayoutParams(portrait ? -1 : 0, portrait ? 0 : -1, 2));
        mapPanel.setLayoutParams(new LinearLayout.LayoutParams(portrait ? -1 : 0, portrait ? 0 : -1, 1));
        mapSeparator.setLayoutParams(new FrameLayout.LayoutParams(portrait ? -1 : dp(1),
                portrait ? dp(1) : -1, Gravity.TOP | Gravity.LEFT));
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                bars.hide(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void createLayout() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff030d09);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.displayCutout());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });
        instrument = new FrameLayout(this);
        root.addView(instrument, new LinearLayout.LayoutParams(-1, 0, 2));
        hud = new HudView(this, telemetry);
        instrument.addView(hud, new FrameLayout.LayoutParams(-1, -1));
        zero = button("ZERO", 0xff102c20, GREEN);
        zero.setContentDescription("Recalibrate pitch and roll");
        FrameLayout.LayoutParams topRight = new FrameLayout.LayoutParams(dp(74), dp(40), Gravity.TOP | Gravity.RIGHT);
        topRight.setMargins(0, dp(3), dp(8), 0);
        instrument.addView(zero, topRight);
        zero.setOnClickListener(v -> {
            telemetry.resetPeaks();
            showCalibration();
        });

        calibration = new LinearLayout(this);
        calibration.setOrientation(LinearLayout.VERTICAL);
        calibration.setGravity(Gravity.CENTER);
        calibration.setPadding(dp(20), dp(12), dp(20), dp(12));
        calibration.setBackground(panel(0xff071b12, 0xff2a7350, 8));
        calibration.setElevation(dp(5));
        TextView title = text("ZERO PITCH & ROLL", 15, GREEN);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        calibration.addView(title);
        instructions = text("Park and secure your phone in its mount.", 13, MINT);
        LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(-2, -2);
        copy.topMargin = dp(6);
        copy.bottomMargin = dp(9);
        calibration.addView(instructions, copy);
        go = button("GO", GREEN, 0xff05170c);
        calibration.addView(go, new LinearLayout.LayoutParams(dp(100), dp(44)));
        instrument.addView(calibration, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        go.setOnClickListener(v -> {
            calibratedRotation = hud.getDisplay().getRotation();
            if (!telemetry.calibrate(calibratedRotation)) return;
            running = true;
            calibration.setVisibility(View.GONE);
            zero.setVisibility(View.VISIBLE);
            if (fineLocation()) startLocation();
            else if (!requestedPermission) requestLocation();
            render();
        });
        zero.setVisibility(View.GONE);

        mapPanel = new FrameLayout(this);
        mapPanel.setBackgroundColor(Color.BLACK);
        root.addView(mapPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        mapContent = new FrameLayout(this);
        mapPanel.addView(mapContent, new FrameLayout.LayoutParams(-1, -1));
        map = new MapView(this);
        mapContent.addView(map, new FrameLayout.LayoutParams(-1, -1));
        mapSeparator = new View(this);
        mapSeparator.setBackgroundColor(0xff47936b);
        mapContent.addView(mapSeparator, new FrameLayout.LayoutParams(-1, dp(1)));
        TextView attribution = text("© OpenStreetMap contributors", 11, Color.WHITE);
        attribution.setPadding(dp(6), dp(4), dp(6), dp(4));
        attribution.setBackgroundColor(0xe3142c24);
        FrameLayout.LayoutParams attributionPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
        mapContent.addView(attribution, attributionPosition);
        attribution.setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.openstreetmap.org/copyright"))));

        LinearLayout zoom = new LinearLayout(this);
        FrameLayout.LayoutParams zoomPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        zoomPosition.setMargins(dp(8), dp(7), 0, 0);
        mapContent.addView(zoom, zoomPosition);
        Button less = button("−", 0xff102c20, Color.WHITE);
        Button more = button("+", 0xff102c20, Color.WHITE);
        less.setContentDescription("Zoom out");
        more.setContentDescription("Zoom in");
        zoom.addView(less, new LinearLayout.LayoutParams(dp(42), dp(40)));
        LinearLayout.LayoutParams plusPosition = new LinearLayout.LayoutParams(dp(42), dp(40));
        plusPosition.leftMargin = dp(4);
        zoom.addView(more, plusPosition);
        less.setOnClickListener(v -> map.zoomBy(-1));
        more.setOnClickListener(v -> map.zoomBy(1));

        mapSwitch = new android.widget.Switch(this);
        mapSwitch.setText("MAP");
        mapSwitch.setTextSize(12);
        mapSwitch.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mapSwitch.setTextColor(GREEN);
        mapSwitch.setSwitchPadding(dp(6));
        mapSwitch.setPadding(dp(8), 0, dp(8), 0);
        mapSwitch.setBackground(panel(0xff102c20, 0xff33654d, 6));
        mapSwitch.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{GREEN, 0xff829389}));
        FrameLayout.LayoutParams togglePosition = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.RIGHT);
        togglePosition.setMargins(0, dp(7), dp(8), 0);
        mapPanel.addView(mapSwitch, togglePosition);
        mapEnabled = getPreferences(MODE_PRIVATE).getBoolean("mapEnabled", true);
        mapSwitch.setChecked(mapEnabled);
        setMapEnabled(mapEnabled);
        mapSwitch.setOnCheckedChangeListener((view, enabled) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("mapEnabled", enabled).apply();
            setMapEnabled(enabled);
            render();
        });

        locationButton = button("ENABLE LOCATION", 0xff16392a, GREEN);
        FrameLayout.LayoutParams access = new FrameLayout.LayoutParams(-2, dp(40), Gravity.BOTTOM | Gravity.LEFT);
        access.setMargins(dp(8), 0, 0, dp(6));
        instrument.addView(locationButton, access);
        locationButton.setVisibility(View.GONE);
        locationButton.setOnClickListener(v -> {
            if (fineLocation()) {
                open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } else if (!requestedPermission || shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                requestLocation();
            } else {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        });
        setContentView(root);
        applyOrientation(getResources().getConfiguration().orientation);
    }

    private void setMapEnabled(boolean enabled) {
        mapEnabled = enabled;
        map.setEnabled(enabled);
        mapContent.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void showCalibration() {
        telemetry.suspendAttitude();
        zero.setVisibility(View.GONE);
        calibration.setVisibility(View.VISIBLE);
    }

    private void render() {
        if (running && calibration.getVisibility() != View.VISIBLE && hud.getDisplay() != null
                && hud.getDisplay().getRotation() != calibratedRotation) {
            showCalibration();
        }
        boolean calibrating = calibration.getVisibility() == View.VISIBLE;
        if (calibrating) {
            boolean ready = telemetry.ready();
            go.setEnabled(ready);
            go.setAlpha(ready ? 1 : 0.45f);
            String instruction = ready ? "Park and secure your phone in its mount." : "Waiting for motion sensors…";
            if (!instruction.contentEquals(instructions.getText())) instructions.setText(instruction);
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= nextLocationStatus) {
            permitted = fineLocation();
            locationEnabled = gps.isProviderEnabled(LocationManager.GPS_PROVIDER);
            nextLocationStatus = now + 500;
        }
        boolean needsLocation = running && (!permitted || !locationEnabled);
        int visibility = needsLocation ? View.VISIBLE : View.GONE;
        if (locationButton.getVisibility() != visibility) locationButton.setVisibility(visibility);
        String action = permitted ? "TURN ON LOCATION" : "ALLOW PRECISE LOCATION";
        if (needsLocation && !action.contentEquals(locationButton.getText())) locationButton.setText(action);
        Location fix = running && !needsLocation ? telemetry.fix() : null;
        String state = !running ? "CALIBRATE" : !permitted ? "LOCATION PERMISSION"
                : !locationEnabled ? "LOCATION OFF" : fix == null ? "WAITING FOR GNSS" : "GNSS FIX";
        hud.update(running && !calibrating, running && !needsLocation, state);
        if (mapEnabled) {
            map.update(fix, fix == null ? Double.NaN : telemetry.mapCourse(fix),
                    needsLocation ? "Location unavailable" : running ? "Waiting for a GNSS fix" : "Tap GO to start");
        }
    }

    private void requestLocation() {
        requestedPermission = true;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_LOCATION);
    }

    private boolean fineLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocation() {
        if (locationStarted || !fineLocation() || !active) return;
        try {
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0, this, Looper.getMainLooper());
            locationStarted = true;
        } catch (IllegalArgumentException | SecurityException ignored) {
            // The permission or provider can change while the activity is open.
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == ASK_LOCATION) {
            nextLocationStatus = 0;
            startLocation();
            immersive();
        }
    }

    @Override public void onLocationChanged(Location fix) {
        if (!active || !Double.isFinite(fix.getLatitude()) || !Double.isFinite(fix.getLongitude())
                || Math.abs(fix.getLatitude()) > 90 || Math.abs(fix.getLongitude()) > 180) return;
        Location previous = telemetry.fix();
        if (previous != null && fix.getElapsedRealtimeNanos() < previous.getElapsedRealtimeNanos()) return;
        telemetry.setLocation(fix);
        updateAltitude(fix);
    }

    private void updateAltitude(Location fix) {
        double altitude;
        if (Build.VERSION.SDK_INT >= 34 && fix.hasMslAltitude()) {
            altitude = fix.getMslAltitudeMeters();
        } else if (fix.hasAltitude() && geoid != null) {
            altitude = fix.getAltitude() - geoid.offset(fix.getLatitude(), fix.getLongitude());
        } else {
            waitingAltitude = fix;
            return;
        }
        telemetry.setMslAltitude(altitude, fix.getElapsedRealtimeNanos() / 1_000_000L);
    }

    @Override public void onProviderDisabled(String provider) {
        telemetry.setLocation(null);
        nextLocationStatus = 0;
    }
    @Override public void onProviderEnabled(String provider) { nextLocationStatus = 0; }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException ignored) {
            android.widget.Toast.makeText(this, "No app can open this link.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private Button button(String value, int color, int textColor) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(textColor);
        button.setTextSize(13);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setLetterSpacing(0.03f);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x388ffff1), panel(color, 0xff33654d, 6), null));
        return button;
    }

    private GradientDrawable panel(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setStroke(dp(1), stroke);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
