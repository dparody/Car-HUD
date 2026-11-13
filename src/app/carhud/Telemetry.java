package app.carhud;

import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.SystemClock;
import android.view.Surface;

final class Telemetry implements SensorEventListener {
    private static final long FIX_TIMEOUT_MS = 6000;
    private static final float MOVING_MPS = 1.4f;
    private final SensorManager sensors;
    private final float[] up = new float[3];
    private final float[] referenceUp = new float[3];
    private final float[] right = new float[3];
    private final float[] forward = new float[3];
    private final float[] raw = new float[3];
    private final float[] magnetic = new float[3];
    private final float[] rotation = new float[9];
    private final float[] motionRotation = new float[9];
    private final boolean gravitySensor;
    private final boolean linearSensor;
    private final boolean attitudeSensor;
    private boolean hasUp;
    private boolean hasMagnetic;
    private boolean hasRotation;
    private boolean hasAcceleration;
    private boolean calibrated;
    private int calibratedRotation;
    private float pitch;
    private float roll;
    private float acceleration;
    private float lateralAcceleration;
    private float pressure = Float.NaN;
    private float pressureAtAnchor = Float.NaN;
    private double altitudeAnchor = Double.NaN;
    private long pressureTime;
    private Location location;
    private double lastCourse = Double.NaN;
    private Location declinationFix;
    private float declination;
    private long altitudeTime;
    private boolean recordingPeaks;
    private double peakSpeed = Double.NEGATIVE_INFINITY;
    private double peakAltitude = Double.NEGATIVE_INFINITY;
    private double peakPitch = Double.NEGATIVE_INFINITY;
    private double minimumAltitude = Double.POSITIVE_INFINITY;
    private double minimumPitch;
    private double peakLeftG;
    private double peakRightG;

    Telemetry(SensorManager sensors) {
        this.sensors = sensors;
        gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) != null;
        linearSensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null;
        attitudeSensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null;
    }

    void start() {
        if (attitudeSensor) listen(Sensor.TYPE_GAME_ROTATION_VECTOR);
        else if (gravitySensor) listen(Sensor.TYPE_GRAVITY);
        if (linearSensor) listen(Sensor.TYPE_LINEAR_ACCELERATION);
        if (!linearSensor || (!gravitySensor && !attitudeSensor)) listen(Sensor.TYPE_ACCELEROMETER);
        if (!listen(Sensor.TYPE_ROTATION_VECTOR)) listen(Sensor.TYPE_MAGNETIC_FIELD);
        listen(Sensor.TYPE_PRESSURE);
    }

    private boolean listen(int type) {
        Sensor sensor = sensors.getDefaultSensor(type);
        return sensor != null && sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        sensors.unregisterListener(this);
        hasUp = false;
        hasRotation = false;
        hasMagnetic = false;
        hasAcceleration = false;
        location = null;
        pressure = Float.NaN;
        pressureAtAnchor = Float.NaN;
        altitudeAnchor = Double.NaN;
    }

    boolean ready() {
        return hasUp;
    }

    boolean calibrate(int displayRotation) {
        if (!hasUp) return false;
        System.arraycopy(up, 0, referenceUp, 0, 3);
        // The view into the display defines forward on an upright mount.
        // On a flat mount, use the top edge of the display.
        float[] ahead = {0, 0, -1};
        if (Math.abs(up[2]) >= 0.85f) {
            ahead[2] = 0;
            switch (displayRotation) {
                case Surface.ROTATION_90: ahead[0] = -1; break;
                case Surface.ROTATION_180: ahead[1] = -1; break;
                case Surface.ROTATION_270: ahead[0] = 1; break;
                default: ahead[1] = 1; break;
            }
        }
        float vertical = dot(ahead, up);
        for (int i = 0; i < 3; i++) forward[i] = ahead[i] - vertical * up[i];
        normalize(forward);
        right[0] = forward[1] * up[2] - forward[2] * up[1];
        right[1] = forward[2] * up[0] - forward[0] * up[2];
        right[2] = forward[0] * up[1] - forward[1] * up[0];
        calibratedRotation = displayRotation;
        calibrated = true;
        pitch = roll = 0;
        hasAcceleration = false;
        recordingPeaks = true;
        peakPitch = Math.max(peakPitch, pitch);
        return true;
    }

    void suspendAttitude() {
        calibrated = false;
    }

    void resetPeaks() {
        recordingPeaks = false;
        peakSpeed = peakAltitude = peakPitch = Double.NEGATIVE_INFINITY;
        minimumAltitude = Double.POSITIVE_INFINITY;
        minimumPitch = 0;
        peakLeftG = peakRightG = 0;
    }

    double peakSpeed() { return peakSpeed; }
    double peakAltitude() { return peakAltitude; }
    double peakPitch() { return peakPitch; }
    double minimumAltitude() { return minimumAltitude; }
    double minimumPitch() { return minimumPitch; }
    double peakLeftG() { return peakLeftG; }
    double peakRightG() { return peakRightG; }

    private static double higherPeak(double peak, double value) {
        return Double.isFinite(value) ? Math.max(peak, value) : peak;
    }

    void setLocation(Location fix) {
        location = fix;
        if (hasCourse()) lastCourse = fix.getBearing();
        if (recordingPeaks) peakSpeed = higherPeak(peakSpeed, speed());
    }

    Location fix() {
        if (location == null) return null;
        long age = SystemClock.elapsedRealtime() - location.getElapsedRealtimeNanos() / 1_000_000L;
        return age <= FIX_TIMEOUT_MS ? location : null;
    }

    double speed() {
        Location fix = fix();
        if (fix == null || !fix.hasSpeed()) return Double.NaN;
        return Math.max(0, fix.getSpeed() * 3.6);
    }

    boolean hasCourse() {
        Location fix = fix();
        return fix != null && fix.hasSpeed() && fix.getSpeed() >= MOVING_MPS && fix.hasBearing()
                && (!fix.hasBearingAccuracy() || fix.getBearingAccuracyDegrees() <= 35);
    }

    double heading(boolean useLocation) {
        if (useLocation && hasCourse()) return fix().getBearing();
        if ((useLocation && speed() >= MOVING_MPS * 3.6) || !calibrated) return Double.NaN;
        return magneticHeading();
    }

    double mapCourse(Location fix) {
        if (Double.isFinite(lastCourse)) return lastCourse;
        if (!calibrated) return Double.NaN;
        double magneticHeading = magneticHeading();
        if (!Double.isFinite(magneticHeading)) return Double.NaN;
        if (declinationFix != fix) {
            GeomagneticField field = new GeomagneticField((float) fix.getLatitude(), (float) fix.getLongitude(),
                    fix.hasAltitude() ? (float) fix.getAltitude() : 0, fix.getTime());
            declination = field.getDeclination();
            declinationFix = fix;
        }
        return (magneticHeading + declination + 360) % 360;
    }

    private double magneticHeading() {
        if (!hasRotation && (!hasMagnetic || !hasUp ||
                !SensorManager.getRotationMatrix(rotation, null, up, magnetic))) return Double.NaN;
        double east = rotation[0] * forward[0] + rotation[1] * forward[1] + rotation[2] * forward[2];
        double north = rotation[3] * forward[0] + rotation[4] * forward[1] + rotation[5] * forward[2];
        return Math.hypot(east, north) > 0.25 ? (Math.toDegrees(Math.atan2(east, north)) + 360) % 360
                : Double.NaN;
    }

    void setMslAltitude(double meters, long fixTime) {
        if (!Double.isFinite(meters) || fixTime <= altitudeTime) return;
        double estimate = pressureAdjustedAltitude();
        long interval = fixTime - altitudeTime;
        altitudeTime = fixTime;
        if (pressureFresh()) {
            altitudeAnchor = Double.isFinite(estimate) && interval < FIX_TIMEOUT_MS
                    ? estimate + 0.12 * (meters - estimate) : meters;
            pressureAtAnchor = pressure;
        } else {
            altitudeAnchor = meters;
            pressureAtAnchor = Float.NaN;
        }
        recordAltitudeExtrema();
    }

    private void recordAltitudeExtrema() {
        if (!recordingPeaks) return;
        double value = altitude();
        if (!Double.isFinite(value)) return;
        peakAltitude = Math.max(peakAltitude, value);
        minimumAltitude = Math.min(minimumAltitude, value);
    }

    private boolean pressureFresh() {
        return Float.isFinite(pressure) && SystemClock.elapsedRealtime() - pressureTime < 3000;
    }

    private double pressureAdjustedAltitude() {
        if (!pressureFresh() || !Float.isFinite(pressureAtAnchor)) return Double.NaN;
        return altitudeAnchor + SensorManager.getAltitude(pressureAtAnchor, pressure);
    }

    double altitude() {
        long age = SystemClock.elapsedRealtime() - altitudeTime;
        double estimate = pressureAdjustedAltitude();
        if (Double.isFinite(estimate) && age <= 30_000) return estimate;
        return age <= FIX_TIMEOUT_MS ? altitudeAnchor : Double.NaN;
    }

    boolean usingPressure() {
        return Double.isFinite(pressureAdjustedAltitude()) && Double.isFinite(altitude());
    }

    boolean hasAttitude(int displayRotation) {
        return hasUp && calibrated && calibratedRotation == displayRotation;
    }

    double pitch() { return pitch; }
    double roll() { return roll; }
    double acceleration() { return hasAcceleration && calibrated ? acceleration : Double.NaN; }
    double lateralG() {
        return hasAcceleration && calibrated ? lateralAcceleration / SensorManager.GRAVITY_EARTH : Double.NaN;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                SensorManager.getRotationMatrixFromVector(motionRotation, event.values);
                updateUp(motionRotation[6], motionRotation[7], motionRotation[8], 1);
                break;
            case Sensor.TYPE_GRAVITY:
                updateUp(event.values[0], event.values[1], event.values[2], 1);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, raw, 0, 3);
                if (!gravitySensor && !attitudeSensor) updateUp(raw[0], raw[1], raw[2], 0.06f);
                if (!linearSensor && hasUp) {
                    updateAcceleration(raw, SensorManager.GRAVITY_EARTH);
                }
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                updateAcceleration(event.values, 0);
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                SensorManager.getRotationMatrixFromVector(rotation, event.values);
                hasRotation = event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, magnetic, 0, 3);
                hasMagnetic = event.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE;
                break;
            case Sensor.TYPE_PRESSURE:
                float value = event.values[0];
                if (Float.isFinite(value) && value > 0) {
                    pressure = pressureFresh() ? pressure + 0.18f * (value - pressure) : value;
                    pressureTime = SystemClock.elapsedRealtime();
                    recordAltitudeExtrema();
                }
                break;
            default:
                break;
        }
    }

    private void updateAcceleration(float[] sample, float gravity) {
        if (!calibrated) return;
        float longitudinal = dot(sample, forward) - gravity * dot(up, forward);
        float lateral = dot(sample, right) - gravity * dot(up, right);
        acceleration = hasAcceleration ? acceleration + 0.16f * (longitudinal - acceleration) : longitudinal;
        lateralAcceleration = hasAcceleration ? lateralAcceleration + 0.16f * (lateral - lateralAcceleration) : lateral;
        hasAcceleration = true;
        if (recordingPeaks) {
            double g = lateralG();
            peakLeftG = higherPeak(peakLeftG, -g);
            peakRightG = higherPeak(peakRightG, g);
        }
    }

    private void updateUp(float x, float y, float z, float weight) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(length > 0.1f)) return;
        float gain = hasUp ? weight : 1;
        up[0] += gain * (x / length - up[0]);
        up[1] += gain * (y / length - up[1]);
        up[2] += gain * (z / length - up[2]);
        normalize(up);
        hasUp = true;
        if (calibrated) {
            double nextPitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dot(up, forward)))));
            double nextRoll = Math.toDegrees(Math.atan2(-dot(up, right), dot(up, referenceUp)));
            pitch += 0.22f * (nextPitch - pitch);
            if (recordingPeaks) {
                peakPitch = higherPeak(peakPitch, pitch);
                minimumPitch = Math.min(minimumPitch, pitch);
            }
            roll += 0.22f * (((nextRoll - roll + 540) % 360) - 180);
            roll = (roll + 540) % 360 - 180;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE) return;
        if (sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) hasRotation = false;
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) hasMagnetic = false;
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static void normalize(float[] vector) {
        float length = (float) Math.sqrt(dot(vector, vector));
        for (int i = 0; i < 3; i++) vector[i] /= length;
    }
}
