package app.carhud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.location.Location;
import android.net.http.HttpResponseCache;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class MapView extends View {
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final LruCache<Tile, Bitmap> images = new LruCache<>(72);
    private final Set<Tile> pending = new HashSet<>();
    private final Map<Tile, Long> retryAfter = new HashMap<>();
    private volatile Set<Tile> visible = Collections.emptySet();
    private final Set<HttpURLConnection> connections = new HashSet<>();
    private boolean downloading = true;
    private int requestGeneration;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF tileRect = new RectF();
    private final Path arrow = new Path();
    private final float density;
    private final float tileSize;
    private Location location;
    private double course = Double.NaN;
    private int zoom = 16;
    private String emptyMessage = "Waiting for a GNSS fix";
    private HttpResponseCache diskCache;

    MapView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        tileSize = 256 * Math.min(density, 2);
        try {
            diskCache = HttpResponseCache.getInstalled();
            if (diskCache == null) {
                diskCache = HttpResponseCache.install(new File(context.getCacheDir(), "osm"), 64L * 1024 * 1024);
            }
        } catch (IOException ignored) {
            // The in-memory cache still avoids repeated downloads within a session.
        }
    }

    void update(Location location, double course, String emptyMessage) {
        if (this.location == location && Double.compare(this.course, course) == 0
                && this.emptyMessage.equals(emptyMessage)) return;
        this.location = location;
        this.course = course;
        this.emptyMessage = emptyMessage;
        if (location == null || !Double.isFinite(course)) visible = Collections.emptySet();
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        Set<HttpURLConnection> cancelled = Collections.emptySet();
        synchronized (connections) {
            downloading = enabled;
            if (!enabled) {
                visible = Collections.emptySet();
                requestGeneration++;
                if (!connections.isEmpty()) cancelled = new HashSet<>(connections);
            }
        }
        if (!cancelled.isEmpty()) {
            Set<HttpURLConnection> inFlight = cancelled;
            new Thread(() -> inFlight.forEach(HttpURLConnection::disconnect), "map-cancel").start();
        }
        invalidate();
    }

    void zoomBy(int delta) {
        zoom = Math.max(3, Math.min(19, zoom + delta));
        invalidate();
    }

    void close() {
        setEnabled(false);
        workers.shutdownNow();
        if (diskCache != null) diskCache.flush();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isEnabled()) return;
        canvas.drawColor(Color.rgb(12, 28, 23));
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        if (location == null) {
            drawMessage(canvas, emptyMessage, centerX, centerY);
            return;
        }
        if (!Double.isFinite(course)) {
            drawMessage(canvas, "Waiting for course", centerX, centerY);
            return;
        }
        int count = 1 << zoom;
        double x = (location.getLongitude() + 180) / 360 * count;
        double lat = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, location.getLatitude())));
        double y = (1 - Math.log(Math.tan(lat) + 1 / Math.cos(lat)) / Math.PI) / 2 * count;
        y = Math.max(0, Math.min(count - 1e-8, y));
        double angle = Math.toRadians(course);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double halfWidth = Math.abs(cos) * centerX + Math.abs(sin) * centerY;
        double halfHeight = Math.abs(sin) * centerX + Math.abs(cos) * centerY;
        int firstX = (int) Math.floor(x - halfWidth / tileSize);
        int lastX = (int) Math.floor(x + halfWidth / tileSize);
        int firstY = Math.max(0, (int) Math.floor(y - halfHeight / tileSize));
        int lastY = Math.min(count - 1, (int) Math.floor(y + halfHeight / tileSize));
        Set<Tile> viewport = new HashSet<>();
        boolean hasTile = false;
        canvas.save();
        canvas.rotate((float) -course, centerX, centerY);
        double projectedTileRadius = tileSize / 2 * (Math.abs(cos) + Math.abs(sin));
        for (int ty = firstY; ty <= lastY; ty++) {
            for (int tx = firstX; tx <= lastX; tx++) {
                double dx = (tx + 0.5 - x) * tileSize;
                double dy = (ty + 0.5 - y) * tileSize;
                // The bounding box covers the rotated viewport. Skip tiles beyond its corners.
                if (Math.abs(dx * cos + dy * sin) >= centerX + projectedTileRadius
                        || Math.abs(-dx * sin + dy * cos) >= centerY + projectedTileRadius) continue;
                Tile tile = new Tile(zoom, Math.floorMod(tx, count), ty);
                viewport.add(tile);
                Bitmap bitmap = images.get(tile);
                if (bitmap == null) continue;
                hasTile = true;
                float left = centerX + (float) (tx - x) * tileSize;
                float top = centerY + (float) (ty - y) * tileSize;
                tileRect.set(left, top, left + tileSize, top + tileSize);
                canvas.drawBitmap(bitmap, null, tileRect, paint);
            }
        }
        canvas.restore();
        visible = viewport;
        long now = SystemClock.elapsedRealtime();
        retryAfter.values().removeIf(time -> time <= now);
        for (Tile tile : viewport) {
            if (images.get(tile) == null && !retryAfter.containsKey(tile) && pending.add(tile)) {
                fetch(tile, requestGeneration);
            }
        }
        drawMarker(canvas, centerX, centerY);
        if (!hasTile) {
            drawMessage(canvas, pending.isEmpty() ? "Map unavailable · check internet" : "Loading map…",
                    centerX, centerY + 35 * density);
        }
    }

    private void drawMarker(Canvas canvas, float x, float y) {
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(density, density);
        paint.setColor(0xff08261b);
        canvas.drawCircle(0, 0, 13, paint);
        paint.setColor(0xff56ff9f);
        arrow.reset();
        arrow.moveTo(0, -10);
        arrow.lineTo(7, 7);
        arrow.lineTo(0, 4);
        arrow.lineTo(-7, 7);
        arrow.close();
        canvas.drawPath(arrow, paint);
        canvas.restore();
    }

    private void drawMessage(Canvas canvas, String text, float x, float y) {
        paint.setColor(0xffb0cbbd);
        paint.setTextSize(13 * density);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(text, x, y, paint);
    }

    private void fetch(Tile tile, int generation) {
        try {
            workers.execute(() -> {
                Bitmap bitmap = download(tile, generation);
                post(() -> {
                    pending.remove(tile);
                    if (bitmap != null) {
                        images.put(tile, bitmap);
                    } else if (generation == requestGeneration && visible.contains(tile)) {
                        retryAfter.put(tile, SystemClock.elapsedRealtime() + 30_000);
                        postInvalidateDelayed(30_000);
                    }
                    if (visible.contains(tile)) invalidate();
                });
            });
        } catch (RejectedExecutionException ignored) {
            pending.remove(tile);
        }
    }

    private Bitmap download(Tile tile, int generation) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://tile.openstreetmap.org/" + tile.z + "/" + tile.x + "/" + tile.y + ".png");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "CarHUD/1.4 (Android; app.carhud)");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            synchronized (connections) {
                if (!downloading || generation != requestGeneration || !visible.contains(tile)) return null;
                connections.add(connection);
            }
            try (InputStream input = connection.getInputStream()) {
                // Read to EOF so the HTTP cache stores the full response.
                byte[] png = readTile(input);
                return png == null ? null : BitmapFactory.decodeByteArray(png, 0, png.length);
            }
        } catch (IOException ignored) {
            return null;
        } finally {
            if (connection != null) {
                synchronized (connections) {
                    connections.remove(connection);
                }
                connection.disconnect();
            }
        }
    }

    private static byte[] readTile(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            if (output.size() + read > 1_000_000) return null;
            output.write(chunk, 0, read);
        }
        return output.toByteArray();
    }

    private static final class Tile {
        final int z;
        final int x;
        final int y;

        Tile(int z, int x, int y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }

        @Override
        public int hashCode() { return (z * 31 + x) * 31 + y; }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Tile)) return false;
            Tile other = (Tile) value;
            return z == other.z && x == other.x && y == other.y;
        }
    }
}
