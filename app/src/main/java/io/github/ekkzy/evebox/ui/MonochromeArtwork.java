package io.github.ekkzy.evebox.ui;

import android.graphics.Bitmap;
import java.util.concurrent.CancellationException;

/**
 * Separates independently licensed bundled artwork into its established mask,
 * with optional source-brand color restoration. Native installed artwork never
 * enters this helper. Call on the image worker after source decoding.
 * The returned bitmap is new and belongs to the caller. The input is never
 * modified or recycled. No shape, letter or counter is drawn by this helper.
 */
public final class MonochromeArtwork {
    private MonochromeArtwork() {}

    /**
     * Keeps exactly the established transparent mask, restoring source brand
     * colors only inside it. Achromatic letters use the readable palette color;
     * brandColor supplies a documented brand tint for white-only source paths.
     * Input ownership and cancellation are the same as mask().
     */
    public static Bitmap colored(Bitmap source, String key, int brandColor, boolean light) {
        Bitmap result = mask(source, key);
        int width = source.getWidth(), height = source.getHeight();
        int[] sourceRow = new int[width], output = new int[width];
        int neutral = light ? 0x00242424 : 0x00f4f4f4;
        try {
            for (int y = 0; y < height; y++) {
                checkInterrupted();
                source.getPixels(sourceRow, 0, width, 0, y, width, 1);
                result.getPixels(output, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    int alpha = output[x] >>> 24;
                    if (alpha == 0) { output[x] = 0; continue; }
                    int pixel = sourceRow[x], red = (pixel >>> 16) & 255;
                    int green = (pixel >>> 8) & 255, blue = pixel & 255;
                    int color;
                    if ("vk".equals(key)) {
                        float back = clamp(1 - red / 255f);
                        float play = clamp((green - 43 - 76 * back) / 212f);
                        float front = clamp(1 - back - play);
                        color = mix(0x00ff2b42, front, 0x000077ff, .42f * back);
                    } else if ("rutube".equals(key)) {
                        float white = clamp(((green - 9) * 221f - (red - 16) * 11f)
                                / (246 * 221f - 239 * 11f));
                        float accent = clamp(((red - 16) - 239 * white) / 221f);
                        color = mix(neutral, white, 0x00ed143b, accent);
                    } else if (Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 24) {
                        color = pixel & 0x00ffffff;
                    } else {
                        color = brandColor == 0 ? neutral : brandColor & 0x00ffffff;
                    }
                    output[x] = (alpha << 24) | color;
                }
                result.setPixels(output, 0, width, 0, y, width, 1);
            }
            return result;
        } catch (RuntimeException | Error failure) {
            result.recycle(); throw failure;
        }
    }

    private static int mix(int first, float firstWeight, int second, float secondWeight) {
        float total = firstWeight + secondWeight;
        if (total <= .001f) return first;
        int red = Math.round((((first >>> 16) & 255) * firstWeight + ((second >>> 16) & 255) * secondWeight) / total);
        int green = Math.round((((first >>> 8) & 255) * firstWeight + ((second >>> 8) & 255) * secondWeight) / total);
        int blue = Math.round(((first & 255) * firstWeight + (second & 255) * secondWeight) / total);
        return (red << 16) | (green << 8) | blue;
    }

    public static Bitmap mask(Bitmap source, String key) {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("A live source bitmap is required");
        }
        int width = source.getWidth(), height = source.getHeight();
        int[] row = new int[width];
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setDensity(source.getDensity());
        try {
            for (int y = 0; y < height; y++) {
                checkInterrupted();
                source.getPixels(row, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    int pixel = row[x], alpha = pixel >>> 24;
                    if (alpha == 0) { row[x] = 0; continue; }
                    int red = (pixel >>> 16) & 255;
                    int green = (pixel >>> 8) & 255;
                    int blue = pixel & 255;
                    float coverage = 1;
                    if ("vk".equals(key)) {
                        // Exact source planes: blue #0077FF, red #FF2B42,
                        // white play. Resolve their mixture, retaining the back
                        // plane quietly and knocking the white play out.
                        float back = 1 - red / 255f;
                        float play = clamp((green - 43 - 76 * back) / 212f);
                        float front = clamp(1 - back - play);
                        coverage = clamp(front + .42f * back);
                    } else if ("rutube".equals(key)) {
                        // Resolve #100943 background / #ED143B accent / white R.
                        // This also preserves the R counter and antialiased edges.
                        float white = ((green - 9) * 221f - (red - 16) * 11f)
                                / (246 * 221f - 239 * 11f);
                        float accent = ((red - 16) - 239 * white) / 221f;
                        coverage = clamp(white + accent);
                    } else if ("smarttube".equals(key)) {
                        // The actual transparent monitor/stand is retained.
                        // White S becomes negative space in its cyan screen.
                        coverage = 1 - Math.min(red, Math.min(green, blue)) / 255f;
                    }
                    int maskAlpha = Math.round(alpha * clamp(coverage));
                    row[x] = maskAlpha == 0 ? 0 : (maskAlpha << 24) | 0x00ffffff;
                }
                result.setPixels(row, 0, width, 0, y, width, 1);
            }
            return result;
        } catch (RuntimeException failure) {
            result.recycle();
            throw failure;
        }
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
