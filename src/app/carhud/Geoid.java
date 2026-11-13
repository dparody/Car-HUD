package app.carhud;

import android.content.Context;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

final class Geoid {
    // The 15-minute NGA EGM96 grid starts at 180° W, 90° N, with longitude wrapping.
    private static final int WIDTH = 1440;
    private static final int HEIGHT = 721;
    private final short[] centimeters = new short[WIDTH * HEIGHT];

    Geoid(Context context) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(context.getAssets().open("egm96.bin.gz")))) {
            for (int i = 0; i < centimeters.length; i++) centimeters[i] = in.readShort();
        }
    }

    double offset(double lat, double lon) {
        double x = ((lon + 180) * 4) % WIDTH;
        double y = (90 - lat) * 4;
        int left = (int) x;
        int right = (left + 1) % WIDTH;
        int top = (int) y;
        int bottom = Math.min(HEIGHT - 1, top + 1);
        double north = at(left, top) + (at(right, top) - at(left, top)) * (x - left);
        double south = at(left, bottom) + (at(right, bottom) - at(left, bottom)) * (x - left);
        return (north + (south - north) * (y - top)) / 100;
    }

    private int at(int x, int y) { return centimeters[y * WIDTH + x]; }
}
