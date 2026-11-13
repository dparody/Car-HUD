package app.carhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

final class HudView extends View {
    private static final int BACK = Color.rgb(3, 13, 9);
    private static final int GREEN = Color.rgb(98, 255, 154);
    private static final int SOFT = Color.rgb(86, 178, 127);
    private static final int GRID = Color.rgb(24, 77, 52);
    private static final int PEAK = Color.rgb(255, 71, 79);
    private static final int MINIMUM = Color.rgb(74, 157, 255);
    private static final int[] SIDES = {-1, 1};
    private static final String[] CARDINAL = {"N", "E", "S", "W"};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Telemetry telemetry;
    private float displaySpeed = Float.NaN;
    private float displayAltitude = Float.NaN;
    private float displayHeading = Float.NaN;
    private boolean wasCourse;
    private boolean running;
    private boolean locationEnabled;
    private boolean portrait;
    private String status = "WAITING FOR GNSS";

    HudView(Context context, Telemetry telemetry) {
        super(context);
        this.telemetry = telemetry;
        paint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
    }

    void update(boolean running, boolean locationEnabled, String status) {
        this.running = running;
        this.locationEnabled = locationEnabled;
        this.status = status;
        invalidate();
    }

    void setPortrait(boolean portrait) {
        this.portrait = portrait;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BACK);
        float width = portrait ? 420 : 720;
        float height = portrait ? 580 : 300;
        float midX = width / 2;
        float midY = portrait ? 219 : 137;
        float scale = Math.min(getWidth() / width, getHeight() / height);
        c.save();
        c.translate((getWidth() - width * scale) / 2f, (getHeight() - height * scale) / 2f);
        c.scale(scale, scale);
        boolean live = running && telemetry.hasAttitude(getDisplay().getRotation());
        boolean course = locationEnabled && telemetry.hasCourse();
        displaySpeed = approach(displaySpeed, locationEnabled ? telemetry.speed() : Double.NaN, 0.24f);
        displayAltitude = approach(displayAltitude, locationEnabled ? telemetry.altitude() : Double.NaN, 0.23f);
        double heading = course || running ? telemetry.heading(locationEnabled) : Double.NaN;
        if (course != wasCourse || !Double.isFinite(heading) || !Float.isFinite(displayHeading)) {
            displayHeading = (float) heading;
        } else {
            displayHeading = (float) wrap(displayHeading + 0.26 * angleDelta(heading, displayHeading));
        }
        wasCourse = course;

        float left = portrait ? 16 : 24;
        float right = width - left;
        label(c, "CAR HUD", left, portrait ? 35 : 27, portrait ? 20 : 15, GREEN, Paint.Align.LEFT);
        label(c, status, portrait ? midX : 545, portrait ? 65 : 27, portrait ? 13 : 11, SOFT,
                portrait ? Paint.Align.CENTER : Paint.Align.RIGHT);
        float headerRule = portrait ? 78 : 38;
        line(c, left, headerRule, right, headerRule, GRID, 1);
        tape(c, true, displaySpeed, left, portrait ? 90 : 122, midY);
        tape(c, false, displayAltitude, right, width - (portrait ? 90 : 122), midY);
        double roll = live ? telemetry.roll() : Double.NaN;
        attitude(c, live ? telemetry.pitch() : Double.NaN, roll, midX, midY);
        compass(c, displayHeading, course, midX, portrait ? 382 : 200);
        lateralScale(c, live ? telemetry.lateralG() : Double.NaN, midX, portrait ? 423 : 229);

        if (portrait) line(c, left, 505, right, 505, GRID, 1);
        label(c, "ACCEL", left, portrait ? 531 : 245, portrait ? 14 : 12, SOFT, Paint.Align.LEFT);
        double acceleration = live ? telemetry.acceleration() : Double.NaN;
        label(c, Double.isFinite(acceleration) ? format("%+.1f", Math.abs(acceleration) < 0.05 ? 0 : acceleration)
                : "—", left, portrait ? 565 : 275, portrait ? 28 : 23, GREEN, Paint.Align.LEFT);
        label(c, "m/s²", portrait ? 119 : 105, portrait ? 565 : 275, 13, SOFT, Paint.Align.LEFT);

        android.location.Location fix = locationEnabled ? telemetry.fix() : null;
        label(c, "GNSS POSITION", right, portrait ? 526 : 228, 12, SOFT, Paint.Align.RIGHT);
        label(c, fix == null ? "LAT  —" : format("LAT  %+.5f°", fix.getLatitude()),
                right, portrait ? 547 : 250, 15, GREEN, Paint.Align.RIGHT);
        label(c, fix == null ? "LON  —" : format("LON  %+.5f°", fix.getLongitude()),
                right, portrait ? 569 : 273, 15, GREEN, Paint.Align.RIGHT);
        c.restore();
    }

    private void tape(Canvas c, boolean speed, float value, float edge, float axis, float midY) {
        int direction = speed ? -1 : 1;
        int step = speed ? 5 : 10;
        int major = speed ? 10 : 20;
        float center = Float.isFinite(value) ? (speed ? Math.min(250, value) : value) : 0;
        float spacing = portrait ? (speed ? 2.4f : 1.2f) : (speed ? 1.7f : 0.9f);
        float top = portrait ? 137 : 80;
        float bottom = portrait ? 313 : 200;
        Paint.Align align = speed ? Paint.Align.LEFT : Paint.Align.RIGHT;
        label(c, speed ? (portrait ? "SPEED" : "GROUND SPEED") : "ALTITUDE MSL", edge, top - 22, 13, SOFT, align);
        label(c, speed ? "km/h" : "m", edge, top - 5, 11, SOFT, align);
        c.save();
        c.clipRect(Math.min(edge, axis) - 4, top, Math.max(edge, axis) + 4, bottom);
        line(c, axis, top, axis, bottom, GRID, 1);
        if (Float.isFinite(value)) {
            int first = (int) Math.floor((center + (midY - bottom) / spacing) / step) * step;
            int last = (int) Math.ceil((center + (midY - top) / spacing) / step) * step;
            for (int tick = first; tick <= last; tick += step) {
                if (speed && (tick < 0 || tick > 250)) continue;
                float y = midY - (tick - center) * spacing;
                boolean marked = tick % major == 0;
                line(c, axis, y, axis + direction * (marked ? 12 : 6), y, marked ? GREEN : SOFT, 1.5f);
                if (marked && Math.abs(y - midY) > 20 && y > top + 7 && y < bottom - 6) {
                    label(c, Integer.toString(tick), axis + direction * 20, y + 4, 12, SOFT,
                            speed ? Paint.Align.RIGHT : Paint.Align.LEFT);
                }
            }
        }
        c.restore();
        shape.reset();
        shape.moveTo(edge, midY - 14);
        shape.lineTo(axis + direction * 9, midY - 14);
        shape.lineTo(axis, midY);
        shape.lineTo(axis + direction * 9, midY + 14);
        shape.lineTo(edge, midY + 14);
        shape.close();
        outlined(c);
        String readout = Float.isFinite(value)
                ? (speed && value > 250 ? "250+" : Integer.toString(Math.round(value))) : "—";
        label(c, readout, (axis + edge) / 2 + direction * 3, midY + 7, 22, GREEN, Paint.Align.CENTER);
        double peak = speed ? telemetry.peakSpeed() : telemetry.peakAltitude();
        if (Float.isFinite(value)) {
            c.save();
            c.clipRect(Math.min(edge, axis) - 4, top, Math.max(edge, axis) + 4, bottom);
            float peakY = midY - (float) (peak - center) * spacing;
            float minimumY = speed ? Float.POSITIVE_INFINITY
                    : midY - (float) (telemetry.minimumAltitude() - center) * spacing;
            if (Math.abs(peakY - minimumY) < 1.5f) {
                float midpoint = (peakY + minimumY) / 2;
                peakY = midpoint - 0.75f;
                minimumY = midpoint + 0.75f;
            }
            if (!speed || peak <= 250) {
                tapeMarker(c, axis, direction, peakY, top, bottom, PEAK);
            }
            if (!speed) {
                tapeMarker(c, axis, direction, minimumY, top, bottom, MINIMUM);
            }
            c.restore();
        }
        if (!speed && Float.isFinite(value)) {
            label(c, telemetry.usingPressure() ? "GNSS + BARO" : "GNSS", edge, bottom + 13, 10, SOFT, align);
        }
    }

    private void tapeMarker(Canvas c, float axis, int direction, float y, float top, float bottom, int color) {
        if (y >= top && y <= bottom) line(c, axis, y, axis + direction * 12, y, color, 1.5f);
    }

    private void attitude(Canvas c, double pitch, double roll, float midX, float midY) {
        c.save();
        float halfWidth = portrait ? 112 : 220;
        c.clipRect(midX - halfWidth, portrait ? 136 : 62, midX + halfWidth, portrait ? 316 : 184);
        if (Double.isFinite(pitch)) {
            c.rotate((float) -roll, midX, midY);
            float spacing = portrait ? 7.5f : 5.5f;
            for (int mark = -90; mark <= 90; mark += 5) {
                float y = midY + (float) (pitch - mark) * spacing;
                if (Math.abs(y - midY) > (portrait ? 140 : 225)) continue;
                pitchLine(c, mark, midX, y, mark == 0 ? GREEN : SOFT);
            }
            double peak = telemetry.peakPitch();
            if (Double.isFinite(peak)) {
                pitchLine(c, peak, midX, midY + (float) (pitch - peak) * spacing, PEAK);
            }
            double minimum = telemetry.minimumPitch();
            if (minimum < 0) {
                pitchLine(c, minimum, midX, midY + (float) (pitch - minimum) * spacing, MINIMUM);
            }
        }
        c.restore();
        for (int side : SIDES) {
            line(c, midX + side * 54, midY, midX + side * 24, midY, GREEN, 2);
            line(c, midX + side * 24, midY, midX + side * 18, midY + 5, GREEN, 2);
        }
        paint.setColor(GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        c.drawCircle(midX, midY, 5, paint);
        paint.setStyle(Paint.Style.FILL);
        label(c, Double.isFinite(pitch) ? format("PITCH %+.0f°", Math.abs(pitch) < 0.5 ? 0 : pitch)
                : "PITCH —", midX, portrait ? 126 : 56, portrait ? 13 : 11, SOFT, Paint.Align.CENTER);
    }

    private void pitchLine(Canvas c, double degrees, float midX, float y, int color) {
        boolean horizon = degrees == 0;
        float outer = portrait ? (horizon ? 99 : 62) : (horizon ? 186 : 112);
        float inner = horizon ? 29 : portrait ? 28 : 49;
        for (int side : SIDES) {
            float a = midX + side * outer;
            float b = midX + side * inner;
            if (degrees < 0) {
                float third = (b - a) / 3;
                line(c, a, y, a + third, y, color, 1.6f);
                line(c, b - third, y, b, y, color, 1.6f);
            } else {
                line(c, a, y, b, y, color, horizon ? 2 : 1.6f);
            }
            if (!horizon && color == SOFT) {
                line(c, a, y, a, y + (degrees > 0 ? 6 : -6), color, 1.5f);
                label(c, format("%+d", (int) degrees), a + side * 9, y + 4, 12, color,
                        side < 0 ? Paint.Align.RIGHT : Paint.Align.LEFT);
            }
        }
    }

    private void lateralScale(Canvas c, double lateralG, float pivotX, float pivotY) {
        for (int mark = -60; mark <= 60; mark += 10) {
            lateralTick(c, pivotX, pivotY, mark, mark % 30 == 0, mark == 0 ? GREEN : SOFT);
        }
        double left = telemetry.peakLeftG();
        double right = telemetry.peakRightG();
        if (left >= 0.005 && left <= 2) lateralTick(c, pivotX, pivotY, (float) (30 * left), true, PEAK);
        if (right >= 0.005 && right <= 2) lateralTick(c, pivotX, pivotY, (float) (-30 * right), true, PEAK);
        if (Double.isFinite(lateralG)) {
            c.save();
            c.rotate((float) (-30 * Math.max(-2, Math.min(2, lateralG))), pivotX, pivotY);
            line(c, pivotX - 4, pivotY + 41, pivotX, pivotY + 46, GREEN, 1.5f);
            line(c, pivotX, pivotY + 46, pivotX + 4, pivotY + 41, GREEN, 1.5f);
            c.restore();
        }
        label(c, "2L", pivotX - 56, pivotY + 31, 10, SOFT, Paint.Align.RIGHT);
        label(c, "2R", pivotX + 56, pivotY + 31, 10, SOFT, Paint.Align.LEFT);
        String value = !Double.isFinite(lateralG) ? "—"
                : Math.abs(lateralG) < 0.005 ? "0.00 g"
                : format("%.2f g %s", Math.abs(lateralG), lateralG < 0 ? "L" : "R");
        label(c, "SIDE " + value, pivotX, pivotY + 67, portrait ? 14 : 12, GREEN, Paint.Align.CENTER);
    }

    private void lateralTick(Canvas c, float pivotX, float pivotY, float angle, boolean major, int color) {
        c.save();
        c.rotate(angle, pivotX, pivotY);
        line(c, pivotX, pivotY + (major ? 45 : 49), pivotX, pivotY + 55, color, 1.5f);
        c.restore();
    }

    private void compass(Canvas c, double heading, boolean course, float midX, float y) {
        float left = midX - 150;
        float right = midX + 150;
        line(c, left, y, right, y, GRID, 1);
        c.save();
        c.clipRect(left, y - 10, right, y + 27);
        if (Double.isFinite(heading)) {
            for (int tick = 0; tick < 360; tick += 5) {
                float x = midX + (float) angleDelta(tick, heading) * 3.1f;
                if (x < left - 16 || x > right + 16) continue;
                line(c, x, y, x, y + (tick % 10 == 0 ? 11 : 6), SOFT, 1.5f);
                if (tick % 10 == 0) {
                    String value = tick % 90 == 0 ? CARDINAL[tick / 90]
                            : format("%02d", tick / 10);
                    label(c, value, x, y + 24, 12, SOFT, Paint.Align.CENTER);
                }
            }
        }
        c.restore();
        line(c, midX, y - 10, midX, y + 9, GREEN, 2);
        String bearing = Double.isFinite(heading) ? format("%03d°", Math.round(heading) % 360) : "—";
        paint.setColor(BACK);
        c.drawRect(midX - 33, y + 15, midX + 33, y + 40, paint);
        paint.setColor(GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        c.drawRect(midX - 33, y + 15, midX + 33, y + 40, paint);
        paint.setStyle(Paint.Style.FILL);
        label(c, bearing, midX, y + 34, 18, GREEN, Paint.Align.CENTER);
        label(c, !Double.isFinite(heading) ? "COURSE" : course ? "COURSE · TRUE" : "HEADING · MAG",
                midX, y + 51, 11, SOFT, Paint.Align.CENTER);
    }

    private void outlined(Canvas c) {
        paint.setColor(BACK);
        c.drawPath(shape, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setColor(GREEN);
        c.drawPath(shape, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void line(Canvas c, float x, float y, float x2, float y2, int color, float width) {
        paint.setColor(color);
        paint.setStrokeWidth(width);
        c.drawLine(x, y, x2, y2, paint);
    }

    private void label(Canvas c, String text, float x, float y, float size, int color, Paint.Align align) {
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTextAlign(align);
        c.drawText(text, x, y, paint);
    }

    private static float approach(float previous, double value, float fraction) {
        return Float.isFinite(previous) && Double.isFinite(value)
                ? previous + (float) (value - previous) * fraction : (float) value;
    }

    private static double wrap(double angle) { return (angle + 360) % 360; }
    private static double angleDelta(double a, double b) { return (a - b + 540) % 360 - 180; }
    private static String format(String pattern, Object... values) { return String.format(Locale.US, pattern, values); }
}
